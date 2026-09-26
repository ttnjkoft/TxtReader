package com.example.txtreader

import org.json.JSONArray
import org.json.JSONObject

/**
 * 備份：書架 + 進度匯出／匯入 JSON（org.json 內建，不加依賴）。
 * 注意：Uri 讀取權限是系統授權的，不在備份裡。同一台手機還原一般還能開；
 * 換機還原後若打不開，對那本書重選一次檔即可（進度還在）。
 */
object Backup {
    fun export(books: List<Book>): String {
        val arr = JSONArray()
        for (b in books) {
            arr.put(
                JSONObject()
                    .put("uri", b.uri)
                    .put("name", b.name)
                    .put("addedAt", b.addedAt)
                    .put("lastLine", b.lastLine)
                    .put("lastOffset", b.lastOffset)
                    .put("pct", b.pct)
                    .put("chapter", b.chapter)
                    .put("lastOpen", b.lastOpen)
            )
        }
        return arr.toString()
    }

    fun parse(json: String): List<Book> {
        val out = ArrayList<Book>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uri = o.optString("uri", "")
            if (uri.isBlank()) continue
            out.add(
                Book(
                    uri = uri,
                    name = o.optString("name", "未知書籍"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    lastLine = o.optInt("lastLine", 0),
                    lastOffset = o.optInt("lastOffset", 0),
                    pct = o.optInt("pct", 0),
                    chapter = o.optString("chapter", ""),
                    lastOpen = o.optLong("lastOpen", 0L)
                )
            )
        }
        return out
    }
}
