package com.xzero.agent

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SoundFeedback: sintetiza pequenos tons/bipes digitais extremamente sutis e futuristas
 * (curtos, suaves, sem chiados e sem falar por voz) para indicar mudanças de estado:
 * - OUVINDO (dois tons ascendentes suaves)
 * - PENSANDO (um pulso suave de baixa frequência)
 * - CONCLUÍDO (dois tons cristalinos de sucesso)
 * - CONFIRMAÇÃO (tom sutil de pergunta)
 */
object SoundFeedback {

    private const val SAMPLE_RATE = 44100

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val audioFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    /** Tom ascendente suave indicando que começou a ouvir (440Hz -> 880Hz, 80ms) */
    suspend fun playListening() = withContext(Dispatchers.Default) {
        val samples = generateChirp(520.0, 880.0, 0.08, 0.22)
        playPcm(samples)
    }

    /** Tom discreto indicando que começou a pensar/processar (440Hz, 60ms) */
    suspend fun playThinking() = withContext(Dispatchers.Default) {
        val samples = generateTone(440.0, 0.06, 0.15)
        playPcm(samples)
    }

    /** Tom sutil de sucesso/conclusão (dois beeps harmônicos suaves: 660Hz e 990Hz, 90ms) */
    suspend fun playDone() = withContext(Dispatchers.Default) {
        val part1 = generateTone(660.0, 0.045, 0.20)
        val part2 = generateTone(990.0, 0.055, 0.22)
        val combined = ShortArray(part1.size + part2.size)
        System.arraycopy(part1, 0, combined, 0, part1.size)
        System.arraycopy(part2, 0, combined, part1.size, part2.size)
        playPcm(combined)
    }

    /** Tom discreto de pergunta / esperando confirmação (580Hz -> 720Hz, 70ms) */
    suspend fun playPrompt() = withContext(Dispatchers.Default) {
        val samples = generateChirp(580.0, 720.0, 0.07, 0.20)
        playPcm(samples)
    }

    private fun generateTone(freq: Double, durationSec: Double, volume: Double): ShortArray {
        val numSamples = (durationSec * SAMPLE_RATE).toInt()
        val out = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            // Envelope suave de ataque e decaimento (Fade in / Fade out) para não fazer 'clique'
            val env = when {
                i < numSamples * 0.15 -> i / (numSamples * 0.15)
                i > numSamples * 0.70 -> (numSamples - i) / (numSamples * 0.30)
                else -> 1.0
            }
            val sample = Math.sin(2.0 * Math.PI * freq * t) * env * volume * Short.MAX_VALUE
            out[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun generateChirp(f0: Double, f1: Double, durationSec: Double, volume: Double): ShortArray {
        val numSamples = (durationSec * SAMPLE_RATE).toInt()
        val out = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val curFreq = f0 + (f1 - f0) * (i.toDouble() / numSamples)
            val env = when {
                i < numSamples * 0.15 -> i / (numSamples * 0.15)
                i > numSamples * 0.70 -> (numSamples - i) / (numSamples * 0.30)
                else -> 1.0
            }
            val sample = Math.sin(2.0 * Math.PI * curFreq * t) * env * volume * Short.MAX_VALUE
            out[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun playPcm(samples: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep((samples.size * 1000L / SAMPLE_RATE) + 15)
            track.release()
        } catch (_: Exception) {
        }
    }
}
