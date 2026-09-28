package com.example.txtreader

import android.app.Activity
import android.content.Context
import android.provider.Settings
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.WriteMode

/**
 * Dropbox 同步：只同步進度＋書單（Backup JSON），書檔本體不同步。
 * 存在 App folder（Dropbox 裡以 APP 命名的專屬資料夾）。
 *
 * 授權是 PKCE＋refresh token：登入一次一直有效，沒有 Google 那套 7 天過期。
 * 跨裝置關鍵跟之前一樣：用「檔名」對，同名書取 lastOpen 新的進度，Uri 永遠用本機的。
 * 別台獨有的書不寫入本機書架（但雲端那份保留全量）。
 */
object DropboxSync {
    // 去 https://www.dropbox.com/developers/apps 建 App（Scoped Access→App folder），
    // 把 App key 貼在這裡；Manifest 裡 AuthActivity 的 scheme（db-開頭那串）同步換掉。
    const val APP_KEY = "uj9ilj9zatu2dok"
    const val FILE_PREFIX = "progress-"
    const val FILE_SUFFIX = ".json"
    const val LEGACY_FILE = "/progress.json"

    /** 本機在雲端的檔：每台手機寫自己獨立的一份，上傳只是覆蓋，不用先下載，不互蓋。 */
    private fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun ownPath(ctx: Context) = "/$FILE_PREFIX${deviceId(ctx)}$FILE_SUFFIX"

    data class SyncResult(val msg: String, val watched: Book?)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("dropbox", Context.MODE_PRIVATE)

    fun isLinked(ctx: Context): Boolean =
        prefs(ctx).contains("refresh_token")

    fun unlink(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** 打開瀏覽器／Dropbox App 登入；回來落地在 onResume。 */
    fun beginAuth(activity: Activity) {
        val config = DbxRequestConfig.newBuilder("TxtReader").build()
        Auth.startOAuth2PKCE(activity, APP_KEY, config)
    }

    /**
     * onResume 呼叫：有新憑證就存下回傳 true（沒登入／跟上次同一組回傳 false）。
     * 主線程呼叫即可（只讀靜態欄位＋寫 prefs）。
     */
    fun finishAuth(ctx: Context): Boolean {
        val cred = try {
            Auth.getDbxCredential()
        } catch (_: Exception) {
            null
        } ?: return false
        val rt = cred.refreshToken ?: return false
        val p = prefs(ctx)
        if (rt == p.getString("refresh_token", null)) return false
        p.edit()
            .putString("access_token", cred.accessToken ?: "")
            .putString("refresh_token", rt)
            .putLong("expires_at", cred.expiresAt ?: -1L)
            .apply()
        return true
    }

    private fun client(ctx: Context): DbxClientV2 {
        val p = prefs(ctx)
        val cred = DbxCredential(
            p.getString("access_token", ""),
            p.getLong("expires_at", -1L).takeIf { it >= 0 },
            p.getString("refresh_token", ""),
            APP_KEY
        )
        return DbxClientV2(DbxRequestConfig.newBuilder("TxtReader").build(), cred)
    }

    /**
     * 完整同步一次：下載遠端→按檔名合併→本機寫回（只寫本機已有的書）→上傳超集。
     * 阻塞呼叫，一定走背景線程。watchName 是正在看的書名，回傳它合併後的列給畫面用。
     */
    fun syncNow(ctx: Context, watchName: String? = null): SyncResult {
        val appCtx = ctx.applicationContext
        if (!isLinked(appCtx)) throw IllegalStateException("尚未登入 Dropbox")
        val dao = Db.get(appCtx).books()
        val local = dao.all()
        val api = client(appCtx)
        val merged = merge(local, downloadAll(api))
        val localNames = local.map { it.name }.toHashSet()
        merged.filter { it.name in localNames }.forEach { dao.upsert(it) }
        writeOwn(api, appCtx, Backup.export(dao.all()))
        val watched = watchName?.let { n -> merged.find { it.name == n } }
        return SyncResult("同步完成（${merged.size} 本，Dropbox）", watched)
    }

    /** 存檔後順手上傳：只寫自己那份，幾 KB，一次 PUT。未登入直接跳過。 */
    fun uploadLocal(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!isLinked(appCtx)) return
        val books = Db.get(appCtx).books().all()
        writeOwn(client(appCtx), appCtx, Backup.export(books))
    }

    /**
     * 新舊比較：0%（行號偏移都是 0）永遠輸給有進度的，不管時間戳——
     * 剛開書的空白列不能把別台的真進度洗掉。都有進度才比時間。
     */
    private fun beats(a: Book, b: Book): Boolean {
        val ap = a.lastLine > 0 || a.lastOffset > 0
        val bp = b.lastLine > 0 || b.lastOffset > 0
        if (ap != bp) return ap
        return a.lastOpen > b.lastOpen
    }

    /** 合併：同名書取勝者，Uri 用本機的（copy 保留）。 */
    fun merge(local: List<Book>, remote: List<Book>): List<Book> {
        val m = LinkedHashMap<String, Book>()
        for (b in local) m[b.name] = b
        for (r in remote) {
            val l = m[r.name]
            if (l == null) {
                m[r.name] = r
            } else if (beats(r, l)) {
                m[r.name] = l.copy(
                    lastLine = r.lastLine,
                    lastOffset = r.lastOffset,
                    pct = r.pct,
                    chapter = r.chapter,
                    lastOpen = r.lastOpen
                )
            }
        }
        return m.values.toList()
    }

    /** 讀全部裝置檔（progress-*.json）＋舊單檔（讀完刪掉，只遷移一次）。 */
    private fun downloadAll(api: DbxClientV2): List<Book> {
        val out = ArrayList<Book>()
        try {
            var res = api.files().listFolder("")
            while (true) {
                for (e in res.entries) {
                    if (e is FileMetadata && e.name.startsWith(FILE_PREFIX) && e.name.endsWith(FILE_SUFFIX)) {
                        out += downloadPath(api, "/" + e.name)
                    }
                }
                if (!res.hasMore) break
                res = api.files().listFolderContinue(res.cursor)
            }
        } catch (_: Exception) {
        }
        val legacy = downloadPath(api, LEGACY_FILE)
        if (legacy.isNotEmpty()) {
            out += legacy
            try {
                api.files().deleteV2(LEGACY_FILE)
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun downloadPath(api: DbxClientV2, path: String): List<Book> {
        return try {
            val out = java.io.ByteArrayOutputStream()
            api.files().downloadBuilder(path).download(out)
            val json = out.toString(Charsets.UTF_8.name())
            if (json.isBlank()) emptyList() else Backup.parse(json)
        } catch (_: Exception) {
            // 沒檔案或斷網都當空（上傳時網路錯會再報，不在這裡吵）
            emptyList()
        }
    }

    private fun writeOwn(api: DbxClientV2, ctx: Context, json: String) {
        api.files().uploadBuilder(ownPath(ctx))
            .withMode(WriteMode.OVERWRITE)
            .uploadAndFinish(json.toByteArray(Charsets.UTF_8).inputStream())
    }
}
