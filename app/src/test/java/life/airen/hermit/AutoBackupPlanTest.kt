package life.airen.hermit

import life.airen.hermit.backup.AutoBackupPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoBackupPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun onlyThisFeatureWritesAreRecognised() {
        val day = "20260921"
        assertEquals(day, AutoBackupPlan.dayOf("Hermit-auto-20260921.hermit-backup.zip"))
        // Earlier versions wrote a time-stamped name; it still belongs to that day.
        assertEquals(day, AutoBackupPlan.dayOf("Hermit-auto-20260921-225500.hermit-backup.zip"))
        // A provider that cannot replace a document may append its own suffix.
        assertEquals(day, AutoBackupPlan.dayOf("Hermit-auto-20260921 (1).hermit-backup.zip"))
        assertEquals(day, AutoBackupPlan.dayOf("Hermit-auto-20260921.hermit-backup.zip.zip"))
        assertTrue(AutoBackupPlan.isTemporary("Hermit-auto-20260921.hermit-backup.zip.part"))
        // Manual backups and unrelated user files must never be touched.
        assertNull(AutoBackupPlan.dayOf("Hermit-backup-20260921.zip"))
        assertNull(AutoBackupPlan.dayOf("我的资料.zip"))
        assertNull(AutoBackupPlan.dayOf("Hermit-auto-20260921.hermit-backup.zip.part"))
        assertNull(AutoBackupPlan.dayOf("Hermit-auto-notes.zip"))
        assertFalse(AutoBackupPlan.isTemporary("Hermit-backup-20260921.zip"))
        assertFalse(AutoBackupPlan.isTemporary("notes.txt"))
    }

    @Test fun fileNamesCarryTheDayOfTheArchive() {
        val day = LocalDate.of(2026, 9, 21)
        assertEquals("Hermit-auto-20260921.hermit-backup.zip", AutoBackupPlan.fileName(AutoBackupPlan.dayKey(day)))
    }

    @Test fun repeatRunsOfOneDayLeaveOneFileAndSpareEarlierDays() {
        val committed = AutoBackupPlan.fileName("20260921")
        val superseded = "Hermit-auto-20260921-030000.hermit-backup.zip"
        val names = listOf(AutoBackupPlan.fileName("20260919"), AutoBackupPlan.fileName("20260920"), committed, superseded)
        // Three days retained: only the same-day leftover goes.
        assertEquals(listOf(superseded), AutoBackupPlan.selectForRemoval(names, 3, committed))
        // Two days retained: the oldest whole day goes as well.
        assertEquals(
            setOf(AutoBackupPlan.fileName("20260919"), superseded),
            AutoBackupPlan.selectForRemoval(names, 2, committed).toSet(),
        )
    }

    @Test fun retentionCountsDaysNotRuns() {
        val names = (19..23).map { AutoBackupPlan.fileName("202609$it") }
        val committed = names.last()
        val removed = AutoBackupPlan.selectForRemoval(names, 3, committed)
        assertEquals(setOf(names[0], names[1]), removed.toSet())
    }

    @Test fun keepCountStaysInsideTheOfferedRange() {
        assertEquals(1, AutoBackupPlan.normalizeKeepCount(0))
        assertEquals(1, AutoBackupPlan.normalizeKeepCount(-5))
        assertEquals(10, AutoBackupPlan.normalizeKeepCount(99))
        assertEquals(7, AutoBackupPlan.normalizeKeepCount(7))
        val names = (19..23).map { AutoBackupPlan.fileName("202609$it") }
        // Zero is raised to one, so only the newest day survives.
        assertEquals(names.take(4).toSet(), AutoBackupPlan.selectForRemoval(names, 0, names.last()).toSet())
    }

    @Test fun theCommittedFileIsNeverRemoved() {
        val names = (19..23).map { AutoBackupPlan.fileName("202609$it") }
        // Even a day that falls outside the window is kept while it is the one just written.
        assertFalse(AutoBackupPlan.selectForRemoval(names, 1, names.first()).contains(names.first()))
    }

    @Test fun removalIgnoresForeignAndTemporaryEntries() {
        val own = AutoBackupPlan.fileName("20260921")
        val others = listOf("Hermit-backup-20260920.zip", "photos.zip", "notes.txt", "Hermit-auto-20260919.part")
        assertEquals(emptyList<String>(), AutoBackupPlan.selectForRemoval(others, 1, own))
        assertEquals(emptyList<String>(), AutoBackupPlan.selectForRemoval(listOf(own) + others, 1, own))
    }

    @Test fun nextRunUsesTodayWhenTheTimeIsStillAheadOtherwiseTomorrow() {
        val before = time(2026, 9, 21, 2, 30)
        assertEquals(time(2026, 9, 21, 3, 0), AutoBackupPlan.nextRunAt(before, 3, 0, zone))
        val after = time(2026, 9, 21, 3, 0)
        assertEquals(time(2026, 9, 22, 3, 0), AutoBackupPlan.nextRunAt(after, 3, 0, zone))
    }

    @Test fun nextRunKeepsWallClockAcrossDaylightSaving() {
        val london = ZoneId.of("Europe/London")
        // Europe/London springs forward on 2027-03-28; the wall clock must stay at 03:00.
        val now = ZonedDateTime.of(2027, 3, 27, 23, 0, 0, 0, london).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2027, 3, 28, 3, 0, 0, 0, london).toInstant().toEpochMilli()
        assertEquals(expected, AutoBackupPlan.nextRunAt(now, 3, 0, london))
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
