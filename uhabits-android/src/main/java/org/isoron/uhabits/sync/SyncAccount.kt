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

import android.content.Context
import android.content.SharedPreferences
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.inject.AppContext

/**
 * Sign-in state for sync, kept in its own private preferences file. It is
 * deliberately not part of the database, so restoring a backup never
 * restores someone's session.
 */
@AppScope
@Inject
class SyncAccount(@AppContext context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_account", Context.MODE_PRIVATE)

    /** Server base URL, without trailing slash. Empty when not configured. */
    var serverUrl: String
        get() = (prefs.getString(KEY_SERVER, null) ?: BuildConfig.SYNC_SERVER_URL).trimEnd('/')
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()

    val isConfigured: Boolean get() = serverUrl.startsWith("https://") || serverUrl.startsWith("http://")

    val isSignedIn: Boolean get() = refreshToken != null

    var accountId: String?
        get() = prefs.getString(KEY_ACCOUNT_ID, null)
        private set(v) = prefs.edit().putString(KEY_ACCOUNT_ID, v).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        private set(v) = prefs.edit().putString(KEY_EMAIL, v).apply()

    /** Account the local database was last synced with. Survives sign-out. */
    var lastAccountId: String?
        get() = prefs.getString(KEY_LAST_ACCOUNT_ID, null)
        set(v) = prefs.edit().putString(KEY_LAST_ACCOUNT_ID, v).apply()

    @get:Synchronized
    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)

    @get:Synchronized
    val accessTokenExpiresAt: Long get() = prefs.getLong(KEY_ACCESS_EXP, 0)

    @get:Synchronized
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(v) = prefs.edit().putLong(KEY_LAST_SYNC, v).apply()

    /** Short machine-readable reason of the last failed sync, or null. */
    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(v) = prefs.edit().putString(KEY_LAST_ERROR, v).apply()

    /** PKCE verifier and email login id of a sign-in in progress. */
    var pendingVerifier: String?
        get() = prefs.getString(KEY_PENDING_VERIFIER, null)
        set(v) = prefs.edit().putString(KEY_PENDING_VERIFIER, v).apply()

    var pendingLoginId: String?
        get() = prefs.getString(KEY_PENDING_LOGIN, null)
        set(v) = prefs.edit().putString(KEY_PENDING_LOGIN, v).apply()

    var pendingEmail: String?
        get() = prefs.getString(KEY_PENDING_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_PENDING_EMAIL, v).apply()

    @Synchronized
    fun saveTokens(tokens: AuthTokens) {
        prefs.edit().apply {
            putString(KEY_ACCESS, tokens.accessToken)
            putLong(KEY_ACCESS_EXP, System.currentTimeMillis() + tokens.expiresInSeconds * 1000)
            putString(KEY_REFRESH, tokens.refreshToken)
            putString(KEY_ACCOUNT_ID, tokens.accountId)
            putString(KEY_EMAIL, tokens.email)
        }.commit()
    }

    @Synchronized
    fun clearSession() {
        prefs.edit().apply {
            remove(KEY_ACCESS)
            remove(KEY_ACCESS_EXP)
            remove(KEY_REFRESH)
            remove(KEY_ACCOUNT_ID)
            remove(KEY_EMAIL)
            remove(KEY_LAST_SYNC)
            remove(KEY_PENDING_VERIFIER)
            remove(KEY_PENDING_LOGIN)
            remove(KEY_PENDING_EMAIL)
        }.commit()
    }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_LAST_ACCOUNT_ID = "last_account_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXP = "access_token_expires_at"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_PENDING_LOGIN = "pending_login_id"
        private const val KEY_PENDING_EMAIL = "pending_email"
    }
}

data class AuthTokens(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val accountId: String,
    val email: String?
)
