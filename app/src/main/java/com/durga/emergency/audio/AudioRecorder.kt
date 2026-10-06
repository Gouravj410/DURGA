package com.durga.emergency.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

interface AudioRecorder {
    suspend fun recordAudio(
        durationMillis: Long = 5000L,
        onProgress: (secondsRemaining: Int) -> Unit = {}
    ): File?
}

class DurgaPcmAudioRecorder(private val context: Context) : AudioRecorder {

    companion object {
        private const val TAG = "DURGA_AUDIO"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    override suspend fun recordAudio(
        durationMillis: Long,
        onProgress: (secondsRemaining: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord minBufferSize error: $minBufferSize")
            return@withContext null
        }

        val bufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE * 2) // at least 1 second buffer
        var audioRecord: AudioRecord? = null

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return@withContext null
            }

            val recordingsDir = File(context.cacheDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val outputFile = File(recordingsDir, "emergency_${System.currentTimeMillis()}.wav")
            val outputStream = FileOutputStream(outputFile)

            // Write temporary 44-byte WAV header placeholder
            val emptyHeader = ByteArray(44)
            outputStream.write(emptyHeader)

            audioRecord.startRecording()
            Log.d(TAG, "Audio recording started: ${outputFile.name} (duration=${durationMillis}ms)")

            val shortBuffer = ShortArray(bufferSize / 2)
            val byteBuffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            var totalPcmBytes = 0L

            while (System.currentTimeMillis() - startTime < durationMillis) {
                val elapsed = System.currentTimeMillis() - startTime
                val remainingSec = (((durationMillis - elapsed) / 1000) + 1).toInt()
                withContext(Dispatchers.Main) {
                    onProgress(remainingSec)
                }

                val readShorts = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readShorts > 0) {
                    // Convert shorts to little-endian bytes
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i]
                        byteBuffer[i * 2] = (s.toInt() and 0x00FF).toByte()
                        byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0x00FF).toByte()
                    }
                    val bytesWritten = readShorts * 2
                    outputStream.write(byteBuffer, 0, bytesWritten)
                    totalPcmBytes += bytesWritten
                }
            }

            audioRecord.stop()
            outputStream.flush()
            outputStream.close()

            // Update WAV header with final size
            writeWavHeader(outputFile, totalPcmBytes, SAMPLE_RATE, 1, 16)
            Log.d(TAG, "Audio recording complete: ${outputFile.absolutePath} (${totalPcmBytes} bytes PCM)")

            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error recording audio: ${e.message}", e)
            null
        } finally {
            try {
                audioRecord?.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun writeWavHeader(
        file: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        val raf = RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(header)
        raf.close()
    }
}
