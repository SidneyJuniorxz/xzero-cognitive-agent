package com.xzero.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Assinatura de voz (voice print) local, sem modelo externo e sem rede.
 *
 * Calcula bandas log-mel do trecho que disparou o VAD e compara com o perfil do usuário,
 * que é construído sozinho a partir dos despertares PRODUTIVOS (os que viraram ação —
 * esses são, por construção, a voz dele). Serve para separar "o usuário falou comigo" de
 * "a TV/outra pessoa falou na sala", que é a maior fonte dos disparos desperdiçados.
 *
 * Por padrão NÃO bloqueia nada: só alimenta a telemetria e a pontuação do [VadAutoTuner].
 * O bloqueio só liga via `Prefs.voicePrintGate` depois que o perfil amadurece, porque um
 * falso-negativo aqui significa o X0 ficar surdo — pior que acordar à toa.
 */
object VoicePrint {

    private const val TAG = "VoicePrint"
    private const val FFT_SIZE = 512
    private const val BANDS = 24
    private const val SAMPLE_RATE = 16_000
    private const val MIN_SAMPLES_FOR_MATURE_PROFILE = 12
    /** Abaixo desta similaridade, com perfil maduro, a voz é considerada "não é o usuário". */
    const val UNKNOWN_VOICE_THRESHOLD = 0.70

    @Volatile private var profile: DoubleArray? = null
    @Volatile var profileSamples: Int = 0
        private set
    @Volatile var lastSimilarity: Double = -1.0
        private set
    @Volatile var lastDecision: String = "—"
        private set
    @Volatile var rejectedByGate: Int = 0
        private set
    @Volatile private var ctxRef: Context? = null

    private val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1)) }
    private val bandEdges: IntArray by lazy { buildBandEdges() }

    val isMature: Boolean get() = profileSamples >= MIN_SAMPLES_FOR_MATURE_PROFILE

    fun init(ctx: Context) {
        if (ctxRef != null) return
        ctxRef = ctx.applicationContext
        load()
        Log.i(TAG, "🗣️ Perfil de voz: $profileSamples amostra(s)${if (isMature) " (maduro)" else ""}")
    }

    /** Bordas de banda log-espaçadas entre ~80 Hz e ~7,6 kHz (aproximação de mel). */
    private fun buildBandEdges(): IntArray {
        val lowHz = 80.0
        val highHz = 7600.0
        val edges = IntArray(BANDS + 1)
        for (i in 0..BANDS) {
            val hz = lowHz * Math.pow(highHz / lowHz, i.toDouble() / BANDS)
            edges[i] = ((hz / SAMPLE_RATE) * FFT_SIZE).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
        }
        return edges
    }

    /**
     * Extrai o vetor de 24 bandas log-mel (média no tempo) do PCM 16 kHz mono informado.
     * Retorna null se o trecho for curto ou silencioso demais para ter conteúdo de voz.
     */
    fun featuresFrom(pcm: ShortArray, length: Int): DoubleArray? {
        if (length < FFT_SIZE * 2) return null
        val acc = DoubleArray(BANDS)
        var frames = 0
        var pos = 0
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        while (pos + FFT_SIZE <= length) {
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = pcm[pos + i].toDouble()
                energy += s * s
                re[i] = s * hann[i]
                im[i] = 0.0
            }
            // Ignora janelas praticamente silenciosas (não carregam timbre)
            if (sqrt(energy / FFT_SIZE) >= 60.0) {
                fft(re, im)
                for (b in 0 until BANDS) {
                    var sum = 0.0
                    for (k in bandEdges[b] until bandEdges[b + 1].coerceAtLeast(bandEdges[b] + 1)) {
                        sum += re[k] * re[k] + im[k] * im[k]
                    }
                    acc[b] += ln(1.0 + sum)
                }
                frames++
            }
            pos += FFT_SIZE / 2 // hop de 50%
        }
        if (frames < 2) return null
        for (b in 0 until BANDS) acc[b] /= frames
        // Remove o nível geral (volume/distância) deixando só a FORMA espectral (timbre)
        val mean = acc.average()
        for (b in 0 until BANDS) acc[b] -= mean
        val norm = sqrt(acc.sumOf { it * it })
        if (norm <= 1e-9) return null
        for (b in 0 until BANDS) acc[b] /= norm
        return acc
    }

    /** Similaridade de cosseno com o perfil (-1 quando ainda não há perfil). */
    fun similarity(feats: DoubleArray): Double {
        val p = profile ?: return -1.0
        var dot = 0.0
        for (i in feats.indices) dot += feats[i] * p[i]
        return dot
    }

    /**
     * Avalia um disparo do VAD. Retorna true se o despertar deve prosseguir.
     * Com `Prefs.voicePrintGate` desligado (padrão) sempre prossegue — só registra.
     */
    fun evaluateTrigger(feats: DoubleArray?, gateEnabled: Boolean): Boolean {
        if (feats == null) {
            lastSimilarity = -1.0
            lastDecision = "sem_features"
            return true
        }
        val sim = similarity(feats)
        lastSimilarity = sim
        if (sim < 0) {
            lastDecision = "perfil_vazio"
            return true
        }
        val unknown = isMature && sim < UNKNOWN_VOICE_THRESHOLD
        lastDecision = when {
            !isMature -> "perfil_imaturo(${fmt(sim)})"
            unknown -> "voz_desconhecida(${fmt(sim)})"
            else -> "voz_do_usuario(${fmt(sim)})"
        }
        if (unknown && gateEnabled) {
            rejectedByGate++
            Log.i(TAG, "🚫 Disparo barrado: voz não reconhecida (sim=${fmt(sim)})")
            return false
        }
        return true
    }

    /** Agrega o trecho ao perfil (média corrida). Chamado só em despertar produtivo. */
    @Synchronized
    fun enroll(feats: DoubleArray?) {
        if (feats == null) return
        val p = profile
        if (p == null) {
            profile = feats.copyOf()
            profileSamples = 1
        } else {
            val n = profileSamples.toDouble()
            for (i in p.indices) p[i] = (p[i] * n + feats[i]) / (n + 1.0)
            val norm = sqrt(p.sumOf { it * it })
            if (norm > 1e-9) for (i in p.indices) p[i] /= norm
            profileSamples++
        }
        Log.i(TAG, "🗣️ Perfil de voz atualizado ($profileSamples amostras)")
        save()
    }

    @Synchronized
    fun reset() {
        profile = null
        profileSamples = 0
        rejectedByGate = 0
        lastSimilarity = -1.0
        lastDecision = "—"
        runCatching { file()?.delete() }
    }

    private fun file(): File? = ctxRef?.let { File(it.filesDir, "voiceprint.json") }

    private fun save() {
        val f = file() ?: return
        val p = profile ?: return
        runCatching {
            val arr = JSONArray()
            for (x in p) arr.put(Math.round(x * 100000.0) / 100000.0)
            f.writeText(JSONObject().put("samples", profileSamples).put("profile", arr).toString())
        }
    }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("profile") ?: return
            if (arr.length() != BANDS) return
            profile = DoubleArray(BANDS) { arr.optDouble(it, 0.0) }
            profileSamples = o.optInt("samples", 0)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("amostras_perfil", profileSamples)
        .put("perfil_maduro", isMature)
        .put("ultima_similaridade", Math.round(lastSimilarity * 1000.0) / 1000.0)
        .put("ultima_decisao", lastDecision)
        .put("barrados_pelo_gate", rejectedByGate)
        .put("limiar_voz_desconhecida", UNKNOWN_VOICE_THRESHOLD)

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    /** FFT radix-2 in-place (512 pontos, ~0,05 ms por janela). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
