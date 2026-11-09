package com.reword.app.service

import com.reword.app.model.Site
import com.reword.app.model.Vault
import java.net.URI
import java.util.Locale

internal object AutofillSiteMatcher {

    /** Browsers report the page's web domain; their own package name must never select a site. */
    val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.chromium.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.mozilla.focus", "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "com.microsoft.emmx", "com.brave.browser", "com.opera.browser", "com.opera.mini.native",
        "com.opera.gx", "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
        "com.mi.globalbrowser", "com.UCMobile.intl", "com.yandex.browser", "com.android.browser"
    )

    /** Well-known apps whose package name does not contain their website's brand. */
    private val PACKAGE_ALIASES = mapOf(
        "com.twitter.android" to listOf("twitter.com", "x.com"),
        "com.google.android.gm" to listOf("google.com", "gmail.com"),
        "com.google.android.youtube" to listOf("youtube.com", "google.com"),
        "com.facebook.katana" to listOf("facebook.com"),
        "com.facebook.lite" to listOf("facebook.com"),
        "com.facebook.orca" to listOf("facebook.com", "messenger.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "com.ss.android.ugc.trill" to listOf("tiktok.com"),
        "com.microsoft.office.outlook" to listOf("outlook.com", "live.com", "microsoft.com"),
        "com.netflix.mediaclient" to listOf("netflix.com"),
        "com.spotify.music" to listOf("spotify.com"),
        "com.amazon.mshop.android.shopping" to listOf("amazon.com")
    )

    /**
     * Hosting suffixes where sibling subdomains belong to different owners
     * (alice.github.io must never receive bob.github.io's password).
     */
    private val SHARED_SUFFIXES = setOf(
        "github.io", "gitlab.io", "herokuapp.com", "blogspot.com", "netlify.app", "vercel.app",
        "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net",
        "cloudfront.net", "wordpress.com", "wixsite.com", "glitch.me", "onrender.com", "fly.dev",
        "ngrok.io", "ngrok-free.app", "s3.amazonaws.com"
    )

    /** Package segments that say nothing about the brand ("com.example.android.app"). */
    private val GENERIC_PACKAGE_PARTS = setOf(
        "com", "org", "net", "io", "app", "apps", "android", "mobile", "client", "main", "prod", "release", "lite", "free", "pro"
    )

    /** "Cloudflare One" -> "cloudflareone"; keeps letters/digits of any script. */
    internal fun normalize(raw: String?): String =
        raw.orEmpty().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun isBrowser(packageName: String?): Boolean =
        packageName != null && packageName.lowercase(Locale.ROOT) in BROWSER_PACKAGES

    /**
     * @param linkedSiteId site the user previously chose for this app/domain (ranked first).
     */
    fun find(vault: Vault, domain: String?, packageName: String?, linkedSiteId: String? = null, appLabel: String? = null): List<Site> {
        val sites = vault.sites.orEmpty()
        val domainHost = domain?.let(::host).orEmpty()
        val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
        val packageParts = pkg.split('.').filter { it !in GENERIC_PACKAGE_PARTS }.toSet()
        val label = normalize(appLabel)

        val matches = sites.filter { site ->
            val url = site.url.orEmpty()
            if (url.startsWith("androidapp://")) {
                return@filter domainHost.isBlank() && url.removePrefix("androidapp://").equals(packageName, true)
            }
            val siteHosts = hosts(site)
            if (domainHost.isNotBlank()) {
                // A browser package must never override a mismatching website domain.
                siteHosts.any { hostMatches(domainHost, it) } || nameMatchesDomain(site, domainHost)
            } else {
                if (pkg.isBlank() || isBrowser(pkg)) return@filter false
                val aliases = PACKAGE_ALIASES[pkg].orEmpty()
                if (siteHosts.any { h -> aliases.any { hostMatches(h, it) } }) return@filter true
                val name = normalize(site.name)
                val brands = siteHosts.map { registrable(it).substringBefore('.') }.filter { it.length > 2 }
                val nameBrand = if (siteHosts.isEmpty() && site.name.orEmpty().contains('.')) {
                    registrable(host(site.name.orEmpty())).substringBefore('.')
                } else ""
                val keys = (brands + name + nameBrand).filter { it.length > 2 }.distinct()
                keys.any { key ->
                    key in packageParts ||
                        // "instagramlite", "netflixapp": brand at the start of a package segment
                        (key.length >= 4 && packageParts.any { it.length > key.length && it.startsWith(key) }) ||
                        // App label shown on the launcher ("Cloudflare One", "Reverso")
                        (label.length > 2 && (label == key || (key.length >= 4 && label.startsWith(key)) ||
                            (label.length >= 4 && key.startsWith(label))))
                }
            }
        }
        val linked = linkedSiteId?.takeIf { it.isNotBlank() }?.let { id -> sites.firstOrNull { it.id == id } }
        return if (linked == null) matches else listOf(linked) + matches.filter { it !== linked }
    }

    /** Key under which the user's manual choice is remembered. Browsers are keyed by site, never by package. */
    fun linkKey(domain: String?, packageName: String?): String? {
        val d = domain?.let(::host).orEmpty()
        if (d.isNotBlank()) return "web:$d"
        if (!packageName.isNullOrBlank() && !isBrowser(packageName)) return "app:${packageName.lowercase(Locale.ROOT)}"
        return null
    }

    /**
     * Exact host, a subdomain of the saved host, or its parent domain.
     * Sibling subdomains (a.example.com vs b.example.com) are deliberately NOT matched: without a full
     * Public Suffix List, siblings on shared hosting (alice.github.io / bob.github.io) would leak passwords.
     */
    internal fun hostMatches(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || !a.contains('.') || !b.contains('.')) return false
        if (a == b || a.endsWith(".$b")) return true
        // Parent page receiving a subdomain's login (saved login.example.com, page example.com)
        return b.endsWith(".$a") && SHARED_SUFFIXES.none { a == it }
    }

    /** "accounts.google.com" -> "google.com", "www.bbc.co.uk" -> "bbc.co.uk" */
    internal fun registrable(host: String): String {
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        SHARED_SUFFIXES.firstOrNull { host.endsWith(".$it") }?.let { suffix ->
            val n = suffix.count { it == '.' } + 2
            return parts.takeLast(n).joinToString(".")
        }
        val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
        return if (parts.last().length == 2 && parts[parts.size - 2] in secondLevel) {
            parts.takeLast(3).joinToString(".")
        } else parts.takeLast(2).joinToString(".")
    }

    /**
     * A site saved without URL but named like a domain ("facebook.com") still matches its website.
     * A plain brand name ("Facebook") is NOT enough on the web: facebook.xyz phishing would match it.
     */
    private fun nameMatchesDomain(site: Site, domainHost: String): Boolean {
        if (hosts(site).isNotEmpty()) return false
        val name = site.name.orEmpty().trim().lowercase(Locale.ROOT)
        return name.contains('.') && !name.contains(' ') && hostMatches(domainHost, host(name))
    }

    /** A site's URL field may hold several URLs separated by commas, semicolons or spaces. */
    private fun hosts(site: Site): List<String> =
        site.url.orEmpty().split(Regex("[,;\\s]+")).map(::host).filter { it.contains('.') }

    private fun host(raw: String): String = try {
        val r = raw.trim()
        if (r.isEmpty()) "" else URI(if (r.contains("://")) r else "https://$r")
            .host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
    } catch (_: Exception) { "" }
}
