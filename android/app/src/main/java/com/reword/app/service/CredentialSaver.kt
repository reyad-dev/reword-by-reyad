package com.reword.app.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.reword.app.crypto.CryptoEngine
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.Vault
import com.reword.app.network.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal object CredentialSaver {
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fields edited from the in-app account editor. */
    data class AccountEdit(
        val siteId: String?,
        val accountId: String?,
        val siteName: String,
        val siteUrl: String,
        val user: String,
        val pass: String,
        val notes: String,
        /** Comma-separated categories for the site; null leaves them unchanged. */
        val tags: String? = null,
        val email: String? = null
    )

    suspend fun save(storage: SecureStorage, domain: String?, app: String?, user: String, pass: String, appLabel: String? = null, email: String = "") {
        require(pass.isNotEmpty())
        val linkKey = AutofillSiteMatcher.linkKey(domain, app)
        mutate(storage) { root ->
            val siteId = mergeCredential(root, domain, app, user, pass, storage.getLinkedSiteId(linkKey), appLabel, email)
            storage.linkSite(linkKey, siteId)
        }
    }

    /** Adds or edits an account (with notes) from the app UI. */
    suspend fun upsertAccount(storage: SecureStorage, edit: AccountEdit) = mutate(storage) { root -> applyEdit(root, edit) }

    suspend fun deleteAccount(storage: SecureStorage, siteId: String, accountId: String) = mutate(storage) { root ->
        removeAccount(root, siteId, accountId)
    }

    /**
     * Decrypts the newest vault, lets [block] edit the raw JSON (so fields unknown to Android survive),
     * re-encrypts, publishes the new session vault and pushes to the cloud in the background.
     */
    private suspend fun mutate(storage: SecureStorage, block: (JsonObject) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val password = requireNotNull(SecureStorage.activeMasterPassword) { "Vault locked" }
            pullNewerRemote(storage, password)
            val root = JsonParser.parseString(CryptoEngine.decrypt(requireNotNull(storage.vaultBlob), password)).asJsonObject
            block(root)
            val blob = CryptoEngine.encrypt(root.toString(), password)
            val ts = System.currentTimeMillis()
            check(storage.saveVault(blob, ts)) { "Encrypted vault save failed" }
            SecureStorage.setSession(Gson().fromJson(root, Vault::class.java), password)
            val sid = storage.syncId
            val worker = storage.workerUrl
            // Confirm the durable local save promptly, even offline. Sync can finish after the UI closes.
            if (sid != null && worker != null) syncScope.launch {
                syncMutex.withLock {
                    val (latest, latestTs) = mutex.withLock { storage.vaultBlob to storage.vaultTs }
                    if (latest != null) SyncRepository(storage).pushVault(sid, worker, latest, latestTs)
                }
            }
            Unit
        }
    }

    /**
     * Starts from the newest cloud vault so a save never overwrites edits made on other devices.
     * Offline or failing pulls keep the local vault.
     */
    private suspend fun pullNewerRemote(storage: SecureStorage, password: String) {
        val sid = storage.syncId ?: return
        val worker = storage.workerUrl ?: return
        try {
            val remote = SyncRepository(storage).pullVault(sid, worker).getOrNull() ?: return
            if (remote.data.isNotEmpty() && remote.ts > storage.vaultTs) {
                CryptoEngine.decrypt(remote.data, password) // must open with the same master password
                storage.saveVault(remote.data, remote.ts)
            }
        } catch (_: Exception) { }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.sites(): JsonArray = getAsJsonArray("sites") ?: JsonArray().also { add("sites", it) }

    private fun JsonObject.accounts(): JsonArray = getAsJsonArray("accounts") ?: JsonArray().also { add("accounts", it) }

    /** Accepts "example.com" and stores "https://example.com"; keeps androidapp:// and multi-URL values as typed. */
    internal fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty() || u.contains("://") || u.contains(',') || u.contains(';') || u.contains(' ')) return u
        return "https://$u"
    }

    internal fun applyEdit(root: JsonObject, edit: AccountEdit): String {
        val sites = root.sites()
        val siteObjects = sites.map { it.asJsonObject }
        val url = normalizeUrl(edit.siteUrl)
        val site = edit.siteId?.let { id -> siteObjects.firstOrNull { it.str("id") == id } }
            ?: siteObjects.firstOrNull { url.isNotEmpty() && it.str("url").equals(url, ignoreCase = true) }
            ?: JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                add("accounts", JsonArray())
            }.also { sites.add(it) }
        val name = edit.siteName.trim().ifEmpty { url.substringAfter("://").substringBefore('/').ifEmpty { site.str("name") } }
        if (name.isNotEmpty()) site.addProperty("name", name)
        site.addProperty("url", url)
        edit.tags?.let { site.addProperty("tags", cleanTags(it)) }
        val accounts = site.accounts()
        val account = edit.accountId?.let { id -> accounts.map { it.asJsonObject }.firstOrNull { it.str("id") == id } }
            ?: JsonObject().apply { addProperty("id", UUID.randomUUID().toString()) }.also { accounts.add(it) }
        val changed = account.str("user") != edit.user.trim() || account.str("pass") != edit.pass || account.str("notes") != edit.notes ||
            (edit.email != null && account.str("email") != edit.email.trim())
        account.addProperty("user", edit.user.trim())
        account.addProperty("pass", edit.pass)
        account.addProperty("notes", edit.notes)
        edit.email?.let { account.addProperty("email", it.trim()) }
        if (changed || !account.has("lastModified")) account.addProperty("lastModified", System.currentTimeMillis())
        return site.str("id")
    }

    /** "Work, , social,work" -> "Work, social" — same format as the web app and extension. */
    internal fun cleanTags(raw: String): String {
        val seen = mutableSetOf<String>()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.joinToString(", ")
    }

    internal fun removeAccount(root: JsonObject, siteId: String, accountId: String) {
        // Like the web app: the site stays (with its URL) even when its last account is removed.
        val site = root.sites().map { it.asJsonObject }.firstOrNull { it.str("id") == siteId } ?: return
        val accounts = site.accounts()
        accounts.map { it.asJsonObject }.firstOrNull { it.str("id") == accountId }?.let { accounts.remove(it) }
    }

    /**
     * Mutates only the matching credential; unrelated JSON must survive round trips.
     * @return id of the site that received the credential.
     */
    internal fun mergeCredential(
        root: JsonObject, domain: String?, app: String?, user: String, pass: String, linkedSiteId: String? = null,
        appLabel: String? = null, email: String = ""
    ): String? {
        require(pass.isNotEmpty())
        val sites = root.sites()
        val target = domain?.takeIf { it.isNotBlank() }?.let { "https://$it" }
            ?: "androidapp://${requireNotNull(app)}"
        val matchedIds = AutofillSiteMatcher.find(Gson().fromJson(root, Vault::class.java), domain, app, linkedSiteId, appLabel)
            .map { it.id }
        val siteObjects = sites.map { it.asJsonObject }
        val matchingSites = siteObjects.filter { it.str("id") in matchedIds || it.str("url") == target }
        val site = matchingSites.firstOrNull { s -> s.accounts().any { containsCredential(it.asJsonObject, user, email, pass) } }
            ?: matchingSites.firstOrNull { s -> s.accounts().any { matchesIdentity(it.asJsonObject, user, email) } }
            ?: siteObjects.firstOrNull { it.str("url") == target }
            ?: matchedIds.firstNotNullOfOrNull { id -> siteObjects.firstOrNull { it.str("id") == id } }
            ?: JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                addProperty("name", domain?.takeIf { it.isNotBlank() } ?: appLabel?.takeIf { it.isNotBlank() } ?: app)
                addProperty("url", target)
                add("accounts", JsonArray())
            }.also { sites.add(it) }
        val accounts = site.accounts()
        val list = accounts.map { it.asJsonObject }
        val trimmed = user.trim()
        // A password alone only matches an unchanged saved password.
        val account = list.firstOrNull { containsCredential(it, trimmed, email, pass) }
            ?: list.firstOrNull { matchesIdentity(it, trimmed, email) }
            ?: JsonObject().apply {
            addProperty("id", UUID.randomUUID().toString())
            addProperty("user", trimmed)
            addProperty("notes", "")
            accounts.add(this)
        }
        val identityChanged = email.isNotBlank() && (account.str("email") != email.trim() ||
            (account.str("user").equals(email.trim(), true) && trimmed.isNotEmpty() && account.str("user") != trimmed))
        if (account.str("pass") != pass || identityChanged) {
            account.addProperty("pass", pass)
            account.addProperty("lastModified", System.currentTimeMillis())
        }
        if (email.isNotBlank()) {
            if (account.str("user").equals(email.trim(), ignoreCase = true) && trimmed.isNotEmpty()) account.addProperty("user", trimmed)
            account.addProperty("email", email.trim())
        }
        return site.str("id").ifBlank { null }
    }

    private fun matchesIdentity(account: JsonObject, user: String, email: String): Boolean {
        val identifiers = listOf(user.trim(), email.trim()).filter { it.isNotEmpty() }
        if (identifiers.isEmpty()) return false
        val savedUser = account.str("user").trim()
        val savedEmail = account.str("email").trim()
        if (user.isNotBlank() && email.isNotBlank() && savedUser.isNotEmpty() &&
            !savedUser.equals(user.trim(), true) && !savedUser.equals(email.trim(), true)) return false
        if (email.isNotBlank() && savedEmail.isNotEmpty() && !savedEmail.equals(email.trim(), true)) return false
        return identifiers.any { it.equals(savedUser, true) || it.equals(savedEmail, true) }
    }

    private fun containsCredential(account: JsonObject, user: String, email: String, pass: String): Boolean {
        if (account.str("pass") != pass) return false
        val aliases = listOf(account.str("user").trim(), account.str("email").trim())
        return listOf(user.trim(), email.trim()).filter { it.isNotEmpty() }.all { value -> aliases.any { it.equals(value, true) } }
    }
}
