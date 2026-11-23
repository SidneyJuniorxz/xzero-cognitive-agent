package com.xzero.agent

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Detector de palavra-chave offline (Sherpa-ONNX Zipformer 3.3M) rodando direto
 * sobre os frames PCM 16 kHz do AudioRecord no VoiceActivityDetector.
 *
 * Zero risco: se o modelo falhar ao carregar por qualquer motivo, `isReady` fica
 * false e o VoiceActivityDetector continua operando normalmente via energia/SNR.
 */
object OfflineWakeWordSpotter {

    private const val TAG = "OfflineKWS"
    private const val SAMPLE_RATE = 16_000

    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var stream: OnlineStream? = null
    @Volatile var isReady: Boolean = false
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (isReady) return
        runCatching {
            val am = ctx.assets
            val dir = "kws"
            val transducer = OnlineTransducerModelConfig(
                encoder = "$dir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$dir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                joiner = "$dir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            )
            val modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2",
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                maxActivePaths = 4,
                keywordsFile = "$dir/keywords.txt",
                keywordsScore = 2.2f,
                keywordsThreshold = 0.05f,
                numTrailingBlanks = 1,
            )
            val ks = KeywordSpotter(am, config)
            val st = ks.createStream("")
            spotter = ks
            stream = st
            isReady = true
            Log.i(TAG, "✅ Sherpa-ONNX KWS offline pronto (X-Zero / X0 / Xenon / Ada)")
        }.onFailure {
            isReady = false
            Log.w(TAG, "⚠️ KWS offline indisponível (fallback VAD ativo): ${it.message}")
        }
    }

    /**
     * Alimenta um bloco PCM 16-bit (16 kHz) e retorna o nome da wake word detectada
     * (ex.: "XZERO", "X0", "XENON", "ADA") ou null se ainda não detectou.
     */
    @Synchronized
    fun feedPcm16(pcm: ShortArray, count: Int): String? {
        val ks = spotter ?: return null
        val st = stream ?: return null
        if (!isReady || count <= 0) return null
        return runCatching {
            val floats = FloatArray(count) { i -> pcm[i] / 32768.0f }
            st.acceptWaveform(floats, SAMPLE_RATE)
            while (ks.isReady(st)) {
                ks.decode(st)
            }
            val kw = ks.getResult(st).keyword.trim()
            if (kw.isNotEmpty()) {
                ks.reset(st)
                Log.i(TAG, "🎯 Wake word detectada offline: '$kw'")
                kw
            } else {
                null
            }
        }.getOrNull()
    }

    @Synchronized
    fun reset() {
        val ks = spotter ?: return
        val st = stream ?: return
        runCatching { ks.reset(st) }
    }
}
