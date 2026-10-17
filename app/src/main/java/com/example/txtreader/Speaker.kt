package com.example.txtreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import java.util.Locale

/**
 * Phase 6 TTS：系統語音逐源行朗讀（從目前看到的行開始，看到什麼念什麼，含簡繁轉換）。
 * - 空行跳過；單行超過引擎上限（~4000字）自動切塊；一次只排 ~30 句進引擎，播完補。
 * - 暫停 = stop() + 記住行號，繼續從該行重播（句級精度，不用倒帶找位置）。
 * - 來電／被搶音訊自動暫停；短暫失去重拿回來自動繼續。
 * - 耳機線控／藍牙耳機：MediaSession + 本進程無聲 AudioTrack 搶佔媒體焦點 + 顯式 BroadcastReceiver。
 * - 螢幕關掉整夜連播：搭配 TtsService 前景服務（mediaPlayback）＋ PARTIAL_WAKE_LOCK 防止系統休眠殺程序。
 */
class Speaker(
    context: Context,
    private val source: () -> Paginator.LineSource?,
    private val listener: Listener
) {
    interface Listener {
        fun onSpeakLine(line: Int)
        fun onBookDone()
        fun onTtsError(msg: String)
    }

    private val appCtx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var playing = false
    private var queued = 0
    private var speaking = -1
    private var nextLine = -1
    private var focusLossPause = false
    private var pendingPlay: Int? = null
    private var session: MediaSession? = null
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastToggleAt = 0L

    val sessionToken: MediaSession.Token?
        get() = session?.sessionToken

    /**
     * 統一防連跳（Debounce 300ms）：
     * 藍牙耳機按下時，系統可能同時觸發 MediaSession.Callback 與 MediaButtonReceiver，
     * 若不節流會瞬間「暫停→又開始」抵銷掉，看起來就像沒反應。
     */
    fun dispatchToggle() {
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (now - lastToggleAt < 300L) return@post
            lastToggleAt = now
            onMediaToggle?.invoke()
        }
    }

    /**
     * 持有多媒體喚醒鎖（PARTIAL_WAKE_LOCK）：
     * 每次開播與每念完一句補佇列（topUp）時續租 30 分鐘，確保關螢幕數小時 CPU 也不會在句間休眠；
     * 暫停／停止／播完立即釋放，且有 30 分鐘安全上限避免意外常駐耗電。
     */
    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: run {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TxtReader:TTS").apply {
                    setReferenceCounted(false)
                }.also { wakeLock = it }
            }
            wl.acquire(30 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    /**
     * 關鍵：Android 8+ 的 MediaSessionStack 只把藍牙耳機按鍵派給「自己 UID 有實際播放 USAGE_MEDIA AudioTrack」的 App。
     * 因為系統 TTS 是在語音引擎程序（如 Google/Samsung TTS）發聲，本 App 自己的 UID 若沒播過 AudioTrack，
     * 藍牙耳機按鍵會被系統送給上一個音樂 App 或直接忽略。
     * 這裡在本進程播放 100ms 無聲 PCM，讓系統 AudioPlaybackMonitor 立即將 TxtReader 認證為當前媒體播放器。
     */
    private fun claimMediaButtonSession() {
        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 8000
                val samples = sampleRate / 10 // 100ms 靜音
                val buf = ByteArray(samples * 2)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buf.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(120)
            } catch (_: Exception) {
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 播放狀態＋MediaMetadata＋保持 session 活躍：暫停也不關，這樣藍牙耳機才能按繼續。 */
    private fun updateState(playing: Boolean, currentText: String? = null) {
        try {
            val s = session ?: return
            val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val speed = if (playing) 1f else 0f
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(
                        MediaMetadata.METADATA_KEY_TITLE,
                        currentText ?: if (playing) "TxtReader 朗讀中" else "TxtReader 已暫停"
                    )
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "TxtReader")
                    .build()
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed, SystemClock.elapsedRealtime())
                    .build()
            )
            s.isActive = true
        } catch (_: Exception) {
        }
    }

    /** 給 MediaButtonReceiver 呼叫（同進程靜態，免綁定；Activity 啟動時掛上，銷毀時清掉）。 */
    companion object {
        var onExternalToggle: (() -> Unit)? = null
        var onExternalStop: (() -> Unit)? = null
    }

    /** 耳機／藍牙播放暫停鍵的回調（Activity 切開關）。 */
    var onMediaToggle: (() -> Unit)? = null

    /** 被來電搶走自動暫停時通知 Activity 改按鈕字。 */
    var onAutoPaused: (() -> Unit)? = null

    /** 短暫失去重拿回來自動繼續時通知 Activity 改按鈕字。 */
    var onAutoResumed: (() -> Unit)? = null

    /** 語速（1.0 正常；各家引擎上限不同，太離譜會被引擎打回）。改了下一句生效。 */
    var speechRate: Float = 1f
        set(v) {
            field = v.coerceIn(0.25f, 4f)
            try {
                tts?.setSpeechRate(field)
            } catch (_: Exception) {
            }
        }

    /** 聲調（1.0 正常）。改了下一句生效。 */
    var pitch: Float = 1f
        set(v) {
            field = v.coerceIn(0.5f, 2f)
            try {
                tts?.setPitch(field)
            } catch (_: Exception) {
            }
        }

    fun isPlaying(): Boolean = playing

    private val channelId = TtsService.CHANNEL_ID

    /** 通知列控制：播／暫停各一個直達按鈕（不經媒體鍵派送，保證能按）。失敗不影響播音。 */
    private fun toggleIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            appCtx, 0,
            Intent(appCtx, MediaButtonReceiver::class.java).setAction(MediaButtonReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            appCtx, 1,
            Intent(appCtx, MediaButtonReceiver::class.java).setAction(MediaButtonReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        try {
            val mgr = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "朗讀控制", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun showNotification(playing: Boolean, text: String?) {
        try {
            ensureChannel()
            val style = Notification.MediaStyle()
            // 摺疊視圖兩個鈕都顯示，不然三星只給第一個
            style.setShowActionsInCompactView(0, 1)
            try {
                session?.let { style.setMediaSession(it.sessionToken) }
            } catch (_: Exception) {
            }
            val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val nb = Notification.Builder(appCtx, channelId)
                .setSmallIcon(icon)
                .setContentTitle("TxtReader" + if (playing) "朗讀中" else "已暫停")
                .setContentText(text ?: if (playing) "點暫停停止" else "點繼續")
                .setContentIntent(
                    PendingIntent.getActivity(
                        appCtx, 0,
                        appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(icon, if (playing) "暫停" else "繼續", toggleIntent())
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent())
                .setStyle(style)
                // 播才常駐；暫停可滑掉，滑掉＝停止（三星會收進媒體面板，滑掉照樣停）
                .setOngoing(playing)
                .setDeleteIntent(stopIntent())
                .setOnlyAlertOnce(true)
            // 啟動或更新前景服務（TtsService），防止關螢幕一小時後被系統殺掉
            TtsService.startOrUpdate(appCtx, nb.build(), playing)
        } catch (_: Exception) {
        }
    }

    private fun updateNotifText(line: Int) {
        if (!playing || line < 0) return
        val t = try {
            source()?.get(line)?.trim()?.take(60)
        } catch (_: Exception) {
            null
        }
        val clean = t?.ifBlank { null }
        updateState(true, clean)
        showNotification(true, clean)
    }

    private fun cancelNotification() {
        TtsService.stop(appCtx)
    }

    fun lastSpoken(): Int = speaking

    @Suppress("DEPRECATION")
    fun init() {
        audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // 舊式搶鍵：直接登記為媒體鍵接收器，覆寫殭屍記錄（已解除安裝的播放器殘留）。
        // 三星認這套，比 MediaSession 回調還優先。
        try {
            audio?.registerMediaButtonEventReceiver(
                ComponentName(appCtx, MediaButtonReceiver::class.java)
            )
        } catch (_: Exception) {
        }
        // 拔耳機／藍牙斷線自動暫停
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action && playing) {
                    pause()
                    main.post { onAutoPaused?.invoke() }
                }
            }
        }.also {
            try {
                appCtx.registerReceiver(it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            } catch (_: Exception) {
                noisyReceiver = null
            }
        }
        session = MediaSession(appCtx, "TxtReader").apply {
            // 明說要收媒體鍵＋傳輸控制：部分手機（三星）預設不給，不寫就收不到耳機事件
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev != null) {
                        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                            when (ev.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY,
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP,
                                KeyEvent.KEYCODE_MEDIA_NEXT,
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                    dispatchToggle()
                                    return true
                                }
                            }
                        }
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    dispatchToggle()
                }

                override fun onPause() {
                    dispatchToggle()
                }

                override fun onStop() {
                    dispatchToggle()
                }

                override fun onSkipToNext() {
                    dispatchToggle()
                }

                override fun onSkipToPrevious() {
                    dispatchToggle()
                }
            })
        }
        // 舊式通道也指過來：注意 Android 12+ (API 31+) PendingIntent 必須是 FLAG_MUTABLE，
        // 系統才塞得進 EXTRA_KEY_EVENT（之前寫 IMMUTABLE 導致 event 永遠是 null！）
        try {
            val mutFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                appCtx, 0,
                Intent(appCtx, MediaButtonReceiver::class.java).setAction(Intent.ACTION_MEDIA_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT or mutFlag
            )
            session?.setMediaButtonReceiver(pi)
            if (Build.VERSION.SDK_INT >= 31) {
                session?.setMediaButtonBroadcastReceiver(
                    ComponentName(appCtx, MediaButtonReceiver::class.java)
                )
            }
        } catch (_: Exception) {
        }
        // 啟動時就先激活 session 並宣告本進程為媒體播放者，讓耳機隨時按都能直接開讀
        updateState(false)
        claimMediaButtonSession()
    }

    /**
     * TTS 引擎本體懶加載：第一次朗讀／試聽才建（綁系統服務約幾百毫秒）。
     * 建好後若有人在等（pendingPlay），直接開播。
     */
    private fun initEngine() {
        if (tts != null) return
        tts = TextToSpeech(appCtx) { st ->
            if (st != TextToSpeech.SUCCESS) {
                post { listener.onTtsError("語音引擎啟動失敗") }
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            try {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (_: Exception) {
            }
            // 繁→簡→中：第一個可用的中文語音
            val ok = listOf(Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE, Locale.CHINESE)
                .any { loc ->
                    try {
                        val r = t.setLanguage(loc)
                        r == TextToSpeech.LANG_AVAILABLE ||
                            r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                            r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                    } catch (_: Exception) {
                        false
                    }
                }
            if (!ok) {
                post { listener.onTtsError("缺中文語音，請到系統設定安裝") }
                return@TextToSpeech
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {
                    speaking = id.substringAfter("t_").substringBefore("_").toIntOrNull() ?: -1
                    updateNotifText(speaking)
                    post { listener.onSpeakLine(speaking) }
                }

                override fun onDone(id: String) {
                    onUtteranceEnd()
                }

                @Deprecated("compat")
                override fun onError(id: String) {
                    onUtteranceEnd()
                }

                override fun onError(id: String, code: Int) {
                    onUtteranceEnd()
                }
            })
            engineReady = true
            // 懶加載：之前有人按過朗讀，引擎好了直接開播（binder 線程，play 內部都是線程安全的）
            pendingPlay?.let {
                pendingPlay = null
                play(it)
            }
        }
    }

    private fun post(r: () -> Unit) {
        main.post(r)
    }

    /** 從第 from 行開始播。引擎還沒好就先建，等好了自動開播。 */
    fun play(from: Int) {
        acquireWakeLock()
        if (tts == null) {
            initEngine()
            pendingPlay = maxOf(0, from)
            return
        }
        if (!engineReady) {
            pendingPlay = maxOf(0, from)
            return
        }
        requestFocus()
        claimMediaButtonSession()
        try {
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)
        } catch (_: Exception) {
        }
        tts?.stop()
        queued = 0
        speaking = -1
        nextLine = maxOf(0, from)
        topUp()
        val src = source()
        if (queued == 0) {
            releaseWakeLock()
            // play 可能跑在 binder 線程（懶加載回調），listener 一律 post 到主線程
            if (src == null || nextLine >= src.size) {
                abandonFocus()
                cancelNotification()
                post { listener.onBookDone() }
            } else post { listener.onTtsError("後面沒有可朗讀的文字") }
            return
        }
        playing = true
        focusLossPause = false
        updateState(true)
        showNotification(true, null)
    }

    /** 暫停：清空引擎佇列，行號已記住，繼續時從該行重播。 */
    fun pause() {
        playing = false
        releaseWakeLock()
        tts?.stop()
        queued = 0
        // 故意保持 session 活躍：暫停後耳機還能按繼續
        updateState(false)
        showNotification(false, null)
    }

    fun stop() {
        playing = false
        releaseWakeLock()
        tts?.stop()
        queued = 0
        speaking = -1
        nextLine = -1
        abandonFocus()
        // 保持 session 活躍設為 paused，讓藍牙耳機就算按過停止也還能隨時按播放鍵重啟
        updateState(false)
        cancelNotification()
    }

    /** 設定頁試聽：不動正片佇列，直接插播一句。沒在播才呼叫（呼叫方保證）。 */
    fun speakPreview(text: String) {
        if (tts == null) {
            // 引擎還沒建：先建起來，這次不播（半秒後再拉就有聲）
            initEngine()
            return
        }
        if (!engineReady) return
        try {
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(pitch)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
        } catch (_: Exception) {
        }
    }

    /** 播完一句補一句，保持約 30 句緩衝。超長單行切塊（引擎單句上線 ~4000 字）。 */
    private fun topUp() {
        val src = source() ?: return
        val t = tts ?: return
        if (playing) acquireWakeLock()
        var n = nextLine
        while (queued < 30 && n < src.size) {
            val text = try {
                src.get(n).trim()
            } catch (_: Exception) {
                ""
            }
            // 裝飾行（整行標點符號，如 =====、……、※※※）直接跳過不念；章節標題有字會照念
            if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) {
                if (text.length <= 3900) {
                    if (t.speak(text, TextToSpeech.QUEUE_ADD, null, "t_$n") == TextToSpeech.SUCCESS) {
                        queued++
                    }
                } else {
                    var k = 0
                    var off = 0
                    while (off < text.length) {
                        val end = minOf(off + 3900, text.length)
                        if (t.speak(text.substring(off, end), TextToSpeech.QUEUE_ADD, null, "t_${n}_$k") == TextToSpeech.SUCCESS) {
                            queued++
                        }
                        k++
                        off = end
                    }
                }
            }
            n++
        }
        nextLine = n
    }

    private fun onUtteranceEnd() {
        if (queued > 0) queued--
        if (!playing) return
        topUp()
        val src = source()
        if (queued == 0 && (src == null || nextLine >= src.size)) {
            playing = false
            releaseWakeLock()
            abandonFocus()
            updateState(false)
            cancelNotification()
            post { listener.onBookDone() }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) {
                    focusLossPause = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    pause()
                    post { onAutoPaused?.invoke() }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusLossPause) {
                    focusLossPause = false
                    val resumeFrom = if (speaking >= 0) speaking else nextLine.coerceAtLeast(0)
                    play(resumeFrom)
                    if (playing) post { onAutoResumed?.invoke() }
                }
            }
        }
    }

    private fun requestFocus() {
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonFocus() {
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    fun release() {
        releaseWakeLock()
        try {
            noisyReceiver?.let { appCtx.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        noisyReceiver = null
        try {
            audio?.unregisterMediaButtonEventReceiver(
                ComponentName(appCtx, MediaButtonReceiver::class.java)
            )
        } catch (_: Exception) {
        }
        stop()
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        engineReady = false
    }
}
