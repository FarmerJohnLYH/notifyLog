package com.notifylog.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the actual SQL. The paging query carries the app filter, the
 * keyword filter and their "no filter" cases in one statement, which is exactly
 * the kind of thing that looks right and silently returns the wrong rows.
 */
@RunWith(RobolectricTestRunner::class)
class NotifyDaoTest {

    private lateinit var db: NotifyDatabase
    private lateinit var dao: NotifyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotifyDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.notifyDao()
    }

    @After
    fun tearDown() = db.close()

    private fun row(
        time: Long,
        pkg: String = "com.chat",
        label: String? = "Chat",
        title: String? = "标题",
        text: String? = "正文"
    ) = NotificationEntity(
        postTime = time,
        packageName = pkg,
        appLabel = label,
        title = title,
        text = text,
        bigText = null,
        subText = null,
        category = null,
        channelId = "c",
        sbnKey = "k$time",
        dedupHash = time
    )

    // ---- ordering & paging ----

    @Test
    fun `rows come back newest first`() = runTest {
        dao.insertAll(listOf(row(100), row(300), row(200)))
        val all = dao.page(null, null, 10, 0)
        assertEquals(listOf(300L, 200L, 100L), all.map { it.postTime })
    }

    @Test
    fun `paging walks the whole table without repeats or holes`() = runTest {
        dao.insertAll((1L..25L).map { row(it) })
        val first = dao.page(null, null, 10, 0)
        val second = dao.page(null, null, 10, 10)
        val third = dao.page(null, null, 10, 20)

        assertEquals(10, first.size)
        assertEquals(10, second.size)
        assertEquals(5, third.size)
        val seen = (first + second + third).map { it.id }
        assertEquals(25, seen.toSet().size)
    }

    // ---- filters ----

    @Test
    fun `a null package filter means all apps`() = runTest {
        dao.insertAll(listOf(row(1, pkg = "com.a"), row(2, pkg = "com.b")))
        assertEquals(2, dao.page(null, null, 10, 0).size)
    }

    @Test
    fun `the package filter narrows to one app`() = runTest {
        dao.insertAll(listOf(row(1, pkg = "com.a"), row(2, pkg = "com.b")))
        val only = dao.page("com.a", null, 10, 0)
        assertEquals(1, only.size)
        assertEquals("com.a", only[0].packageName)
    }

    @Test
    fun `the keyword matches any text column`() = runTest {
        dao.insertAll(
            listOf(
                row(1, title = "订单已发货", text = "无关"),
                row(2, title = "无关", text = "验证码 1234"),
                row(3, title = "无关", text = "无关")
            )
        )
        assertEquals(1, dao.page(null, "发货", 10, 0).size)
        assertEquals(1, dao.page(null, "验证码", 10, 0).size)
        assertEquals(3, dao.page(null, null, 10, 0).size)
    }

    @Test
    fun `the keyword also matches the app label`() = runTest {
        dao.insertAll(listOf(row(1, label = "微信"), row(2, label = "支付宝")))
        assertEquals(1, dao.page(null, "微信", 10, 0).size)
    }

    @Test
    fun `package and keyword filters combine`() = runTest {
        dao.insertAll(
            listOf(
                row(1, pkg = "com.a", text = "hit"),
                row(2, pkg = "com.b", text = "hit"),
                row(3, pkg = "com.a", text = "miss")
            )
        )
        val result = dao.page("com.a", "hit", 10, 0)
        assertEquals(1, result.size)
        assertEquals("com.a", result[0].packageName)
    }

    @Test
    fun `an escaped wildcard matches literally instead of matching everything`() = runTest {
        dao.insertAll(listOf(row(1, text = "电量 100%"), row(2, text = "普通消息")))
        // Unescaped, "%" would match every row.
        val result = dao.page(null, NotifyDao.escapeLike("100%"), 10, 0)
        assertEquals(1, result.size)
        assertEquals("电量 100%", result[0].text)
    }

    @Test
    fun `an escaped underscore does not act as a single-char wildcard`() = runTest {
        dao.insertAll(listOf(row(1, text = "a_b"), row(2, text = "axb")))
        val result = dao.page(null, NotifyDao.escapeLike("a_b"), 10, 0)
        assertEquals(1, result.size)
        assertEquals("a_b", result[0].text)
    }

    // ---- time range (export) ----

    @Test
    fun `pageRange bounds are inclusive start and exclusive end`() = runTest {
        dao.insertAll(listOf(row(100), row(200), row(300)))
        val result = dao.pageRange(null, null, 100, 300, 10, 0)
        assertEquals(listOf(200L, 100L), result.map { it.postTime })
    }

    @Test
    fun `pageRange with null bounds returns everything`() = runTest {
        dao.insertAll(listOf(row(100), row(200)))
        assertEquals(2, dao.pageRange(null, null, null, null, 10, 0).size)
    }

    @Test
    fun `pageRange combines with the package filter`() = runTest {
        dao.insertAll(
            listOf(
                row(100, pkg = "com.a"),
                row(200, pkg = "com.a"),
                row(150, pkg = "com.b")
            )
        )
        val result = dao.pageRange("com.a", null, 100, 180, 10, 0)
        assertEquals(1, result.size)
        assertEquals(100L, result[0].postTime)
    }

    // ---- aggregates ----

    @Test
    fun `app counts are grouped and ordered by volume`() = runTest {
        dao.insertAll(
            listOf(
                row(1, pkg = "com.quiet", label = "Quiet"),
                row(2, pkg = "com.loud", label = "Loud"),
                row(3, pkg = "com.loud", label = "Loud"),
                row(4, pkg = "com.loud", label = "Loud")
            )
        )
        val counts = dao.appCounts()
        assertEquals(2, counts.size)
        assertEquals("com.loud", counts[0].packageName)
        assertEquals(3, counts[0].logCount)
        assertEquals(1, counts[1].logCount)
    }

    @Test
    fun `oldest post time is null on an empty table`() = runTest {
        assertNull(dao.oldestPostTime())
        dao.insertAll(listOf(row(500), row(100)))
        assertEquals(100L, dao.oldestPostTime())
    }

    // ---- retention ----

    @Test
    fun `deleteOlderThan removes only rows before the cutoff`() = runTest {
        dao.insertAll(listOf(row(100), row(200), row(300)))
        val removed = dao.deleteOlderThan(250)
        assertEquals(2, removed)
        assertEquals(listOf(300L), dao.page(null, null, 10, 0).map { it.postTime })
    }

    @Test
    fun `trimToNewest keeps exactly the newest rows`() = runTest {
        dao.insertAll((1L..10L).map { row(it) })
        dao.trimToNewest(3)
        val left = dao.page(null, null, 10, 0)
        assertEquals(3, left.size)
        assertEquals(listOf(10L, 9L, 8L), left.map { it.postTime })
    }

    @Test
    fun `trimToNewest is a no-op when the table is already small`() = runTest {
        dao.insertAll((1L..3L).map { row(it) })
        dao.trimToNewest(100)
        assertEquals(3, dao.count())
    }

    @Test
    fun `deleteByPackage removes just that app`() = runTest {
        dao.insertAll(listOf(row(1, pkg = "com.a"), row(2, pkg = "com.b")))
        assertEquals(1, dao.deleteByPackage("com.a"))
        assertEquals(1, dao.count())
    }

    @Test
    fun `clearAll empties the table`() = runTest {
        dao.insertAll((1L..5L).map { row(it) })
        dao.clearAll()
        assertEquals(0, dao.count())
        assertTrue(dao.page(null, null, 10, 0).isEmpty())
    }
}
