package com.xzero.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Fonte única de verdade (FSM) para o estado de situação do XZero / ADA.
 *
 * Elimina a divergência entre flags booleanas soltas (borda, microfone, TTS,
 * animações do Xenon e fases do Agent): cada subsistema observa [state] e reage
 * de forma determinística.
 *
 * Invariante visual combinada com o usuário:
 *  - As bordas iluminadas ([Snapshot.shouldShowGlowBorder]) acendem EXCLUSIVAMENTE
 *    em [AdaState.LISTENING_USER], sinalizando a hora exata para falar.
 */
object AdaStateMachine {

    private const val TAG = "AdaFSM"
    private const val MAX_HISTORY = 30

    enum class AdaState(val labelPt: String) {
        DORMANT("Inativo"),
        STANDBY_VAD("Aguardando voz (VAD)"),
        LISTENING_USER("Ouvindo você"),
        UNDERSTANDING("Interpretando fala"),
        PLANNING_GRAPH("Planejando no grafo"),
        AWAITING_CONFIRM("Aguardando confirmação"),
        EXECUTING_STEP("Executando ação"),
        VERIFYING("Verificando resultado"),
        RECOVERING("Recuperando rota"),
        SPEAKING_REPLY("Falando resposta"),
        FOLLOWUP_WINDOW("Janela Live (60s)")
    }

    data class TransitionRecord(
        val from: AdaState,
        val to: AdaState,
        val reason: String,
        val atMs: Long
    )

    data class Snapshot(
        val state: AdaState = AdaState.DORMANT,
        val reason: String = "inicial",
        val detail: String = "",
        val updatedAtMs: Long = System.currentTimeMillis()
    ) {
        /** Regra de ouro: borda neon ativa apenas quando é a hora exata de o usuário falar. */
        val shouldShowGlowBorder: Boolean
            get() = state == AdaState.LISTENING_USER

        val isBusy: Boolean
            get() = state == AdaState.UNDERSTANDING ||
                state == AdaState.PLANNING_GRAPH ||
                state == AdaState.EXECUTING_STEP ||
                state == AdaState.VERIFYING ||
                state == AdaState.RECOVERING
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val history = ArrayDeque<TransitionRecord>(MAX_HISTORY + 1)

    val current: Snapshot
        get() = _state.value

    @Synchronized
    fun transition(to: AdaState, reason: String = "", detail: String = "") {
        val prev = _state.value
        if (prev.state == to && prev.detail == detail && reason.isBlank()) return
        val now = System.currentTimeMillis()
        if (prev.state != to) {
            if (history.size >= MAX_HISTORY) history.removeFirst()
            history.addLast(TransitionRecord(prev.state, to, reason.ifBlank { detail }, now))
            Log.i(TAG, "🧭 [FSM] ${prev.state.name} -> ${to.name}${if (reason.isNotBlank()) " ($reason)" else ""}")
        }
        _state.value = Snapshot(
            state = to,
            reason = reason.ifBlank { to.labelPt },
            detail = detail,
            updatedAtMs = now
        )
    }

    @Synchronized
    fun toJson(): JSONObject {
        val snap = _state.value
        val arr = JSONArray()
        for (h in history) {
            arr.put(JSONObject().apply {
                put("from", h.from.name)
                put("to", h.to.name)
                put("reason", h.reason)
                put("at_ms", h.atMs)
            })
        }
        return JSONObject().apply {
            put("state", snap.state.name)
            put("label", snap.state.labelPt)
            put("reason", snap.reason)
            put("detail", snap.detail)
            put("glow_border_active", snap.shouldShowGlowBorder)
            put("updated_at_ms", snap.updatedAtMs)
            put("recent_transitions", arr)
        }
    }
}
