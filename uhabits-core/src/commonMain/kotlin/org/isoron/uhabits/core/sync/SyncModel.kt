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

/**
 * Value of a single field of a synced record, together with the clock of the
 * write that produced it. See docs/SYNC.md.
 *
 * [v] is a JSON scalar: String, Number, Boolean or null.
 * [c] is a hybrid logical clock value: max(previous + 1, unixMillis * 1000).
 * [n] is the id of the node (app install) that made the write; it breaks ties.
 */
data class FieldValue(
    val v: Any?,
    val c: Long,
    val n: String
) {
    /** True if this write wins over a write with clock [otherC] by node [otherN]. */
    fun isNewerThan(otherC: Long, otherN: String): Boolean =
        c > otherC || (c == otherC && n > otherN)

    fun isNewerThan(other: FieldValue?): Boolean =
        other == null || isNewerThan(other.c, other.n)
}

/** A habit or an entry, as exchanged with the server. */
data class SyncRecord(
    val type: String,
    val key: String,
    val fields: Map<String, FieldValue>,
    val seq: Long? = null
) {
    companion object {
        const val HABIT = "habit"
        const val ENTRY = "entry"

        fun entryKey(habitUuid: String, timestamp: Long) = "$habitUuid:$timestamp"

        /** Splits an entry key into (habit uuid, timestamp), or null if malformed. */
        fun parseEntryKey(key: String): Pair<String, Long>? {
            val i = key.lastIndexOf(':')
            if (i <= 0) return null
            val ts = key.substring(i + 1).toLongOrNull() ?: return null
            return Pair(key.substring(0, i), ts)
        }
    }
}

data class SyncRequest(
    val cursor: Long,
    val node: String,
    val limit: Int,
    val changes: List<SyncRecord>
)

data class SyncResponse(
    val changes: List<SyncRecord>,
    val cursor: Long,
    val hasMore: Boolean,
    val serverHlc: Long = 0
)

/** Sends one push+pull round trip to the server. Implemented per platform. */
interface SyncTransport {
    /**
     * @throws SyncException on any failure. Implementations must map
     * HTTP 401 to [SyncException.Unauthorized].
     */
    suspend fun sync(request: SyncRequest): SyncResponse
}

sealed class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The session is gone; the user must sign in again. */
    class Unauthorized(message: String = "unauthorized") : SyncException(message)

    /** The server rejected our clocks; the device clock is probably wrong. */
    class ClockSkew(message: String = "clock_skew") : SyncException(message)

    /** Server answered with an unexpected status. Retrying later may help. */
    class Server(val status: Int, message: String) : SyncException("HTTP $status: $message")

    /** No connection, timeout, DNS failure, etc. Retrying later will help. */
    class Network(cause: Throwable) : SyncException(cause.message ?: "network error", cause)
}

data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    /** UUIDs of habits whose fields or entries were changed by remote data. */
    val changedHabits: Set<String>
)
