package com.example.txtreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 朗讀前景服務（Foreground Service）：
 * 防止手機螢幕關閉約 30~60 分鐘進入 Doze Mode 或省電清理時，系統把背景朗讀程序殺掉。
 *
 * 注意：Android 規定呼叫 startForegroundService() 後，必須在 onStartCommand 內完成 startForeground()，
 * 絕不可在 onStartCommand 執行前從外部直接呼叫 context.stopService()，否則會觸發
 * ForegroundServiceDidNotStartInTimeException 閃退。
 */
class TtsService : Service() {

    companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "tts_playback"

        @Volatile
        private var lastNotification: Notification? = null

        @Volatile
        private var shouldStop: Boolean = false

        @Volatile
        private var instance: TtsService? = null

        fun startOrUpdate(ctx: Context, notif: Notification, playing: Boolean) {
            val app = ctx.applicationContext
            lastNotification = notif

            // 暫停狀態不需要新啟動 ForegroundService：若服務已在跑就更新通知，沒在跑直接發一般通知
            if (!playing) {
                try {
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.notify(NOTIF_ID, notif)
                } catch (_: Exception) {
                }
                return
            }

            shouldStop = false
            // 服務實例已存在時，直接更新通知即可，不重複呼叫 startForegroundService
            val running = instance
            if (running != null) {
                try {
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.notify(NOTIF_ID, notif)
                    return
                } catch (_: Exception) {
                }
            }

            try {
                val intent = Intent(app, TtsService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (_: Exception) {
                try {
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.notify(NOTIF_ID, notif)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            shouldStop = true
            lastNotification = null
            // 若服務已經完成 startForeground，直接由實例安全卸載；
            // 若 startForegroundService 還在排隊中（instance == null），不要呼叫 stopService，
            // 交給 onStartCommand 先完成 startForeground 後立刻自我關閉，徹底杜絕閃退。
            val running = instance
            if (running != null) {
                running.stopSafe()
            } else {
                try {
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.cancel(NOTIF_ID)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = lastNotification ?: buildFallbackNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            instance = this
        } catch (_: Exception) {
        }

        if (shouldStop) {
            stopSafe()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun stopSafe() {
        instance = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        try {
            stopSelf()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildFallbackNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "朗讀控制", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TxtReader 朗讀中")
            .setOngoing(true)
            .build()
    }
}
