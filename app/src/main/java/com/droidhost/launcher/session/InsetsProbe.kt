package com.droidhost.launcher.session

import android.content.Context
import android.os.Build
import android.util.Log
import com.droidhost.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Device-level safe-area probe: logcat plus a flat file in the runtime log dir (not the per-session
// subdir), so it survives sessions with logging disabled and is easy to pull for real-device checks.
object InsetsProbe {
    private const val TAG = "DroidHostInsets"
    private const val FILE = "insets-probe.json"
    private const val MAX_BYTES = 128 * 1024L
    private val lock = Any()

    fun record(context: Context, trigger: String, payload: JSONObject) {
        val entry = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("trigger", trigger)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("density", context.resources.displayMetrics.density)
        payload.keys().forEach { key -> entry.put(key, payload.get(key)) }
        val line = entry.toString()
        Log.i(TAG, line)
        try {
            synchronized(lock) {
                val dir = LinuxRuntime.logDir(context)
                dir.mkdirs()
                val file = File(dir, FILE)
                FileOutputStream(file, file.length() < MAX_BYTES).use { stream ->
                    stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "writing $FILE", e)
        }
    }
}
