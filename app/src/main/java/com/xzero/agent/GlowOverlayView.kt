package com.xzero.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Overlay de tela cheia que renderiza bordas pulsantes e luminosas com gradiente animado
 * (estilo Gemini / Siri / ficção científica / fantasia) ao redor de toda a tela enquanto o agente ouve ou pensa.
 *
 * É totalmente NÃO-FOCÁVEL e NÃO-TOCÁVEL (FLAG_NOT_TOUCHABLE), permitindo que você continue
 * tocando e usando a tela normalmente por baixo do brilho.
 */
class GlowOverlayView(context: Context) : View(context) {

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cornerRadius = 36f * resources.displayMetrics.density
    private val strokeWidthDp = 7.5f * resources.displayMetrics.density
    private val haloWidthDp = 16f * resources.displayMetrics.density

    private var phase = 0f
    private var pulseAlpha = 0.90f
    private var animator: ValueAnimator? = null

    // Paleta roxa/violeta/magenta vibrante para sinalizar claramente quando a ADA está ouvindo
    private val neonColors = intArrayOf(
        Color.parseColor("#9D4EDD"), // Roxo vivo
        Color.parseColor("#7B2CBF"), // Violeta profundo
        Color.parseColor("#C77DFF"), // Lilás neon luminoso
        Color.parseColor("#E040FB"), // Magenta elétrico
        Color.parseColor("#5A189A"), // Roxo cósmico
        Color.parseColor("#9D4EDD")  // Roxo vivo
    )

    init {
        borderPaint.strokeWidth = strokeWidthDp
        haloPaint.strokeWidth = haloWidthDp
        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                phase = fraction
                // Mantém sempre visível (0.68 a 1.0) enquanto estiver ouvindo, sem nunca sumir no meio do ciclo!
                pulseAlpha = 0.84f + 0.16f * kotlin.math.sin(fraction * 2 * Math.PI).toFloat()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Gradiente linear com ângulo que rotaciona suavemente pela fase
        val angle = (phase * 2 * Math.PI)
        val x0 = (w / 2) + (w / 2) * kotlin.math.cos(angle).toFloat()
        val y0 = (h / 2) + (h / 2) * kotlin.math.sin(angle).toFloat()
        val x1 = (w / 2) - (w / 2) * kotlin.math.cos(angle).toFloat()
        val y1 = (h / 2) - (h / 2) * kotlin.math.sin(angle).toFloat()

        val shader = LinearGradient(
            x0, y0, x1, y1,
            neonColors,
            null,
            Shader.TileMode.CLAMP
        )
        haloPaint.shader = shader
        haloPaint.alpha = (pulseAlpha * 110).toInt().coerceIn(40, 140)
        val halfHalo = haloWidthDp / 2f
        val haloRect = RectF(halfHalo, halfHalo, w - halfHalo, h - halfHalo)
        canvas.drawRoundRect(haloRect, cornerRadius, cornerRadius, haloPaint)

        borderPaint.shader = shader
        borderPaint.alpha = (pulseAlpha * 255).toInt().coerceIn(170, 255)

        val halfStroke = strokeWidthDp / 2f
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    companion object {
        fun makeLayoutParams(): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
            }
        }
    }
}
