package com.reword.app.service

import com.reword.app.model.Site
import com.reword.app.model.Vault
import org.junit.Assert.*
import org.junit.Test

class AutofillSiteMatcherTest {
    private val site = Site(name = "Example", url = "https://www.example.com/login")
    private val vault = Vault(listOf(site))

    @Test fun exactDomainAndSubdomainMatch() {
        assertEquals(listOf(site), AutofillSiteMatcher.find(vault, "example.com", null))
        assertEquals(listOf(site), AutofillSiteMatcher.find(vault, "login.example.com", null))
    }
    @Test fun unrelatedDomainsNeverMatchBySubstring() {
        assertTrue(AutofillSiteMatcher.find(vault, "example.com.attacker.test", null).isEmpty())
        assertTrue(AutofillSiteMatcher.find(vault, "fakeexample.com", null).isEmpty())
        assertTrue(AutofillSiteMatcher.find(vault, "com", null).isEmpty())
    }
    @Test fun browserPackageCannotOverrideWebsiteDomain() {
        assertTrue(AutofillSiteMatcher.find(vault, "other.test", "com.example.browser").isEmpty())
    }
    @Test fun nativePackageUsesWholeSegments() {
        assertEquals(listOf(site), AutofillSiteMatcher.find(vault, null, "com.example.android"))
        assertTrue(AutofillSiteMatcher.find(vault, null, "com.fakeexample.android").isEmpty())
    }
    @Test fun hostCaseAndTrailingDotAreNormalized() {
        assertEquals(listOf(site), AutofillSiteMatcher.find(vault, "WWW.EXAMPLE.COM.", null))
    }
    @Test fun blankWebsiteDoesNotMatchEveryPackage() {
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(Site())), null, "com.other.app").isEmpty())
    }
    @Test fun parentMatchesButSiblingsAndSharedHostingDoNot() {
        val login = Site(id = "l", name = "Example", url = "https://login.example.com")
        assertEquals(listOf(login), AutofillSiteMatcher.find(Vault(listOf(login)), "example.com", null))
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(login)), "evil.example.com", null).isEmpty())
        val pages = Site(id = "p", name = "Alice", url = "https://alice.github.io")
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(pages)), "github.io", null).isEmpty())
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(pages)), "bob.github.io", null).isEmpty())
        assertEquals(listOf(pages), AutofillSiteMatcher.find(Vault(listOf(pages)), "alice.github.io", null))
    }
    @Test fun shortDomainNeverMatchesLongerOneBySubstring() {
        val x = Site(id = "x", name = "X", url = "https://x.com")
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(x)), "netflix.com", null).isEmpty())
    }
    @Test fun multipleUrlsAndNameOnlySites() {
        val multi = Site(id = "m", name = "Work", url = "https://a.example, https://b.example.org/login")
        assertEquals(listOf(multi), AutofillSiteMatcher.find(Vault(listOf(multi)), "b.example.org", null))
        val named = Site(id = "n", name = "facebook.com", url = "")
        assertEquals(listOf(named), AutofillSiteMatcher.find(Vault(listOf(named)), "m.facebook.com", null))
        val brandOnly = Site(id = "f", name = "Facebook", url = "")
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(brandOnly)), "facebook.xyz", null).isEmpty())
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(Site(id = "e", name = "", url = ""))), "any.test", null).isEmpty())
    }
    @Test fun packageAliasesAndBrowsersAndLinkedSites() {
        val twitter = Site(id = "t", name = "My social", url = "https://x.com")
        assertEquals(listOf(twitter), AutofillSiteMatcher.find(Vault(listOf(twitter)), null, "com.twitter.android"))
        val chrome = Site(id = "c", name = "chrome", url = "")
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(chrome)), null, "com.android.chrome").isEmpty())
        val bank = Site(id = "b", name = "My Bank", url = "https://bank.test")
        assertEquals(listOf(bank), AutofillSiteMatcher.find(Vault(listOf(bank)), null, "com.unrelated.wallet", "b"))
        assertNull(AutofillSiteMatcher.linkKey(null, "com.android.chrome"))
        assertEquals("app:com.foo.app", AutofillSiteMatcher.linkKey(null, "com.foo.app"))
    }
    @Test fun capturedNativePackageMatchesAgainWithoutMatchingOtherApps() {
        val captured = Site(name = "com.example.android", url = "androidapp://com.example.android")
        assertEquals(listOf(captured), AutofillSiteMatcher.find(Vault(listOf(captured)), null, "com.example.android"))
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(captured)), null, "com.unrelated.android").isEmpty())
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(captured)), "com.example.android", "com.example.android").isEmpty())
    }

    @Test fun appRecognisedByLauncherLabelOrBrandPrefix() {
        val reverso = Site(id = "r", name = "account.reverso.net", url = "account.reverso.net")
        val cf = Site(id = "c", name = "cloudflare", url = "cloudflare.com")
        val v = Vault(listOf(reverso, cf))
        assertEquals(listOf(reverso), AutofillSiteMatcher.find(v, null, "com.softissimo.context", appLabel = "Reverso Context"))
        assertEquals(listOf(cf), AutofillSiteMatcher.find(v, null, "com.cloudflare.onedotonedotonedotone"))
        assertEquals(listOf(cf), AutofillSiteMatcher.find(v, null, "com.cloudflareapp.dash"))
        assertTrue(AutofillSiteMatcher.find(v, null, "com.unrelated.app", appLabel = "Notes").isEmpty())
    }
}
