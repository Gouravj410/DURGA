package com.durga.emergency

import com.durga.emergency.ai.DurgaScoringDistressAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class DistressAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val analyzer = DurgaScoringDistressAnalyzer()

    @Test
    fun analyzeAudioFile_nonExistentFile_returnsInconclusiveSafetyFallback() = runBlocking {
        val dummyFile = File("non_existent_file.wav")
        val result = analyzer.analyzeAudioFile(dummyFile)

        // Safety principle: missing/empty audio must NOT auto-escalate with high distress score
        assertEquals(0.25f, result.score, 0.01f)
        assertTrue(result.classification.contains("Inconclusive"))
        assertEquals(0.50f, result.confidence, 0.01f)
    }

    @Test
    fun analyzeAudioFile_highAmplitudeAudio_classifiesHighDistress() = runBlocking {
        // Generate synthetic high-amplitude 16-bit PCM WAV file (simulating loud vocalization / screaming)
        val audioFile = createSyntheticWavFile(amplitude = 24000.toShort(), sampleCount = 16000)
        val result = analyzer.analyzeAudioFile(audioFile)

        assertTrue(result.score >= 0.70f)
        assertTrue(result.classification.contains("High Distress"))
        assertTrue(result.detectedSignals.any { it.contains("Peak Transient") })
    }

    @Test
    fun analyzeAudioFile_lowAmplitudeAudio_classifiesLowDistress() = runBlocking {
        // Generate synthetic low-amplitude 16-bit PCM WAV file (simulating quiet ambient room)
        val audioFile = createSyntheticWavFile(amplitude = 200.toShort(), sampleCount = 16000)
        val result = analyzer.analyzeAudioFile(audioFile)

        assertTrue(result.score < 0.40f)
        assertTrue(result.classification.contains("Low Distress"))
    }

    private fun createSyntheticWavFile(amplitude: Short, sampleCount: Int): File {
        val file = tempFolder.newFile("test_audio.wav")
        val pcmDataLen = sampleCount * 2
        val totalDataLen = pcmDataLen + 36
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLen and 0xff).toByte()
        header[41] = ((pcmDataLen shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLen shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLen shr 24) and 0xff).toByte()

        val fos = FileOutputStream(file)
        fos.write(header)

        // Alternate positive and negative samples for sinusoidal waveform
        val sampleBytes = ByteArray(pcmDataLen)
        for (i in 0 until sampleCount) {
            val s = if (i % 2 == 0) amplitude else (-amplitude).toShort()
            sampleBytes[i * 2] = (s.toInt() and 0x00FF).toByte()
            sampleBytes[i * 2 + 1] = ((s.toInt() shr 8) and 0x00FF).toByte()
        }
        fos.write(sampleBytes)
        fos.flush()
        fos.close()

        return file
    }
}
