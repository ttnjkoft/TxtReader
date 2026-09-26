package com.example.txtreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Phase 1 檔案層：mmap + 位元組行偏移索引 + 惰性解碼。
 *
 * 為什麼位元組掃描可行：UTF-8 / GBK / Big5 的中文字尾位元組永遠不會是 0x0A，
 * 所以直接掃 0x0A 切行，不用先解碼，3MB 約幾十毫秒。
 * SAF 的 Uri 不能直接 mmap，所以先複製一份到 cache 再 map。
 * 章節正則在掃描時同一遍收（Phase 3 的目錄直接用這份）。
 */
class TxtFile private constructor(
    val name: String,
    val charset: Charset,
    private val buf: ByteBuffer,
    private val lineStart: LongArray,
    val totalBytes: Long,
    val chapters: List<Chapter>,
    @Suppress("unused") private val cacheFile: File
) : Paginator.LineSource {

    data class Chapter(val title: String, val lineIndex: Int)

    override val size: Int get() = lineStart.size

    override fun get(index: Int): String = getLine(index)

    // 小 LRU：翻頁/重排會重複解同一頁，只用 UI 線程碰，512 行夠了
    private val cache = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>): Boolean {
            return size > 512
        }
    }

    /** 取第 i 行（惰性解碼，不含換行符） */
    fun getLine(i: Int): String {
        val idx = i.coerceIn(0, size - 1)
        synchronized(cache) { cache[idx]?.let { return it } }
        val s = lineStart[idx].toInt()
        var e = if (idx + 1 < lineStart.size) lineStart[idx + 1].toInt() - 1 else buf.limit()
        if (e > s && buf.get(e - 1) == '\r'.code.toByte()) e-- // 吃掉 \r\n 的 \r
        val len = maxOf(0, e - s)
        val arr = ByteArray(len)
        for (k in 0 until len) arr[k] = buf.get(s + k)
        val line = String(arr, charset)
        synchronized(cache) { cache[idx] = line }
        return line
    }

    /** 二分查找：第 line 行屬於第幾個章節（-1 = 還沒進第一章） */
    fun chapterIndexForLine(line: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (chapters[m].lineIndex <= line) {
                ans = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        return ans
    }

    /** 無章節書的兜底：按比例切 n 段虛擬目錄 */
    fun virtualChapters(n: Int = 20): List<Chapter> {
        if (size <= 1) return listOf(Chapter(name, 0))
        return (0 until n).map { k -> Chapter("(${k * 100 / n}%)", (k * size / n).coerceIn(0, size - 1)) }
    }

    fun close() {
        synchronized(cache) { cache.clear() }
        // mapping 交給 GC；cache 檔留著給 Phase 5 歷史重開用
    }

    companion object {
        private val DEFAULT_CHAPTER = Regex(
            """^(第[0-9一二三四五六七八九十百千萬零〇兩]+[章節卷回話集篇部]|Chapter\s*[0-9IVXivx\-]+|楔子|序章|終章|前言|後記|番外|引子|尾聲)"""
        )
        private val TITLE_PUNCT = setOf('。', '！', '？', '；')

        /** 阻塞呼叫，一定要在背景線程跑 */
        fun open(context: Context, uri: Uri, customChapterRegex: Regex? = null): TxtFile {
            val name = displayName(context, uri)
            val file = copyToCache(context, uri, name)
            val raf = RandomAccessFile(file, "r")
            val mapped = try {
                raf.channel.use { ch -> ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, raf.length()) }
            } finally {
                raf.close()
            }

            val n = mapped.limit()
            // 1) 位元組掃描建行偏移表（行為與 String.split('\n') 一致，含尾空行）
            val starts = ArrayList<Long>(n / 40 + 2)
            starts.add(0L)
            var i = 0
            while (i < n) {
                if (mapped.get(i) == 0x0A.toByte()) starts.add((i + 1).toLong())
                i++
            }
            val lineStart = starts.toLongArray()

            // 2) 編碼偵測：嚴格 UTF-8 先試，不行就 GBK/Big5 二選一（中日韓字數多的贏）
            val charset = detectCharset(mapped, n)

            // 3) 同一遍收章節：行太長(>40字)或含 。！？； 的當正文跳過
            val chapters = scanChapters(mapped, lineStart, charset, customChapterRegex)

            return TxtFile(name, charset, mapped, lineStart, n.toLong(), chapters, file)
        }

        private fun displayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            } catch (_: Exception) { }
            uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
            return "未知書籍"
        }

        private fun copyToCache(context: Context, uri: Uri, name: String): File {
            val dir = File(context.cacheDir, "books").apply { mkdirs() }
            // 快取只留最近 10 本，避免越堆越多
            try {
                dir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                    if (files.size > 10) files.take(files.size - 10).forEach { it.delete() }
                }
            } catch (_: Exception) { }
            val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").takeLast(60)
            val out = File(dir, "${System.currentTimeMillis()}_$safe")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { ous -> ins.copyTo(ous) }
            } ?: throw IllegalStateException("讀不到檔案內容")
            return out
        }

        private fun detectCharset(buf: ByteBuffer, n: Int): Charset {
            val all = ByteArray(n)
            buf.duplicate().get(all)
            // 嚴格 UTF-8
            try {
                Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(all))
                return Charset.forName("UTF-8")
            } catch (_: Exception) { }
            // GBK vs Big5：解開後誰的中日韓字多用誰
            val gbkCjk = countCjk(String(all, Charset.forName("GBK")))
            val big5Cjk = try {
                countCjk(String(all, Charset.forName("Big5")))
            } catch (_: Exception) {
                -1
            }
            return if (big5Cjk > gbkCjk) Charset.forName("Big5") else Charset.forName("GBK")
        }

        private fun countCjk(s: String): Int {
            var c = 0
            for (ch in s) {
                if (ch in '一'..'鿿' || ch in '぀'..'ヿ' || ch in '가'..'힯') c++
            }
            return c
        }

        private fun scanChapters(
            buf: ByteBuffer,
            lineStart: LongArray,
            charset: Charset,
            custom: Regex?
        ): List<Chapter> {
            val out = ArrayList<Chapter>()
            val tmp = ByteArray(256)
            for (li in lineStart.indices) {
                val s = lineStart[li].toInt()
                var e = if (li + 1 < lineStart.size) lineStart[li + 1].toInt() - 1 else buf.limit()
                if (e > s && buf.get(e - 1) == '\r'.code.toByte()) e--
                val len = e - s
                if (len <= 0 || len > 120) continue // 太長的行不可能是章節名，先跳過（省解碼）
                val arr = if (len <= tmp.size) tmp else ByteArray(len)
                for (k in 0 until len) arr[k] = buf.get(s + k)
                val line = String(arr, 0, len, charset).trim()
                if (line.isEmpty() || line.length > 40) continue
                val hit = if (custom != null) {
                    custom.containsMatchIn(line)
                } else {
                    if (TITLE_PUNCT.any { it in line }) false
                    else DEFAULT_CHAPTER.containsMatchIn(line)
                }
                if (hit) out.add(Chapter(line, li))
            }
            return out
        }
    }
}
