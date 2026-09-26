package com.example.txtreader

import android.content.Context

/**
 * Phase 4 簡轉繁：純 Kotlin 版 OpenCC s2t，顯示時逐行即時轉，不預處理、不動原文。
 *
 * 詞庫是官方 OpenCC 數據（Apache 2.0），放 assets/opencc/：
 * STCharacters.txt（單字）+ STPhrases.txt（詞），格式都是「簡\t繁1 繁2…」，取第一個。
 * 轉換語義 = OpenCC s2t：最長匹配優先，單字兜底。一對多（發/髮、后/後…）靠詞表拆。
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

    /** 轉一段文字。未載入／無簡體特徵字直接回傳原字串（繁體書零成本）。 */
    fun convert(src: String): String {
        if (!ready || src.isEmpty() || !maybeSimplified(src)) return src
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

    /** 啟發式快路徑：不含簡體特徵單字就不用轉。 */
    fun maybeSimplified(s: String): Boolean {
        if (!ready) return false
        for (c in s) if (singleChars.contains(c)) return true
        return false
    }
}
