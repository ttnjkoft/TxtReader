package com.example.txtreader

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayOutputStream

/**
 * Phase 7 Google Drive 同步：只同步進度＋書單（Backup JSON），書檔本體不同步。
 * 存在 appDataFolder（Drive 裡看不見的 APP 專區，不弄髒使用者的雲端）。
 *
 * 跨裝置關鍵：兩台手機同一個檔案的 SAF Uri 不同，所以合併用「檔名」對，
 * 同名書取 lastOpen 新的進度，Uri 永遠用本機自己的。
 * → 兩邊放同名書檔，進度才合得上；別台獨有的書不寫入本機書架（但雲端那份保留全量）。
 */
object DriveSync {
    const val FILE_NAME = "progress.json"
    private val SCOPES = listOf(DriveScopes.DRIVE_APPDATA)

    data class SyncResult(val msg: String, val watched: Book?)

    fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()

    fun lastAccount(ctx: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(ctx)

    fun signOut(ctx: Context, cb: () -> Unit) {
        GoogleSignIn.getClient(ctx, signInOptions()).signOut().addOnCompleteListener { cb() }
    }

    private fun drive(ctx: Context, account: GoogleSignInAccount): Drive {
        val cred = GoogleAccountCredential.usingOAuth2(ctx, SCOPES).apply {
            selectedAccount = account.account
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory(), cred)
            .setApplicationName("TxtReader")
            .build()
    }

    /**
     * 完整同步一次：下載遠端→按檔名合併→本機寫回（只寫本機已有的書）→上傳超集。
     * 阻塞呼叫，一定走背景線程。watchName 是正在看的書名，回傳它合併後的列給畫面用。
     */
    fun syncNow(ctx: Context, watchName: String? = null): SyncResult {
        val appCtx = ctx.applicationContext
        val account = lastAccount(appCtx) ?: throw IllegalStateException("尚未登入 Google")
        val dao = Db.get(appCtx).books()
        val local = dao.all()
        val service = drive(appCtx, account)
        val remote = download(service)
        val merged = merge(local, remote)
        val localNames = local.map { it.name }.toHashSet()
        merged.filter { it.name in localNames }.forEach { dao.upsert(it) }
        upload(service, Backup.export(merged))
        val watched = watchName?.let { n -> merged.find { it.name == n } }
        return SyncResult("同步完成（${merged.size} 本，${account.email ?: ""}）", watched)
    }

    /** 存檔後順手上傳：先下載合併再上傳超集（不能直接蓋，否則洗掉別台獨有的列）。未登入直接跳過。 */
    fun uploadLocal(ctx: Context) {
        val appCtx = ctx.applicationContext
        val account = lastAccount(appCtx) ?: return
        val dao = Db.get(appCtx).books()
        val service = drive(appCtx, account)
        val merged = merge(dao.all(), download(service))
        upload(service, Backup.export(merged))
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

    private fun findFile(service: Drive): String? {
        val list = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$FILE_NAME' and trashed = false")
            .setFields("files(id)")
            .execute()
        return list.files?.firstOrNull()?.id
    }

    private fun download(service: Drive): List<Book> {
        val id = findFile(service) ?: return emptyList()
        val out = ByteArrayOutputStream()
        service.files().get(id).executeMediaAndDownloadTo(out)
        val json = out.toString(Charsets.UTF_8.name())
        if (json.isBlank()) return emptyList()
        return try {
            Backup.parse(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun upload(service: Drive, json: String) {
        val content = ByteArrayContent("application/json", json.toByteArray(Charsets.UTF_8))
        val id = findFile(service)
        if (id == null) {
            val meta = File().apply {
                name = FILE_NAME
                parents = listOf("appDataFolder")
            }
            service.files().create(meta, content).setFields("id").execute()
        } else {
            service.files().update(id, File(), content).execute()
        }
    }
}
