package com.notifylog.app.data

import android.content.Context
import android.net.Uri
import com.notifylog.app.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * Writes the log out through a user-picked SAF document.
 *
 * Everything streams a page at a time: with unlimited retention the table can
 * hold hundreds of thousands of rows, and materialising that into a list before
 * writing would be the one place this app could plausibly run out of memory.
 */
object Exporter {

    enum class Format { JSON, CSV }

    private const val PAGE = 500

    val Format.mimeType: String
        get() = when (this) {
            Format.JSON -> "application/json"
            Format.CSV -> "text/csv"
        }

    val Format.extension: String
        get() = when (this) {
            Format.JSON -> "json"
            Format.CSV -> "csv"
        }

    /**
     * @param pkg optional package filter, matching what the list screen shows
     * @param query optional keyword filter, already raw (escaped internally)
     * @param from optional inclusive start of the time range (epoch millis)
     * @param to optional exclusive end of the time range (epoch millis)
     * @return number of rows written
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        format: Format,
        pkg: String? = null,
        query: String? = null,
        from: Long? = null,
        to: Long? = null
    ): Int = withContext(Dispatchers.IO) {
        val dao = NotifyDatabase.get(context).notifyDao()
        val escaped = query?.takeIf { it.isNotBlank() }?.let { NotifyDao.escapeLike(it) }

        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("Cannot open ${uri} for writing")

        stream.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                when (format) {
                    Format.JSON -> writeJson(writer, dao, pkg, escaped, from, to)
                    Format.CSV -> writeCsv(writer, dao, pkg, escaped, from, to)
                }
            }
        }
    }

    private suspend fun writeJson(
        writer: Writer,
        dao: NotifyDao,
        pkg: String?,
        escapedQuery: String?,
        from: Long?,
        to: Long?
    ): Int {
        writer.write("[\n")
        var count = 0
        forEachRow(dao, pkg, escapedQuery, from, to) { row ->
            if (count > 0) writer.write(",\n")
            writer.write(row.toJson().toString())
            count++
        }
        writer.write("\n]\n")
        return count
    }

    /**
     * A three-column table built for Excel filtering: one consistent app name
     * (label, falling back to the package), one uniform `yyyy-MM-dd HH:mm:ss`
     * timestamp, and the whole text content folded into a single cell.
     */
    private suspend fun writeCsv(
        writer: Writer,
        dao: NotifyDao,
        pkg: String?,
        escapedQuery: String?,
        from: Long?,
        to: Long?
    ): Int {
        // BOM so Excel opens UTF-8 Chinese text correctly instead of mojibake.
        // Escaped rather than a literal so this file stays plain ASCII.
        writer.write("\uFEFF")
        writer.write("\u5E94\u7528\u540D,\u901A\u77E5\u65F6\u95F4,\u901A\u77E5\u5185\u5BB9\n")
        var count = 0
        forEachRow(dao, pkg, escapedQuery, from, to) { row ->
            writer.write(
                listOf(
                    row.appLabel ?: row.packageName,
                    Formats.dateTime(row.postTime),
                    contentCell(row)
                ).joinToString(",") { csvCell(it) }
            )
            writer.write("\n")
            count++
        }
        return count
    }

    /** Title and body in one cell, `|`-separated \u2014 Excel-friendly single line. */
    internal fun contentCell(row: NotificationEntity): String =
        listOfNotNull(row.title, row.bigText ?: row.text, row.subText)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .replace('\n', ' ')

    /** Pages through the query so memory stays flat regardless of table size. */
    private suspend inline fun forEachRow(
        dao: NotifyDao,
        pkg: String?,
        escapedQuery: String?,
        from: Long?,
        to: Long?,
        action: (NotificationEntity) -> Unit
    ) {
        var offset = 0
        while (true) {
            val page = dao.pageRange(pkg, escapedQuery, from, to, PAGE, offset)
            if (page.isEmpty()) break
            page.forEach(action)
            if (page.size < PAGE) break
            offset += PAGE
        }
    }

    private fun NotificationEntity.toJson(): JSONObject = JSONObject().apply {
        put("time", Formats.iso(postTime))
        put("timeMillis", postTime)
        put("package", packageName)
        put("app", appLabel ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("text", text ?: JSONObject.NULL)
        put("bigText", bigText ?: JSONObject.NULL)
        put("subText", subText ?: JSONObject.NULL)
        put("category", category ?: JSONObject.NULL)
        put("channel", channelId ?: JSONObject.NULL)
    }

    /** RFC 4180 quoting: wrap when needed, double any embedded quote. */
    internal fun csvCell(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
