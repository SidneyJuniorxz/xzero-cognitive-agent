package com.xzero.agent

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Dá ao agente acesso às notificações ativas e permite responder mensagens (ex.: WhatsApp) direto delas. */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifListener? = null
    }

    private var cached: List<StatusBarNotification> = emptyList()

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        if (sbn.packageName == packageName) return
        val e = sbn.notification.extras
        val title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        if (title.isNotBlank() || text.isNotBlank()) {
            ProactiveWatcher.onNewNotification(sbn.packageName, title, text)
        }
    }

    fun summary(limit: Int = 25): String {
        val list = activeNotifications?.sortedByDescending { it.postTime }?.take(limit) ?: return "(sem acesso)"
        cached = list
        if (list.isEmpty()) return "(nenhuma notificação)"
        return list.mapIndexed { idx, sbn ->
            val e = sbn.notification.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE) ?: ""
            val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: ""
            val canReply = findReplyAction(sbn) != null
            val replyFlag = if (canReply) " [respondível id=$idx]" else ""
            "[$idx] ${sbn.packageName}: $title — ${text.toString().take(180)}$replyFlag"
        }.joinToString("\n")
    }

    fun reply(index: Int, text: String): String {
        val list = if (cached.isNotEmpty()) cached else (activeNotifications?.sortedByDescending { it.postTime } ?: emptyList())
        val sbn = list.getOrNull(index) ?: return "notificação [$index] não encontrada (use read_notifications primeiro)"
        val (action, remoteInputs) = findReplyAction(sbn) ?: return "essa notificação não aceita resposta rápida"
        val intent = Intent()
        val bundle = Bundle()
        for (ri in remoteInputs) bundle.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        action.actionIntent.send(this, 0, intent)
        return "resposta enviada para ${sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: sbn.packageName}"
    }

    private fun findReplyAction(sbn: StatusBarNotification): Pair<Notification.Action, Array<RemoteInput>>? {
        val actions = sbn.notification.actions ?: return null
        for (a in actions) {
            val ris = a.remoteInputs
            if (!ris.isNullOrEmpty() && a.actionIntent != null) return a to ris
        }
        return null
    }
}
