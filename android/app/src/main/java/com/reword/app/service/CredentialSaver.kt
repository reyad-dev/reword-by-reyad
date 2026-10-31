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
                // Edit the original JSON so metadata and fields unknown to Android survive saving.
                val root = JsonParser.parseString(CryptoEngine.decrypt(requireNotNull(storage.vaultBlob), password)).asJsonObject
                mergeCredential(root, domain, app, user, pass)
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

    /** Mutates only the matching credential; unrelated JSON must survive round trips. */
    internal fun mergeCredential(root: JsonObject, domain: String?, app: String?, user: String, pass: String) {
        require(pass.isNotEmpty())
        val sites = root.getAsJsonArray("sites") ?: JsonArray().also { root.add("sites", it) }
        val target = domain?.takeIf { it.isNotBlank() }?.let { "https://$it" }
            ?: "androidapp://${requireNotNull(app)}"
        val matchedIds = AutofillSiteMatcher.find(Gson().fromJson(root, Vault::class.java), domain, app).map { it.id }.toSet()
        val site = sites.map { it.asJsonObject }.firstOrNull {
            it.get("url")?.asString == target || (domain != null &&
                it.get("id")?.asString in matchedIds)
        } ?: JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                addProperty("name", domain ?: app)
                addProperty("url", target)
                add("accounts", JsonArray())
            }.also { sites.add(it) }
        val accounts = site.getAsJsonArray("accounts") ?: JsonArray().also { site.add("accounts", it) }
        val account = accounts.map { it.asJsonObject }.firstOrNull { it.get("user")?.asString == user }
            ?: JsonObject().apply {
                addProperty("id", UUID.randomUUID().toString())
                addProperty("user", user)
                addProperty("notes", "")
                accounts.add(this)
            }
        account.addProperty("pass", pass)
        account.addProperty("lastModified", System.currentTimeMillis())
    }

}
