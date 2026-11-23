package com.xzero.agent

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Ativa o AgentePhone quando você SEGURA O BOTÃO POWER ou desliza do canto inferior (Assistente padrão do Android).
 */
class AgentVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AgentVoiceSession(this)
    }
}

class AgentVoiceSession(context: android.content.Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Inicia a escuta mágica imediatamente na bolha e fecha a janela padrão do sistema
        BubbleService.instance?.let {
            it.activateFromAssistantTrigger()
        } ?: run {
            BubbleService.start(context)
        }
        hide()
    }
}
