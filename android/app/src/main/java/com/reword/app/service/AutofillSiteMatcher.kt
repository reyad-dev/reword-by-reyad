package com.reword.app.service

import com.reword.app.model.Site
import com.reword.app.model.Vault
import java.net.URI
import java.util.Locale

internal object AutofillSiteMatcher {
    fun find(vault: Vault, domain: String?, packageName: String?): List<Site> {
        val domainHost = domain?.let(::host).orEmpty()
        val packageParts = packageName.orEmpty().lowercase(Locale.ROOT).split('.').toSet()
        return vault.sites.filter { site ->
            if (site.url.startsWith("androidapp://")) {
                return@filter domainHost.isBlank() && site.url.removePrefix("androidapp://") == packageName
            }
            val siteHost = host(site.url)
            if (domainHost.isNotBlank()) {
                // A browser package must never override a mismatching website domain.
                siteHost.isNotBlank() && (domainHost == siteHost || domainHost.endsWith(".$siteHost"))
            } else {
                val name = site.name.trim().lowercase(Locale.ROOT)
                val brand = siteHost.split('.').firstOrNull().orEmpty()
                (name.length > 2 && name in packageParts) || (brand.length > 2 && brand in packageParts)
            }
        }
    }

    private fun host(raw: String): String = try {
        URI(if (raw.contains("://")) raw.trim() else "https://${raw.trim()}")
            .host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')
    } catch (_: Exception) { "" }
}
