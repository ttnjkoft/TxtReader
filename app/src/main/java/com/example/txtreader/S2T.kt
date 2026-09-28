package com.example.txtreader

import android.content.Context

/**
 * Phase 4 簡轉繁：純 Kotlin 版 OpenCC s2t，顯示時逐行即時轉，不預處理、不動原文。
 *
 * 詞庫是官方 OpenCC 數據（Apache 2.0），放 assets/opencc/：
 * STCharacters.txt（單字）+ STPhrases.txt（詞），格式都是「簡\t繁1 繁2…」，取第一個。
 * 轉換語義 = OpenCC s2t：最長匹配優先，單字兜底。一對多（發/髮、后/後…）靠詞表拆。
 * 第二層台灣用語：大陸詞→台灣詞（如 軟件→軟體），跑在 s2t 之後，簡繁兩種寫法都收。
 * 沒用 TWVariants（台灣慣用形）；想用把 TWVariants.txt 放進來、載入順序擺最後即可。
 *
 * 注意：開關是按書切的。拿繁體書去按「繁體」不會怎樣（快路徑直接回傳），
 * 但簡轉繁本來就是單向的，跟 OpenCC 一樣，不適合拿繁體原文書來轉。
 */
object S2T {

    @Volatile
    var ready = false
        private set

    private var dict: HashMap<String, String> = HashMap()
    private var singleChars: HashSet<Char> = HashSet()
    private var maxLen = 1
    private var loading = false

    /** APP 啟動時呼叫一次（背景線程載入約幾百毫秒，切換才即時）。冪等。載入完成調 onDone。 */
    fun preload(context: Context, onDone: (() -> Unit)? = null) {
        if (ready) {
            onDone?.invoke()
            return
        }
        synchronized(this) {
            if (ready || loading) return
            loading = true
        }
        Thread {
            try {
                load(context.applicationContext)
                onDone?.invoke()
            } catch (_: Exception) {
                synchronized(this) { loading = false }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun load(ctx: Context) {
        val map = HashMap<String, String>(60000)
        val singles = HashSet<Char>(8000)
        var mLen = 1
        // 單字先載、詞後載：key 理論上不重疊，順序只是保險（詞優先符合 OpenCC 語義）
        for (name in arrayOf("STCharacters.txt", "STPhrases.txt")) {
            ctx.assets.open("opencc/$name").bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    var end = line.indexOf(' ', tab + 1)
                    if (end < 0) end = line.length
                    if (end <= tab + 1) return@forEach
                    val key = line.substring(0, tab)
                    map[key] = line.substring(tab + 1, end)
                    if (key.length == 1) singles.add(key[0])
                    if (key.length > mLen) mLen = key.length
                }
            }
        }
        synchronized(this) {
            // 台灣用字覆寫：官方 s2t 把「吃」轉成「喫」（含詞組如吃飯→喫飯），
            // 台灣不用喫——把所有值裡的喫換回吃，key 不動。這裡集中處理，
            // 以後還有類似的字（回報制），加一行 replace 意圖最清楚。
            for (e in map.entries) {
                if ('喫' in e.value) e.setValue(e.value.replace('喫', '吃'))
            }
            dict = map
            singleChars = singles
            maxLen = mLen
            ready = true
            loading = false
        }
    }

    /** 包裝行來源：get 時即時轉。開關切換只換包裝，原文（mmap 那份）完全不動。 */
    class ConvertingSource(private val inner: Paginator.LineSource) : Paginator.LineSource {
        override val size: Int get() = inner.size
        override fun get(index: Int): String = convert(inner.get(index))
    }

    /**
     * 轉一段文字：簡轉繁（官方詞庫）→台灣用語第二層。
     * 兩層都有快路徑：繁體書、無大陸詞的書都接近零成本。
     */
    fun convert(src: String): String {
        if (src.isEmpty() || !ready) return src
        var s = src
        if (maybeSimplified(s)) s = convertChars(s)
        return convertTw(s)
    }

    /** 第一層：簡轉繁。 */
    private fun convertChars(src: String): String {
        val d = dict
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            var hit: String? = null
            var hitLen = 0
            var len = minOf(maxLen, n - i)
            while (len > 1) {
                val t = d[src.substring(i, i + len)]
                if (t != null) {
                    hit = t
                    hitLen = len
                    break
                }
                len--
            }
            if (hit != null) {
                out.append(hit)
                i += hitLen
            } else {
                val c = src[i]
                out.append(d[c.toString()] ?: c.toString())
                i++
            }
        }
        return out.toString()
    }

    /**
     * 第二層：台灣用語（大陸詞→台灣詞）。跑在 s2t 之後，所以簡繁兩種寫法都要收
     * （如 软件 和 s2t 吐出來的 軟件 都要能對到 軟體）。
     * 只收無歧義高頻詞；有歧義的一律不收（对象＝對象／物件、水平＝水準／水平面、
     * 信号＝訊號／信號彈），等回報制。有問題照「喫→吃」模式在這裡加。
     */
    private val TW_WORDS: List<Pair<String, String>> = listOf(
        "软件" to "軟體", "軟件" to "軟體",
        "网络" to "網路", "網絡" to "網路",
        "视频" to "影片", "視頻" to "影片",
        "音频" to "音訊", "音頻" to "音訊",
        "质量" to "品質", "質量" to "品質",
        "鼠标" to "滑鼠", "鼠標" to "滑鼠",
        "键盘" to "鍵盤", "鍵盤" to "鍵盤",
        "内存" to "記憶體",
        "硬盘" to "硬碟", "硬盤" to "硬碟",
        "硬件" to "硬體",
        "默认" to "預設", "默認" to "預設",
        "设置" to "設定", "設置" to "設定",
        "运行" to "執行", "運行" to "執行",
        "激活" to "啟用",
        "博客" to "部落格",
        "复印" to "影印", "複印" to "影印",
        "打印机" to "印表機",
        "打印" to "列印",
        "摄像头" to "鏡頭",
        "像素" to "畫素",
        "分辨率" to "解析度",
        "屏幕" to "螢幕",
        "服务器" to "伺服器", "服務器" to "伺服器",
        "数据库" to "資料庫", "數據庫" to "資料庫",
        "激光" to "雷射",
        "地铁" to "捷運", "地鐵" to "捷運",
        "公交车" to "公車", "公交" to "公車",
        "外卖" to "外送", "外賣" to "外送",
        "网吧" to "網咖",
        "充电宝" to "行動電源", "充電寶" to "行動電源",
        "方便面" to "泡麵", "方便麵" to "泡麵",
        "出租车" to "計程車", "出租車" to "計程車",
        "摩托车" to "機車", "摩托車" to "機車",
        "短信" to "簡訊",
        "宽带" to "寬頻", "寬帶" to "寬頻",
        "光纤" to "光纖", "光纖" to "光纖",
        "移动支付" to "行動支付", "移動支付" to "行動支付",
        "数码" to "數位", "數碼" to "數位",
        "仿真" to "模擬",
        "人工智能" to "人工智慧",
        "机器学习" to "機器學習",
        "算法" to "演算法",
        "代码" to "程式碼", "代碼" to "程式碼",
        "应用程序" to "應用程式",
        "小程序" to "小程式",
        "变量" to "變數",
        "函数" to "函式", "函數" to "函式",
        "接口" to "介面",
        "缓存" to "快取", "緩存" to "快取",
        "线程" to "執行緒", "線程" to "執行緒",
        "递归" to "遞迴", "遞歸" to "遞迴",
        "链表" to "鏈結串列", "鏈表" to "鏈結串列",
        "堆栈" to "堆疊", "堆棧" to "堆疊",
        "队列" to "佇列",
        "二进制" to "二進位", "二進制" to "二進位",
        "字节" to "位元組", "字節" to "位元組",
        "显卡" to "顯示卡", "顯卡" to "顯示卡",
        "声卡" to "音效卡",
        "网卡" to "網路卡", "網卡" to "網路卡",
        "主板" to "主機板",
        "芯片" to "晶片",
        "文件" to "檔案",
        "文件夹" to "資料夾",
        "另存为" to "另存新檔", "另存為" to "另存新檔",
        "剪切" to "剪下",
        "粘贴" to "貼上", "黏貼" to "貼上",
        "U盘" to "隨身碟", "U盤" to "隨身碟",
        "云服务" to "雲端服務", "雲服务" to "雲端服務",
        "云存储" to "雲端儲存", "雲存储" to "雲端儲存"
    )

    private val TW_BY_FIRST: Map<Char, List<Pair<String, String>>> by lazy {
        TW_WORDS.groupBy { it.first[0] }.mapValues { (_, v) -> v.sortedByDescending { it.first.length } }
    }

    private val TW_FIRST: Set<Char> by lazy { TW_BY_FIRST.keys }

    /** 台灣用語轉換（最長匹配）。無大陸詞特徵字直接回傳。 */
    fun convertTw(src: String): String {
        if (src.isEmpty()) return src
        var has = false
        for (c in src) {
            if (TW_FIRST.contains(c)) {
                has = true
                break
            }
        }
        if (!has) return src
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            var done = false
            TW_BY_FIRST[src[i]]?.let { cands ->
                for ((k, v) in cands) {
                    if (i + k.length <= n && src.startsWith(k, i)) {
                        out.append(v)
                        i += k.length
                        done = true
                        break
                    }
                }
            }
            if (!done) {
                out.append(src[i])
                i++
            }
        }
        return out.toString()
    }

    /** 啟發式快路徑：不含簡體特徵單字就不用轉。 */
    fun maybeSimplified(s: String): Boolean {
        if (!ready) return false
        for (c in s) if (singleChars.contains(c)) return true
        return false
    }
}
