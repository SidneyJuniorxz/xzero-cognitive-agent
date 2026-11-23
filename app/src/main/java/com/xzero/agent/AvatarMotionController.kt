package com.xzero.agent

import android.util.DisplayMetrics
import java.util.Locale

/**
 * Controlador modular de interpretação de comandos de movimento e pose do Xenon / ADA.
 * Extraído do [BubbleService] para desacoplar a lógica de deslocamento 2D e poses
 * do ciclo de vida do ForegroundService.
 */
object AvatarMotionController {

    data class Bounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    fun safeBounds(dm: DisplayMetrics, dp: (Int) -> Int): Bounds {
        val minX = dp(8)
        val maxX = (dm.widthPixels - dp(126)).coerceAtLeast(minX)
        val minY = dp(64)
        val maxY = (dm.heightPixels - dp(210)).coerceAtLeast(minY)
        return Bounds(minX, maxX, minY, maxY)
    }

    /**
     * Interpreta pedidos curtos e naturais voltados ao movimento/pose do Xenon:
     * direita, esquerda, cima, baixo, centro, cantos, notebook e muro.
     */
    fun tryHandleCommand(
        raw: String,
        currentX: Int,
        currentY: Int,
        dm: DisplayMetrics,
        dp: (Int) -> Int,
        onMoveTo: (targetX: Int, targetY: Int, speech: String) -> Unit,
        onAction: (action: XenonSpriteView.Action, speech: String, returnToIdle: Boolean) -> Unit,
    ): Boolean {
        val norm = raw.lowercase(Locale("pt", "BR"))
            .replace("á", "a").replace("à", "a").replace("ã", "a")
            .replace("é", "e").replace("ê", "e").replace("í", "i")
            .replace("ó", "o").replace("ô", "o").replace("ú", "u")
            .trim()
        if (norm.length > 140) return false

        val walkToward = listOf(
            "anda pra frente", "anda para frente", "andar para frente",
            "caminha para frente", "caminhe para frente", "vem pra frente"
        ).any { norm.contains(it) }
        val walkAway = listOf(
            "anda de costas", "andar de costas", "caminhe de costas",
            "anda pra tras", "anda para tras", "andar para tras"
        ).any { norm.contains(it) }
        val mentionsMove = walkToward || walkAway || listOf(
            "direit", "esquerd", "sobe", "subir", "cima", "desce", "descer", "baixo",
            "centro", "meio", "canto", "superior", "inferior", "borda", "notebook", "laptop", "muro"
        ).any { norm.contains(it) }
        if (!mentionsMove) return false

        // Evita interceptar comandos de scroll de outro app ("desce a página", "sobe o feed")
        if (listOf("pagina", "feed", "lista", "tela do", "conversa", "youtube", "whatsapp", "instagram").any { norm.contains(it) }) {
            return false
        }

        if (norm.contains("muro")) {
            onAction(XenonSpriteView.Action.WALL_DESCENT, "🧱 Pulando do muro…", true)
            return true
        }

        val b = safeBounds(dm, dp)
        val stepX = dp(86)
        val stepY = dp(110)
        var handled = false
        var dx = 0
        var dy = 0
        if (norm.contains("direit")) { dx += stepX; handled = true }
        if (norm.contains("esquerd")) { dx -= stepX; handled = true }
        if (norm.contains("sobe") || norm.contains("subir") || norm.contains("cima")) { dy -= stepY; handled = true }
        if (norm.contains("desce") || norm.contains("descer") || norm.contains("baixo")) { dy += stepY; handled = true }
        if (walkToward) { dy += stepY; handled = true }
        if (walkAway) { dy -= stepY; handled = true }
        if (norm.contains("centro") || norm.contains("meio")) handled = true

        if (handled) {
            val absolutePosition = listOf("centro", "meio", "canto", "superior", "inferior", "borda").any { norm.contains(it) }
            val targetX = when {
                norm.contains("centro") || norm.contains("meio") -> (b.minX + b.maxX) / 2
                norm.contains("direit") -> b.maxX
                norm.contains("esquerd") -> b.minX
                else -> currentX + dx
            }
            val targetY = when {
                norm.contains("centro") || norm.contains("meio") -> (b.minY + b.maxY) / 2
                norm.contains("cima") || norm.contains("superior") || norm.contains("subir") || norm.contains("sobe") -> b.minY
                norm.contains("baixo") || norm.contains("inferior") || norm.contains("descer") || norm.contains("desce") -> b.maxY
                else -> currentY + dy
            }
            onMoveTo(
                targetX,
                targetY,
                if (absolutePosition) "✨ Indo para a posição escolhida…" else "✨ Movendo suavemente…"
            )
            return true
        }

        if (norm.contains("notebook") || norm.contains("laptop")) {
            onAction(XenonSpriteView.Action.LAPTOP_WORK, "💻 Trabalhando no notebook…", false)
            return true
        }
        return false
    }
}
