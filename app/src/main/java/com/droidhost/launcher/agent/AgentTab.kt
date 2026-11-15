package com.droidhost.launcher.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droidhost.launcher.session.SessionPrefs

object AgentTab {
    var shown by mutableStateOf(true)
        private set

    val visible: Boolean get() = shown && AgentHost.available

    fun load(context: Context) {
        shown = SessionPrefs.agentTab(context)
    }

    fun set(context: Context, show: Boolean) {
        shown = show
        SessionPrefs.setAgentTab(context, show)
    }
}
