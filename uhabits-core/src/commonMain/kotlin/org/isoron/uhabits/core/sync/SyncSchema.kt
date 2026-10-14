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
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import kotlin.random.Random

enum class ColumnType { TEXT, INT, REAL }

/**
 * A synced field. The wire name equals the SQLite column name.
 *
 * [default] is used when a record arrives without this field, or with a null
 * value for a column that must not be null.
 */
class FieldSpec(
    val name: String,
    val type: ColumnType,
    val default: Any?,
    val nullable: Boolean = false
)

/**
 * Change-tracking schema. Lives in its own tables and triggers instead of a
 * numbered migration, so that the main schema (and therefore backup files)
 * stays identical to upstream Loop. See docs/SYNC.md, section 3.
 */
object SyncSchema {
    /** Bump when trigger definitions change; triggers are then recreated. */
    const val VERSION = 1

    const val DELETED = "deleted"

    val HABIT_FIELDS = listOf(
        FieldSpec("name", ColumnType.TEXT, ""),
        FieldSpec("description", ColumnType.TEXT, ""),
        FieldSpec("question", ColumnType.TEXT, ""),
        FieldSpec("freq_num", ColumnType.INT, 1L),
        FieldSpec("freq_den", ColumnType.INT, 1L),
        FieldSpec("color", ColumnType.INT, 8L),
        FieldSpec("position", ColumnType.INT, 0L),
        FieldSpec("reminder_hour", ColumnType.INT, null, nullable = true),
        FieldSpec("reminder_min", ColumnType.INT, null, nullable = true),
        FieldSpec("reminder_days", ColumnType.INT, 127L),
        FieldSpec("archived", ColumnType.INT, 0L),
        FieldSpec("type", ColumnType.INT, 0L),
        FieldSpec("target_value", ColumnType.REAL, 0.0),
        FieldSpec("target_type", ColumnType.INT, 0L),
        FieldSpec("unit", ColumnType.TEXT, "")
    )

    val ENTRY_FIELDS = listOf(
        FieldSpec("value", ColumnType.INT, -1L),
        FieldSpec("notes", ColumnType.TEXT, "")
    )

    val HABIT_FIELD_NAMES: Set<String> = HABIT_FIELDS.map { it.name }.toSet() + DELETED
    val ENTRY_FIELD_NAMES: Set<String> = ENTRY_FIELDS.map { it.name }.toSet()

    private val TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS SyncState (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            node TEXT NOT NULL,
            hlc INTEGER NOT NULL DEFAULT 0,
            applying INTEGER NOT NULL DEFAULT 0,
            server_cursor INTEGER NOT NULL DEFAULT 0,
            schema_version INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS SyncClock (
            rtype TEXT NOT NULL,
            rkey TEXT NOT NULL,
            field TEXT NOT NULL,
            hlc INTEGER NOT NULL,
            node TEXT NOT NULL,
            PRIMARY KEY (rtype, rkey, field))""",
        """CREATE TABLE IF NOT EXISTS SyncDirty (
            rtype TEXT NOT NULL,
            rkey TEXT NOT NULL,
            hlc INTEGER NOT NULL,
            PRIMARY KEY (rtype, rkey))""",
        """CREATE TABLE IF NOT EXISTS SyncOrphan (
            rkey TEXT PRIMARY KEY,
            habit TEXT NOT NULL,
            value INTEGER, value_hlc INTEGER, value_node TEXT,
            notes TEXT, notes_hlc INTEGER, notes_node TEXT)"""
    )

    private val TRIGGER_NAMES = listOf(
        "sync_habit_insert",
        "sync_habit_update",
        "sync_habit_delete",
        "sync_entry_insert",
        "sync_entry_update"
    )

    /** Advances the HLC to max(hlc + 1, now_ms * 1000). */
    private const val BUMP =
        "UPDATE SyncState SET hlc = max(hlc + 1, " +
            "CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER) * 1000);"

    private const val NOT_APPLYING = "(SELECT applying FROM SyncState WHERE id = 1) = 0"

    private fun stampHabit(uuidExpr: String, field: String, condition: String? = null): String {
        val where = if (condition != null) " AND ($condition)" else ""
        return "INSERT OR REPLACE INTO SyncClock (rtype, rkey, field, hlc, node) " +
            "SELECT 'habit', $uuidExpr, '$field', hlc, node FROM SyncState WHERE id = 1$where;"
    }

    private fun markDirty(type: String, keyExpr: String, condition: String? = null): String {
        val where = if (condition != null) " AND ($condition)" else ""
        return "INSERT OR REPLACE INTO SyncDirty (rtype, rkey, hlc) " +
            "SELECT '$type', $keyExpr, hlc FROM SyncState WHERE id = 1$where;"
    }

    private const val ENTRY_KEY_NEW =
        "(SELECT uuid FROM Habits WHERE id = NEW.habit) || ':' || NEW.timestamp"

    private fun stampEntry(field: String, condition: String? = null): String {
        val where = if (condition != null) " AND ($condition)" else ""
        return "INSERT OR REPLACE INTO SyncClock (rtype, rkey, field, hlc, node) " +
            "SELECT 'entry', $ENTRY_KEY_NEW, '$field', hlc, node FROM SyncState WHERE id = 1$where;"
    }

    internal fun triggerStatements(): List<String> {
        val habitCols = HABIT_FIELDS.map { it.name }
        val anyHabitChange = (habitCols + "uuid").joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        val uuidChanged = "OLD.uuid IS NOT NEW.uuid"

        val habitInsert = buildString {
            append("CREATE TRIGGER sync_habit_insert AFTER INSERT ON Habits ")
            append("WHEN $NOT_APPLYING AND NEW.uuid IS NOT NULL BEGIN ")
            append(BUMP)
            for (f in habitCols + DELETED) append(stampHabit("NEW.uuid", f))
            append(markDirty("habit", "NEW.uuid"))
            append(" END")
        }

        val habitUpdate = buildString {
            append("CREATE TRIGGER sync_habit_update AFTER UPDATE ON Habits ")
            append("WHEN $NOT_APPLYING AND NEW.uuid IS NOT NULL AND ($anyHabitChange) BEGIN ")
            append(BUMP)
            for (f in habitCols) {
                append(stampHabit("NEW.uuid", f, "OLD.$f IS NOT NEW.$f OR $uuidChanged"))
            }
            append(stampHabit("NEW.uuid", DELETED, uuidChanged))
            append(markDirty("habit", "NEW.uuid"))
            // If the uuid itself changed, the old record is gone.
            append(stampHabit("OLD.uuid", DELETED, "OLD.uuid IS NOT NULL AND $uuidChanged"))
            append(markDirty("habit", "OLD.uuid", "OLD.uuid IS NOT NULL AND $uuidChanged"))
            append(" END")
        }

        val habitDelete = buildString {
            append("CREATE TRIGGER sync_habit_delete AFTER DELETE ON Habits ")
            append("WHEN $NOT_APPLYING AND OLD.uuid IS NOT NULL BEGIN ")
            append(BUMP)
            append(stampHabit("OLD.uuid", DELETED))
            append(markDirty("habit", "OLD.uuid"))
            append(" END")
        }

        val habitKnown = "(SELECT uuid FROM Habits WHERE id = NEW.habit) IS NOT NULL"

        val entryInsert = buildString {
            append("CREATE TRIGGER sync_entry_insert AFTER INSERT ON Repetitions ")
            append("WHEN $NOT_APPLYING AND $habitKnown BEGIN ")
            append(BUMP)
            for (f in ENTRY_FIELDS) append(stampEntry(f.name))
            append(markDirty("entry", ENTRY_KEY_NEW))
            append(" END")
        }

        val entryUpdate = buildString {
            append("CREATE TRIGGER sync_entry_update AFTER UPDATE ON Repetitions ")
            append("WHEN $NOT_APPLYING AND $habitKnown AND ")
            append("(OLD.value IS NOT NEW.value OR OLD.notes IS NOT NEW.notes) BEGIN ")
            append(BUMP)
            for (f in ENTRY_FIELDS) {
                append(stampEntry(f.name, "OLD.${f.name} IS NOT NEW.${f.name}"))
            }
            append(markDirty("entry", ENTRY_KEY_NEW))
            append(" END")
        }

        return listOf(habitInsert, habitUpdate, habitDelete, entryInsert, entryUpdate)
    }

    /**
     * Creates the sync tables and triggers if needed. Idempotent; call once
     * every time the database is opened, before anything writes to it.
     */
    fun ensure(db: Database) {
        for (sql in TABLES) db.run(sql)
        db.run("UPDATE Habits SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL")
        db.run("INSERT OR IGNORE INTO SyncState (id, node) VALUES (1, ?)") {
            bindText(1, newNodeId())
        }
        db.run("UPDATE SyncState SET applying = 0 WHERE id = 1")
        val version = db.querySingle("SELECT schema_version FROM SyncState WHERE id = 1") {
            it.getInt(0)
        } ?: 0
        if (version != VERSION) {
            for (name in TRIGGER_NAMES) db.run("DROP TRIGGER IF EXISTS $name")
            for (sql in triggerStatements()) db.run(sql)
            db.run("UPDATE SyncState SET schema_version = $VERSION WHERE id = 1")
        }
    }

    fun newNodeId(): String {
        val bytes = Random.nextBytes(8)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
