package com.example.txtreader

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自動更新：抓 version.json 比對 versionCode，有新版就下載 APK（FileProvider 交安裝器）。
 * 發版紀律：versionCode 一定要 +1，APK 丟 Releases，version.json 推上去，手機隔天自動提示。
 */
object UpdateChecker {
    const val VERSION_URL = "https://raw.githubusercontent.com/ttnjkoft/TxtReader/master/version.json"

    data class Remote(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    private fun localCode(ctx: Context): Int {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    /** 有新版回傳 Remote，沒有回傳 null。阻塞呼叫，一定走背景線程。 */
    fun check(ctx: Context): Remote? {
        val o = JSONObject(httpGet(VERSION_URL))
        val remote = Remote(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            apkUrl = o.optString("apkUrl", ""),
            notes = o.optString("notes", "")
        )
        if (remote.versionCode <= 0 || remote.apkUrl.isBlank()) return null
        return if (remote.versionCode > localCode(ctx.applicationContext)) remote else null
    }

    /** 下載 APK 到快取，回傳檔案。progress(doneBytes, totalBytes)，total 可能為 -1。 */
    fun download(ctx: Context, url: String, progress: (Long, Long) -> Unit): File {
        cancelled = false
        val dir = File(ctx.applicationContext.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "TxtReader-new.apk")
        if (out.exists()) out.delete()
        val conn = open(url)
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { ins ->
            FileOutputStream(out).use { ous ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) throw IllegalStateException("已取消")
                    val r = ins.read(buf)
                    if (r < 0) break
                    ous.write(buf, 0, r)
                    done += r
                    progress(done, total)
                }
            }
        }
        return out
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TxtReader")
        }
    }

    private fun httpGet(url: String): String {
        val conn = open(url).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
