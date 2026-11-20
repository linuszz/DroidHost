package com.droidhost.launcher.wayland

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import com.droidhost.launcher.session.SessionPrefs
import com.droidhost.launcher.session.SessionState

object CompositorHost {
    @Volatile
    private var started = false

    @Volatile
    var displayChoice: String? = null

    @Volatile
    private var vsyncRunning = false

    @Volatile
    private var ownVsyncThread = true

    private val vsyncThread: HandlerThread by lazy {
        HandlerThread("droiddeck-vsync", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val vsyncHandler: Handler by lazy { Handler(vsyncThread.looper) }

    @Volatile
    private var vsyncGeneration = 0

    private fun vsyncCallback(generation: Int) = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (generation != vsyncGeneration) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun startOrAttach(
        context: android.content.Context,
        surface: Surface,
        xdgRuntimeDir: String,
        driverPath: String?,
        libraryName: String?,
        nativeLibDir: String,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
    ): Boolean {
        WaylandCompositor.nativeSetClockControl(java.io.File(context.filesDir, "${com.droidhost.launcher.runtime.LinuxRuntime.DIR}/tmp/.bl-clock").path)
        ownVsyncThread = SessionPrefs.vsyncThread(context)
        WaylandCompositor.nativeSetRenderOnCommit(SessionPrefs.renderOnCommit(context))
        pace(refreshHz, fpsLimit)
        if (!SessionState.running) WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
        if (started) {
            attached = surface
            WaylandCompositor.nativeSetSurface(surface)
            applySurfaceVote()
            resumeVsync()
            return false
        }
        com.droidhost.launcher.gpu.TpUbwcHint.applyToProcess(context)
        WaylandCompositor.nativeStartWithSurface(
            surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir,
        )
        attached = surface
        started = true
        applySurfaceVote()
        resumeVsync()
        return true
    }

    private fun pace(refreshHz: Float, fpsLimit: Int) {
        WaylandCompositor.nativeSetFpsLimit(fpsLimit)
        val vote = if (fpsLimit > 0) fpsLimit.toFloat() else refreshHz
        WaylandCompositor.nativeSetLayerFrameRate(vote)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
        SessionState.refreshHz = refreshHz
        surfaceVoteHz = vote
        applySurfaceVote()
    }

    // The desktop renders through the SurfaceView's ANativeWindow, not the sc_layer SurfaceControl
    // path games use, so nativeSetLayerFrameRate never reaches SurfaceFlinger here. Vote the paced
    // rate explicitly (CHANGE_FRAME_RATE_ALWAYS) or content detection locks the static desktop at 60 Hz.
    private fun applySurfaceVote() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val surface = attached ?: return
        val hz = surfaceVoteHz
        if (hz <= 1f) return
        runCatching {
            surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
        }
    }

    @Synchronized
    fun updatePacing(refreshHz: Float, fpsLimit: Int) {
        if (started) pace(refreshHz, fpsLimit)
    }

    @Synchronized
    fun resize(surface: Surface, rearm: () -> Unit) {
        if (!started) return
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "surface resized: rebinding the window, frame generation re-armed after")
        WaylandCompositor.nativeSetSurface(null)
        attached = surface
        WaylandCompositor.nativeSetSurface(surface)
        applySurfaceVote()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    fun rearmFrameGen(rearm: () -> Unit) {
        if (!started) return
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "presenting window changed: frame generation re-armed")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    private var attached: Surface? = null

    @Volatile
    private var surfaceVoteHz: Float = 0f

    @Synchronized
    fun attachXr() {
        check(started) { "DroidHost compositor is not running" }
        pauseVsync()
        attached = null
        WaylandCompositor.nativeSetSurface(null)
    }

    @Synchronized
    fun detach(surface: Surface?) {
        if (!started) return
        if (surface != null && attached != null && attached !== surface) return
        attached = null
        pauseVsync()
        WaylandCompositor.nativeSetSurface(null)
    }

    private fun resumeVsync() {
        if (vsyncRunning) return
        val callback = vsyncCallback(++vsyncGeneration)
        vsyncRunning = true
        WaylandCompositor.nativeLog("screen", if (ownVsyncThread) "screen tick runs on its own display-priority thread" else "screen tick runs on the main thread")
        if (!ownVsyncThread) {
            Choreographer.getInstance().postFrameCallback(callback)
            return
        }
        vsyncHandler.post { Choreographer.getInstance().postFrameCallback(callback) }
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    @Synchronized
    fun newSession() {
        WaylandCompositor.nativeSetClockControl(null)
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    val isStarted: Boolean get() = started
}
