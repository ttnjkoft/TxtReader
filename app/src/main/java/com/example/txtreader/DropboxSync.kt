package com.example.txtreader

import android.app.Activity
import android.content.Context
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
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
    const val FILE_PATH = "/progress.json"

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
        val remote = download(api)
        val merged = merge(local, remote)
        val localNames = local.map { it.name }.toHashSet()
        merged.filter { it.name in localNames }.forEach { dao.upsert(it) }
        upload(api, Backup.export(merged))
        val watched = watchName?.let { n -> merged.find { it.name == n } }
        return SyncResult("同步完成（${merged.size} 本，Dropbox）", watched)
    }

    /** 存檔後順手上傳：先下載合併再上傳超集（不能直接蓋，否則洗掉別台獨有的列）。未登入直接跳過。 */
    fun uploadLocal(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!isLinked(appCtx)) return
        val dao = Db.get(appCtx).books()
        val api = client(appCtx)
        val merged = merge(dao.all(), download(api))
        upload(api, Backup.export(merged))
    }

    /** 合併：同名書取 lastOpen 新的進度，Uri 用本機的（copy 保留）。 */
    fun merge(local: List<Book>, remote: List<Book>): List<Book> {
        val m = LinkedHashMap<String, Book>()
        for (b in local) m[b.name] = b
        for (r in remote) {
            val l = m[r.name]
            if (l == null) {
                m[r.name] = r
            } else if (r.lastOpen > l.lastOpen) {
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

    private fun download(api: DbxClientV2): List<Book> {
        return try {
            api.files().getMetadata(FILE_PATH) ?: return emptyList()
            val out = java.io.ByteArrayOutputStream()
            api.files().downloadBuilder(FILE_PATH).download(out)
            val json = out.toString(Charsets.UTF_8.name())
            if (json.isBlank()) emptyList() else Backup.parse(json)
        } catch (_: Exception) {
            // 沒檔案或斷網都當空（上傳時網路錯會再報，不在這裡吵）
            emptyList()
        }
    }

    private fun upload(api: DbxClientV2, json: String) {
        api.files().uploadBuilder(FILE_PATH)
            .withMode(WriteMode.OVERWRITE)
            .uploadAndFinish(json.toByteArray(Charsets.UTF_8).inputStream())
    }
}
