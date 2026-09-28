package com.example.txtreader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Phase 0-5 主畫面：程式碼排版面，不寫 XML。
 * 上排：狀態列 + 書架 / 目錄 / 繁體 / 開檔 / A- / A+；下排：ReaderView。
 * 目錄是左側面板，書架是右側面板（書名＋進度，點書回上次位置，長按刪書，含備份／還原）。
 * 進度每 2 秒存一次＋切后台／關閉時存，開檔自動回到上次位置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var reader: ReaderView
    private lateinit var btnOpen: Button
    private lateinit var status: TextView
    private lateinit var lineCount: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var tocPanel: LinearLayout
    private lateinit var scrim: View
    private lateinit var tocList: ListView
    private lateinit var tocTitle: TextView
    private lateinit var tocAdapter: ArrayAdapter<String>
    private lateinit var btnS2T: Button
    private lateinit var btnSpeak: Button
    private lateinit var btnSettings: Button
    private lateinit var speaker: Speaker
    private var pausedLine = -1

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var shelfPanel: LinearLayout
    private lateinit var shelfList: ListView
    private lateinit var shelfTitle: TextView
    private lateinit var shelfAdapter: ArrayAdapter<String>

    private var currentFile: TxtFile? = null
    private var currentUri: Uri? = null
    private var currentChapters: List<TxtFile.Chapter> = emptyList()
    private var lastChecked = -2
    private var shelfBooks: List<Book> = emptyList()
    private var lastSaveAt = 0L
    private var lastUploadAt = 0L
    private var openedPos: TextPos? = null
    private var openedRowTime = 0L
    private var fontLabelView: TextView? = null

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) openUri(uri)
    }
    private val exportDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) doExport(uri)
        }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) doImport(uri)
    }
    private val fontPick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) copyFont(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reader = ReaderView(this)
        reader.textSizeSp = prefs.getFloat("textSizeSp", 20f)
        reader.lineSpacingExtraPx = prefs.getFloat("linePx", 8f * resources.displayMetrics.density)
        reader.letterSpacingEm = prefs.getFloat("letterEm", 0f)
        reader.paddingDp = prefs.getFloat("paddingDp", 16f)
        reader.firstLineIndent = prefs.getBoolean("indent", true)
        reader.paragraphGapDp = prefs.getFloat("gapDp", 8f)
        reader.justifyEdges = prefs.getBoolean("justify", true)
        reader.textColor = prefs.getInt("textColor", Color.parseColor("#FFFFFF"))
        // 自選字型：內部拷貝還在就套用，不在就靜靜用系統預設
        prefs.getString("fontPath", null)?.let { p ->
            try {
                val f = java.io.File(p)
                if (f.exists()) reader.typeface = android.graphics.Typeface.createFromFile(f)
            } catch (_: Exception) {
            }
        }
        reader.onProgress = { line, total ->
            refreshStatus()
            refreshTocSelection()
            maybeSave()
            lineCount.text = "${line + 1}/$total"
        }
        reader.onToggleMenu = { toggleMenu() }

        status = TextView(this).apply {
            textSize = 12f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#B0B0B0"))
            text = "範例文字"
        }
        // 行數跟資訊列同字級，放資訊列最右
        lineCount = TextView(this).apply {
            textSize = 12f
            setSingleLine()
            setTextColor(Color.parseColor("#B0B0B0"))
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, 0, 0)
            text = ""
        }
        // 頂欄按鈕：小字＋去最小寬度，六顆等寬一定塞得下
        fun barBtn(t: String): Button = Button(this).apply {
            text = t
            textSize = 12f
            minimumWidth = 0
            minimumHeight = 0
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val btnShelf = barBtn("書架")
        val btnToc = barBtn("目錄")
        btnS2T = barBtn(if (prefs.getBoolean("s2t", false)) "简体" else "繁體")
        btnOpen = barBtn("開檔")
        val btnSmaller = barBtn("A-")
        val btnBigger = barBtn("A+")
        btnSpeak = barBtn("朗讀")
        btnSettings = barBtn("設定")
        btnShelf.setOnClickListener { openShelf() }
        btnToc.setOnClickListener { openToc() }
        btnS2T.setOnClickListener { toggleS2T() }
        btnOpen.setOnClickListener { openDoc.launch(arrayOf("text/*")) }
        btnSmaller.setOnClickListener { reader.textSizeSp -= 1f; saveSettings() }
        btnBigger.setOnClickListener { reader.textSizeSp += 1f; saveSettings() }
        btnSpeak.setOnClickListener { toggleSpeak() }
        btnSettings.setOnClickListener { openDisplaySettings() }

        // 頂欄改兩行：第一行狀態全文顯示，第二行六顆等寬按鈕，不再被擠掉
        // 頂選單：平時隱藏全螢幕看，點中間叫出來（覆蓋在上，不擠版面）
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
                for (b in listOf(btnShelf, btnToc, btnS2T, btnOpen, btnSmaller, btnBigger, btnSpeak, btnSettings)) {
                addView(b, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonsRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            visibility = View.GONE
        }
        // 底資訊列：常駐。[書名｜章節｜進度｜編碼] ＋最右行數
        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            addView(status, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(lineCount, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            addView(reader, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(infoBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        tocTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            text = "目錄"
        }
        // 夜間模式：字色寫死白，不跟系統主題賭（之前白底白字就是這樣來的）
        tocAdapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_activated_1, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1)
                    ?.setTextColor(Color.parseColor("#E0E0E0"))
                return v
            }
        }
        tocList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = tocAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                currentChapters.getOrNull(pos)?.let { reader.jumpToLine(it.lineIndex) }
                closeToc()
            }
        }
        tocPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            addView(tocTitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(tocList, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            visibility = View.GONE
        }

        shelfTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            text = "書架"
        }
        val btnExport = Button(this).apply { text = "備份" }
        val btnImport = Button(this).apply { text = "還原" }
        val btnSync = Button(this).apply { text = "同步" }
        btnExport.setOnClickListener { exportDoc.launch("TxtReader-backup.json") }
        btnImport.setOnClickListener { importDoc.launch(arrayOf("application/json")) }
        btnSync.setOnClickListener { onSyncButton() }
        val shelfBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(shelfTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnSync)
            addView(btnExport)
            addView(btnImport)
        }
        shelfAdapter = object : ArrayAdapter<String>(
            // 四參數構造：指明標題欄位是 text1，否則雙行布局會被硬轉 TextView 而崩潰
            this, android.R.layout.simple_list_item_2, android.R.id.text1, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1)
                    ?.setTextColor(Color.parseColor("#E0E0E0"))
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t2?.setTextColor(Color.parseColor("#B0B0B0"))
                t2?.text = shelfBooks.getOrNull(position)?.let { b ->
                    if (b.pct > 0 || b.lastLine > 0) {
                        val ch = b.chapter.ifBlank { "（卷首）" }
                        "${b.pct}% · ${displayTitle(ch)}"
                    } else "尚未閱讀"
                } ?: ""
                return v
            }
        }
        shelfList = ListView(this).apply {
            adapter = shelfAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                shelfBooks.getOrNull(pos)?.let {
                    closeShelf()
                    openUri(Uri.parse(it.uri))
                }
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                shelfBooks.getOrNull(pos)?.let { deleteBook(it) }
                true
            }
        }
        shelfPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            addView(shelfBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(shelfList, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            visibility = View.GONE
        }

        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
            visibility = View.GONE
            setOnClickListener { closeToc(); closeShelf() }
        }
        val panelW = (300 * resources.displayMetrics.density).toInt()
        val root = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ))
            addView(scrim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(tocPanel, FrameLayout.LayoutParams(
                panelW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START
            ))
            addView(shelfPanel, FrameLayout.LayoutParams(
                panelW, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END
            ))
        }
        setContentView(root)
        applyFullscreen(true) // 預設全螢幕（選單隱藏）

        S2T.preload(this) {
            // 詞庫就緒：把存的簡繁開關補上（目錄跟著重建，時序早晚都對）
            runOnUiThread {
                if (prefs.getBoolean("s2t", false) && S2T.ready) {
                    reader.s2tEnabled = true
                    btnS2T.text = "简体"
                    rebuildToc()
                    refreshStatus()
                }
            }
        }

        speaker = Speaker(
            this,
            { if (currentFile == null) null else reader.activeSource() },
            object : Speaker.Listener {
                override fun onSpeakLine(line: Int) {
                    reader.followSpeak(line)
                }

                override fun onBookDone() {
                    reader.ttsLine = -1
                    pausedLine = -1
                    btnSpeak.text = "朗讀"
                    Toast.makeText(this@MainActivity, "播完了", Toast.LENGTH_SHORT).show()
                }

                override fun onTtsError(msg: String) {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
        speaker.onMediaToggle = { toggleSpeak() }
        speaker.onAutoPaused = { btnSpeak.text = "繼續" }
        speaker.onAutoResumed = { btnSpeak.text = "暫停" }
        speaker.init()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    shelfPanel.visibility == View.VISIBLE -> closeShelf()
                    tocPanel.visibility == View.VISIBLE -> closeToc()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        // 沒開檔時先有東西看
        reader.setText("歡迎使用 TxtReader。\n點右上「開檔」選一個 .txt。\n點上方上一頁、點下方下一頁，點中間叫出選單；上下滑也可翻頁。\n點「目錄」可跳章；沒章節的書會自動按比例切段。\n點「書架」回上次進度；長按書架可刪書。\n長按畫面可跑丟字自我檢測。\n\n這是第一章 楔子\n測試斷行：，。！？；：這些標點不該出現在行首。\n")
        refreshShelf()
        // 啟動自動開上次那本：有就回到上次位置，沒有／打不開就停在範例頁
        Thread {
            try {
                val last = Db.get(applicationContext).books().all().firstOrNull()
                if (last != null) runOnUiThread { openUri(Uri.parse(last.uri), quiet = true) }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        autoSyncOnStart()
        checkForUpdate()
    }

    /** 在「目前顯示的目錄」（真實或虛擬）裡二分查找第 line 行屬於哪一章 */
    private fun chapterIndexFor(line: Int): Int {
        var lo = 0
        var hi = currentChapters.size - 1
        var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (currentChapters[m].lineIndex <= line) {
                ans = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        return ans
    }

    /** 狀態列：書名｜目前章節｜進度｜編碼 */
    private fun refreshStatus() {
        val f = currentFile ?: run { status.text = "範例文字"; return }
        if (currentChapters.isEmpty()) {
            status.text = "${f.name}｜｜${f.charset.name()}"
            return
        }
        val ci = chapterIndexFor(reader.currentLine())
        val ch = displayTitle(if (ci >= 0) currentChapters[ci].title else "（卷首）")
        val pct = (reader.currentLine() + 1) * 100 / maxOf(1, f.size)
        status.text = "${f.name}｜$ch｜$pct%｜${f.charset.name()}"
    }

    /** 開檔後（重）建目錄：有章節用真的，沒有用 20 段虛擬目錄 */
    private fun rebuildToc() {
        val f = currentFile ?: return
        val real = f.chapters
        currentChapters = if (real.isEmpty()) f.virtualChapters() else real
        lastChecked = -2
        tocTitle.text = if (real.isEmpty()) "目錄（無章節，按比例）" else "目錄（${real.size}章）"
        tocAdapter.clear()
        tocAdapter.addAll(currentChapters.map { displayTitle(it.title) })
        tocAdapter.notifyDataSetChanged()
    }

    private fun openToc() {
        if (currentFile == null) {
            Toast.makeText(this, "先開一本書", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentChapters.isEmpty()) rebuildToc()
        closeShelf()
        tocPanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        val ci = chapterIndexFor(reader.currentLine())
        if (ci >= 0) {
            tocList.setItemChecked(ci, true)
            tocList.setSelection(ci)
            lastChecked = ci
        }
    }

    private fun closeToc() {
        tocPanel.visibility = View.GONE
        if (shelfPanel.visibility != View.VISIBLE) scrim.visibility = View.GONE
    }

    /** 翻頁時跟著高亮當前章（面板開著才做，省效能） */
    private fun refreshTocSelection() {
        if (tocPanel.visibility != View.VISIBLE || currentChapters.isEmpty()) return
        val ci = chapterIndexFor(reader.currentLine())
        if (ci != lastChecked) {
            lastChecked = ci
            if (ci >= 0) tocList.setItemChecked(ci, true)
        }
    }

    /** 簡/繁切換：只換顯示包裝，原文不動；目錄與狀態列跟著轉。按鈕顯示目前模式。 */
    private fun toggleS2T() {
        if (!S2T.ready) {
            Toast.makeText(this, "詞庫載入中，稍後再按", Toast.LENGTH_SHORT).show()
            S2T.preload(this)
            return
        }
        stopSpeak(false)
        reader.s2tEnabled = !reader.s2tEnabled
        btnS2T.text = if (reader.s2tEnabled) "简体" else "繁體"
        saveSettings()
        rebuildToc()
        refreshStatus()
    }

    /** 設定存檔：字體＋簡繁＋行距＋字距＋字色（SharedPreferences，夠自用了）。 */
    private fun saveSettings() {
        prefs.edit()
            .putFloat("textSizeSp", reader.textSizeSp)
            .putBoolean("s2t", reader.s2tEnabled)
            .putFloat("linePx", reader.lineSpacingExtraPx)
            .putFloat("letterEm", reader.letterSpacingEm)
            .putInt("textColor", reader.textColor)
            .putFloat("paddingDp", reader.paddingDp)
            .putBoolean("indent", reader.firstLineIndent)
            .putFloat("gapDp", reader.paragraphGapDp)
            .putBoolean("justify", reader.justifyEdges)
            .apply()
    }

    /** 顯示設定：行距／字距／字色，拉了即時預覽，關掉自動存。 */
    private fun openDisplaySettings() {
        val den = resources.displayMetrics.density
        fun label(t: String): TextView = TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        val pad = (20 * den).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val lineLabel = label("")
        val lineBar = SeekBar(this).apply {
            max = 20
            progress = (reader.lineSpacingExtraPx / den).toInt().coerceIn(0, 20)
        }
        val letterLabel = label("")
        val letterBar = SeekBar(this).apply {
            max = 10
            progress = (reader.letterSpacingEm * 50).toInt().coerceIn(0, 10)
        }
        val marginLabel = label("")
        val marginBar = SeekBar(this).apply {
            max = 48
            progress = reader.paddingDp.toInt().coerceIn(0, 48)
        }
        val gapLabel = label("")
        val gapBar = SeekBar(this).apply {
            max = 30
            progress = reader.paragraphGapDp.toInt().coerceIn(0, 30)
        }
        fun refreshLabels() {
            lineLabel.text = "行距：${lineBar.progress}dp"
            letterLabel.text = "字距：${"%.2f".format(letterBar.progress / 50f)}em"
            marginLabel.text = "邊界：${marginBar.progress}dp"
            gapLabel.text = "段間距：${gapBar.progress}dp"
        }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (s === lineBar) reader.lineSpacingExtraPx = p * den
                else if (s === marginBar) reader.paddingDp = p.toFloat()
                else if (s === gapBar) reader.paragraphGapDp = p.toFloat()
                else reader.letterSpacingEm = p / 50f
                refreshLabels()
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        lineBar.setOnSeekBarChangeListener(seekListener)
        letterBar.setOnSeekBarChangeListener(seekListener)
        marginBar.setOnSeekBarChangeListener(seekListener)
        gapBar.setOnSeekBarChangeListener(seekListener)
        refreshLabels()
        root.addView(lineLabel)
        root.addView(lineBar)
        root.addView(letterLabel)
        root.addView(letterBar)
        root.addView(marginLabel)
        root.addView(marginBar)
        val indentBox = CheckBox(this).apply {
            text = "段首空兩格"
            isChecked = reader.firstLineIndent
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        indentBox.setOnCheckedChangeListener { _, checked -> reader.firstLineIndent = checked }
        root.addView(indentBox)
        root.addView(gapLabel)
        root.addView(gapBar)
        val justifyBox = CheckBox(this).apply {
            text = "左右對齊（右緣補齊）"
            isChecked = reader.justifyEdges
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        justifyBox.setOnCheckedChangeListener { _, checked -> reader.justifyEdges = checked }
        root.addView(justifyBox)
        root.addView(label("字色"))
        val colors = intArrayOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#F5F0E6"),
            Color.parseColor("#CCCCCC"),
            Color.parseColor("#E8C46A")
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<Button>()
        for (c in colors) {
            val b = Button(this).apply {
                text = if (c == reader.textColor) "✓" else ""
                setBackgroundColor(c)
            }
            b.setOnClickListener {
                reader.textColor = c
                swatches.forEach { it.text = "" }
                b.text = "✓"
            }
            swatches.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        fontLabelView = label("字型：${prefs.getString("fontName", null) ?: "系統預設"}")
        root.addView(fontLabelView)
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPickFont = Button(this).apply { text = "選擇字型檔" }
        val btnDefFont = Button(this).apply { text = "恢復預設" }
        btnPickFont.setOnClickListener { fontPick.launch(arrayOf("*/*")) }
        btnDefFont.setOnClickListener {
            reader.typeface = null
            prefs.edit().remove("fontPath").remove("fontName").apply()
            fontLabelView?.text = "字型：系統預設"
        }
        fontRow.addView(btnPickFont, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fontRow.addView(btnDefFont, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(fontRow)
        lateinit var settingDlg: AlertDialog
        val btnCheckUpdate = Button(this).apply { text = "檢查更新" }
        btnCheckUpdate.setOnClickListener {
            settingDlg.dismiss()
            checkForUpdate(manual = true)
        }
        root.addView(btnCheckUpdate)
        settingDlg = AlertDialog.Builder(this)
            .setTitle("顯示設定")
            .setView(root)
            .setPositiveButton("完成", null)
            .create()
        settingDlg.setOnDismissListener { saveSettings() }
        settingDlg.show()
    }

    /** 字型檔：第一次拷貝到內部存著（以後不用再選），套用＋記名。只收 ttf/otf/ttc。 */
    private fun copyFont(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        Thread {
            try {
                var name = "custom"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "ttf" && ext != "otf" && ext != "ttc") {
                    throw IllegalStateException("只支援 ttf / otf / ttc")
                }
                val out = java.io.File(java.io.File(filesDir, "fonts").apply { mkdirs() }, "custom.$ext")
                contentResolver.openInputStream(uri)?.use { ins ->
                    out.outputStream().use { ous -> ins.copyTo(ous) }
                } ?: throw IllegalStateException("讀不到字型檔")
                val tf = android.graphics.Typeface.createFromFile(out)
                runOnUiThread {
                    reader.typeface = tf
                    prefs.edit().putString("fontPath", out.absolutePath).putString("fontName", name).apply()
                    fontLabelView?.text = "字型：$name"
                    Toast.makeText(this, "已套用「$name」", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "字型載入失敗：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun displayTitle(raw: String): String =
        if (reader.s2tEnabled) S2T.convert(raw) else raw

    // ---------- 書架／歷史 ----------

    private fun openShelf() {
        closeToc()
        refreshShelf()
        shelfPanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    private fun closeShelf() {
        shelfPanel.visibility = View.GONE
        if (tocPanel.visibility != View.VISIBLE) scrim.visibility = View.GONE
    }

    private fun refreshShelf() {
        Thread {
            try {
                val books = Db.get(applicationContext).books().all()
                runOnUiThread {
                    shelfBooks = books
                    shelfAdapter.clear()
                    shelfAdapter.addAll(books.map { it.name })
                    shelfAdapter.notifyDataSetChanged()
                    shelfTitle.text = "書架（${books.size}）"
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    private fun deleteBook(b: Book) {
        Thread {
            try {
                Db.get(applicationContext).books().delete(b.uri)
                runOnUiThread {
                    Toast.makeText(this, "已移除「${b.name}」", Toast.LENGTH_SHORT).show()
                    refreshShelf()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "刪除失敗：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 翻頁時最多每 2 秒存一次進度（存原文行號，不受簡繁開關影響）。 */
    private fun maybeSave() {
        if (currentUri == null) return
        val now = System.currentTimeMillis()
        if (now - lastSaveAt > 2000) saveNow()
    }

    private fun saveNow() {
        val uriStr = currentUri?.toString() ?: return
        val f = currentFile ?: return
        val pos = reader.currentPos()
        val ci = chapterIndexFor(reader.currentLine())
        val ch = if (ci >= 0) currentChapters[ci].title else ""
        val pct = (reader.currentLine() + 1) * 100 / maxOf(1, f.size)
        lastSaveAt = System.currentTimeMillis()
        maybeUpload()
        // 章節存原文，顯示時再按開關轉，避免重複轉換
        Thread {
            try {
                Db.get(applicationContext).books()
                    .saveProgress(uriStr, pos.lineIndex, pos.charOffset, pct, ch, lastSaveAt)
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- 備份／還原 ----------

    private fun doExport(uri: Uri) {
        Thread {
            try {
                val books = Db.get(applicationContext).books().all()
                contentResolver.openOutputStream(uri)?.use {
                    it.write(Backup.export(books).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("寫入失敗")
                runOnUiThread { Toast.makeText(this, "備份 ${books.size} 本完成", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "備份失敗：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun doImport(uri: Uri) {
        Thread {
            try {
                val json = contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("讀取失敗")
                val books = Backup.parse(json)
                val dao = Db.get(applicationContext).books()
                books.forEach { dao.upsert(it) }
                runOnUiThread {
                    Toast.makeText(this, "還原 ${books.size} 本", Toast.LENGTH_SHORT).show()
                    refreshShelf()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "還原失敗：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- Dropbox 同步 ----------

    private fun onSyncButton() {
        if (DropboxSync.isLinked(this)) syncAfterLogin()
        else {
            Toast.makeText(this, "開啟瀏覽器登入 Dropbox（一次就好）", Toast.LENGTH_SHORT).show()
            DropboxSync.beginAuth(this)
        }
    }

    /** 手動同步的統一入口（一定走背景線程；瀏覽器授權回來走 onResume）。 */
    private fun syncAfterLogin() {
        Thread {
            try {
                val r = DropboxSync.syncNow(applicationContext, currentFile?.name)
                runOnUiThread {
                    Toast.makeText(this, r.msg, Toast.LENGTH_SHORT).show()
                    refreshShelf()
                    refreshStatus()
                    applyWatched(r.watched)
                }
            } catch (e: com.dropbox.core.InvalidAccessTokenException) {
                // token 被使用者在網頁端收回等：清掉，下次重登
                DropboxSync.unlink(applicationContext)
                runOnUiThread { Toast.makeText(this, "授權失效，請重按同步登入", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "同步失敗：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 別台較新的進度：如果使用者還停在開檔時的位置，直接跳過去；已翻頁就不搶。 */
    private fun applyWatched(w: Book?) {
        val cf = currentFile ?: return
        val op = openedPos ?: return
        if (w == null || w.name != cf.name) return
        if (reader.currentPos() != op) return
        if (w.lastOpen <= openedRowTime) return
        if (w.lastLine == op.lineIndex && w.lastOffset == op.charOffset) return
        reader.restore(TextPos(w.lastLine, w.lastOffset))
        openedPos = reader.currentPos()
        refreshStatus()
        Toast.makeText(this, "已同步另一台的進度：${w.pct}%", Toast.LENGTH_SHORT).show()
    }

    /** 啟動自動同步一次（有登入才做，靜默，失敗不吵）。 */
    private fun autoSyncOnStart() {
        Thread {
            try {
                if (!DropboxSync.isLinked(applicationContext)) return@Thread
                val r = DropboxSync.syncNow(applicationContext, currentFile?.name)
                runOnUiThread {
                    refreshShelf()
                    refreshStatus()
                    applyWatched(r.watched)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    /** 存檔後順手上傳（60 秒節流；切后台強制一次）。未登入直接跳過。 */
    private fun maybeUpload(force: Boolean = false) {
        Thread {
            try {
                val now = System.currentTimeMillis()
                synchronized(this@MainActivity) {
                    if (!force && now - lastUploadAt < 60000) return@Thread
                    lastUploadAt = now
                }
                DropboxSync.uploadLocal(applicationContext)
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- 自動更新 ----------

    /** 啟動靜默檢查（24 小時一次）；manual=true 一定給結果。 */
    private fun checkForUpdate(manual: Boolean = false) {
        Thread {
            try {
                val now = System.currentTimeMillis()
                if (!manual && now - prefs.getLong("lastUpdateCheck", 0L) < 24L * 3600 * 1000) {
                    return@Thread
                }
                prefs.edit().putLong("lastUpdateCheck", now).apply()
                val remote = UpdateChecker.check(applicationContext) ?: run {
                    if (manual) runOnUiThread { Toast.makeText(this, "已是最新版", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                runOnUiThread { showUpdateDialog(remote) }
            } catch (_: Exception) {
                if (manual) runOnUiThread { Toast.makeText(this, "檢查失敗（網路？）", Toast.LENGTH_SHORT).show() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun showUpdateDialog(remote: UpdateChecker.Remote) {
        AlertDialog.Builder(this)
            .setTitle("發現新版 v${remote.versionName}")
            .setMessage(remote.notes.ifBlank { "建議更新" })
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(remote) }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun downloadAndInstall(remote: UpdateChecker.Remote) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("下載更新中…")
            .setView(bar)
            .setNegativeButton("取消") { _, _ -> UpdateChecker.cancel() }
            .setCancelable(false)
            .show()
        Thread {
            try {
                val apk = UpdateChecker.download(applicationContext, remote.apkUrl) { done, total ->
                    if (total > 0) runOnUiThread { bar.progress = (done * 100 / total).toInt() }
                }
                runOnUiThread {
                    dlg.dismiss()
                    installApk(apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "下載失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun installApk(apk: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    // ---------- 開檔 ----------

    /** 朗讀開關：從目前看到的行開始；暫停記行號，繼續從該行重播。 */
    private fun toggleSpeak() {
        if (speaker.isPlaying()) {
            stopSpeak(paused = true)
            return
        }
        if (currentFile == null) {
            Toast.makeText(this, "先開一本書", Toast.LENGTH_SHORT).show()
            return
        }
        val from = if (pausedLine >= 0) pausedLine else reader.currentLine()
        pausedLine = -1
        speaker.play(from)
        // 引擎沒就緒會拒播（另有 Toast），按鈕別亂變
        btnSpeak.text = if (speaker.isPlaying()) "暫停" else "朗讀"
    }

    private fun stopSpeak(paused: Boolean) {
        if (paused) {
            pausedLine = speaker.lastSpoken().takeIf { it >= 0 } ?: reader.currentLine()
        } else {
            pausedLine = -1
        }
        speaker.stop()
        reader.ttsLine = -1
        btnSpeak.text = if (paused) "繼續" else "朗讀"
    }

    /** 選單開關：點中間切換；開選單時退出全螢幕，關選單回到全螢幕。 */
    private fun toggleMenu() {
        val show = topBar.visibility != View.VISIBLE
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        applyFullscreen(!show)
    }

    private fun applyFullscreen(hide: Boolean) {
        try {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hide) c.hide(WindowInsetsCompat.Type.statusBars())
            else c.show(WindowInsetsCompat.Type.statusBars())
        } catch (_: Exception) {
        }
    }

    private fun openUri(uri: Uri, quiet: Boolean = false) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // 有些 provider 不支援 persist，忽略即可
        }
        btnOpen.isEnabled = false
        btnOpen.text = "載入…"
        Thread {
            try {
                val f = TxtFile.open(applicationContext, uri)
                val uriStr = uri.toString()
                // 書架：有就更新最後開啟，沒有就建檔；順手取出上次位置
                val dao = Db.get(applicationContext).books()
                val old = dao.byUri(uriStr)
                if (old == null) {
                    dao.upsert(Book(uri = uriStr, name = f.name, lastOpen = System.currentTimeMillis()))
                } else {
                    dao.saveProgress(
                        uriStr, old.lastLine, old.lastOffset, old.pct, old.chapter,
                        System.currentTimeMillis()
                    )
                }
                val saved = dao.byUri(uriStr)
                runOnUiThread {
                    currentFile?.close()
                    currentFile = f
                    currentUri = uri
                    reader.openFile(f)
                    stopSpeak(false)
                    rebuildToc()
                    if (saved != null && (saved.lastLine > 0 || saved.lastOffset > 0)) {
                        reader.restore(TextPos(saved.lastLine, saved.lastOffset))
                        Toast.makeText(this, "回到上次：${saved.pct}%", Toast.LENGTH_SHORT).show()
                    }
                    openedPos = reader.currentPos()
                    openedRowTime = saved?.lastOpen ?: 0L
                    refreshStatus()
                    refreshShelf()
                    btnOpen.isEnabled = true
                    btnOpen.text = "開檔"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnOpen.isEnabled = true
                    btnOpen.text = "開檔"
                    // 啟動自動開舊檔失敗就靜靜停著（授權被收回或檔刪了），不等於手動開檔失敗
                    if (!quiet)                     Toast.makeText(this@MainActivity, "開檔失敗 [${e.javaClass.simpleName}]: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 瀏覽器授權回來落地在這：有新憑證就存下並同步一次（沒登入／舊憑證直接跳過）。 */
    override fun onResume() {
        super.onResume()
        try {
            if (DropboxSync.finishAuth(this)) syncAfterLogin()
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        saveNow()
        maybeUpload(force = true)
        super.onPause()
    }

    override fun onDestroy() {
        saveNow()
        if (::speaker.isInitialized) speaker.release()
        currentFile?.close()
        super.onDestroy()
    }
}
