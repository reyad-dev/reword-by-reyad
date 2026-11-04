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

    suspend fun save(storage: SecureStorage, domain: String?, app: String?, user: String, pass: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(pass.isNotEmpty())
                val password = requireNotNull(SecureStorage.activeMasterPassword)
                pullNewerRemote(storage, password)
                // Edit the original JSON so metadata and fields unknown to Android survive saving.
                val root = JsonParser.parseString(CryptoEngine.decrypt(requireNotNull(storage.vaultBlob), password)).asJsonObject
                val linkKey = AutofillSiteMatcher.linkKey(domain, app)
                val siteId = mergeCredential(root, domain, app, user, pass, storage.getLinkedSiteId(linkKey))
                storage.linkSite(linkKey, siteId)
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

    /**
     * Mutates only the matching credential; unrelated JSON must survive round trips.
     * @return id of the site that received the credential.
     */
    internal fun mergeCredential(
        root: JsonObject, domain: String?, app: String?, user: String, pass: String, linkedSiteId: String? = null
    ): String? {
        require(pass.isNotEmpty())
        val sites = root.getAsJsonArray("sites") ?: JsonArray().also { root.add("sites", it) }
        val target = domain?.takeIf { it.isNotBlank() }?.let { "https://$it" }
            ?: "androidapp://${requireNotNull(app)}"
        val matchedIds = AutofillSiteMatcher.find(Gson().fromJson(root, Vault::class.java), domain, app, linkedSiteId)
            .map { it.id }
        val siteObjects = sites.map { it.asJsonObject }
        val site = siteObjects.firstOrNull { it.str("url") == target }
            ?: matchedIds.firstNotNullOfOrNull { id -> siteObjects.firstOrNull { it.str("id") == id } }
            ?: JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                addProperty("name", domain ?: app)
                addProperty("url", target)
                add("accounts", JsonArray())
            }.also { sites.add(it) }
        val accounts = site.getAsJsonArray("accounts") ?: JsonArray().also { site.add("accounts", it) }
        val list = accounts.map { it.asJsonObject }
        val trimmed = user.trim()
        val account = (if (trimmed.isNotEmpty()) {
            // Same login typed with different case/spaces is the same account (password change)
            list.firstOrNull { it.str("user").trim().equals(trimmed, ignoreCase = true) }
        } else {
            // Password-only screens: update the site's single account instead of adding a nameless one
            list.firstOrNull { it.str("pass") == pass } ?: list.singleOrNull()
        }) ?: JsonObject().apply {
            addProperty("id", UUID.randomUUID().toString())
            addProperty("user", trimmed)
            addProperty("notes", "")
            accounts.add(this)
        }
        if (account.str("pass") != pass) {
            account.addProperty("pass", pass)
            account.addProperty("lastModified", System.currentTimeMillis())
        }
        return site.str("id").ifBlank { null }
    }

}
