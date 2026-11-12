package com.notifylog.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
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

    @Test
    fun changeSignalEmitsEvenWhenTableRemainsNonempty() = runBlocking {
        val events = Channel<Boolean>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) { dao.changeFlow().collect { events.send(it) } }
        try {
            suspend fun next() = withTimeout(10_000) { events.receive() }
            assertEquals(false, next())
            dao.insertAll(listOf(row(1)))
            assertEquals(true, next())
            dao.insertAll(listOf(row(2)))
            assertEquals(true, next()) // equal EXISTS values still invalidate the list
            dao.deleteByPackage("com.chat")
            assertEquals(false, next())
        } finally {
            observer.cancel()
        }
    }

    // ---- ordering & paging ----

    @Test
    fun `rows come back newest first`() = runTest {
        dao.insertAll(listOf(row(100), row(300), row(200)))
        val all = dao.page(limit = 10)
        assertEquals(listOf(300L, 200L, 100L), all.map { it.postTime })
    }

    @Test
    fun `paging walks the whole table without repeats or holes`() = runTest {
        dao.insertAll((1L..25L).map { row(it) })
        val pages = ArrayList<NotificationEntity>()
        var cursor: NotificationEntity? = null
        repeat(3) {
            val page = dao.page(afterTime = cursor?.postTime, afterId = cursor?.id, limit = 10)
            pages.addAll(page)
            cursor = page.lastOrNull()
        }
        assertEquals(25, pages.size)
        assertEquals(25, pages.map { it.id }.toSet().size)
    }

    // ---- filters ----

    @Test
    fun `a null package filter means all apps`() = runTest {
        dao.insertAll(listOf(row(1, pkg = "com.a"), row(2, pkg = "com.b")))
        assertEquals(2, dao.page(limit = 10).size)
    }

    @Test
    fun `the package filter narrows to one app`() = runTest {
        dao.insertAll(listOf(row(1, pkg = "com.a"), row(2, pkg = "com.b")))
        val only = dao.page(pkg = "com.a", limit = 10)
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
        assertEquals(1, dao.page(q = "发货", limit = 10).size)
        assertEquals(1, dao.page(q = "验证码", limit = 10).size)
        assertEquals(3, dao.page(limit = 10).size)
    }

    @Test
    fun `the keyword also matches the app label`() = runTest {
        dao.insertAll(listOf(row(1, label = "微信"), row(2, label = "支付宝")))
        assertEquals(1, dao.page(q = "微信", limit = 10).size)
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
        val result = dao.page(pkg = "com.a", q = "hit", limit = 10)
        assertEquals(1, result.size)
        assertEquals("com.a", result[0].packageName)
    }

    @Test
    fun `an escaped wildcard matches literally instead of matching everything`() = runTest {
        dao.insertAll(listOf(row(1, text = "电量 100%"), row(2, text = "普通消息")))
        // Unescaped, "%" would match every row.
        val result = dao.page(q = NotifyDao.escapeLike("100%"), limit = 10)
        assertEquals(1, result.size)
        assertEquals("电量 100%", result[0].text)
    }

    @Test
    fun `an escaped underscore does not act as a single-char wildcard`() = runTest {
        dao.insertAll(listOf(row(1, text = "a_b"), row(2, text = "axb")))
        val result = dao.page(q = NotifyDao.escapeLike("a_b"), limit = 10)
        assertEquals(1, result.size)
        assertEquals("a_b", result[0].text)
    }

    // ---- time range (export) ----

    @Test
    fun `range bounds are inclusive start and exclusive end`() = runTest {
        dao.insertAll(listOf(row(100), row(200), row(300)))
        val result = dao.page(from = 100, to = 300, limit = 10)
        assertEquals(listOf(200L, 100L), result.map { it.postTime })
    }

    @Test
    fun `null range bounds return everything`() = runTest {
        dao.insertAll(listOf(row(100), row(200)))
        assertEquals(2, dao.page(limit = 10).size)
    }

    @Test
    fun `the range combines with the package filter`() = runTest {
        dao.insertAll(
            listOf(
                row(100, pkg = "com.a"),
                row(200, pkg = "com.a"),
                row(150, pkg = "com.b")
            )
        )
        val result = dao.page(pkg = "com.a", from = 100, to = 180, limit = 10)
        assertEquals(1, result.size)
        assertEquals(100L, result[0].postTime)
    }

    // ---- keyset paging (list + export) ----

    @Test
    fun `keyset paging does not re-serve rows when captures arrive mid-walk`() = runTest {
        dao.insertAll((1L..10L).map { row(it) })
        val first = dao.page(limit = 5)
        assertEquals(listOf(10L, 9L, 8L, 7L, 6L), first.map { it.postTime })

        // New captures land at the top between pages — OFFSET would re-serve
        // rows already shown; a keyset cursor must not.
        dao.insertAll(listOf(row(11), row(12)))
        val last = first.last()
        val second = dao.page(afterTime = last.postTime, afterId = last.id, limit = 5)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), second.map { it.postTime })
        assertEquals(10, (first + second).map { it.id }.toSet().size)
    }

    @Test
    fun `rows sharing a timestamp are paged by id without loss`() = runTest {
        dao.insertAll((1L..6L).map { row(500) })       // identical postTime
        val first = dao.page(limit = 3)
        val last = first.last()
        val second = dao.page(afterTime = last.postTime, afterId = last.id, limit = 3)
        assertEquals(6, (first + second).map { it.id }.toSet().size)
    }

    @Test
    fun `maxId freezes the walk at the snapshot boundary`() = runTest {
        dao.insertAll((1L..5L).map { row(it) })
        val snapshot = dao.maxId()
        dao.insertAll(listOf(row(6)))   // captured during the export
        val page = dao.page(maxId = snapshot, limit = 50)
        assertEquals(5, page.size)
        assertTrue(page.none { it.postTime == 6L })
    }

    @Test
    fun `maxId is zero on an empty table`() = runTest {
        assertEquals(0L, dao.maxId())
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
        assertEquals(listOf(300L), dao.page(limit = 10).map { it.postTime })
    }

    @Test
    fun `trimToNewest keeps exactly the newest rows`() = runTest {
        dao.insertAll((1L..10L).map { row(it) })
        dao.trimToNewest(3)
        val left = dao.page(limit = 10)
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
        assertTrue(dao.page(limit = 10).isEmpty())
    }
}
