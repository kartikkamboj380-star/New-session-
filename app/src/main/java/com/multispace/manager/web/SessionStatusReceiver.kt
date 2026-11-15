package com.multispace.manager.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.multispace.manager.MultiSpaceApp
import com.multispace.manager.core.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives session status broadcasts from the WebView slot processes and
 * keeps the main-process session list accurate (ACTIVE/IDLE).
 */
class SessionStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SESSION_STATUS) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val status = intent.getStringExtra(EXTRA_STATUS) ?: return
        val container = MultiSpaceApp.instance?.container ?: return
        CoroutineScope(Dispatchers.IO).launch {
            when (status) {
                SessionStatus.ACTIVE.name -> container.sessionManager.activate(sessionId)
                SessionStatus.IDLE.name -> container.sessionManager.close(sessionId)
            }
        }
    }

    companion object {
        const val ACTION_SESSION_STATUS = "com.multispace.manager.action.SESSION_STATUS"
        const val EXTRA_SESSION_ID = "extra.session_id"
        const val EXTRA_STATUS = "extra.status"
    }
}
