package com.reword.app.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * ReWord Vault Data Structure
 * Matches 100% with the web/extension format:
 * {
 *   "sites": [
 *     {
 *       "id": "...",
 *       "name": "Twitter",
 *       "url": "https://twitter.com",
 *       "accounts": [
 *         {
 *           "id": "...",
 *           "user": "johndoe",
 *           "pass": "secret123",
 *           "notes": "",
 *           "lastModified": 1729000000000
 *         }
 *       ]
 *     }
 *   ]
 * }
 */
data class Vault(
    @SerializedName("sites") val sites: List<Site> = emptyList()
) : Serializable

data class Site(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("tags") val tags: String? = "",
    @SerializedName("accounts") val accounts: List<Account> = emptyList()
) : Serializable

data class Account(
    @SerializedName("id") val id: String = "",
    @SerializedName("user") val user: String = "",
    @SerializedName("pass") val pass: String = "",
    @SerializedName("notes") val notes: String = "",
    @SerializedName("lastModified") val lastModified: Long = 0L,
    @SerializedName("email") val email: String? = null
) : Serializable

data class SyncPayload(
    @SerializedName("id") val id: String,
    @SerializedName("data") val data: String,
    @SerializedName("ts") val ts: Long
)

data class VaultResponse(
    @SerializedName("data") val data: String = "",
    @SerializedName("ts") val ts: Long = 0L,
    @SerializedName("error") val error: String? = null
)

data class AutofillMatch(
    val siteName: String,
    val siteUrl: String,
    val username: String,
    val password: String
)
