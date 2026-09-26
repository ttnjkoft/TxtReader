package com.example.txtreader

import android.graphics.Paint

/** 文字位置：sourceLines[lineIndex] 的第 charOffset 個字 */
data class TextPos(val lineIndex: Int, val charOffset: Int)

/** 一頁的結果：lines 是已斷好行、可直接 drawText 的字串；origins 是每行對應的原文位置（TTS 高亮用） */
data class Page(
    val lines: List<String>,
    val next: TextPos,
    val hasNext: Boolean,
    val origins: List<TextPos> = emptyList()
)

/**
 * Phase 0/2 種子：最小可用分頁器。
 * 只做一件事：從 start 開始，用 Paint.breakText 切出 linesPerPage 行。
 * 自用簡化：標點禁則只擋「行首不能是 。，！？；：」』）」。
 */
class Paginator {
    /** 行來源抽象：記憶體 List 或 TxtFile(mmap 惰性解碼)都能排 */
    interface LineSource {
        val size: Int
        fun get(index: Int): String
    }

    /** 記憶體版（範例文字、測試用） */
    class ListSource(private val list: List<String>) : LineSource {
        override val size: Int get() = list.size
        override fun get(index: Int): String = list[index]
    }
    // 行首禁則字：不能出現在一行開頭，斷行時多留一個字給上一行
    private val lineHeadForbidden = setOf(
        '，', '。', '！', '？', '；', '：', '、',
        '」', '』', '）', ')', '》', '〉', '…', '—', '.', ',', '!', '?'
    )

    /**
     * 把一個原始段落切成多個顯示行。
     * @param paragraph 原始段落（不含換行）
     * @param paint 已設定好 textSize 的 Paint
     * @param maxWidth 行寬 px
     */
    fun breakParagraph(paragraph: String, paint: Paint, maxWidth: Float): List<String> {
        if (paragraph.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var start = 0
        val n = paragraph.length
        while (start < n) {
            var count = paint.breakText(paragraph, start, n, true, maxWidth, null)
            if (count <= 0) count = 1
            // 禁則：下一行的第一個字若是禁則字，就把斷點往前挪一個字
            // 讓禁則字黏在上一行行尾（自用粗糙版，夠用）
            if (start + count < n && paragraph[start + count] in lineHeadForbidden && count > 1) {
                count -= 1
            }
            out.add(paragraph.substring(start, start + count))
            start += count
        }
        return out
    }

    /**
     * 從 start 排出一頁。只排可見這一頁，微秒級。
     * @param source 原始行（按 \n 切，未斷行）
     * @param paint 已設定好 textSize
     */
    fun layoutPage(
        sourceLines: List<String>,
        start: TextPos,
        paint: Paint,
        maxWidth: Float,
        linesPerPage: Int
    ): Page = layoutPage(ListSource(sourceLines), start, paint, maxWidth, linesPerPage)

    /** 同上，只是行來源換成抽象（TxtFile 直接吃 mmap 惰性解碼）。 */
    fun layoutPage(
        source: LineSource,
        start: TextPos,
        paint: Paint,
        maxWidth: Float,
        linesPerPage: Int
    ): Page {
        if (source.size == 0) return Page(emptyList(), TextPos(0, 0), false)
        val lines = ArrayList<String>(linesPerPage)
        val origins = ArrayList<TextPos>(linesPerPage)
        var li = start.lineIndex.coerceIn(0, source.size - 1)
        var off = start.charOffset.coerceIn(0, source.get(li).length)

        while (lines.size < linesPerPage && li < source.size) {
            val para = source.get(li)
            // 該段從 off 開始斷行
            val broken = if (off >= para.length) listOf("") else breakParagraph(para.substring(off), paint, maxWidth)
            var segOff = off
            for ((bi, b) in broken.withIndex()) {
                if (lines.size >= linesPerPage) {
                    // 這一頁裝不下了：記住段內斷點（用 index 累加，不用 indexOf，避免重複行算錯）
                    val consumed = broken.subList(0, bi).sumOf { it.length }
                    return Page(lines.toList(), TextPos(li, off + consumed), true, origins.toList())
                }
                lines.add(b)
                origins.add(TextPos(li, segOff))
                segOff += b.length
            }
            li++
            off = 0
        }
        val hasNext = li < source.size
        return Page(lines.toList(), TextPos(li.coerceAtMost(maxOf(0, source.size - 1)), 0), hasNext, origins.toList())
    }
}
