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
     * 像素預算制：每顯示行扣 lineHeightPx，每段排完扣 paraGapPx。
     * 段首縮排：非空段從行首開始時，前綴兩個全形空白一起量寬；
     * 若首行被禁則擠到只剩縮排（極窄螢幕），整段不縮排——否則下一頁會重複顯示那行。
     * @param source 原始行（按 \n 切，未斷行）
     * @param paint 已設定好 textSize（含字型／字距）
     */
    fun layoutPage(
        source: LineSource,
        start: TextPos,
        paint: Paint,
        maxWidth: Float,
        lineHeightPx: Float,
        maxHeightPx: Float,
        paraGapPx: Float,
        indent: Boolean = true
    ): Page {
        if (source.size == 0) return Page(emptyList(), TextPos(0, 0), false)
        val lines = ArrayList<String>()
        val origins = ArrayList<TextPos>()
        var li = start.lineIndex.coerceIn(0, source.size - 1)
        var off = start.charOffset.coerceIn(0, source.get(li).length)
        var usedPx = 0f

        while (li < source.size) {
            val para = source.get(li)
            var ind = 0
            val broken: List<String>
            if (off >= para.length) {
                broken = listOf("")
            } else {
                val raw = para.substring(off)
                if (indent && off == 0 && raw.isNotBlank()) {
                    val withInd = breakParagraph("　　$raw", paint, maxWidth)
                    if (withInd.size > 1 && withInd[0].length <= 2) {
                        broken = breakParagraph(raw, paint, maxWidth)
                    } else {
                        broken = withInd
                        ind = 2
                    }
                } else {
                    broken = breakParagraph(raw, paint, maxWidth)
                }
            }
            var segOff = off
            var firstPiece = true
            for ((bi, b) in broken.withIndex()) {
                // 空頁時第一行無條件收（再矮的畫面至少顯示一行，不卡死）
                if (lines.isNotEmpty() && usedPx + lineHeightPx > maxHeightPx + 0.5f) {
                    val consumed = broken.subList(0, bi).sumOf { it.length }
                    val nextOff = if (consumed == 0) off else off + consumed - ind
                    return Page(lines.toList(), TextPos(li, nextOff), true, origins.toList())
                }
                lines.add(b)
                origins.add(TextPos(li, segOff))
                segOff += b.length - if (firstPiece) ind else 0
                firstPiece = false
                usedPx += lineHeightPx
            }
            li++
            off = 0
            usedPx += paraGapPx
        }
        val hasNext = li < source.size
        return Page(lines.toList(), TextPos(li.coerceAtMost(maxOf(0, source.size - 1)), 0), hasNext, origins.toList())
    }
}
