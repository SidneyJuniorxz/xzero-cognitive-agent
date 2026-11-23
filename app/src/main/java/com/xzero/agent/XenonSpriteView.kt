package com.xzero.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * Renderizador de animação por spritesheet do Xenon — a forma visível da ADA (2-slot full-body).
 * Folhas V2 de 4 e 8 colunas; todos os quadros padronizados em 192x208 px.
 * Emite callbacks de deslocamento aparente por frame para que o label/balão de fala
 * acompanhe organicamente o movimento do corpo do Xenon.
 */
class XenonSpriteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var spriteSheet: Bitmap? = null
    private var emotionSheet: Bitmap? = null
    private var wallDescentSheet: Bitmap? = null
    private var lifelikeSheet: Bitmap? = null
    private var interactionsSheet: Bitmap? = null
    private var cognitiveSheet: Bitmap? = null
    private var motionSheet: Bitmap? = null
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#5535D7FF")
    }
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val shadowRect = RectF()
    private val handler = Handler(Looper.getMainLooper())

    private var tileW = 192
    private var tileH = 208

    companion object {
        /** Duração do fade-in ao trocar de animação. */
        private const val TRANSITION_MS = 220f
        /** Tempo mínimo que uma animação fica no ar antes de poder ser trocada. */
        private const val MIN_HOLD_MS = 180L
    }

    /**
     * Deslocamento aparente (em dp) da cabeça/tronco em cada ação para sincronizar o label flutuante.
     */
    /** Folhas de sprite: a original (11 linhas), a de emoções (6 linhas) e a de descida do muro (1 linha). */
    enum class Sheet(val rows: Int, val columns: Int = 8) {
        CORE(11), EMOTIONS(6), WALL_DESCENT(1), LIFELIKE(4, 4),
        INTERACTIONS(4, 4), COGNITIVE(4, 4), MOTION(6, 8)
    }

    enum class Action(
        val frames: Array<Pair<Int, Int>>,
        val frameMs: Long,
        val loops: Boolean,
        val headBobDpY: FloatArray,
        val headSwayDpX: FloatArray,
        val sheet: Sheet = Sheet.CORE
    ) {
        IDLE(
            // Xenon V2: Respiracao natural e piscar
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(2,0), Pair(1,0)),
            430L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.LIFELIKE
        ),
        RUN_RIGHT(
            // Xenon V2: Passada lateral direita
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(4,0), Pair(5,0), Pair(6,0), Pair(7,0)),
            105L, true,
            floatArrayOf(-1.1f, -1.7f, -2f, -1.7f, -1.1f, -0.5f, -0.2f, -0.5f),
            floatArrayOf(1.5f, 2.6f, 3f, 2.6f, 1.5f, 0.4f, 0f, 0.4f),
            Sheet.MOTION
        ),
        RUN_LEFT(
            // Xenon V2: Passada lateral esquerda
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(4,1), Pair(5,1), Pair(6,1), Pair(7,1)),
            105L, true,
            floatArrayOf(-1.1f, -1.7f, -2f, -1.7f, -1.1f, -0.5f, -0.2f, -0.5f),
            floatArrayOf(-1.5f, -2.6f, -3f, -2.6f, -1.5f, -0.4f, 0f, -0.4f),
            Sheet.MOTION
        ),
        /** Caminhada frontal: personagem avança em direção ao observador. */
        WALK_FORWARD(
            arrayOf(Pair(0,4), Pair(1,4), Pair(2,4), Pair(3,4), Pair(4,4), Pair(5,4), Pair(6,4), Pair(7,4)),
            145L, true,
            floatArrayOf(0f, -0.3f, -0.8f, -0.3f, 0f, -0.3f, -0.8f, -0.3f),
            floatArrayOf(0f, 0.3f, 0.5f, 0.3f, 0f, -0.3f, -0.5f, -0.3f),
            Sheet.MOTION
        ),
        /** Caminhada de costas: personagem se afasta do observador. */
        WALK_BACKWARD(
            arrayOf(Pair(0,5), Pair(1,5), Pair(2,5), Pair(3,5), Pair(4,5), Pair(5,5), Pair(6,5), Pair(7,5)),
            145L, true,
            floatArrayOf(0f, -0.3f, -0.8f, -0.3f, 0f, -0.3f, -0.8f, -0.3f),
            floatArrayOf(0f, -0.3f, -0.5f, -0.3f, 0f, 0.3f, 0.5f, 0.3f),
            Sheet.MOTION
        ),
        WAVE(
            // Xenon V2: Acenar com antecipacao e retorno
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0)),
            230L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.INTERACTIONS
        ),
        JUMP(
            // Xenon V2: Salto/descoberta com retorno a base
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2), Pair(4,2), Pair(5,2), Pair(6,2), Pair(7,2)),
            175L, false,
            floatArrayOf(0f, -2f, -4f, -6f, -6f, -3f, -1f, 0f),
            floatArrayOf(0f, 0.4f, 0.5f, 0.4f, 0f, -0.4f, -0.5f, -0.4f),
            Sheet.MOTION
        ),
        THINK(
            // Xenon V2: Mao no queixo
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(2,1), Pair(1,1)),
            365L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.LIFELIKE
        ),
        EXPLAIN(
            // Xenon V2: Gestos abertos ao falar
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(2,1), Pair(1,1)),
            280L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.INTERACTIONS
        ),
        POINT(
            // Xenon V2: Indicar objeto ou opcao
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2)),
            230L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.INTERACTIONS
        ),
        HOLOGRAM(
            // Xenon V2: Orbe holografico interativo
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1)),
            285L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        BLINK_LOOK(
            // Xenon V2: Atencao e olhar lateral
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3), Pair(2,3), Pair(1,3)),
            340L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.INTERACTIONS
        ),
        CELEBRATE(
            // Xenon V2: Comemorar apenas evento concluido
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            240L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.LIFELIKE
        ),
        ALERT_RECOIL(
            // Xenon V2: Falha com alerta vermelho
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2)),
            340L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),

        // ---- Folha de EMOÇÕES (branch feat/xenon-emotions) ----
        WAITING_IMPATIENT(
            // Xenon V2: Bater o pe
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2), Pair(2,2), Pair(1,2)),
            290L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.LIFELIKE
        ),
        SUCCESS_HAPPY(
            // Xenon V2: Comemorar apenas evento concluido
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            240L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.LIFELIKE
        ),
        ERROR_SAD(
            // Xenon V2: Falha com alerta vermelho
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2)),
            340L, false,
            floatArrayOf(0f, 1f, 2f, 1f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        CONFUSED(
            // Xenon V2: Duvida ou curiosidade
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            340L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        ATTENDING(
            // Xenon V2: Painel holografico em uso
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(2,0), Pair(1,0)),
            250L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.COGNITIVE
        ),
        LAPTOP_WORK(
            // Xenon V2: Painel holografico em uso
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(2,0), Pair(1,0)),
            250L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.COGNITIVE
        ),
        BORED_TAPPING(
            // Xenon V2: Bater o pe
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2), Pair(2,2), Pair(1,2)),
            290L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.LIFELIKE
        ),
        WALL_DESCENT(
            // Xenon V2: Prototipo visual de descida com proporcoes preservadas - requer revisao da pose sentada
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3), Pair(4,3), Pair(5,3), Pair(6,3), Pair(7,3)),
            260L, false,
            floatArrayOf(0f, -1f, -2f, -1f, 0f, 3f, 2f, 0f),
            floatArrayOf(0f, 1f, 1f, 0f, -1f, 1f, 0f, 0f),
            Sheet.MOTION
        ),

        // Estados funcionais. As sequências reutilizam somente quadros existentes: manter
        // a grade 8x11 / 8x6 é o contrato que também permite ao site exibir o mesmo Xenon.
        LISTENING_ATTENTIVELY(
            // Xenon V2: Atencao e olhar lateral
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3), Pair(2,3), Pair(1,3)),
            340L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.INTERACTIONS
        ),
        DEEP_THINKING(
            // Xenon V2: Mao no queixo
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(2,1), Pair(1,1)),
            365L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.LIFELIKE
        ),
        RESEARCHING_WEB(
            // Xenon V2: Painel holografico em uso
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(2,0), Pair(1,0)),
            250L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.COGNITIVE
        ),
        EXECUTING_TASK(
            // Xenon V2: Painel holografico em uso
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0), Pair(2,0), Pair(1,0)),
            250L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.COGNITIVE
        ),
        ASKING_HELP(
            // Xenon V2: Gestos abertos ao falar
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(2,1), Pair(1,1)),
            280L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.INTERACTIONS
        ),
        RECOVERING_ERROR(
            // Xenon V2: Falha com alerta vermelho
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2)),
            340L, false,
            floatArrayOf(0f, 1f, 2f, 1f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        SUCCESS_CONFIRMED(
            // Xenon V2: Comemorar apenas evento concluido
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            240L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.LIFELIKE
        ),
        LEARNING_NEW(
            // Xenon V2: Observa informacao assimilada
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1), Pair(2,1), Pair(1,1)),
            320L, true,
            floatArrayOf(0f, -0.2f, -0.7f, -0.9f, -0.7f, -0.2f),
            floatArrayOf(0f, 0.4f, 0.4f, 0f, -0.4f, -0.4f),
            Sheet.COGNITIVE
        ),

        // Ações espontâneas: só são escolhidas pelo loop ocioso do BubbleService.
        LOOKING_AROUND(
            // Xenon V2: Olhar ao redor breve
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            340L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.INTERACTIONS
        ),
        RESTING(
            // Xenon V2: Pausa sutil
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0)),
            460L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.LIFELIKE
        ),
        PLAYING_HOLOGRAM(
            // Xenon V2: Orbe holografico interativo
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1)),
            285L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        FOOT_TAPPING(
            // Xenon V2: Bater o pe curta duracao
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2)),
            290L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.LIFELIKE
        ),
        ASKING_QUESTION(
            // Xenon V2: Perguntar explicitamente
            arrayOf(Pair(0,1), Pair(1,1), Pair(2,1), Pair(3,1)),
            280L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.INTERACTIONS
        ),
        CURIOUS(
            // Xenon V2: Duvida ou curiosidade
            arrayOf(Pair(0,3), Pair(1,3), Pair(2,3), Pair(3,3)),
            340L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.COGNITIVE
        ),
        WAVING_SPONTANEOUSLY(
            // Xenon V2: Acenar com antecipacao e retorno
            arrayOf(Pair(0,0), Pair(1,0), Pair(2,0), Pair(3,0)),
            230L, false,
            floatArrayOf(0f, -0.4f, -0.9f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f, -0.5f),
            Sheet.INTERACTIONS
        ),
        DISCOVERY_REACTION(
            // Xenon V2: Salto/descoberta com retorno a base
            arrayOf(Pair(0,2), Pair(1,2), Pair(2,2), Pair(3,2), Pair(4,2), Pair(5,2), Pair(6,2), Pair(7,2)),
            175L, false,
            floatArrayOf(0f, -2f, -4f, -6f, -6f, -3f, -1f, 0f),
            floatArrayOf(0f, 0.4f, 0.5f, 0.4f, 0f, -0.4f, -0.5f, -0.4f),
            Sheet.MOTION
        )
    }

    var onApparentMotionListener: ((offsetXdp: Float, offsetYdp: Float, action: Action, frameIdx: Int) -> Unit)? = null
    private var glowColor: Int = Color.parseColor("#35D7FF")
    private var currentAction: Action = Action.IDLE
    private var currentFrameIndex = 0
    private var onFinishedCallback: (() -> Unit)? = null
    private var actionStartedAtMs: Long = System.currentTimeMillis()
    private var queuedAction: Action? = null
    private var queuedCallback: (() -> Unit)? = null
    // Quadro anterior para crossfade real, sem deixar o personagem semitransparente.
    private var previousAction: Action? = null
    private var previousFrameIndex: Int = 0

    private val frameRunnable = object : Runnable {
        override fun run() {
            val frames = currentAction.frames
            if (frames.isEmpty()) return

            currentFrameIndex++
            if (currentFrameIndex >= frames.size) {
                if (currentAction.loops) {
                    currentFrameIndex = 0
                } else {
                    currentFrameIndex = frames.size - 1
                    val cb = onFinishedCallback
                    onFinishedCallback = null
                    // Consome a animação enfileirada durante esta one-shot (transição natural)
                    val queued = queuedAction
                    val queuedCb = queuedCallback
                    queuedAction = null
                    queuedCallback = null
                    when {
                        queued != null -> setAction(queued, force = true, onFinished = queuedCb)
                        cb != null -> cb.invoke()
                        else -> setAction(Action.IDLE, force = true)
                    }
                    return
                }
            }
            notifyApparentMotion()
            invalidate()
            handler.postDelayed(this, currentAction.frameMs)
        }
    }

    init {
        decodeSheetUnscaled()
    }

    private fun decodeSheetUnscaled() {
        try {
            val opts = BitmapFactory.Options().apply {
                inScaled = false // CRÍTICO: impede distorção por densidade de tela (mdpi vs xxhdpi)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.xenon_spritesheet, opts)
            if (bmp != null) {
                spriteSheet = bmp
                tileW = bmp.width / 8
                tileH = bmp.height / Sheet.CORE.rows
                emotionSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_emotions_spritesheet, opts)
                }.getOrNull()
                wallDescentSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_wall_descent_spritesheet, opts)
                }.getOrNull()
                lifelikeSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_lifelike_spritesheet, opts)
                }.getOrNull()
                interactionsSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_interactions_spritesheet, opts)
                }.getOrNull()
                cognitiveSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_cognitive_spritesheet, opts)
                }.getOrNull()
                motionSheet = runCatching {
                    BitmapFactory.decodeResource(resources, R.drawable.xenon_motion_spritesheet, opts)
                }.getOrNull()
                android.util.Log.i("AgentePhone", "Xenon Lifelike: ${lifelikeSheet?.width ?: 0}x${lifelikeSheet?.height ?: 0}")
                android.util.Log.i(
                    "AgentePhone",
                    "XenonSprite folha de emoções: ${emotionSheet?.width ?: 0}x${emotionSheet?.height ?: 0}" +
                        if (emotionSheet == null) " (FALHOU ao carregar!)" else " (tile=${(emotionSheet!!.width / 8)}x${(emotionSheet!!.height / Sheet.EMOTIONS.rows)})",
                )
                android.util.Log.i("AgentePhone", "XenonSprite carregado: ${bmp.width}x${bmp.height} (tile=${tileW}x${tileH})")
            }
        } catch (e: Throwable) {
            android.util.Log.e("AgentePhone", "Erro ao carregar spritesheet: ${e.message}")
        }
    }

    fun setAuraColor(color: Int) {
        glowColor = color
        auraPaint.color = color
        invalidate()
    }

    fun getCurrentAction(): Action = currentAction

    fun enterThinking() {
        if (currentAction == Action.DEEP_THINKING || currentAction == Action.THINK) return
        setAction(Action.DEEP_THINKING, force = true)
    }

    fun exitThinking(nextAction: Action) {
        setAction(nextAction, force = true)
    }

    /**
     * Troca a animação com TRANSIÇÃO NATURAL.
     *
     * Antes o corte era seco: a pose trocava no meio do movimento e o corpo "piscava",
     * ainda mais ao pular entre as duas folhas de sprite. Agora:
     *  1. uma animação que NÃO repete (comemorar, errar) termina seu ciclo antes de ceder
     *     a vez — a próxima fica enfileirada em vez de abortar a atual;
     *  2. a entrada da nova animação tem fade-in com easeOutCubic (ver onDraw);
     *  3. trocas muito rápidas (< 120 ms) são ignoradas para não tremer com micro-eventos.
     *
     * `force = true` ignora a fila (usado por barge-in, erro grave e reset para IDLE).
     */
    fun setAction(action: Action, force: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (currentAction == action && action.loops && onFinished == null) return

        val now = System.currentTimeMillis()
        if (!force) {
            // Deixa a animação one-shot em curso terminar antes de trocar
            val currentIsOneShotRunning = !currentAction.loops &&
                currentFrameIndex < currentAction.frames.size - 1
            if (currentIsOneShotRunning) {
                queuedAction = action
                queuedCallback = onFinished
                return
            }
            // Anti-tremor: não troca de novo em menos de 120 ms
            if (now - actionStartedAtMs < MIN_HOLD_MS && currentAction != Action.IDLE) {
                queuedAction = action
                queuedCallback = onFinished
                return
            }
        }
        queuedAction = null
        queuedCallback = null
        // Somente transiciona quando o estado realmente muda.
        if (currentAction != action) {
            previousAction = currentAction
            previousFrameIndex = currentFrameIndex
        } else {
            previousAction = null
        }
        handler.removeCallbacks(frameRunnable)
        currentAction = action
        currentFrameIndex = 0
        actionStartedAtMs = now
        onFinishedCallback = onFinished
        notifyApparentMotion()
        invalidate()
        handler.postDelayed(frameRunnable, action.frameMs)
    }

    private fun notifyApparentMotion() {
        val bobArr = currentAction.headBobDpY
        val swayArr = currentAction.headSwayDpX
        val dy = if (bobArr.isNotEmpty()) bobArr[currentFrameIndex % bobArr.size] else 0f
        val dx = if (swayArr.isNotEmpty()) swayArr[currentFrameIndex % swayArr.size] else 0f
        onApparentMotionListener?.invoke(dx, dy, currentAction, currentFrameIndex)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (spriteSheet == null) decodeSheetUnscaled()
        handler.removeCallbacks(frameRunnable)
        handler.post(frameRunnable)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(frameRunnable)
        super.onDetachedFromWindow()
    }

    /** Resolve folhas novas e mantém as originais como fallback seguro. */
    private fun bitmapFor(sheet: Sheet): Bitmap? = when (sheet) {
        Sheet.CORE -> spriteSheet
        Sheet.EMOTIONS -> emotionSheet
        Sheet.WALL_DESCENT -> wallDescentSheet
        Sheet.LIFELIKE -> lifelikeSheet
        Sheet.INTERACTIONS -> interactionsSheet
        Sheet.COGNITIVE -> cognitiveSheet
        Sheet.MOTION -> motionSheet
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val decodedSheet = bitmapFor(currentAction.sheet)
        val sheet = decodedSheet ?: spriteSheet ?: return
        val activeSheet = if (decodedSheet == null) Sheet.CORE else currentAction.sheet
        val frames = currentAction.frames
        if (frames.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        // Desenha base energética neon suave sob os pés do Xenon com respiração orgânica
        val pulse = 0.85f + 0.15f * kotlin.math.sin(now * 0.0028).toFloat()
        val cx = w * 0.5f
        val cy = h * 0.91f
        val rx = w * 0.34f * (0.96f + 0.04f * pulse)
        val ry = h * 0.065f
        shadowRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        shadowPaint.shader = RadialGradient(
            cx, cy, rx.coerceAtLeast(1f),
            intArrayOf(
                Color.argb((145 * pulse).toInt().coerceIn(0, 255), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.argb((42 * pulse).toInt().coerceIn(0, 255), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(shadowRect, shadowPaint)

        // Brilho contextual sutil na altura do notebook/mente quando operando notebook ou pensando
        if (currentAction == Action.LAPTOP_WORK || currentAction == Action.ATTENDING) {
            val lcx = w * 0.60f
            val lcy = h * 0.44f
            val lrx = w * 0.24f * pulse
            shadowRect.set(lcx - lrx, lcy - lrx * 0.7f, lcx + lrx, lcy + lrx * 0.7f)
            shadowPaint.shader = RadialGradient(
                lcx, lcy, lrx.coerceAtLeast(1f),
                intArrayOf(Color.argb(65, 53, 215, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(shadowRect, shadowPaint)
            postInvalidateDelayed(60L)
        } else if (currentAction == Action.THINK || currentAction == Action.ERROR_SAD) {
            postInvalidateDelayed(80L)
        }

        val (col, row) = if (decodedSheet == null) Pair(0, 0)
            else frames[currentFrameIndex % frames.size]
        // Cada folha tem sua própria geometria (CORE 11 linhas, EMOTIONS 6 linhas)
        val activeTileW = sheet.width / activeSheet.columns
        val activeTileH = sheet.height / activeSheet.rows
        val srcX = col * activeTileW
        val srcY = row * activeTileH

        srcRect.set(srcX, srcY, srcX + activeTileW, srcY + activeTileH)

        // Geometria fixa: toda folha V2 usa 192 × 208 por quadro.
        // Ajuste proporcional evita esticar ou reduzir aparentemente o personagem.
        val maxWidth = w * 0.96f
        val maxHeight = h * 0.95f
        val scale = minOf(maxWidth / 192f, maxHeight / 208f)
        val drawW = 192f * scale
        val drawH = 208f * scale
        val left = (w - drawW) * 0.5f
        val top = (h - drawH) * 0.5f
        dstRect.set(left.toInt(), top.toInt(), (left + drawW).toInt(), (top + drawH).toInt())

        // Crossfade real entre quadros: soma das opacidades é sempre 100%.
        // Evita o fantasma semitransparente criado pelo antigo fade-in sobre vazio.
        val from = previousAction
        val elapsed = (now - actionStartedAtMs).coerceAtLeast(0L)
        val fade = (elapsed / TRANSITION_MS).coerceIn(0f, 1f)
        val eased = fade * fade * (3f - 2f * fade) // smoothstep
        if (from != null && fade < 1f) {
            val prevSheet = bitmapFor(from.sheet)
            if (prevSheet != null && from.frames.isNotEmpty()) {
                val (pc, pr) = from.frames[previousFrameIndex % from.frames.size]
                val pw = prevSheet.width / from.sheet.columns
                val ph = prevSheet.height / from.sheet.rows
                val previousSrc = Rect(pc * pw, pr * ph, (pc + 1) * pw, (pr + 1) * ph)
                spritePaint.alpha = (255f * (1f - eased)).toInt().coerceIn(0, 255)
                canvas.drawBitmap(prevSheet, previousSrc, dstRect, spritePaint)
            }
            postInvalidateDelayed(24L)
        } else if (from != null) {
            previousAction = null
        }
        spritePaint.alpha = if (from == null || bitmapFor(from.sheet) == null) 255
            else (255f * eased).toInt().coerceIn(0, 255)
        canvas.drawBitmap(sheet, srcRect, dstRect, spritePaint)
        spritePaint.alpha = 255
    }
}
