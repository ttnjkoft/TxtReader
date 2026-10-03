package com.example.txtreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * 舊式媒體鍵通道：部分三星機不把按鍵轉給 MediaSession 回調，只能走顯式 Receiver。
 * 跟 session 回調並存，誰先收到誰觸發（都調同一個 toggle，冪等操作，重複收到也只是多切一次——
 * 按鍵是 DOWN 邊緣觸發，不會連打）。
 */
class MediaButtonReceiver : BroadcastReceiver() {
    companion object {
        /** 通知欄按鈕專用（顯式指定，直達不經派送）。 */
        const val ACTION_TOGGLE = "com.example.txtreader.TOGGLE_SPEAK"
        const val ACTION_STOP = "com.example.txtreader.STOP_SPEAK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_TOGGLE == intent.action) {
            try {
                Speaker.onExternalToggle?.invoke()
            } catch (_: Exception) {
            }
            return
        }
        if (ACTION_STOP == intent.action) {
            try {
                Speaker.onExternalStop?.invoke()
            } catch (_: Exception) {
            }
            return
        }
        if (Intent.ACTION_MEDIA_BUTTON != intent.action) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                try {
                    if (isOrderedBroadcast) abortBroadcast()
                } catch (_: Exception) {
                }
                try {
                    Speaker.onExternalToggle?.invoke()
                } catch (_: Exception) {
                }
            }
        }
    }
}
