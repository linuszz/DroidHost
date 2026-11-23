package com.droidhost.launcher.input

import android.content.Context
import java.io.File

// Diagnostic only: the Red Magic (Nubia) ROM restricts logcat reads — both shell and app uid get
// "0 B readable" from logd, and bugreport needs a one-time user consent dialog. Mirror the KBHost
// trace into an app-private file so it can be read via run-as without logcat or bugreport. Revert
// once the Red Magic soft-IME chord failure is diagnosed.
object KbFileLog {
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "kb-debug.log")
    }

    fun log(msg: String) {
        val f = file ?: return
        try {
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }
}
