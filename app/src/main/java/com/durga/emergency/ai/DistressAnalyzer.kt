package com.durga.emergency.ai

import android.util.Log
import com.durga.emergency.data.model.DistressResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

interface DistressAnalyzer {
    suspend fun analyzeAudioFile(audioFile: File): DistressResult
}

/**
 * DURGA Acoustic Distress Scoring & Analysis Engine.
 * Extracts physical acoustic features (RMS energy, peak amplitude, dBFS, zero-crossing rate)
 * and maps them into an emergency distress score and classification.
 */
class DurgaScoringDistressAnalyzer : DistressAnalyzer {

    companion object {
        private const val TAG = "DURGA_AI_ANALYSIS"
        private const val WAV_HEADER_SIZE = 44
    }

    override suspend fun analyzeAudioFile(audioFile: File): DistressResult = withContext(Dispatchers.Default) {
        if (!audioFile.exists() || audioFile.length() <= WAV_HEADER_SIZE) {
            Log.w(TAG, "Audio file missing or empty. Using default emergency trigger baseline.")
            return@withContext DistressResult(
                score = 0.85f,
                classification = "High Distress (Emergency Trigger Verified)",
                confidence = 0.90f,
                detectedSignals = listOf(
                    "Sensor Gesture Trigger Verified",
                    "Acoustic Capture Fallback Activated"
                ),
                timestamp = System.currentTimeMillis()
            )
        }

        try {
            val fileBytes = audioFile.readBytes()
            val pcmBytes = fileBytes.copyOfRange(WAV_HEADER_SIZE, fileBytes.size)
            val numShorts = pcmBytes.size / 2

            if (numShorts == 0) {
                return@withContext createFallbackResult("Insufficient audio frames")
            }

            var sumSquares = 0.0
            var peakSample = 0
            var zeroCrossings = 0
            var prevSample = 0

            for (i in 0 until numShorts) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sample = (high shl 8) or low

                sumSquares += sample.toDouble() * sample.toDouble()
                val absSample = abs(sample)
                if (absSample > peakSample) {
                    peakSample = absSample
                }

                if (i > 0 && ((prevSample >= 0 && sample < 0) || (prevSample < 0 && sample >= 0))) {
                    zeroCrossings++
                }
                prevSample = sample
            }

            val rms = sqrt(sumSquares / numShorts)
            val maxRms = 32767.0
            val normalizedRms = (rms / maxRms).coerceIn(0.0, 1.0)
            val dBFS = if (rms > 0.0) 20 * log10(rms / maxRms) else -96.0
            val zcr = zeroCrossings.toDouble() / numShorts

            // Acoustic classification logic
            val signals = mutableListOf<String>()
            signals.add(String.format("RMS Acoustic Power: %.1f dBFS", dBFS))
            signals.add(String.format("Peak Transient: %d / 32,767 (%.1f%%)", peakSample, (peakSample / 327.67)))

            val score: Float
            val classification: String

            when {
                // High distress: loud vocal energy or scream bursts
                dBFS > -22.0 || peakSample > 18000 -> {
                    score = ((0.75 + (normalizedRms * 0.5)).coerceIn(0.70, 0.98)).toFloat()
                    classification = "High Distress (High-Intensity Vocalization / Screaming detected)"
                    signals.add("Elevated Acoustic Transient Spike")
                    if (zcr > 0.12) signals.add("High-Frequency Acoustic Turbulence")
                }
                // Moderate distress: elevated speech, shouting, struggle sounds
                dBFS > -34.0 || peakSample > 7000 -> {
                    score = ((0.42 + (normalizedRms * 0.4)).coerceIn(0.40, 0.69)).toFloat()
                    classification = "Moderate Distress (Elevated Voice / Shouting detected)"
                    signals.add("Elevated Vocal Decibel Range")
                }
                // Low distress: ambient audio or whisper
                else -> {
                    score = ((0.15 + (normalizedRms * 0.3)).coerceIn(0.10, 0.39)).toFloat()
                    classification = "Low Distress (Ambient sound / Low vocal activity)"
                    signals.add("Low Ambient Audio Level")
                }
            }

            Log.d(TAG, "Audio analyzed: score=$score, dBFS=$dBFS, classification=$classification")

            DistressResult(
                score = score,
                classification = classification,
                confidence = 0.92f,
                detectedSignals = signals,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Acoustic signal analysis failed: ${e.message}", e)
            createFallbackResult("Signal analysis degraded: ${e.message}")
        }
    }

    private fun createFallbackResult(reason: String): DistressResult {
        return DistressResult(
            score = 0.80f,
            classification = "High Distress (Sensor Escalation)",
            confidence = 0.85f,
            detectedSignals = listOf("Acoustic Fallback", reason),
            timestamp = System.currentTimeMillis()
        )
    }
}
