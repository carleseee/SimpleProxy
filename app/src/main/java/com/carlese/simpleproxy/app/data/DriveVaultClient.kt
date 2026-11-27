package com.carlese.simpleproxy.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.SecretKey

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthOutcome
    data class Error(val message: String) : DriveAuthOutcome
}

data class DriveVaultKey(
    val vaultId: String,
    val dek: SecretKey
)

class DriveUnauthorizedException(message: String) : IOException(message)

object DriveVaultClient {
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val KEY_FILE_NAME = "simpleproxy-vault-key-v1.json"
    private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
    private const val TIMEOUT_MS = 15_000

    suspend fun authorizeSilently(context: Context): DriveAuthOutcome {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            val token = result.accessToken
            val pending = result.pendingIntent
            when {
                !result.hasResolution() && !token.isNullOrBlank() -> DriveAuthOutcome.Authorized(token)
                pending != null -> DriveAuthOutcome.NeedsConsent(pending)
                else -> DriveAuthOutcome.Error("Google Drive authorization unavailable")
            }
        } catch (t: Throwable) {
            DriveAuthOutcome.Error(t.localizedMessage ?: "Google Drive authorization error")
        }
    }

    fun extractTokenFromIntent(context: Context, data: Intent?): Result<String> {
        return runCatching {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            authResult.accessToken?.takeIf { it.isNotBlank() }
                ?: error("Google Drive access token missing")
        }
    }

    @Suppress("DEPRECATION")
    suspend fun clearCachedToken(context: Context, accessToken: String) {
        if (accessToken.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                GoogleAuthUtil.clearToken(context.applicationContext, accessToken)
            }
        }
    }

    suspend fun fetchOrCreateVaultKey(
        uid: String,
        accessToken: String
    ): DriveVaultKey = withContext(Dispatchers.IO) {
        val existingId = findVaultFileId(accessToken)
        if (existingId != null) {
            val rawJson = downloadFileContent(existingId, accessToken)
            val json = JSONObject(rawJson)
            val fileUid = json.optString("uid")
            if (fileUid.isNotBlank() && fileUid != uid) {
                throw IllegalStateException("Google Drive account does not match signed-in account")
            }
            val vaultId = json.optString("vaultId").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val dek = E2eeCipher.decodeDek(json.getString("dekB64"))
            DriveVaultKey(vaultId = vaultId, dek = dek)
        } else {
            val vaultId = UUID.randomUUID().toString()
            val dek = E2eeCipher.generateMasterDek()
            val payloadJson = JSONObject().apply {
                put("version", 1)
                put("uid", uid)
                put("vaultId", vaultId)
                put("dekB64", E2eeCipher.encodeDek(dek))
                put("createdAt", System.currentTimeMillis())
            }.toString()
            uploadVaultFile(accessToken, payloadJson)
            DriveVaultKey(vaultId = vaultId, dek = dek)
        }
    }

    private fun findVaultFileId(accessToken: String): String? {
        val q = URLEncoder.encode("name='$KEY_FILE_NAME' and trashed=false", "UTF-8")
        val url = "$FILES_ENDPOINT?spaces=appDataFolder&q=$q&orderBy=createdTime&fields=files(id,name)&pageSize=1"
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            val code = conn.responseCode
            val body = readResponseBody(conn, code)
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw DriveUnauthorizedException("Drive list unauthorized (401): $body")
            }
            if (code !in 200..299) {
                throw IOException("Drive list failed ($code): $body")
            }
            val files = JSONObject(body).optJSONArray("files")
            if (files != null && files.length() > 0) {
                files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFileContent(fileId: String, accessToken: String): String {
        val url = "$FILES_ENDPOINT/$fileId?alt=media"
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return try {
            val code = conn.responseCode
            val body = readResponseBody(conn, code)
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw DriveUnauthorizedException("Drive download unauthorized (401): $body")
            }
            if (code !in 200..299) {
                throw IOException("Drive download failed ($code): $body")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun uploadVaultFile(accessToken: String, payloadJson: String) {
        val boundary = "sp_drive_${UUID.randomUUID().toString().replace("-", "")}"
        val metadataJson = JSONObject().apply {
            put("name", KEY_FILE_NAME)
            put("parents", org.json.JSONArray().put("appDataFolder"))
            put("mimeType", "application/json")
        }.toString()

        val bodyText = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(payloadJson).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)

        val conn = (URI.create(UPLOAD_ENDPOINT).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setRequestProperty("Content-Length", bodyBytes.size.toString())
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        try {
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            val response = readResponseBody(conn, code)
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw DriveUnauthorizedException("Drive upload unauthorized (401): $response")
            }
            if (code !in 200..299) {
                throw IOException("Drive upload failed ($code): $response")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }
}
