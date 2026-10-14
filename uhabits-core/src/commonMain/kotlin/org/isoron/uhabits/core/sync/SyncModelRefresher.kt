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

import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList

/**
 * Passed to CommandRunner listeners after sync changed local data, so the
 * habit list, widgets and reminders refresh exactly as after a local edit.
 */
class SyncChangesAppliedCommand(val habitUuids: Set<String>) : Command {
    override fun run() {}
}

object SyncModelRefresher {
    /**
     * Reloads the in-memory model after [SyncEngine] wrote to the database.
     * Call on a background thread, then notify listeners on the main thread
     * with [SyncChangesAppliedCommand].
     */
    fun refresh(habitList: HabitList, changedHabitUuids: Set<String>) {
        if (changedHabitUuids.isEmpty()) return
        val list = habitList as? SQLiteHabitList ?: return
        for (habit in list.refreshFromDatabase()) {
            if (habit.uuid !in changedHabitUuids) continue
            (habit.originalEntries as? SQLiteEntryList)?.invalidate()
            habit.recompute()
        }
    }
}
