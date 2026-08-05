package com.notifylog.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CSV quoting and LIKE escaping — both are places where unescaped user content
 * silently corrupts the output (a comma splits a column; a `%` turns a search
 * into a match-everything scan).
 */
class ExportFormattingTest {

    // ---- CSV (RFC 4180) ----

    @Test
    fun `plain text is written unquoted`() {
        assertEquals("hello", Exporter.csvCell("hello"))
    }

    @Test
    fun `a comma forces quoting`() {
        assertEquals("\"a,b\"", Exporter.csvCell("a,b"))
    }

    @Test
    fun `embedded quotes are doubled and the cell quoted`() {
        assertEquals("\"say \"\"hi\"\"\"", Exporter.csvCell("say \"hi\""))
    }

    @Test
    fun `newlines force quoting so a message body stays in one cell`() {
        assertEquals("\"line1\nline2\"", Exporter.csvCell("line1\nline2"))
        assertEquals("\"line1\r\nline2\"", Exporter.csvCell("line1\r\nline2"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", Exporter.csvCell(""))
    }

    @Test
    fun `chinese text needs no quoting`() {
        assertEquals("验证码", Exporter.csvCell("验证码"))
    }

    // ---- content cell (CSV 通知内容 column) ----

    private fun entity(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null
    ) = NotificationEntity(
        postTime = 0,
        packageName = "p",
        appLabel = null,
        title = title,
        text = text,
        bigText = bigText,
        subText = subText,
        category = null,
        channelId = null,
        sbnKey = null,
        dedupHash = 0
    )

    @Test
    fun `title and body join with a pipe`() {
        assertEquals("标题 | 正文", Exporter.contentCell(entity(title = "标题", text = "正文")))
    }

    @Test
    fun `big text wins over text and newlines flatten to spaces`() {
        assertEquals(
            "标题 | 第一行 第二行",
            Exporter.contentCell(entity(title = "标题", text = "短", bigText = "第一行\n第二行"))
        )
    }

    @Test
    fun `blank pieces are dropped`() {
        assertEquals("正文", Exporter.contentCell(entity(title = " ", text = "正文")))
    }

    // ---- LIKE escaping ----

    @Test
    fun `ordinary search terms pass through unchanged`() {
        assertEquals("hello", NotifyDao.escapeLike("hello"))
    }

    @Test
    fun `wildcards are escaped so they match literally`() {
        assertEquals("100\\%", NotifyDao.escapeLike("100%"))
        assertEquals("a\\_b", NotifyDao.escapeLike("a_b"))
    }

    @Test
    fun `the escape character itself is escaped first`() {
        // Otherwise "\%" would escape the backslash and leave % as a wildcard.
        assertEquals("\\\\\\%", NotifyDao.escapeLike("\\%"))
    }
}
