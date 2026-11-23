package com.xzero.agent

import android.content.Context
import android.content.SharedPreferences

/** Configuração persistida (chaves, modelos, ordem dos provedores, wake word). */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("agente", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = p.getString("gemini_key", "") ?: ""
        set(v) = p.edit().putString("gemini_key", v.trim()).apply()
    var geminiModel: String
        get() = p.getString("gemini_model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
        set(v) = p.edit().putString("gemini_model", v.trim()).apply()

    var claudeKey: String
        get() = p.getString("claude_key", "") ?: ""
        set(v) = p.edit().putString("claude_key", v.trim()).apply()
    var claudeModel: String
        get() = p.getString("claude_model", "claude-3-5-haiku-latest") ?: "claude-3-5-haiku-latest"
        set(v) = p.edit().putString("claude_model", v.trim()).apply()

    var openaiKey: String
        get() = p.getString("openai_key", "") ?: ""
        set(v) = p.edit().putString("openai_key", v.trim()).apply()
    var openaiModel: String
        get() = p.getString("openai_model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = p.edit().putString("openai_model", v.trim()).apply()

    /** "gemini", "claude" ou "openai": qual tentar primeiro. */
    var primary: String
        get() = p.getString("primary", "gemini") ?: "gemini"
        set(v) = p.edit().putString("primary", v.trim()).apply()

    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    /** Perfil de voz TTS: "jarvis" (grave/preciso), "natural" ou "fast". */
    var voiceProfile: String
        get() = p.getString("voice_profile", "jarvis") ?: "jarvis"
        set(v) = p.edit().putString("voice_profile", v).apply()

    /** Motor de voz offline neural: "system" (Google TTS) | "faber" | "cadu". */
    var neuralVoice: String
        get() = p.getString("neural_voice", "system") ?: "system"
        set(v) = p.edit().putString("neural_voice", v).apply()

    /**
     * Voz TTS fixada manualmente pelo nome (ex: "pt-br-x-ptd-local").
     * Vazio = deixa o ranking do perfil escolher. Útil porque `ptd` e `pte` empatam
     * no score e só o ouvido decide qual soa mais Jarvis.
     */
    var pinnedVoiceName: String
        get() = p.getString("pinned_voice_name", "") ?: ""
        set(v) = p.edit().putString("pinned_voice_name", v.trim()).apply()

    /**
     * Palavra-chave falada para destravar a tela quando a conversa COMEÇA bloqueada.
     * Se a tela bloquear no meio de uma conversa já em andamento, o [UnlockGate] libera
     * sem pedir nada.
     */
    var unlockPassphrase: String
        get() = p.getString("unlock_passphrase", "powerfly") ?: "powerfly"
        set(v) = p.edit().putString("unlock_passphrase", v.trim()).apply()

    /** Servidor HTTP/SSE de monitoramento (:3030) sob demanda para economizar bateria no dia a dia. */
    var monitoringServerEnabled: Boolean
        get() = p.getBoolean("monitoring_server_enabled", false)
        set(v) = p.edit().putBoolean("monitoring_server_enabled", v).apply()

    /**
     * Iniciativa própria: deixa a ADA trabalhar no tempo ocioso (consolidar aprendizado,
     * escrever o diário local e enfileirar sugestões). Só Tier 0 e 1 — nada com efeito
     * externo acontece sem o usuário mandar.
     */
    var iniciativaAtiva: Boolean
        get() = p.getBoolean("iniciativa_ativa", true)
        set(v) = p.edit().putBoolean("iniciativa_ativa", v).apply()

    /** Ativa/desativa confirmação semântica por embeddings (VoiceSemantics). */
    var semanticConfirmEnabled: Boolean
        get() = p.getBoolean("semantic_confirm_enabled", true)
        set(v) = p.edit().putBoolean("semantic_confirm_enabled", v).apply()

    var subtleSound: Boolean
        get() = p.getBoolean("subtle_sound", true)
        set(v) = p.edit().putBoolean("subtle_sound", v).apply()

    var wakeWord: Boolean
        get() = p.getBoolean("wake_word", true)
        set(v) = p.edit().putBoolean("wake_word", v).apply()

    /**
     * Ajuste aprendido do limiar do VAD, em dB (negativo = mais sensível, positivo = mais exigente).
     * Calibrado automaticamente pelo [VadAutoTuner] conforme o X0 acerta ou erra ao acordar.
     */
    var vadSnrOffsetDb: Double
        get() = p.getFloat("vad_snr_offset_db", 0f).toDouble()
        set(v) = p.edit().putFloat("vad_snr_offset_db", v.toFloat().coerceIn(-6f, 14f)).apply()

    /**
     * Quando ligado, o X0 só acorda se a assinatura de voz bater com o perfil do usuário
     * (exige perfil maduro, >=12 amostras). Desligado por padrão: falso-negativo aqui
     * deixaria o X0 surdo, que é pior do que acordar à toa.
     */
    var voicePrintGate: Boolean
        get() = p.getBoolean("voice_print_gate", false)
        set(v) = p.edit().putBoolean("voice_print_gate", v).apply()

    /** Pontuação acumulada do despertar: +acertos (virou ação), -acionamentos desnecessários. */
    var vadScore: Int
        get() = p.getInt("vad_score", 0)
        set(v) = p.edit().putInt("vad_score", v.coerceIn(-9999, 9999)).apply()

    var vadProductiveWakes: Int
        get() = p.getInt("vad_productive_wakes", 0)
        set(v) = p.edit().putInt("vad_productive_wakes", v).apply()

    var vadWastedWakes: Int
        get() = p.getInt("vad_wasted_wakes", 0)
        set(v) = p.edit().putInt("vad_wasted_wakes", v).apply()

    var autoIntentThreshold: Double
        get() = p.getFloat("auto_intent_threshold", 4.0f).toDouble()
        set(v) = p.edit().putFloat("auto_intent_threshold", v.toFloat().coerceIn(2.0f, 8.5f)).apply()

    var preExecVoiceConfirm: Boolean
        get() = p.getBoolean("pre_exec_voice_confirm", true)
        set(v) = p.edit().putBoolean("pre_exec_voice_confirm", v).apply()

    var sensorMode: String
        get() = p.getString("sensor_mode", "normal") ?: "normal"
        set(v) = p.edit().putString("sensor_mode", v.trim().lowercase()).apply()

    // Persistência de saúde (health monitoring)
    var lastAccessibilityDisableAtMs: Long
        get() = p.getLong("health_last_a11y_disable_ms", 0L)
        set(v) = p.edit().putLong("health_last_a11y_disable_ms", v).apply()

    var accessibilityDisableCount5Min: Int
        get() = p.getInt("health_a11y_disable_count_5min", 0)
        set(v) = p.edit().putInt("health_a11y_disable_count_5min", v).apply()

    var healAttemptCount: Int
        get() = p.getInt("health_heal_attempt_count", 0)
        set(v) = p.edit().putInt("health_heal_attempt_count", v).apply()

    var healthIssuesSummary: String
        get() = p.getString("health_issues_summary", "") ?: ""
        set(v) = p.edit().putString("health_issues_summary", v.take(500)).apply()

    var lastHealTimestampMs: Long
        get() = p.getLong("health_last_heal_ms", 0L)
        set(v) = p.edit().putLong("health_last_heal_ms", v).apply()

    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject()
            .put("primary", primary)
            .put("gemini_model", geminiModel)
            .put("has_gemini_key", geminiKey.isNotBlank())
            .put("claude_model", claudeModel)
            .put("has_claude_key", claudeKey.isNotBlank())
            .put("openai_model", openaiModel)
            .put("has_openai_key", openaiKey.isNotBlank())
            .put("wake_word", wakeWord)
            .put("speak", speak)
            .put("subtle_sound", subtleSound)
            .put("auto_intent_threshold", autoIntentThreshold)
            .put("pre_exec_voice_confirm", preExecVoiceConfirm)
            .put("sensor_mode", sensorMode)
    }
}
