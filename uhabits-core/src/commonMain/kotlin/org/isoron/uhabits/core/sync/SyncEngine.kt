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
package org.isoron.uhabits.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs sync rounds: push up to [pushLimit] dirty records and pull up to
 * [pullLimit] remote changes per request, until both sides are drained.
 *
 * The engine is safe to call concurrently; overlapping calls run one after
 * the other.
 */
class SyncEngine(
    private val store: LocalSyncStore,
    private val transport: SyncTransport,
    private val pushLimit: Int = 400,
    private val pullLimit: Int = 1000,
    private val maxRounds: Int = 10_000
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        var pushed = 0
        var pulled = 0
        val changed = HashSet<String>()
        var rounds = 0
        while (true) {
            val batch = store.readDirty(pushLimit)
            val request = SyncRequest(
                cursor = store.cursor(),
                node = store.node(),
                limit = pullLimit,
                changes = batch.records
            )
            val response = transport.sync(request)
            changed.addAll(store.applyResponse(response.changes, batch.snapshot, response.cursor))
            pushed += batch.records.size
            pulled += response.changes.size
            rounds += 1
            if (!batch.hasMore && !response.hasMore) break
            if (rounds >= maxRounds) break
        }
        SyncResult(pushed, pulled, changed)
    }
}
