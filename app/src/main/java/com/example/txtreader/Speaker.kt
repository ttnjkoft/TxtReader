package com.example.txtreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Phase 6 TTS：系統語音逐源行朗讀（從目前看到的行開始，看到什麼念什麼，含簡繁轉換）。
 * - 空行跳過；單行超過引擎上限（~4000字）自動切塊；一次只排 ~30 句進引擎，播完補。
 * - 暫停 = stop() + 記住行號，繼續從該行重播（句級精度，不用倒帶找位置）。
 * - 來電／被搶音訊自動暫停；短暫失去重拿回來自動繼續。
 * - 耳機線控：MediaSession 接播放／暫停鍵。
 * - 螢幕關掉照播（已排入引擎的不停）；程序被系統殺掉才停。
 *   沒做 foreground Service：自用一般時長夠用，整夜連播不夠再加。
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
    private var session: MediaSession? = null
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

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

    fun lastSpoken(): Int = speaking

    fun init() {
        audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        session = MediaSession(appCtx, "TxtReader").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    main.post { onMediaToggle?.invoke() }
                }

                override fun onPause() {
                    main.post { onMediaToggle?.invoke() }
                }

                override fun onStop() {
                    main.post { onMediaToggle?.invoke() }
                }
            })
        }
        tts = TextToSpeech(appCtx) { st ->
            if (st != TextToSpeech.SUCCESS) {
                post { listener.onTtsError("語音引擎啟動失敗") }
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
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
        }
    }

    private fun post(r: () -> Unit) {
        main.post(r)
    }

    /** 從第 from 行開始播。 */
    fun play(from: Int) {
        if (!engineReady) {
            listener.onTtsError("語音引擎尚未就緒，稍後再按")
            return
        }
        requestFocus()
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
            if (src == null || nextLine >= src.size) {
                abandonFocus()
                listener.onBookDone()
            } else {
                listener.onTtsError("後面沒有可朗讀的文字")
            }
            return
        }
        playing = true
        focusLossPause = false
        session?.isActive = true
    }

    /** 暫停：清空引擎佇列，行號已記住，繼續時從該行重播。 */
    fun pause() {
        playing = false
        tts?.stop()
        queued = 0
        session?.isActive = false
    }

    fun stop() {
        pause()
        speaking = -1
        nextLine = -1
        abandonFocus()
    }

    /** 設定頁試聽：不動正片佇列，直接插播一句。沒在播才呼叫（呼叫方保證）。 */
    fun speakPreview(text: String) {
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
            abandonFocus()
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
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
        stop()
        try {
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
