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

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-memory server with the same merge rules as the backend: field-wise
 * last-writer-wins on (clock, node), and a per-account sequence number
 * stamped on every record whose merged state changed.
 */
class FakeSyncServer : SyncTransport {
    private val records = LinkedHashMap<String, SyncRecord>()
    var seq = 0L
        private set
    var requests = 0
        private set

    /** Lets a test run code while a request is "on the wire". */
    var onRequest: (SyncRequest) -> Unit = {}

    fun record(type: String, key: String): SyncRecord? = records["$type:$key"]

    override suspend fun sync(request: SyncRequest): SyncResponse {
        requests++
        onRequest(request)
        for (incoming in request.changes) {
            val id = "${incoming.type}:${incoming.key}"
            val stored = records[id]
            val merged = HashMap(stored?.fields ?: emptyMap())
            var changed = false
            for ((name, fv) in incoming.fields) {
                if (fv.isNewerThan(merged[name])) {
                    merged[name] = fv
                    changed = true
                }
            }
            if (changed) {
                seq += 1
                records.remove(id) // keep map ordered by seq
                records[id] = SyncRecord(incoming.type, incoming.key, merged, seq)
            }
        }
        val page = records.values.filter { it.seq!! > request.cursor }.sortedBy { it.seq }
        val out = page.take(request.limit)
        val cursor = out.lastOrNull()?.seq ?: request.cursor
        return SyncResponse(out, cursor, hasMore = page.size > out.size)
    }
}

class Device(val db: Database, server: SyncTransport, pullLimit: Int = 1000, pushLimit: Int = 400) {
    val factory = SQLModelFactory(db)
    val habits = SQLiteHabitList(factory)
    val store = LocalSyncStore(db)
    val engine = SyncEngine(store, server, pushLimit = pushLimit, pullLimit = pullLimit)

    init {
        store.ensureSchema()
    }

    fun createHabit(name: String): Habit {
        val h = factory.buildHabit()
        h.name = name
        habits.add(h)
        return h
    }

    fun habit(uuid: String): Habit? = habits.getByUUID(uuid)

    suspend fun sync(): SyncResult {
        val result = engine.sync()
        SyncModelRefresher.refresh(habits, result.changedHabits)
        return result
    }

    fun count(sql: String): Int = db.querySingle(sql) { it.getInt(0) } ?: 0
}

class SyncEngineTest {
    private val day1 = LocalDate(2015, 1, 20)
    private val day2 = LocalDate(2015, 1, 21)

    @BeforeTest
    fun setUp() {
        setToday(LocalDate(2015, 1, 25))
    }

    private suspend fun newDevice(server: SyncTransport, pullLimit: Int = 1000, pushLimit: Int = 400) =
        Device(TestDatabaseHelper.createEmptyDatabase(), server, pullLimit, pushLimit)

    @Test
    fun testHabitAndEntryReachSecondDevice() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)

        val h = a.createHabit("Meditate")
        h.originalEntries.add(Entry(day1, Entry.YES_MANUAL, "felt good"))
        a.sync()
        b.sync()

        val copy = assertNotNull(b.habit(h.uuid!!))
        assertEquals("Meditate", copy.name)
        val entry = copy.originalEntries.get(day1)
        assertEquals(Entry.YES_MANUAL, entry.value)
        assertEquals("felt good", entry.notes)
        assertEquals(Entry.YES_MANUAL, copy.computedEntries.get(day1).value)
    }

    @Test
    fun testSecondSyncSendsNothing() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        a.createHabit("Run")
        val first = a.sync()
        assertEquals(1, first.pushed)
        assertEquals(0, a.store.dirtyCount())
        val second = a.sync()
        assertEquals(0, second.pushed)
        assertEquals(0, second.pulled)
    }

    @Test
    fun testConcurrentEditsOfDifferentFieldsBothSurvive() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)
        val uuid = a.createHabit("Read").uuid!!
        a.sync()
        b.sync()

        // Offline on both devices:
        val ha = a.habit(uuid)!!
        ha.name = "Read 20 pages"
        a.habits.update(ha)
        val hb = b.habit(uuid)!!
        hb.isArchived = true
        b.habits.update(hb)

        a.sync()
        b.sync()
        a.sync()

        for (d in listOf(a, b)) {
            val h = d.habit(uuid)!!
            assertEquals("Read 20 pages", h.name)
            assertTrue(h.isArchived)
        }
    }

    @Test
    fun testLaterWriteToSameFieldWins() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)
        val uuid = a.createHabit("Gym").uuid!!
        a.sync()
        b.sync() // b has now observed a's clock, so b's next edit is later

        val ha = a.habit(uuid)!!
        ha.name = "Gym (a)"
        a.habits.update(ha)
        a.sync()

        b.sync()
        val hb = b.habit(uuid)!!
        hb.name = "Gym (b)"
        b.habits.update(hb)
        b.sync()
        a.sync()

        assertEquals("Gym (b)", a.habit(uuid)!!.name)
        assertEquals("Gym (b)", b.habit(uuid)!!.name)
    }

    @Test
    fun testCheckmarksOnDifferentDaysMerge() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)
        val uuid = a.createHabit("Water").uuid!!
        a.sync()
        b.sync()

        a.habit(uuid)!!.originalEntries.add(Entry(day1, Entry.YES_MANUAL))
        b.habit(uuid)!!.originalEntries.add(Entry(day2, Entry.SKIP))
        a.sync()
        b.sync()
        a.sync()

        for (d in listOf(a, b)) {
            val entries = d.habit(uuid)!!.originalEntries
            assertEquals(Entry.YES_MANUAL, entries.get(day1).value)
            assertEquals(Entry.SKIP, entries.get(day2).value)
        }
    }

    @Test
    fun testValueChangeDoesNotTouchNotesClock() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val h = a.createHabit("Walk")
        h.originalEntries.add(Entry(day1, Entry.YES_MANUAL, "note"))
        val key = SyncRecord.entryKey(h.uuid!!, day1.unixTime)
        val notesClock = a.db.querySingle(
            "SELECT hlc FROM SyncClock WHERE rtype = 'entry' AND rkey = ? AND field = 'notes'",
            key
        ) { it.getLong(0) }
        h.originalEntries.add(Entry(day1, Entry.NO, "note"))
        val notesClockAfter = a.db.querySingle(
            "SELECT hlc FROM SyncClock WHERE rtype = 'entry' AND rkey = ? AND field = 'notes'",
            key
        ) { it.getLong(0) }
        assertEquals(notesClock, notesClockAfter)
    }

    @Test
    fun testDeletedHabitDisappearsEverywhere() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)
        val uuid = a.createHabit("Smoke less").uuid!!
        a.sync()
        b.sync()

        a.habits.remove(a.habit(uuid)!!)
        // b checks it off while offline
        b.habit(uuid)!!.originalEntries.add(Entry(day1, Entry.YES_MANUAL))
        a.sync()
        b.sync()
        a.sync()

        assertNull(a.habit(uuid))
        assertNull(b.habit(uuid))
        assertEquals(0, b.count("SELECT count(*) FROM Repetitions"))
        assertEquals(0, a.count("SELECT count(*) FROM SyncOrphan"))
        assertEquals(0, b.count("SELECT count(*) FROM SyncDirty"))
    }

    @Test
    fun testEntryArrivingBeforeItsHabitIsParked() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val h = a.createHabit("Stretch")
        h.originalEntries.add(Entry(day1, Entry.YES_MANUAL))
        a.sync()
        // Rename after the entry, so the habit now has a higher seq than the entry.
        h.name = "Stretch 10 min"
        a.habits.update(h)
        a.sync()

        val b = newDevice(server, pullLimit = 1)
        b.sync()
        val copy = assertNotNull(b.habit(h.uuid!!))
        assertEquals("Stretch 10 min", copy.name)
        assertEquals(Entry.YES_MANUAL, copy.originalEntries.get(day1).value)
        assertEquals(0, b.count("SELECT count(*) FROM SyncOrphan"))
    }

    @Test
    fun testEditDuringSyncStaysDirty() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val h = a.createHabit("Journal")
        server.onRequest = {
            if (server.requests == 1) {
                h.name = "Journal daily"
                a.habits.update(h)
            }
        }
        a.engine.sync()
        // The rename happened after the record was read for pushing, so it is
        // still dirty and goes out with the next round.
        assertEquals(1, a.store.dirtyCount())
        a.engine.sync()
        assertEquals(0, a.store.dirtyCount())
        assertEquals("Journal daily", server.record(SyncRecord.HABIT, h.uuid!!)!!.fields["name"]!!.v)
    }

    @Test
    fun testPagingPushesAndPullsEverything() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server, pushLimit = 3)
        val h = a.createHabit("Pushups")
        for (i in 0 until 10) h.originalEntries.add(Entry(LocalDate(2015, 1, 1 + i), 5000))
        a.sync()
        assertEquals(0, a.store.dirtyCount())

        val b = newDevice(server, pullLimit = 4)
        b.sync()
        assertEquals(10, b.count("SELECT count(*) FROM Repetitions"))
    }

    @Test
    fun testSignInMergeLetsAccountDataWinButKeepsLocalOnlyData() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val shared = a.createHabit("Yoga")
        a.sync()

        // b already has data from before sync existed: the same habit (e.g.
        // restored from a backup) with a stale name, plus one of its own.
        val b = newDevice(server)
        val stale = b.factory.buildHabit()
        stale.uuid = shared.uuid
        stale.name = "Yoga (old)"
        b.habits.add(stale)
        val own = b.createHabit("Piano")
        // Simulate data that predates change tracking.
        b.db.run("DELETE FROM SyncClock")
        b.db.run("DELETE FROM SyncDirty")

        b.store.prepareForSignIn()
        b.sync()
        a.sync()

        assertEquals("Yoga", b.habit(shared.uuid!!)!!.name)
        assertNotNull(a.habit(own.uuid!!))
    }

    @Test
    fun testStaleObjectDoesNotRevertRemoteEdit() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        val b = newDevice(server)
        val uuid = a.createHabit("Read").uuid!!
        a.sync()
        b.sync()
        val staleOnB = b.habit(uuid)!!

        val ha = a.habit(uuid)!!
        ha.name = "Read daily"
        a.habits.update(ha)
        a.sync()

        // b pulls the rename, but the in-memory object is not refreshed yet
        // (e.g. an edit screen is open), and b then archives through it.
        b.engine.sync()
        staleOnB.isArchived = true
        b.habits.update(staleOnB)
        assertEquals("Read daily", staleOnB.name)
        b.sync()
        a.sync()

        for (d in listOf(a, b)) {
            val h = d.habit(uuid)!!
            assertEquals("Read daily", h.name)
            assertTrue(h.isArchived)
        }
    }

    @Test
    fun testOwnChangesComingBackAreNoOps() = runTest {
        val server = FakeSyncServer()
        val a = newDevice(server)
        a.createHabit("Floss")
        val result = a.sync()
        assertTrue(result.changedHabits.isEmpty())
    }
}
