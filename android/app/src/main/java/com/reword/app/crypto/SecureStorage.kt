package com.reword.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.reword.app.model.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages locally persisted encrypted settings and in-memory unlocked vault cache.
 */
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "reword_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    companion object {
        private const val KEY_SYNC_ID = "rw_sync_id"
        private const val KEY_WORKER_URL = "rw_worker_url"
        private const val KEY_VAULT_BLOB = "rw_vault_blob"
        private const val KEY_VAULT_TS = "rw_vault_ts"
        private const val KEY_BIOMETRIC_IV = "rw_biometric_iv"
        private const val KEY_BIOMETRIC_PASS = "rw_biometric_pass"
        private const val KEY_BIOMETRIC_ENABLED = "rw_biometric_enabled"
        private const val KEY_SITE_LINKS = "rw_site_links"

        // In-memory unlocked vault cache during active session
        @Volatile
        var activeVault: Vault? = null
            private set

        @Volatile
        var activeMasterPassword: String? = null
            private set

        private val _vaultFlow = MutableStateFlow<Vault?>(null)

        /** Emits on unlock, sync, autofill save and lock so open screens stay current. */
        val vaultFlow: StateFlow<Vault?> = _vaultFlow

        fun setSession(vault: Vault, password: String) {
            activeVault = vault
            activeMasterPassword = password
            _vaultFlow.value = vault
        }

        fun clearSession() {
            activeVault = null
            activeMasterPassword = null
            _vaultFlow.value = null
        }
    }

    /** Site the user picked for an app/domain, so the next autofill request recognizes it. */
    fun getLinkedSiteId(key: String?): String? = key?.let { readLinks()[it] }

    fun linkSite(key: String?, siteId: String?) {
        if (key.isNullOrBlank() || siteId.isNullOrBlank()) return
        val links = readLinks().toMutableMap().apply { put(key, siteId) }
        prefs.edit().putString(KEY_SITE_LINKS, gson.toJson(links)).apply()
    }

    private fun readLinks(): Map<String, String> = try {
        prefs.getString(KEY_SITE_LINKS, null)?.let {
            gson.fromJson<Map<String, String>>(it, object : TypeToken<Map<String, String>>() {}.type)
        } ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    var syncId: String?
        get() = prefs.getString(KEY_SYNC_ID, null)
        set(value) = prefs.edit().putString(KEY_SYNC_ID, value).apply()

    var workerUrl: String?
        get() = prefs.getString(KEY_WORKER_URL, null)
        set(value) = prefs.edit().putString(KEY_WORKER_URL, value).apply()

    var vaultBlob: String?
        get() = prefs.getString(KEY_VAULT_BLOB, null)
        set(value) = prefs.edit().putString(KEY_VAULT_BLOB, value).apply()

    var vaultTs: Long
        get() = prefs.getLong(KEY_VAULT_TS, 0L)
        set(value) = prefs.edit().putLong(KEY_VAULT_TS, value).apply()

    /** Called on an IO dispatcher: acknowledge a captured credential only after the encrypted save succeeds. */
    fun saveVault(blob: String, timestamp: Long): Boolean = prefs.edit()
        .putString(KEY_VAULT_BLOB, blob)
        .putLong(KEY_VAULT_TS, timestamp)
        .commit()

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    /**
     * Stores master password wrapped by Keystore cipher.
     */
    fun saveBiometricEncryptedPassword(cipherBytes: ByteArray, iv: ByteArray) {
        prefs.edit()
            .putString(KEY_BIOMETRIC_PASS, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
            .putString(KEY_BIOMETRIC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putBoolean(KEY_BIOMETRIC_ENABLED, true)
            .apply()
    }

    fun getBiometricIv(): ByteArray? {
        val s = prefs.getString(KEY_BIOMETRIC_IV, null) ?: return null
        return Base64.decode(s, Base64.NO_WRAP)
    }

    fun getBiometricEncryptedPassword(): ByteArray? {
        val s = prefs.getString(KEY_BIOMETRIC_PASS, null) ?: return null
        return Base64.decode(s, Base64.NO_WRAP)
    }

    fun isConfigured(): Boolean {
        return !syncId.isNullOrBlank() && !workerUrl.isNullOrBlank()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        clearSession()
        KeystoreHelper.deleteKey()
    }
}
