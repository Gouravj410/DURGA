package com.durga.emergency.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

class DurgaAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "DURGA_AUDIO_PLAYER"
    }

    private var mediaPlayer: MediaPlayer? = null

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    fun playAudio(
        audioPathOrUrl: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        stopAudio()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )

                if (audioPathOrUrl.startsWith("http://") || audioPathOrUrl.startsWith("https://")) {
                    setDataSource(context, Uri.parse(audioPathOrUrl))
                } else {
                    val file = File(audioPathOrUrl)
                    if (!file.exists()) {
                        onError("Audio file not found: ${file.name}")
                        return
                    }
                    setDataSource(file.absolutePath)
                }

                setOnPreparedListener {
                    start()
                    Log.d(TAG, "Audio playback started: $audioPathOrUrl")
                }

                setOnCompletionListener {
                    Log.d(TAG, "Audio playback completed")
                    stopAudio()
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    stopAudio()
                    onError("Playback error: $what")
                    true
                }

                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback: ${e.message}", e)
            onError(e.message ?: "Playback initialization failed")
        }
    }

    fun stopAudio() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
    }
}
