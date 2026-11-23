package com.xzero.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Roteador determinístico local (< 50 ms, zero chamadas de LLM) para perguntas
 * cotidianas de resposta imediata (hora, data, bateria, estado da FSM).
 *
 * Mantém a resposta curta, natural e em português brasileiro (estilo Jarvis/ADA).
 */
object FastPathRouter {

    fun tryAnswer(ctx: Context, rawTask: String): String? {
        val clean = rawTask
            .substringBefore("[Contexto visual")
            .substringBefore("[CONTEXTO CURTO")
            .trim()
        if (clean.isBlank() || clean.length > 70) return null

        val norm = clean.lowercase(Locale("pt", "BR"))
            .replace("á", "a").replace("à", "a").replace("ã", "a").replace("â", "a")
            .replace("é", "e").replace("ê", "e")
            .replace("í", "i")
            .replace("ó", "o").replace("ô", "o").replace("õ", "o")
            .replace("ú", "u")
            .replace("ç", "c")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 1. Hora atual
        if (norm in setOf("que horas sao", "que hora e", "me diz as horas", "qual a hora", "horas", "hora atual") ||
            norm.startsWith("que horas sao")
        ) {
            val fmt = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
            return "Agora são ${fmt.format(Date())}."
        }

        // 2. Data / dia da semana
        if (norm in setOf("que dia e hoje", "qual a data de hoje", "qual a data", "que dia da semana e hoje", "data de hoje")) {
            val fmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
            val txt = fmt.format(Date()).replaceFirstChar { it.uppercase() }
            return "Hoje é $txt."
        }

        // 3. Bateria rápida
        if (norm in setOf("quanto esta a bateria", "como esta a bateria", "nivel da bateria", "bateria", "quantos por cento de bateria") ||
            (norm.contains("bateria") && norm.split(" ").size <= 5 && !norm.contains("economi"))
        ) {
            val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                return if (charging) "A bateria está em $pct%, carregando." else "A bateria está em $pct%."
            }
        }

        // 4. Estado atual da mente / FSM
        if (norm in setOf("qual seu estado", "como voce esta agora", "status do sistema", "como esta o xzero", "como esta a ada")) {
            val snap = AdaStateMachine.current
            return "Estou operando normalmente no estado ${snap.state.labelPt}, pronto para te ajudar."
        }

        return null
    }
}
