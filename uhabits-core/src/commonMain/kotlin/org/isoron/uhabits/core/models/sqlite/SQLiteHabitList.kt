/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
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
package org.isoron.uhabits.core.models.sqlite

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.Synchronized
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.memory.MemoryHabitList

/**
 * Implementation of a [HabitList] that is backed by SQLite.
 */
@Inject
class SQLiteHabitList(private val modelFactory: ModelFactory) : HabitList() {
    private val repository: HabitRepository = (modelFactory as SQLModelFactory).habitRepository
    private val list: MemoryHabitList = MemoryHabitList()
    private var loaded = false

    /**
     * The database row each habit object was last loaded from or written to.
     * [update] compares against it, so that fields the app did not change keep
     * whatever is in the database now, e.g. values that sync wrote meanwhile.
     */
    private val base = HashMap<Long, HabitData>()

    private fun remember(data: HabitData) {
        data.id?.let { base[it] = data.copy() }
    }

    /**
     * Three-way merge of an in-memory habit with the database: fields the app
     * changed since the object was loaded win, all other fields come from the
     * database. Without this, an edit made through a stale object would write
     * old values back and, through the sync triggers, revert remote edits.
     */
    private fun mergeWithDatabase(mine: HabitData): HabitData {
        val id = mine.id ?: return mine
        val old = base[id] ?: return mine
        val db = repository.findById(id) ?: return mine
        if (db == old) return mine
        fun <T> pick(m: T, o: T, d: T) = if (m != o) m else d
        return HabitData(
            id = id,
            name = pick(mine.name, old.name, db.name),
            description = pick(mine.description, old.description, db.description),
            question = pick(mine.question, old.question, db.question),
            freqNum = pick(mine.freqNum, old.freqNum, db.freqNum),
            freqDen = pick(mine.freqDen, old.freqDen, db.freqDen),
            color = pick(mine.color, old.color, db.color),
            position = pick(mine.position, old.position, db.position),
            reminderHour = pick(mine.reminderHour, old.reminderHour, db.reminderHour),
            reminderMin = pick(mine.reminderMin, old.reminderMin, db.reminderMin),
            reminderDays = pick(mine.reminderDays, old.reminderDays, db.reminderDays),
            highlight = db.highlight,
            archived = pick(mine.archived, old.archived, db.archived),
            type = pick(mine.type, old.type, db.type),
            targetValue = pick(mine.targetValue, old.targetValue, db.targetValue),
            targetType = pick(mine.targetType, old.targetType, db.targetType),
            unit = pick(mine.unit, old.unit, db.unit),
            uuid = pick(mine.uuid, old.uuid, db.uuid)
        )
    }

    private fun loadRecords() {
        if (loaded) return
        loaded = true
        list.removeAll()
        val records = repository.findAll()
        var shouldRebuildOrder = false
        for ((expectedPosition, rec) in records.withIndex()) {
            if (rec.position != expectedPosition) shouldRebuildOrder = true
            val h = modelFactory.buildHabit()
            copyTo(rec, h)
            remember(rec)
            (h.originalEntries as SQLiteEntryList).habitId = h.id
            list.add(h)
        }
        if (shouldRebuildOrder) rebuildOrder()
    }

    @Synchronized
    override fun add(habit: Habit) {
        loadRecords()
        require(list.indexOf(habit) < 0) { "habit already added" }
        habit.position = size()
        val data = copyFrom(habit)
        val id = repository.insert(data)
        habit.id = id
        data.id = id
        remember(data)
        (habit.originalEntries as SQLiteEntryList).habitId = id
        list.add(habit)
        observable.notifyListeners()
    }

    @Synchronized
    override fun getById(id: Long): Habit? {
        loadRecords()
        return list.getById(id)
    }

    @Synchronized
    override fun getByUUID(uuid: String?): Habit? {
        loadRecords()
        return list.getByUUID(uuid)
    }

    @Synchronized
    override fun getByPosition(position: Int): Habit {
        loadRecords()
        return list.getByPosition(position)
    }

    @Synchronized
    override fun getFiltered(matcher: HabitMatcher?): HabitList {
        loadRecords()
        return list.getFiltered(matcher)
    }

    @set:Synchronized
    override var primaryOrder: Order
        get() = list.primaryOrder
        set(order) {
            list.primaryOrder = order
            observable.notifyListeners()
        }

    @set:Synchronized
    override var secondaryOrder: Order
        get() = list.secondaryOrder
        set(order) {
            list.secondaryOrder = order
            observable.notifyListeners()
        }

    @Synchronized
    override fun indexOf(h: Habit): Int {
        loadRecords()
        return list.indexOf(h)
    }

    @Synchronized
    override fun iterator(): Iterator<Habit> {
        loadRecords()
        return list.iterator()
    }

    @Synchronized
    private fun rebuildOrder() {
        val records = repository.findAll()
        for ((pos, r) in records.withIndex()) {
            if (r.position != pos) {
                r.position = pos
                repository.update(r)
                if (base.containsKey(r.id)) base[r.id!!] = base[r.id]!!.copy(position = pos)
            }
        }
    }

    @Synchronized
    override fun remove(h: Habit) {
        loadRecords()
        list.remove(h)
        h.originalEntries.clear()
        repository.delete(h.id!!)
        base.remove(h.id!!)
        rebuildOrder()
        observable.notifyListeners()
    }

    @Synchronized
    override fun removeAll() {
        list.removeAll()
        base.clear()
        repository.execSQL("delete from habits")
        repository.execSQL("delete from repetitions")
        observable.notifyListeners()
    }

    @Synchronized
    override fun reorder(from: Habit, to: Habit) {
        loadRecords()
        val fromPos = from.position
        val toPos = to.position
        list.reorder(from, to)
        if (toPos < fromPos) {
            repository.execSQL(
                "update habits set position = position + 1 " +
                    "where position >= $toPos and position < $fromPos"
            )
        } else {
            repository.execSQL(
                "update habits set position = position - 1 " +
                    "where position > $fromPos and position <= $toPos"
            )
        }
        val data = mergeWithDatabase(copyFrom(from))
        data.position = toPos
        repository.update(data)
        remember(data)
        observable.notifyListeners()
    }

    @Synchronized
    override fun repair() {
        loadRecords()
        rebuildOrder()
        observable.notifyListeners()
    }

    @Synchronized
    override fun size(): Int {
        loadRecords()
        return list.size()
    }

    @Synchronized
    override fun update(habits: List<Habit>) {
        loadRecords()
        list.update(habits)
        for (h in habits) {
            val mine = copyFrom(h)
            val merged = mergeWithDatabase(mine)
            repository.update(merged)
            if (merged != mine) copyTo(merged, h)
            remember(merged)
        }
        observable.notifyListeners()
    }

    override fun resort() {
        list.resort()
        observable.notifyListeners()
    }

    @Synchronized
    fun reload() {
        loaded = false
    }

    /**
     * Brings the in-memory habits up to date with the database after it was
     * changed underneath us (by sync). Existing [Habit] objects are updated in
     * place, so screens holding a reference keep working; new rows become new
     * habits and missing rows are dropped.
     *
     * @return the habits that were added or updated. Callers should
     * invalidate their entries (if needed) and recompute them.
     */
    @Synchronized
    fun refreshFromDatabase(): List<Habit> {
        if (!loaded) {
            loadRecords()
            return list.toList()
        }
        var records = repository.findAll()
        if (records.withIndex().any { (i, r) -> r.position != i }) {
            rebuildOrder()
            records = repository.findAll()
        }
        val seen = HashSet<String>()
        val touched = mutableListOf<Habit>()
        for (rec in records) {
            val uuid = rec.uuid ?: continue
            seen.add(uuid)
            val existing = list.getByUUID(uuid)
            remember(rec)
            if (existing == null) {
                val h = modelFactory.buildHabit()
                copyTo(rec, h)
                (h.originalEntries as SQLiteEntryList).habitId = h.id
                list.add(h)
                touched.add(h)
            } else {
                copyTo(rec, existing)
                (existing.originalEntries as SQLiteEntryList).habitId = existing.id
                touched.add(existing)
            }
        }
        for (h in list.toList()) {
            if (h.uuid !in seen) list.remove(h)
        }
        list.resort()
        observable.notifyListeners()
        return touched
    }

    companion object {
        fun copyFrom(habit: Habit): HabitData {
            val (numerator, denominator) = habit.frequency
            return HabitData(
                id = habit.id,
                name = habit.name,
                description = habit.description,
                question = habit.question,
                freqNum = numerator,
                freqDen = denominator,
                color = habit.color.paletteIndex,
                position = habit.position,
                reminderHour = habit.reminder?.hour,
                reminderMin = habit.reminder?.minute,
                reminderDays = habit.reminder?.days?.toInteger() ?: 0,
                highlight = 0,
                archived = if (habit.isArchived) 1 else 0,
                type = habit.type.value,
                targetValue = habit.targetValue,
                targetType = habit.targetType.value,
                unit = habit.unit,
                uuid = habit.uuid
            )
        }

        fun copyTo(data: HabitData, habit: Habit) {
            habit.id = data.id
            habit.name = data.name
            habit.description = data.description
            habit.question = data.question
            habit.frequency = Frequency(data.freqNum, data.freqDen)
            habit.color = PaletteColor(data.color)
            habit.isArchived = data.archived != 0
            habit.type = HabitType.fromInt(data.type)
            habit.targetType = NumericalHabitType.fromInt(data.targetType)
            habit.targetValue = data.targetValue
            habit.unit = data.unit
            habit.position = data.position
            habit.uuid = data.uuid
            habit.reminder = if (data.reminderHour != null && data.reminderMin != null) {
                Reminder(
                    data.reminderHour!!,
                    data.reminderMin!!,
                    WeekdayList(data.reminderDays)
                )
            } else {
                null
            }
        }
    }
}
