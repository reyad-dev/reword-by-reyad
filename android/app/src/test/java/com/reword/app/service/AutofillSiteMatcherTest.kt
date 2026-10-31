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
    @Test fun capturedNativePackageMatchesAgainWithoutMatchingOtherApps() {
        val captured = Site(name = "com.example.android", url = "androidapp://com.example.android")
        assertEquals(listOf(captured), AutofillSiteMatcher.find(Vault(listOf(captured)), null, "com.example.android"))
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(captured)), null, "com.unrelated.android").isEmpty())
        assertTrue(AutofillSiteMatcher.find(Vault(listOf(captured)), "com.example.android", "com.example.android").isEmpty())
    }
}
