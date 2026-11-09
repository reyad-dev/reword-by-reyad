package com.reword.app.network

import com.google.gson.Gson
import com.reword.app.crypto.CryptoEngine
import com.reword.app.crypto.SecureStorage
import com.reword.app.model.SyncPayload
import com.reword.app.model.Vault
import com.reword.app.model.VaultResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Repository for synchronizing encrypted vaults with the Cloudflare Worker.
 */
class SyncRepository(private val storage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sanitizes worker URL exactly as core.js:cleanWorkerUrl does.
     */
    fun cleanWorkerUrl(rawUrl: String): String {
        var u = rawUrl.trim()
        u = u.replace(Regex("['\"`‘’“”′‘]+"), "")
        u = u.replace(Regex("/+$"), "").replace(Regex("/vault$"), "")
        u = u.trim().replace(Regex("^/+"), "")
        if (u.isNotEmpty() && !u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            u = "https://$u"
        }
        return u
    }

    /**
     * Pulls the encrypted vault from Cloudflare Worker.
     */
    suspend fun pullVault(syncId: String, rawWorkerUrl: String): Result<VaultResponse> = withContext(Dispatchers.IO) {
        try {
            val workerUrl = cleanWorkerUrl(rawWorkerUrl)
            val hashId = CryptoEngine.hashId(syncId)
            val targetUrl = "$workerUrl/vault?id=$hashId"

            val request = Request.Builder()
                .url(targetUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val vaultResp = gson.fromJson(body, VaultResponse::class.java)
                Result.success(vaultResp)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Pushes the encrypted vault blob to Cloudflare Worker.
     */
    suspend fun pushVault(
        syncId: String,
        rawWorkerUrl: String,
        encryptedBlob: String,
        timestamp: Long
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val workerUrl = cleanWorkerUrl(rawWorkerUrl)
            val hashId = CryptoEngine.hashId(syncId)
            val targetUrl = "$workerUrl/vault"

            val payload = SyncPayload(id = hashId, data = encryptedBlob, ts = timestamp)
            val jsonBody = gson.toJson(payload).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(targetUrl)
                .post(jsonBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 409) {
                    Result.success(true)
                } else {
                    val err = response.body?.string().orEmpty()
                    Result.failure(Exception("HTTP ${response.code}: $err"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Full sync: Pulls remote vault, decrypts, checks timestamp, updates local cache and session.
     */
    suspend fun sync(password: String): Result<Vault> = withContext(Dispatchers.Default) {
        val syncId = storage.syncId ?: return@withContext Result.failure(Exception("Sync ID not set"))
        val workerUrl = storage.workerUrl ?: return@withContext Result.failure(Exception("Worker URL not set"))

        val pullResult = pullVault(syncId, workerUrl)
        if (pullResult.isFailure) {
            return@withContext Result.failure(pullResult.exceptionOrNull() ?: Exception("Sync failed"))
        }

        val remoteResp = pullResult.getOrThrow()
        val localTs = storage.vaultTs

        // If remote has newer data or local has no data
        if (remoteResp.ts >= localTs || storage.vaultBlob == null) {
            try {
                val jsonString = CryptoEngine.decrypt(remoteResp.data, password)
                val vault = gson.fromJson(jsonString, Vault::class.java)

                storage.vaultBlob = remoteResp.data
                storage.vaultTs = remoteResp.ts
                SecureStorage.setSession(vault, password)
                Result.success(vault)
            } catch (e: Exception) {
                Result.failure(Exception("فشل فك تشفير الخزينة. تأكد من كلمة المرور الرئيسية."))
            }
        } else {
            // Local is newer -> push to remote
            val localBlob = storage.vaultBlob
            if (localBlob != null) {
                pushVault(syncId, workerUrl, localBlob, localTs)
            }
            val active = SecureStorage.activeVault
            if (active != null) {
                Result.success(active)
            } else {
                try {
                    val jsonString = CryptoEngine.decrypt(localBlob!!, password)
                    val vault = gson.fromJson(jsonString, Vault::class.java)
                    SecureStorage.setSession(vault, password)
                    Result.success(vault)
                } catch (e: Exception) {
                    // Wrong master password must show an error, not crash the app.
                    Result.failure(Exception("فشل فك تشفير الخزينة. تأكد من كلمة المرور الرئيسية."))
                }
            }
        }
    }
}
