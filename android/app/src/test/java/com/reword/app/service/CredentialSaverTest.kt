package com.reword.app.service

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CredentialSaverTest {
    @Test fun updatePreservesUnknownFieldsNotesAndOtherAccounts() {
        val root = JsonParser.parseString("""{
          "meta":{"hint":"keep","custom":42},"extra":{"enabled":true},
          "sites":[{"id":"site","name":"Example","url":"https://example.test", "custom":"retain",
          "accounts":[{"id":"alice","user":"alice","pass":"old","notes":"keep notes","otp":"retain"},
                      {"id":"bob","user":"bob","pass":"unchanged"}]}]
        }""").asJsonObject
        CredentialSaver.mergeCredential(root, "example.test", "com.browser", "alice", "new-test-pass")
        assertEquals(42, root.getAsJsonObject("meta").get("custom").asInt)
        assertTrue(root.getAsJsonObject("extra").get("enabled").asBoolean)
        val site = root.getAsJsonArray("sites")[0].asJsonObject
        assertEquals("retain", site.get("custom").asString)
        val accounts = site.getAsJsonArray("accounts")
        assertEquals(2, accounts.size())
        val alice = accounts[0].asJsonObject
        assertEquals("alice", alice.get("id").asString)
        assertEquals("new-test-pass", alice.get("pass").asString)
        assertEquals("keep notes", alice.get("notes").asString)
        assertEquals("retain", alice.get("otp").asString)
        assertEquals("unchanged", accounts[1].asJsonObject.get("pass").asString)
    }

    @Test fun nativeCaptureIsIdempotentAndUsesExactPackage() {
        val root = JsonParser.parseString("""{"sites":[],"meta":{"v":1}}""").asJsonObject
        CredentialSaver.mergeCredential(root, null, "com.example.app", "alice", "test-pass")
        CredentialSaver.mergeCredential(root, null, "com.example.app", "alice", "updated-test-pass")
        val sites = root.getAsJsonArray("sites")
        assertEquals(1, sites.size())
        val site = sites[0].asJsonObject
        assertEquals("androidapp://com.example.app", site.get("url").asString)
        assertEquals(1, site.getAsJsonArray("accounts").size())
        assertEquals("updated-test-pass", site.getAsJsonArray("accounts")[0].asJsonObject.get("pass").asString)
    }

    @Test fun usernameCaseAndPasswordOnlyScreensUpdateExistingAccount() {
        val root = JsonParser.parseString("""{"sites":[{"id":"s","name":"Example","url":"https://example.test",
          "accounts":[{"id":"a","user":"Alice@Mail.test","pass":"old"}]}]}""").asJsonObject
        assertEquals("s", CredentialSaver.mergeCredential(root, "login.example.test", "com.browser", " alice@mail.test ", "new1"))
        CredentialSaver.mergeCredential(root, "example.test", "com.browser", "", "new2")
        val accounts = root.getAsJsonArray("sites")[0].asJsonObject.getAsJsonArray("accounts")
        assertEquals(1, accounts.size())
        assertEquals("new2", accounts[0].asJsonObject.get("pass").asString)
    }

    @Test fun newUsernameOnSameSiteAddsSecondAccount() {
        val root = JsonParser.parseString("""{"sites":[{"id":"s","name":"Example","url":"https://example.test",
          "accounts":[{"id":"a","user":"alice","pass":"p1"}]}]}""").asJsonObject
        CredentialSaver.mergeCredential(root, "example.test", "com.browser", "bob", "p2")
        val accounts = root.getAsJsonArray("sites")[0].asJsonObject.getAsJsonArray("accounts")
        assertEquals(2, accounts.size())
        assertEquals("p1", accounts[0].asJsonObject.get("pass").asString)
    }

    @Test fun anotherDomainCannotOverwriteAnExistingSite() {
        val root = JsonParser.parseString("""{"sites":[]}""").asJsonObject
        CredentialSaver.mergeCredential(root, "example.test", "com.browser", "alice", "original-test-pass")
        CredentialSaver.mergeCredential(root, "example.test.attacker.test", "com.browser", "alice", "other-test-pass")
        val sites = root.getAsJsonArray("sites")
        assertEquals(2, sites.size())
        assertEquals("original-test-pass", sites[0].asJsonObject.getAsJsonArray("accounts")[0].asJsonObject.get("pass").asString)
    }

    @Test fun editorAddsAndEditsAccountsWithNotes() {
        val root = JsonParser.parseString("""{"sites":[{"id":"s","name":"Ex","url":"https://ex.test","custom":1,
          "accounts":[{"id":"a","user":"u","pass":"p","notes":"","otp":"keep"}]}]}""").asJsonObject
        CredentialSaver.applyEdit(root, CredentialSaver.AccountEdit("s", "a", "Ex", "https://ex.test", "u2", "p2", "backup codes in drawer"))
        val acc = root.getAsJsonArray("sites")[0].asJsonObject.getAsJsonArray("accounts")[0].asJsonObject
        assertEquals("u2", acc.get("user").asString)
        assertEquals("backup codes in drawer", acc.get("notes").asString)
        assertEquals("keep", acc.get("otp").asString)
        CredentialSaver.applyEdit(root, CredentialSaver.AccountEdit(null, null, "", "new.test", "me", "pw", "n"))
        val sites = root.getAsJsonArray("sites")
        assertEquals(2, sites.size())
        assertEquals("https://new.test", sites[1].asJsonObject.get("url").asString)
        assertEquals("new.test", sites[1].asJsonObject.get("name").asString)
        CredentialSaver.removeAccount(root, "s", "a")
        assertEquals(0, root.getAsJsonArray("sites")[0].asJsonObject.getAsJsonArray("accounts").size())
    }
}
