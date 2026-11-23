package com.xzero.agent

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Observa mudanças em Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES em tempo real.
 * Quando o AccessibilityService é desabilitado, registra diagnosticamente e inicia heal.
 */
class AccessibilityServiceObserver(
    private val ctx: Context,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean, uri: android.net.Uri?, flags: Int) {
        super.onChange(selfChange, uri, flags)
        checkAccessibilityStatus()
    }

    private fun checkAccessibilityStatus() {
        val pkg = ctx.packageName
        val comp = "$pkg/$pkg.AgentAccessibilityService"
        val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val isEnabled = cur.contains(comp)

        if (!isEnabled && AgentAccessibilityService.instance != null) {
            Log.e("AgentePhone", "⚠️ ALERTA: AccessibilityService foi desabilitado pelo sistema! Registrando...")
            HealthMonitor.recordAccessibilityDisable(ctx, "system_disable_detected_via_observer")

            // Tenta heal se estratégia de backoff permitir
            if (HealthMonitor.shouldAttemptHeal()) {
                Log.i("AgentePhone", "🔧 Tentando heal imediatamente via ContentObserver...")
                HealthMonitor.checkAndHeal(ctx, heal = true)
            } else {
                Log.w("AgentePhone", "⏳ Backoff ativo, próxima tentativa de heal permitida em ${HealthMonitor.getTimeUntilNextHealMs()}ms")
            }
        }
    }

    companion object {
        fun register(ctx: Context): AccessibilityServiceObserver {
            val observer = AccessibilityServiceObserver(ctx)
            ctx.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            Log.i("AgentePhone", "✅ AccessibilityServiceObserver registrado")
            return observer
        }

        fun unregister(ctx: Context, observer: AccessibilityServiceObserver) {
            try {
                ctx.contentResolver.unregisterContentObserver(observer)
                Log.i("AgentePhone", "✅ AccessibilityServiceObserver desregistrado")
            } catch (e: Exception) {
                Log.w("AgentePhone", "Erro ao desregistrar observer: ${e.message}")
            }
        }
    }
}
