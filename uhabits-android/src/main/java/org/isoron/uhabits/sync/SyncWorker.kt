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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.sync.SyncException

/** Background job that runs one sync. Scheduled by [SyncManager]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = (applicationContext as HabitsApplication).component.syncManager
        if (!manager.account.isSignedIn) return@withContext Result.success()
        try {
            manager.syncNow()
            Result.success()
        } catch (e: SyncException.Network) {
            Result.retry()
        } catch (e: SyncException.Server) {
            if (e.status >= 500 || e.status == 429) Result.retry() else Result.failure()
        } catch (e: SyncException) {
            Result.failure()
        }
    }
}
