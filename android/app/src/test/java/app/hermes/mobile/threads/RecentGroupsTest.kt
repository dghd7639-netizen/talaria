package app.hermes.mobile.threads

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RecentGroupsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fixedNow = ZonedDateTime.of(2026, 9, 30, 15, 0, 0, 0, zone)

    private fun epochSeconds(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Double =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond().toDouble()

    @Test
    fun bucketsThreadsIntoTodayYesterdayThisWeekAndEarlier() {
        val tToday = ThreadItem("1", "Today", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 10))
        val tYesterday = ThreadItem("2", "Yesterday", "", "idle", updatedAt = epochSeconds(2026, 9, 29, 18))
        val tThisWeek = ThreadItem("3", "This Week", "", "idle", updatedAt = epochSeconds(2026, 9, 28, 9))
        val tEarlier = ThreadItem("4", "Earlier", "", "idle", updatedAt = epochSeconds(2026, 9, 27, 23))
        val tOlder = ThreadItem("5", "Older", "", "idle", updatedAt = epochSeconds(2026, 8, 1, 12))

        val result = groupRecentThreads(listOf(tOlder, tYesterday, tEarlier, tToday, tThisWeek), fixedNow)

        assertEquals(4, result.size)
        assertEquals(RecentGroup.TODAY, result[0].first)
        assertEquals(listOf("1"), result[0].second.map { it.id })

        assertEquals(RecentGroup.YESTERDAY, result[1].first)
        assertEquals(listOf("2"), result[1].second.map { it.id })

        assertEquals(RecentGroup.THIS_WEEK, result[2].first)
        assertEquals(listOf("3"), result[2].second.map { it.id })

        assertEquals(RecentGroup.EARLIER, result[3].first)
        assertEquals(listOf("4", "5"), result[3].second.map { it.id })
    }

    @Test
    fun sortsDescendingByUpdatedAtAndMaintainsBridgeOrderOnTie() {
        val t1 = ThreadItem("1", "Earlier Morning", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 9, 0))
        val t2First = ThreadItem("2a", "Noon First", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 12, 0))
        val t2Second = ThreadItem("2b", "Noon Second", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 12, 0))
        val t3 = ThreadItem("3", "Afternoon", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 14, 0))

        val result = groupRecentThreads(listOf(t1, t2First, t3, t2Second), fixedNow)

        assertEquals(1, result.size)
        assertEquals(RecentGroup.TODAY, result[0].first)
        assertEquals(listOf("3", "2a", "2b", "1"), result[0].second.map { it.id })
    }

    @Test
    fun omitsEmptyGroups() {
        val tToday = ThreadItem("1", "Today", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 10))
        val tEarlier = ThreadItem("2", "Earlier", "", "idle", updatedAt = epochSeconds(2026, 9, 20, 10))

        val result = groupRecentThreads(listOf(tEarlier, tToday), fixedNow)

        assertEquals(listOf(RecentGroup.TODAY, RecentGroup.EARLIER), result.map { it.first })
    }

    @Test
    fun updatedAtZeroOrNegativeGoesToEarlier() {
        val zero = ThreadItem("0", "Zero", "", "idle", updatedAt = 0.0)
        val negative = ThreadItem("-1", "Negative", "", "idle", updatedAt = -10.0)
        val today = ThreadItem("1", "Today", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 10))

        val result = groupRecentThreads(listOf(zero, today, negative), fixedNow)

        assertEquals(listOf(RecentGroup.TODAY, RecentGroup.EARLIER), result.map { it.first })
        val earlier = result.first { it.first == RecentGroup.EARLIER }.second
        assertEquals(listOf("0", "-1"), earlier.map { it.id })
    }

    @Test
    fun mondayConsidersSundayAsYesterdayNotEarlier() {
        val mondayNow = ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, zone)
        val sundayYesterday = ThreadItem("sun", "Sunday", "", "idle", updatedAt = epochSeconds(2026, 9, 27, 20))
        val mondayToday = ThreadItem("mon", "Monday", "", "idle", updatedAt = epochSeconds(2026, 9, 28, 9))
        val saturdayEarlier = ThreadItem("sat", "Saturday", "", "idle", updatedAt = epochSeconds(2026, 9, 26, 20))

        val result = groupRecentThreads(listOf(saturdayEarlier, mondayToday, sundayYesterday), mondayNow)

        assertEquals(3, result.size)
        assertEquals(RecentGroup.TODAY, result[0].first)
        assertEquals(listOf("mon"), result[0].second.map { it.id })

        assertEquals(RecentGroup.YESTERDAY, result[1].first)
        assertEquals(listOf("sun"), result[1].second.map { it.id })

        assertEquals(RecentGroup.EARLIER, result[2].first)
        assertEquals(listOf("sat"), result[2].second.map { it.id })
    }

    @Test
    fun futureTimestampsCountAsToday() {
        val future = ThreadItem("fut", "Future", "", "idle", updatedAt = epochSeconds(2026, 10, 5, 12))
        val today = ThreadItem("tod", "Today", "", "idle", updatedAt = epochSeconds(2026, 9, 30, 10))

        val result = groupRecentThreads(listOf(today, future), fixedNow)

        assertEquals(1, result.size)
        assertEquals(RecentGroup.TODAY, result[0].first)
        assertEquals(listOf("fut", "tod"), result[0].second.map { it.id })
    }
}
