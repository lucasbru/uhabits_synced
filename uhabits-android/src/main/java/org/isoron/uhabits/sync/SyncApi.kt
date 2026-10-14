/*
 * Copyright (C) 2016-2026 Loop Habit Tracker contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.sync

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.core.sync.FieldValue
import org.isoron.uhabits.core.sync.SyncException
import org.isoron.uhabits.core.sync.SyncRecord
import org.isoron.uhabits.core.sync.SyncRequest
import org.isoron.uhabits.core.sync.SyncResponse
import org.isoron.uhabits.core.sync.SyncTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HTTP client for the sync server (see docs/SYNC.md for the API). Uses only
 * platform classes (HttpURLConnection, org.json), so no extra dependencies.
 *
 * All methods block; call them from a background thread.
 */
class SyncApi(private val account: SyncAccount) : SyncTransport {

    data class Providers(val oauth: List<String>, val email: Boolean)

    /** A non-2xx answer from the server. [code] is the server's error code. */
    class ApiException(val status: Int, val code: String, message: String) : Exception(message)

    // ---------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------

    fun providers(): Providers {
        val json = call("GET", "/v1/auth/providers", null, null)
        val list = json.optJSONArray("providers") ?: JSONArray()
        return Providers((0 until list.length()).map { list.getString(it) }, json.optBoolean("email", false))
    }

    fun oauthStartUri(provider: String, codeChallenge: String): Uri =
        Uri.parse("${account.serverUrl}/v1/auth/oauth/$provider/start").buildUpon()
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    /** Sends a sign-in email. Returns the login id needed to verify the typed code. */
    fun startEmail(email: String, codeChallenge: String): String {
        val body = JSONObject()
            .put("email", email)
            .put("redirectUri", REDIRECT_URI)
            .put("codeChallenge", codeChallenge)
        return call("POST", "/v1/auth/email/start", body, null).getString("loginId")
    }

    fun verifyEmailCode(loginId: String, code: String, codeVerifier: String): AuthTokens {
        val body = JSONObject()
            .put("loginId", loginId)
            .put("code", code)
            .put("codeVerifier", codeVerifier)
        return parseTokens(call("POST", "/v1/auth/email/verify", body, null))
    }

    /** Redeems the one-time code delivered to the app through the deep link. */
    fun exchangeCode(code: String, codeVerifier: String): AuthTokens {
        val body = JSONObject().put("code", code).put("codeVerifier", codeVerifier)
        return parseTokens(call("POST", "/v1/auth/token", body, null))
    }

    fun logout() {
        val refresh = account.refreshToken ?: return
        try {
            call("POST", "/v1/auth/logout", JSONObject().put("refreshToken", refresh), null)
        } catch (e: Exception) {
            // Best effort: the session expires on its own anyway.
        }
    }

    fun deleteAccount() {
        authorized { token -> call("DELETE", "/v1/account", null, token) }
    }

    // ---------------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------------

    override suspend fun sync(request: SyncRequest): SyncResponse = withContext(Dispatchers.IO) {
        try {
            val json = authorized { token -> call("POST", "/v1/sync", encodeRequest(request), token) }
            decodeResponse(json)
        } catch (e: ApiException) {
            throw when {
                e.status == 401 -> SyncException.Unauthorized(e.code)
                e.code == "clock_skew" -> SyncException.ClockSkew(e.message ?: e.code)
                else -> SyncException.Server(e.status, e.code)
            }
        } catch (e: IOException) {
            throw SyncException.Network(e)
        }
    }

    // ---------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------

    private fun <T> authorized(block: (String) -> T): T {
        val token = validAccessToken()
        return try {
            block(token)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            block(refreshTokens(token))
        }
    }

    private fun validAccessToken(): String {
        val token = account.accessToken
        val fresh = account.accessTokenExpiresAt - System.currentTimeMillis() > 60_000
        if (token != null && fresh) return token
        return refreshTokens(token)
    }

    /**
     * Rotates the refresh token. Synchronized, and skipped if another thread
     * already rotated it since [staleAccessToken] was read: presenting an
     * already-used refresh token makes the server revoke the session.
     */
    @Synchronized
    private fun refreshTokens(staleAccessToken: String?): String {
        val current = account.accessToken
        if (current != null && current != staleAccessToken &&
            account.accessTokenExpiresAt - System.currentTimeMillis() > 60_000
        ) {
            return current
        }
        val refresh = account.refreshToken ?: throw ApiException(401, "signed_out", "Not signed in")
        val json = try {
            call("POST", "/v1/auth/refresh", JSONObject().put("refreshToken", refresh), null)
        } catch (e: ApiException) {
            // Only a definite "no" ends the session; 5xx or 429 are retried later.
            if (e.status == 401) account.clearSession()
            throw e
        }
        val tokens = parseTokens(json)
        account.saveTokens(tokens)
        return tokens.accessToken
    }

    // ---------------------------------------------------------------------
    // HTTP + JSON
    // ---------------------------------------------------------------------

    private fun call(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        if (!account.isConfigured) throw IOException("Sync server is not configured")
        val conn = URL(account.serverUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = if (text.isBlank()) JSONObject() else try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("error", "bad_response").put("message", text.take(200))
            }
            if (status !in 200..299) {
                throw ApiException(
                    status,
                    json.optString("error", "http_$status"),
                    json.optString("message", "HTTP $status")
                )
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTokens(json: JSONObject): AuthTokens {
        val acc = json.getJSONObject("account")
        return AuthTokens(
            accessToken = json.getString("accessToken"),
            expiresInSeconds = json.getLong("expiresIn"),
            refreshToken = json.getString("refreshToken"),
            accountId = acc.getString("id"),
            email = if (acc.isNull("email")) null else acc.getString("email")
        )
    }

    companion object {
        // org.isoron.uhabits.sync in release, org.isoron.uhabits.sync.sync in debug
        // (applicationIdSuffix). Must match the intent filter in AndroidManifest.xml.
        const val REDIRECT_SCHEME = BuildConfig.APPLICATION_ID + ".sync"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://auth"
        private const val USER_AGENT = "LoopHabitTracker-Android"

        fun encodeRequest(r: SyncRequest): JSONObject {
            val changes = JSONArray()
            for (rec in r.changes) changes.put(encodeRecord(rec))
            return JSONObject()
                .put("cursor", r.cursor)
                .put("node", r.node)
                .put("limit", r.limit)
                .put("changes", changes)
        }

        fun encodeRecord(rec: SyncRecord): JSONObject {
            val fields = JSONObject()
            for ((name, fv) in rec.fields) {
                fields.put(
                    name,
                    JSONObject()
                        .put("v", fv.v ?: JSONObject.NULL)
                        .put("c", fv.c)
                        .put("n", fv.n)
                )
            }
            return JSONObject().put("type", rec.type).put("key", rec.key).put("fields", fields)
        }

        fun decodeResponse(json: JSONObject): SyncResponse {
            val arr = json.optJSONArray("changes") ?: JSONArray()
            val records = ArrayList<SyncRecord>(arr.length())
            for (i in 0 until arr.length()) records.add(decodeRecord(arr.getJSONObject(i)))
            return SyncResponse(
                changes = records,
                cursor = json.getLong("cursor"),
                hasMore = json.optBoolean("hasMore", false),
                serverHlc = json.optLong("serverHlc", 0)
            )
        }

        fun decodeRecord(o: JSONObject): SyncRecord {
            val fieldsJson = o.getJSONObject("fields")
            val fields = LinkedHashMap<String, FieldValue>()
            val names = fieldsJson.keys()
            while (names.hasNext()) {
                val name = names.next()
                val f = fieldsJson.getJSONObject(name)
                val raw = f.opt("v")
                val v: Any? = when (raw) {
                    null, JSONObject.NULL -> null
                    is Int -> raw.toLong()
                    is Long, is Double, is String, is Boolean -> raw
                    is Number -> raw.toDouble()
                    else -> raw.toString()
                }
                fields[name] = FieldValue(v, f.getLong("c"), f.getString("n"))
            }
            val seq = if (o.has("seq")) o.getLong("seq") else null
            return SyncRecord(o.getString("type"), o.getString("key"), fields, seq)
        }

        fun newCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return base64Url(bytes)
        }

        fun codeChallenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
