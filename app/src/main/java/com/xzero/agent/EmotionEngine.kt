package com.xzero.agent

import android.graphics.Color
import android.util.Log
import org.json.JSONObject

/**
 * Estado emocional do X0 — o Xenon deixa de ser só animação decorativa e passa a ser um
 * INDICADOR DE ESTADO que o próprio agente lê de volta para corrigir rota.
 *
 * A ideia veio do pedido do usuário: "novas emoções que podem até servir de inspiração para
 * alguma correção de rota". Então cada emoção tem três saídas:
 *   1. visual   -> animação do [XenonSpriteView] + cor da aura/borda;
 *   2. prosódia -> pitch/velocidade do TTS (o X0 soa aliviado, tenso ou animado);
 *   3. cognitiva-> [routeCorrectionHint] entra no prompt do próximo passo, empurrando o
 *      modelo a mudar de estratégia quando a frustração sobe, em vez de insistir no mesmo erro.
 */
object EmotionEngine {

    private const val TAG = "EmotionEngine"

    enum class Emotion(
        val emoji: String,
        val auraColor: String,
        val pitchMul: Float,
        val rateMul: Float,
        val xenon: XenonSpriteView.Action,
    ) {
        /** Repouso: nada acontecendo. */
        CALMO("😌", "#35D7FF", 1.00f, 1.00f, XenonSpriteView.Action.IDLE),
        /** Ouvindo com atenção — usa a animação dedicada da folha de emoções. */
        ATENTO("👂", "#00F5D4", 1.01f, 1.00f, XenonSpriteView.Action.LISTENING_ATTENTIVELY),
        /** Raciocinando / executando. */
        FOCADO("🎯", "#8B5CF6", 0.99f, 1.03f, XenonSpriteView.Action.DEEP_THINKING),
        /** Deu certo: comemora e fala um pouco mais leve. */
        SATISFEITO("🎉", "#22C55E", 1.04f, 1.02f, XenonSpriteView.Action.SUCCESS_CONFIRMED),
        /** Primeiro tropeço: ainda tentando, mas já desconfiado da rota. */
        INCERTO("🤔", "#FBBF24", 0.98f, 0.98f, XenonSpriteView.Action.CONFUSED),
        /** Travou de novo / ciclo detectado: precisa mudar de estratégia. */
        FRUSTRADO("😤", "#F97316", 0.94f, 0.96f, XenonSpriteView.Action.ALERT_RECOIL),
        /** Erro duro (LLM caiu, permissão negada, ação impossível). */
        PREOCUPADO("😟", "#EF4444", 0.92f, 0.95f, XenonSpriteView.Action.RECOVERING_ERROR),
        /** Pedindo confirmação de algo sensível. */
        CAUTELOSO("🛡️", "#FF9100", 0.96f, 0.97f, XenonSpriteView.Action.EXPLAIN),
        /** Esperando uma resposta sua que não vem. */
        AGUARDANDO("⌛", "#FFB703", 0.97f, 0.97f, XenonSpriteView.Action.WAITING_IMPATIENT),
        /** Muito tempo ocioso, sem nada para fazer. */
        OCIOSO("🥱", "#64748B", 0.98f, 0.96f, XenonSpriteView.Action.BORED_TAPPING),
    }

    @Volatile var current: Emotion = Emotion.CALMO
        private set
    @Volatile var intensity: Double = 0.0 // 0..1
        private set
    @Volatile var lastReason: String = "—"
        private set
    @Volatile var frustrationStreak: Int = 0
        private set
    @Volatile var successStreak: Int = 0
        private set
    @Volatile private var changedAtMs: Long = System.currentTimeMillis()

    /** Quem desenha o Xenon se registra aqui (BubbleService). */
    @Volatile var renderer: ((Emotion, Double) -> Unit)? = null

    @Synchronized
    fun feel(emotion: Emotion, reason: String, bump: Double = 0.35) {
        when (emotion) {
            Emotion.FRUSTRADO, Emotion.PREOCUPADO -> {
                frustrationStreak++
                successStreak = 0
            }
            Emotion.SATISFEITO -> {
                successStreak++
                frustrationStreak = 0
            }
            else -> {}
        }
        intensity = if (emotion == current) (intensity + bump).coerceAtMost(1.0) else bump
        current = emotion
        lastReason = reason
        changedAtMs = System.currentTimeMillis()
        Log.i(TAG, "${emotion.emoji} $emotion (${String.format(java.util.Locale.US, "%.2f", intensity)}) ← $reason")
        runCatching { renderer?.invoke(emotion, intensity) }
    }

    /**
     * Dica de correção de rota injetada no prompt do próximo passo.
     * É aqui que a emoção vira comportamento: quanto maior a frustração, mais forte a ordem
     * de abandonar a abordagem atual em vez de repetir a mesma tentativa.
     */
    fun routeCorrectionHint(): String = when {
        frustrationStreak >= 3 ->
            "ESTADO EMOCIONAL: 😤 muito frustrado (${frustrationStreak} tropeços seguidos). " +
                "ABANDONE a abordagem atual AGORA. Mude de app, de caminho ou pergunte ao usuário. " +
                "NÃO repita nenhuma ação já tentada nesta sessão."
        frustrationStreak == 2 ->
            "ESTADO EMOCIONAL: 😤 frustrado (2 tropeços). A rota atual provavelmente está errada — " +
                "tente um caminho CLARAMENTE diferente do anterior."
        frustrationStreak == 1 ->
            "ESTADO EMOCIONAL: 🤔 incerto (1 tropeço). Reavalie a tela antes de repetir a mesma ação."
        successStreak >= 2 ->
            "ESTADO EMOCIONAL: 🎯 em fluxo (${successStreak} acertos seguidos). Mantenha a estratégia."
        else -> ""
    }

    /** Pitch/velocidade aplicados ao TTS por cima do perfil de voz escolhido. */
    fun prosody(): Pair<Float, Float> {
        val w = intensity.toFloat().coerceIn(0f, 1f)
        val pitch = 1f + (current.pitchMul - 1f) * w
        val rate = 1f + (current.rateMul - 1f) * w
        return pitch to rate
    }

    fun auraColorInt(): Int = runCatching { Color.parseColor(current.auraColor) }
        .getOrDefault(Color.parseColor("#35D7FF"))

    @Synchronized
    fun relax(reason: String = "tarefa encerrada") {
        intensity = (intensity - 0.5).coerceAtLeast(0.0)
        if (intensity <= 0.05) {
            current = Emotion.CALMO
            lastReason = reason
            runCatching { renderer?.invoke(current, intensity) }
        }
    }

    @Synchronized
    fun resetStreaks() {
        frustrationStreak = 0
        successStreak = 0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("emocao", current.name)
        .put("emoji", current.emoji)
        .put("intensidade", Math.round(intensity * 100.0) / 100.0)
        .put("motivo", lastReason)
        .put("frustracao_seguida", frustrationStreak)
        .put("acertos_seguidos", successStreak)
        .put("ha_ms", System.currentTimeMillis() - changedAtMs)
        .put("dica_correcao_rota", routeCorrectionHint())
}
