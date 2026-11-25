package com.moto.speedalert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the alert sounds through the navigation-guidance audio stream, so they come out
 * of a Bluetooth headset / helmet intercom and briefly lower any music.
 *
 * - Overspeed: one sharp 2 kHz beep every 0.5 s while [setOverspeed] is true.
 * - Camera ahead: three lower 1 kHz pips.
 */
class Beeper(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private val beep = makeTrack(tone(2000.0, 150))
    private val chime = makeTrack(
        tone(1000.0, 120) + silence(80) + tone(1000.0, 120) + silence(80) + tone(1300.0, 250)
    )

    private var overspeed = false
    private var hasFocus = false

    var volume: Float = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            beep.setVolume(field)
            chime.setVolume(field)
        }

    private val beepLoop = object : Runnable {
        override fun run() {
            if (!overspeed) return
            play(beep)
            handler.postDelayed(this, BEEP_INTERVAL_MS)
        }
    }

    fun setOverspeed(on: Boolean) {
        if (on == overspeed) return
        overspeed = on
        handler.removeCallbacks(beepLoop)
        if (on) {
            requestFocus()
            beepLoop.run()
        } else {
            abandonFocusLater()
        }
    }

    fun cameraAlert() {
        requestFocus()
        play(chime)
        if (!overspeed) abandonFocusLater()
    }

    /** Single beep for the "test sound" button. */
    fun testBeep() {
        requestFocus()
        play(beep)
        abandonFocusLater()
    }

    fun release() {
        overspeed = false
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        beep.release()
        chime.release()
    }

    private fun play(track: AudioTrack) {
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
        }
    }

    private fun requestFocus() {
        handler.removeCallbacks(abandonRunnable)
        if (!hasFocus) {
            audioManager.requestAudioFocus(focusRequest)
            hasFocus = true
        }
    }

    private val abandonRunnable = Runnable { if (!overspeed) abandonFocus() }
    private fun abandonFocusLater() {
        handler.removeCallbacks(abandonRunnable)
        handler.postDelayed(abandonRunnable, 1500)
    }

    private fun abandonFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    private fun makeTrack(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    companion object {
        const val BEEP_INTERVAL_MS = 500L
        private const val RATE = 44_100

        private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

        /** Sine tone with 5 ms fade in/out so it does not click. */
        private fun tone(freq: Double, ms: Int): ShortArray {
            val n = RATE * ms / 1000
            val fade = RATE * 5 / 1000
            return ShortArray(n) { i ->
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (sin(2 * PI * freq * i / RATE) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
        }
    }
}
