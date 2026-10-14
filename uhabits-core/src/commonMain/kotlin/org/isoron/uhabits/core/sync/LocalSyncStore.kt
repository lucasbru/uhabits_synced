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

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.query
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.rollback
import org.isoron.platform.io.run
import org.isoron.uhabits.core.sync.SyncRecord.Companion.ENTRY
import org.isoron.uhabits.core.sync.SyncRecord.Companion.HABIT
import org.isoron.uhabits.core.sync.SyncSchema.DELETED
import org.isoron.uhabits.core.sync.SyncSchema.ENTRY_FIELDS
import org.isoron.uhabits.core.sync.SyncSchema.ENTRY_FIELD_NAMES
import org.isoron.uhabits.core.sync.SyncSchema.HABIT_FIELDS
import org.isoron.uhabits.core.sync.SyncSchema.HABIT_FIELD_NAMES
import kotlin.math.max

/**
 * Reads local changes from, and applies remote changes to, the app database.
 * All the SQL of the sync feature lives here; see docs/SYNC.md.
 */
class LocalSyncStore(private val db: Database) {

    data class Dirty(val type: String, val key: String, val hlc: Long)

    class DirtyBatch(
        /** Records to push. */
        val records: List<SyncRecord>,
        /** Dirty markers to clear once the server has accepted [records]. */
        val snapshot: List<Dirty>,
        /** True if more dirty records may be waiting. */
        val hasMore: Boolean
    )

    private data class Clock(val c: Long, val n: String)

    fun ensureSchema() = SyncSchema.ensure(db)

    fun node(): String =
        db.querySingle("SELECT node FROM SyncState WHERE id = 1") { it.getText(0) }!!

    fun cursor(): Long =
        db.querySingle("SELECT server_cursor FROM SyncState WHERE id = 1") { it.getLong(0) } ?: 0

    fun hlc(): Long =
        db.querySingle("SELECT hlc FROM SyncState WHERE id = 1") { it.getLong(0) } ?: 0

    fun dirtyCount(): Int =
        db.querySingle("SELECT count(*) FROM SyncDirty") { it.getInt(0) } ?: 0

    // ---------------------------------------------------------------------
    // Push side
    // ---------------------------------------------------------------------

    fun readDirty(limit: Int): DirtyBatch {
        val rows = mutableListOf<Dirty>()
        db.query("SELECT rtype, rkey, hlc FROM SyncDirty ORDER BY hlc LIMIT $limit") {
            rows.add(Dirty(it.getText(0), it.getText(1), it.getLong(2)))
        }
        val records = mutableListOf<SyncRecord>()
        val snapshot = mutableListOf<Dirty>()
        val unpushable = mutableListOf<Dirty>()
        for (d in rows) {
            val rec = when (d.type) {
                HABIT -> readHabit(d.key)
                ENTRY -> readEntry(d.key)
                else -> null
            }
            if (rec == null) {
                unpushable.add(d)
            } else {
                records.add(rec)
                snapshot.add(d)
            }
        }
        if (unpushable.isNotEmpty()) transaction { for (d in unpushable) clearDirty(d) }
        return DirtyBatch(records, snapshot, rows.size >= limit)
    }

    internal fun readHabit(uuid: String): SyncRecord? {
        val clocks = loadClocks(HABIT, uuid)
        val cols = HABIT_FIELDS.joinToString(", ") { it.name }
        val values = db.querySingle("SELECT $cols FROM Habits WHERE uuid = ?", uuid) { stmt ->
            HABIT_FIELDS.mapIndexed { i, f -> f.name to readColumn(stmt, i, f) }.toMap()
        }
        val fields = LinkedHashMap<String, FieldValue>()
        if (values == null) {
            // Row is gone: push a tombstone, if we know when it was deleted.
            val c = clocks[DELETED] ?: return null
            fields[DELETED] = FieldValue(1L, c.c, c.n)
        } else {
            for (f in HABIT_FIELDS) {
                val c = clocks[f.name] ?: UNKNOWN
                fields[f.name] = FieldValue(values[f.name], c.c, c.n)
            }
            val c = clocks[DELETED] ?: UNKNOWN
            fields[DELETED] = FieldValue(0L, c.c, c.n)
        }
        return SyncRecord(HABIT, uuid, fields)
    }

    internal fun readEntry(key: String): SyncRecord? {
        val (uuid, timestamp) = SyncRecord.parseEntryKey(key) ?: return null
        val habitId = habitIdByUuid(uuid) ?: return null
        val values = db.querySingle(
            "SELECT value, notes FROM Repetitions WHERE habit = ? AND timestamp = ?",
            habitId.toString(),
            timestamp.toString()
        ) { stmt ->
            ENTRY_FIELDS.mapIndexed { i, f -> f.name to readColumn(stmt, i, f) }.toMap()
        } ?: return null
        val clocks = loadClocks(ENTRY, key)
        val fields = LinkedHashMap<String, FieldValue>()
        for (f in ENTRY_FIELDS) {
            val c = clocks[f.name] ?: UNKNOWN
            fields[f.name] = FieldValue(values[f.name], c.c, c.n)
        }
        return SyncRecord(ENTRY, key, fields)
    }

    // ---------------------------------------------------------------------
    // Pull side
    // ---------------------------------------------------------------------

    /**
     * Applies a server response in one transaction: merges [changes] into the
     * local tables (without re-marking them dirty), clears the [pushed] dirty
     * markers that were not modified in the meantime, and stores [newCursor].
     *
     * @return uuids of habits whose fields or entries changed locally.
     */
    fun applyResponse(changes: List<SyncRecord>, pushed: List<Dirty>, newCursor: Long): Set<String> {
        val changed = HashSet<String>()
        transaction {
            db.run("UPDATE SyncState SET applying = 1 WHERE id = 1")
            var maxClock = 0L
            for (rec in changes) for (fv in rec.fields.values) maxClock = max(maxClock, fv.c)
            for (rec in changes) if (rec.type == HABIT && applyHabit(rec)) changed.add(rec.key)
            for (rec in changes) if (rec.type == ENTRY) applyEntry(rec)?.let { changed.add(it) }
            changed.addAll(retryOrphans())
            for (d in pushed) clearDirty(d)
            db.run("UPDATE SyncState SET applying = 0, server_cursor = ?, hlc = max(hlc, ?) WHERE id = 1") {
                bindLong(1, newCursor)
                bindLong(2, maxClock)
            }
        }
        return changed
    }

    private fun applyHabit(rec: SyncRecord): Boolean {
        val uuid = rec.key
        val local = loadClocks(HABIT, uuid)
        val winners = rec.fields.filter { (name, fv) ->
            name in HABIT_FIELD_NAMES && fv.isNewerThan(local[name]?.c ?: -1, local[name]?.n ?: "")
        }
        if (winners.isEmpty()) return false
        val habitId = habitIdByUuid(uuid)
        val deletedWinner = winners[DELETED]
        val deleted = when {
            deletedWinner != null -> isTrue(deletedWinner.v)
            habitId != null -> false
            else -> local[DELETED] != null // row missing and we have a tombstone
        }
        storeClocks(HABIT, uuid, winners)

        if (deleted) {
            if (habitId == null) return false
            db.run("DELETE FROM Repetitions WHERE habit = ?") { bindLong(1, habitId) }
            db.run("DELETE FROM Habits WHERE id = ?") { bindLong(1, habitId) }
            forgetEntriesOf(uuid)
            return true
        }

        if (habitId == null) {
            insertHabit(uuid, rec.fields)
            return true
        }

        val updates = HABIT_FIELDS.filter { winners.containsKey(it.name) }
        if (updates.isNotEmpty()) {
            val set = updates.joinToString(", ") { "${it.name} = ?" }
            db.run("UPDATE Habits SET $set WHERE id = ?") {
                updates.forEachIndexed { i, f -> bindValue(this, i + 1, f, winners[f.name]!!.v) }
                bindLong(updates.size + 1, habitId)
            }
        }
        return true
    }

    private fun insertHabit(uuid: String, fields: Map<String, FieldValue>) {
        val cols = HABIT_FIELDS.joinToString(", ") { it.name }
        val marks = HABIT_FIELDS.joinToString(", ") { "?" }
        val nextPosition = db.querySingle("SELECT coalesce(max(position) + 1, 0) FROM Habits") {
            it.getLong(0)
        } ?: 0L
        db.run("INSERT INTO Habits ($cols, highlight, uuid) VALUES ($marks, 0, ?)") {
            HABIT_FIELDS.forEachIndexed { i, f ->
                val v = if (f.name == "position" && fields[f.name] == null) nextPosition else fields[f.name]?.v
                bindValue(this, i + 1, f, v)
            }
            bindText(HABIT_FIELDS.size + 1, uuid)
        }
    }

    /** @return the habit uuid if a local entry changed, null otherwise. */
    private fun applyEntry(rec: SyncRecord): String? {
        val (uuid, timestamp) = SyncRecord.parseEntryKey(rec.key) ?: return null
        val fields = rec.fields.filterKeys { it in ENTRY_FIELD_NAMES }
        val habitId = habitIdByUuid(uuid)
        if (habitId == null) {
            if (!isHabitDeletedLocally(uuid)) parkOrphan(rec.key, uuid, fields)
            return null
        }
        val local = loadClocks(ENTRY, rec.key)
        val winners = fields.filter { (name, fv) ->
            fv.isNewerThan(local[name]?.c ?: -1, local[name]?.n ?: "")
        }
        if (winners.isEmpty()) return null
        storeClocks(ENTRY, rec.key, winners)

        val exists = db.querySingle(
            "SELECT 1 FROM Repetitions WHERE habit = ? AND timestamp = ?",
            habitId.toString(),
            timestamp.toString()
        ) { true } ?: false

        if (exists) {
            val updates = ENTRY_FIELDS.filter { winners.containsKey(it.name) }
            val set = updates.joinToString(", ") { "${it.name} = ?" }
            db.run("UPDATE Repetitions SET $set WHERE habit = ? AND timestamp = ?") {
                updates.forEachIndexed { i, f -> bindValue(this, i + 1, f, winners[f.name]!!.v) }
                bindLong(updates.size + 1, habitId)
                bindLong(updates.size + 2, timestamp)
            }
        } else {
            db.run("INSERT INTO Repetitions (habit, timestamp, value, notes) VALUES (?, ?, ?, ?)") {
                bindLong(1, habitId)
                bindLong(2, timestamp)
                bindValue(this, 3, ENTRY_FIELDS[0], fields["value"]?.v)
                bindValue(this, 4, ENTRY_FIELDS[1], fields["notes"]?.v)
            }
        }
        return uuid
    }

    private fun isHabitDeletedLocally(uuid: String): Boolean =
        db.querySingle(
            "SELECT 1 FROM SyncClock WHERE rtype = 'habit' AND rkey = ? AND field = 'deleted'",
            uuid
        ) { true } ?: false

    private fun forgetEntriesOf(uuid: String) {
        val pattern = "$uuid:%"
        db.run("DELETE FROM SyncClock WHERE rtype = 'entry' AND rkey LIKE ?") { bindText(1, pattern) }
        db.run("DELETE FROM SyncDirty WHERE rtype = 'entry' AND rkey LIKE ?") { bindText(1, pattern) }
        db.run("DELETE FROM SyncOrphan WHERE habit = ?") { bindText(1, uuid) }
    }

    // Entries can arrive before their habit, e.g. when the habit was edited
    // after the entry and therefore has a higher sequence number. They wait
    // in SyncOrphan until the habit shows up.

    private fun parkOrphan(key: String, habitUuid: String, fields: Map<String, FieldValue>) {
        val merged = HashMap(loadOrphan(key) ?: emptyMap())
        for ((name, fv) in fields) {
            if (fv.isNewerThan(merged[name])) merged[name] = fv
        }
        val value = merged["value"]
        val notes = merged["notes"]
        db.run(
            "INSERT OR REPLACE INTO SyncOrphan (rkey, habit, value, value_hlc, value_node, " +
                "notes, notes_hlc, notes_node) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ) {
            bindText(1, key)
            bindText(2, habitUuid)
            if (value != null) {
                bindValue(this, 3, ENTRY_FIELDS[0], value.v)
                bindLong(4, value.c)
                bindText(5, value.n)
            } else {
                for (i in 3..5) bindNull(i)
            }
            if (notes != null) {
                bindValue(this, 6, ENTRY_FIELDS[1], notes.v)
                bindLong(7, notes.c)
                bindText(8, notes.n)
            } else {
                for (i in 6..8) bindNull(i)
            }
        }
    }

    private fun loadOrphan(key: String): Map<String, FieldValue>? =
        db.querySingle(
            "SELECT value, value_hlc, value_node, notes, notes_hlc, notes_node FROM SyncOrphan WHERE rkey = ?",
            key
        ) { orphanFields(it) }

    private fun orphanFields(stmt: PreparedStatement, offset: Int = 0): Map<String, FieldValue> {
        val m = HashMap<String, FieldValue>()
        val vc = stmt.getLongOrNull(offset + 1)
        if (vc != null) m["value"] = FieldValue(stmt.getLongOrNull(offset), vc, stmt.getTextOrNull(offset + 2) ?: "")
        val nc = stmt.getLongOrNull(offset + 4)
        if (nc != null) m["notes"] = FieldValue(stmt.getTextOrNull(offset + 3), nc, stmt.getTextOrNull(offset + 5) ?: "")
        return m
    }

    private fun retryOrphans(): Set<String> {
        val orphans = mutableListOf<SyncRecord>()
        db.query(
            "SELECT rkey, habit, value, value_hlc, value_node, notes, notes_hlc, notes_node " +
                "FROM SyncOrphan WHERE habit IN (SELECT uuid FROM Habits) " +
                "OR habit IN (SELECT rkey FROM SyncClock WHERE rtype = 'habit' AND field = 'deleted')"
        ) { stmt ->
            orphans.add(SyncRecord(ENTRY, stmt.getText(0), orphanFields(stmt, offset = 2)))
        }
        val changed = HashSet<String>()
        for (rec in orphans) {
            db.run("DELETE FROM SyncOrphan WHERE rkey = ?") { bindText(1, rec.key) }
            applyEntry(rec)?.let { changed.add(it) }
        }
        return changed
    }

    // ---------------------------------------------------------------------
    // Sign-in
    // ---------------------------------------------------------------------

    /**
     * Prepares this device to sync with a (possibly different) account:
     * every record becomes dirty, fields that were never stamped get the
     * lowest possible clock (so data already in the account wins on conflict),
     * and the cursor goes back to zero so the whole account is pulled.
     */
    fun prepareForSignIn() {
        transaction {
            // A fresh node id, in case this database was restored from
            // another device's backup and would otherwise share its id.
            db.run("UPDATE SyncState SET node = ? WHERE id = 1") { bindText(1, SyncSchema.newNodeId()) }
            for (f in HABIT_FIELDS.map { it.name } + DELETED) {
                db.run(
                    "INSERT OR IGNORE INTO SyncClock (rtype, rkey, field, hlc, node) " +
                        "SELECT 'habit', h.uuid, '$f', 0, s.node FROM Habits h, SyncState s " +
                        "WHERE h.uuid IS NOT NULL AND s.id = 1"
                )
            }
            for (f in ENTRY_FIELDS) {
                db.run(
                    "INSERT OR IGNORE INTO SyncClock (rtype, rkey, field, hlc, node) " +
                        "SELECT 'entry', h.uuid || ':' || r.timestamp, '${f.name}', 0, s.node " +
                        "FROM Repetitions r JOIN Habits h ON h.id = r.habit, SyncState s " +
                        "WHERE h.uuid IS NOT NULL AND s.id = 1"
                )
            }
            db.run(
                "INSERT OR IGNORE INTO SyncDirty (rtype, rkey, hlc) " +
                    "SELECT 'habit', uuid, 0 FROM Habits WHERE uuid IS NOT NULL"
            )
            db.run(
                "INSERT OR IGNORE INTO SyncDirty (rtype, rkey, hlc) " +
                    "SELECT 'entry', h.uuid || ':' || r.timestamp, 0 " +
                    "FROM Repetitions r JOIN Habits h ON h.id = r.habit WHERE h.uuid IS NOT NULL"
            )
            db.run("DELETE FROM SyncOrphan")
            db.run("UPDATE SyncState SET server_cursor = 0 WHERE id = 1")
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun habitIdByUuid(uuid: String): Long? =
        db.querySingle("SELECT id FROM Habits WHERE uuid = ?", uuid) { it.getLong(0) }

    private fun loadClocks(type: String, key: String): Map<String, Clock> {
        val m = HashMap<String, Clock>()
        db.query("SELECT field, hlc, node FROM SyncClock WHERE rtype = ? AND rkey = ?", type, key) {
            m[it.getText(0)] = Clock(it.getLong(1), it.getText(2))
        }
        return m
    }

    private fun storeClocks(type: String, key: String, fields: Map<String, FieldValue>) {
        for ((name, fv) in fields) {
            db.run("INSERT OR REPLACE INTO SyncClock (rtype, rkey, field, hlc, node) VALUES (?, ?, ?, ?, ?)") {
                bindText(1, type)
                bindText(2, key)
                bindText(3, name)
                bindLong(4, fv.c)
                bindText(5, fv.n)
            }
        }
    }

    private fun clearDirty(d: Dirty) {
        db.run("DELETE FROM SyncDirty WHERE rtype = ? AND rkey = ? AND hlc = ?") {
            bindText(1, d.type)
            bindText(2, d.key)
            bindLong(3, d.hlc)
        }
    }

    private inline fun <T> transaction(block: () -> T): T {
        db.begin()
        try {
            val result = block()
            db.commit()
            return result
        } catch (e: Throwable) {
            try {
                db.rollback()
            } catch (ignored: Throwable) {
                // Keep the original exception.
            }
            throw e
        }
    }

    companion object {
        /** Clock of a value we have no record of writing. Loses to any real write. */
        private val UNKNOWN = Clock(0, "")

        /** The server rejects longer strings; better to truncate than to get stuck. */
        const val MAX_TEXT = 10_000

        internal fun readColumn(stmt: PreparedStatement, index: Int, f: FieldSpec): Any? = when (f.type) {
            ColumnType.TEXT -> stmt.getTextOrNull(index)?.take(MAX_TEXT)
            ColumnType.INT -> stmt.getLongOrNull(index)
            ColumnType.REAL -> stmt.getRealOrNull(index)
        }

        /** Converts a JSON scalar to the column's type, falling back to the default. */
        fun coerce(f: FieldSpec, v: Any?): Any? {
            val x: Any? = when (f.type) {
                ColumnType.TEXT -> v?.toString()
                ColumnType.INT -> when (v) {
                    is Number -> v.toLong()
                    is Boolean -> if (v) 1L else 0L
                    is String -> v.toLongOrNull()
                    else -> null
                }
                ColumnType.REAL -> when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            return x ?: if (f.nullable) null else f.default
        }

        internal fun bindValue(stmt: PreparedStatement, index: Int, f: FieldSpec, v: Any?) {
            when (val x = coerce(f, v)) {
                null -> stmt.bindNull(index)
                is Long -> stmt.bindLong(index, x)
                is Double -> stmt.bindReal(index, x)
                else -> stmt.bindText(index, x.toString())
            }
        }

        private fun isTrue(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            is String -> v == "1" || v == "true"
            else -> false
        }
    }
}
