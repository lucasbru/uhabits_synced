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
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.sync.LocalSyncStore
import org.isoron.uhabits.core.sync.SyncChangesAppliedCommand
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.SyncException
import org.isoron.uhabits.core.sync.SyncModelRefresher
import org.isoron.uhabits.core.sync.SyncResult
import org.isoron.uhabits.inject.AppContext
import java.util.concurrent.TimeUnit

/**
 * Glue between the sync engine and the app: schedules background syncs,
 * reacts to local edits, refreshes the UI after remote changes, and handles
 * sign-in / sign-out.
 */
@AppScope
@Inject
class SyncManager(
    @AppContext private val context: Context,
    private val modelFactory: ModelFactory,
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    val account: SyncAccount
) : CommandRunner.Listener {

    private val store by lazy { LocalSyncStore((modelFactory as SQLModelFactory).database) }
    val api by lazy { SyncApi(account) }
    private val engine by lazy { SyncEngine(store, api) }

    private val _running = MutableStateFlow(false)

    /** True while a sync round is in progress. */
    val running: StateFlow<Boolean> = _running

    /** Bumped whenever sign-in state or last-sync info changes. */
    val changes = MutableStateFlow(0)

    /** Creates the change-tracking tables and triggers. Call before any DB write. */
    fun ensureSchema() {
        store.ensureSchema()
    }

    fun start() {
        commandRunner.addListener(this)
        if (account.isSignedIn) {
            schedulePeriodic()
            requestSync()
        }
    }

    override fun onCommandFinished(command: Command) {
        if (command is SyncChangesAppliedCommand) return
        if (account.isSignedIn) requestSync(delaySeconds = DEBOUNCE_SECONDS)
    }

    /** Called when the app comes to the foreground. */
    fun onAppResumed() {
        if (account.isSignedIn) requestSync()
    }

    fun requestSync(delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancelWork() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_ONCE)
        wm.cancelUniqueWork(WORK_PERIODIC)
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Runs a full sync round. Must not be called on the main thread. */
    suspend fun syncNow(): SyncResult {
        _running.value = true
        try {
            val result = engine.sync()
            if (result.changedHabits.isNotEmpty()) {
                // Refresh on the main thread: the model objects are shared with the UI.
                withContext(Dispatchers.Main) {
                    SyncModelRefresher.refresh(habitList, result.changedHabits)
                    commandRunner.notifyListeners(SyncChangesAppliedCommand(result.changedHabits))
                }
            }
            account.lastSyncAt = System.currentTimeMillis()
            account.lastError = null
            Log.i(TAG, "sync ok: pushed=${result.pushed} pulled=${result.pulled}")
            return result
        } catch (e: SyncException) {
            Log.w(TAG, "sync failed: ${e.message}")
            account.lastError = when (e) {
                is SyncException.Unauthorized -> ERROR_SIGNED_OUT
                is SyncException.ClockSkew -> ERROR_CLOCK
                is SyncException.Network -> ERROR_NETWORK
                is SyncException.Server -> ERROR_SERVER
            }
            if (e is SyncException.Unauthorized) {
                account.clearSession()
                cancelWork()
            }
            throw e
        } finally {
            _running.value = false
            changes.value += 1
        }
    }

    /**
     * Stores the session and prepares the local database for the account.
     * Existing local data is merged into the account; where both have a
     * value for the same field, the account's value wins.
     */
    suspend fun completeSignIn(tokens: AuthTokens) {
        withContext(Dispatchers.IO) {
            account.saveTokens(tokens)
            account.pendingVerifier = null
            account.pendingLoginId = null
            account.pendingEmail = null
            account.lastError = null
            store.prepareForSignIn()
            account.lastAccountId = tokens.accountId
        }
        changes.value += 1
        schedulePeriodic()
        requestSync()
    }

    /** Signs out on this device. Local data stays. */
    suspend fun signOut() {
        cancelWork()
        withContext(Dispatchers.IO) { api.logout() }
        account.clearSession()
        changes.value += 1
    }

    /** Deletes the account and all its data on the server. Local data stays. */
    suspend fun deleteAccount() {
        withContext(Dispatchers.IO) { api.deleteAccount() }
        cancelWork()
        account.clearSession()
        changes.value += 1
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val WORK_ONCE = "loop-sync-once"
        private const val WORK_PERIODIC = "loop-sync-periodic"
        private const val DEBOUNCE_SECONDS = 5L
        private const val PERIODIC_HOURS = 6L

        const val ERROR_SIGNED_OUT = "signed_out"
        const val ERROR_CLOCK = "clock_skew"
        const val ERROR_NETWORK = "network"
        const val ERROR_SERVER = "server"
    }
}
