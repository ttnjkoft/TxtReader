package com.example.txtreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.max

/**
 * Phase 0/1 自繪閱讀 View：Canvas.drawText，只排可見頁。
 * 行來源是抽象：範例文字走 ListSource，開檔走 TxtFile(mmap 惰性解碼)。
 * 手勢：點上 40% 上一頁，點下 40% 下一頁，點中間開關選單；上下滑也翻頁；長按跑丟字自我檢測。
 */
class ReaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paginator = Paginator()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E0E0E0") }
    private val hlPaint = Paint().apply { color = Color.parseColor("#4A3F00") }

    /** TTS 正在念的源行（-1 = 沒在念）；只 invalidate，不重排。 */
    var ttsLine: Int = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private var rawLines: Paginator.LineSource =
        Paginator.ListSource(listOf("（尚未開啟檔案，請點右上「開檔」）"))
    private var lines: Paginator.LineSource = rawLines

    /** 簡轉繁開關：只換包裝，不動原文；切換時回到本行行首。 */
    var s2tEnabled: Boolean = false
        set(v) {
            field = v
            current = TextPos(current.lineIndex, 0)
            backStack.clear()
            refreshSource()
        }
    private var current = TextPos(0, 0)
    private var page: Page = Page(emptyList(), TextPos(0, 0), false)
    private val backStack = ArrayDeque<TextPos>()

    // 跟隨翻頁用計數器：手動翻頁 manualGen+1；自動跟隨只在「沒手動干預」時動，
    // 一旦手動翻過就暫停跟隨，直到翻回朗讀所在頁自動恢復（不再亂拉畫面）
    private var manualGen = 0
    private var followGen = 0

    /** 翻頁回調（給狀態列更新用） */
    var onProgress: ((line: Int, total: Int) -> Unit)? = null

    /** 點中間的回調（開關選單用） */
    var onToggleMenu: (() -> Unit)? = null

    var textSizeSp: Float = 20f
        set(v) {
            field = v.coerceIn(12f, 32f)
            applyTextSize()
            relayout()
        }

    var lineSpacingExtraPx: Float = 12f

    /** 頁面四邊邊距（dp）。之前寫死 48太大，改 16；要更擠或更鬆改這裡。 */
    var paddingDp: Float = 16f

    private fun padPx(): Float = paddingDp * resources.displayMetrics.density

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 上 40% 上一頁，下 40% 下一頁，中間 20% 開關選單
            val h = height.toFloat()
            when {
                e.y < h * 0.4f -> prevPage()
                e.y > h * 0.6f -> nextPage()
                else -> onToggleMenu?.invoke()
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (dy > 120 || dx > 120) prevPage()
            else if (dy < -120 || dx < -120) nextPage()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            runAudit { msg -> post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
        }
    })

    init {
        applyTextSize()
        isClickable = true
        isFocusable = true
        isLongClickable = true
    }

    private fun applyTextSize() {
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics
        )
    }

    /** 範例文字（記憶體版） */
    fun setText(fullText: String) {
        ttsLine = -1
        rawLines = Paginator.ListSource(fullText.split('\n'))
        current = TextPos(0, 0)
        backStack.clear()
        refreshSource()
    }

    /** Phase 1：開 mmap 檔，不先解全文 */
    fun openFile(f: TxtFile) {
        ttsLine = -1
        rawLines = f
        current = TextPos(0, 0)
        backStack.clear()
        refreshSource()
    }

    private fun refreshSource() {
        lines = if (s2tEnabled && S2T.ready) S2T.ConvertingSource(rawLines) else rawLines
        relayout()
    }

    /** 跳到第 line 行行首（給 Phase 3 目錄用） */
    fun jumpToLine(line: Int) {
        if (lines.size == 0) return
        backStack.addLast(current)
        current = TextPos(line.coerceIn(0, lines.size - 1), 0)
        manualGen++
        relayout()
    }

    fun currentLine(): Int = current.lineIndex

    fun currentPos(): TextPos = current

    /** TTS 取用目前所見文字（含簡繁轉換）。 */
    fun activeSource(): Paginator.LineSource = lines

    /** 回到指定位置（歷史還原用，不經過 backStack）。 */
    fun restore(pos: TextPos) {
        backStack.clear()
        current = pos
        relayout()
    }

    private fun contentWidth(): Float {
        val pad = padPx()
        return max(100f, width - pad * 2)
    }

    private fun linesPerPage(): Int {
        val pad = padPx()
        val avail = max(100f, height - pad * 2)
        val lh = paint.fontSpacing + lineSpacingExtraPx
        return max(1, (avail / lh).toInt())
    }

    private fun relayout() {
        if (width == 0 || height == 0) {
            invalidate()
            return
        }
        page = paginator.layoutPage(lines, current, paint, contentWidth(), linesPerPage())
        onProgress?.invoke(current.lineIndex, lines.size)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    fun nextPage() {
        if (!page.hasNext) return
        backStack.addLast(current)
        current = page.next
        manualGen++
        relayout()
    }

    fun prevPage() {
        if (backStack.isEmpty()) return
        current = backStack.removeLast()
        manualGen++
        relayout()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return detector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * TTS 跟隨翻頁（每念完一句呼叫一次）：
     * - 念的行在可見範圍：只高亮，並恢復跟隨（翻回來就自動接上）。
     * - 念超前且沒手動干預：自動跳到那行（不寫 backStack，不污染返回路徑）。
     * - 念超前但手動翻過：不拉畫面，等翻回來再接上。落後的行永遠不往回拉。
     */
    fun followSpeak(line: Int) {
        ttsLine = line
        if (page.lines.isEmpty() || lines.size == 0) return
        val inRange = line >= current.lineIndex && (line < page.next.lineIndex || !page.hasNext)
        if (inRange) {
            followGen = manualGen
            return
        }
        if (line >= page.next.lineIndex && page.hasNext && followGen == manualGen) {
            current = TextPos(line.coerceIn(0, lines.size - 1), 0)
            followGen = manualGen
            relayout() // 會順便觸發 onProgress，聽書進度也存得下來
        }
    }

    /**
     * 丟字自我檢測（背景線程，用手機上真正的 Paint 跑）：
     * 1) 每段斷開再拼回去，必須一字不差（抓 breakText 吃字）；
     * 2) 整本翻完，看會不會卡住。
     * 請在「會出問題的大字體」下長按執行。
     */
    fun runAudit(cb: (String) -> Unit) {
        if (width == 0) {
            cb("等畫面出來再按")
            return
        }
        val snapPaint = Paint(paint) // 快照，避免檢測時使用者改字體造成誤報
        val w = contentWidth()
        val lpp = linesPerPage()
        val src = lines
        Thread {
            try {
                val pg = Paginator()
                for (i in 0 until src.size) {
                    val para = src.get(i)
                    val joined = pg.breakParagraph(para, snapPaint, w).joinToString("")
                    if (joined != para) {
                        cb("第${i + 1}行斷行丟字：原文${para.length}字，拼回${joined.length}字")
                        return@Thread
                    }
                }
                var pos = TextPos(0, 0)
                var pages = 0
                var dlines = 0
                while (true) {
                    val p = pg.layoutPage(src, pos, snapPaint, w, lpp)
                    pages++
                    dlines += p.lines.size
                    if (!p.hasNext) break
                    pos = p.next
                    if (pages > 2_000_000) {
                        cb("翻頁疑似無窮迴圈，請回報")
                        return@Thread
                    }
                }
                cb("檢測通過：${src.size}源行 → $dlines 顯示行 → $pages 頁，無丟字")
            } catch (e: Exception) {
                cb("檢測出錯：${e.message}")
            }
        }.apply { isDaemon = true; start() }
        cb("檢測中（${src.size}行），請稍候…")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#121212"))
        val pad = padPx()
        val lh = paint.fontSpacing + lineSpacingExtraPx
        var y = pad - paint.fontMetrics.top
        val fm = paint.fontMetrics
        for ((idx, line) in page.lines.withIndex()) {
            if (ttsLine >= 0 && page.origins.getOrNull(idx)?.lineIndex == ttsLine) {
                canvas.drawRect(0f, y + fm.top, width.toFloat(), y + fm.bottom, hlPaint)
            }
            canvas.drawText(line, pad, y, paint)
            y += lh
        }
    }
}
