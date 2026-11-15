package com.droidhost.launcher.agent

import com.droidhost.launcher.session.SessionPaths
import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import com.droidhost.launcher.BuildConfig
import com.droidhost.launcher.frontend.GameLaunchLink
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.runtime.LinuxRuntimeInstaller
import com.droidhost.launcher.session.SessionArtifacts
import com.droidhost.launcher.session.SessionEvents
import com.droidhost.launcher.session.SessionLogShare
import com.droidhost.launcher.session.SessionPhase
import com.droidhost.launcher.session.SessionService
import com.droidhost.launcher.session.SessionState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        Thread({
            try {
                SessionArtifacts.finishAbandoned(appContext)
            } finally {
                recoveryComplete.countDown()
            }
        }, "agent-recover-artifacts").start()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        if (Binder.getCallingUid() != Process.myUid() &&
            context.checkCallingPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("android.permission.DUMP is required")
        }

        val response = try {
            val request = decode(extras?.getString(EXTRA_REQUEST))
            val token = Binder.clearCallingIdentity()
            try {
                dispatch(context, method, request)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: AgentException) {
            error(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            error("COMMAND_FAILED", e.message ?: e.javaClass.simpleName)
        }
        val json = response.toString().let {
            if (it.length <= 384 * 1024) it else error("RESPONSE_TOO_LARGE", "Reduce the command output or select fewer fields").toString()
        }
        return Bundle().apply { putString(RESULT_JSON, json) }
    }

    private fun dispatch(context: Context, method: String, request: JSONObject): JSONObject = when (method) {
        "state" -> state(context).put("ok", true)
        "start" -> error("USE_DROIDDECKCTL", "Start sessions with tools/droiddeckctl so Android launches a visible Activity")
        "stop" -> stop(context)
        "resume" -> resume(context)
        "launch" -> launch(context, request)
        "focus" -> guest(context, JSONObject().put("kind", "focus"), 15_000)
        "quit" -> guest(context, JSONObject().put("kind", "quit").put("appId", request.opt("appId"))
            .put("timeout", request.optDouble("timeout", 15.0)), timeoutMs(request, 15.0, 30_000))
        "input" -> AgentInput.handle(request)
        "ui" -> AgentUi.handle(request)
        "guest" -> {
            AgentAccess.requireCommands(context)
            guest(context, JSONObject(request.toString()).put("kind", "exec"), timeoutMs(request, 30.0, 10_000))
        }
        "native" -> {
            AgentAccess.requireCommands(context)
            AgentNative.run(context, request, timeoutMs(request, 30.0, 0))
        }
        "vm" -> {
            AgentAccess.requireCommands(context)
            vm(context, request, timeoutMs(request, 120.0, 5_000))
        }
        "cdp" -> {
            AgentAccess.requireCommands(context)
            guest(context, JSONObject(request.toString()).put("kind", "cdp"), timeoutMs(request, 15.0, 5_000))
        }
        "env" -> env(context, request)
        "override" -> {
            AgentAccess.requireCommands(context)
            AgentEnv.override(context, request.optString("name")).put("ok", true).put("command", "override")
        }
        "prefs" -> prefs(context, request)
        "glasses" -> glasses(context, request)
        "logs" -> logs(context)
        "access" -> JSONObject().put("ok", true).put("commands", AgentAccess.commandsAllowed(context))
            .put("debugBuild", BuildConfig.DEBUG).put("inbox", AgentEnv.inbox(context).absolutePath)
        else -> error("UNKNOWN_COMMAND", "Unknown agent command '$method'")
    }

    private fun glasses(context: Context, request: JSONObject): JSONObject {
        val g = com.droidhost.launcher.glasses.Glasses
        when (request.optString("op", "status")) {
            "status" -> {}
            "allow" -> g.requestControl()
            "set" -> {
                val key = request.optString("key")
                val value = request.optString("value")
                fun on() = value == "1" || value.equals("true", true) || value.equals("on", true)
                val number = value.toIntOrNull()
                val numeric = setOf("refreshHz", "sbsHz", "brightness", "volume", "picture3d", "screenWidthDm", "screenDistanceDm", "vrFovDeg", "sbsSupport", "eyeHeightCm")
                if (key in numeric && number == null) return error("INVALID_VALUE", "'$key' takes a number")
                when (key) {
                    "refreshHz" -> g.setRefreshHz(number!!)
                    "sbsHz" -> g.setSbsHz(number!!)
                    "resolution2d" -> g.setResolution(com.droidhost.launcher.glasses.GlassesDisplayProfile.TWO_D, value)
                    "resolution3d" -> g.setResolution(com.droidhost.launcher.glasses.GlassesDisplayProfile.THREE_D, value)
                    "resolutionVr" -> g.setResolution(com.droidhost.launcher.glasses.GlassesDisplayProfile.VR, value)
                    "brightness" -> g.setBrightness(number!!)
                    "volume" -> g.setVolume(number!!)
                    "sunblock" -> g.setSunblock(on())
                    "3d" -> g.set3d(on())
                    "3dof" -> g.set3dof(on())
                    "gameOnGlasses" -> g.setGameOnGlasses(on())
                    "headTracking" -> g.setHeadTracking(on())
                    "auto3d" -> g.setAuto3d(on())
                    "picture3d" -> g.setPicture3d(number!!)
                    "screenWidthDm" -> g.setScreenWidthDm(number!!)
                    "screenDistanceDm" -> g.setScreenDistanceDm(number!!)
                    "vrFovDeg" -> g.setVrFovDeg(number!!)
                    "sbsSupport" -> g.setSbsSupport(number!!)
                    "positionalTracking" -> g.setPositionalTracking(on())
                    "handControllers" -> g.setHandControllers(on())
                    "joyConTracking" -> g.setJoyConTracking(on())
                    "eyeHeightCm" -> g.setEyeHeight(number!!)
                    else -> return error("INVALID_KEY", "Unknown glasses setting '$key'")
                }
            }
            "simulate" -> {
                fun angle(key: String) = if (request.has(key)) request.optDouble(key).takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0) else null
                if (!g.simulate(request.optInt("seconds", 20).coerceIn(1, 600), angle("yaw"), angle("pitch"))) return error("BUSY", "Already simulating")
            }
            "stop-simulate" -> g.stopSimulation()
            "calibrate" -> g.startCalibration()
            "cancel-calibration" -> g.cancelCalibration()
            "reset-calibration" -> g.resetCalibration()
            "spatial-diagnostics" -> com.droidhost.launcher.glasses.SpatialTracking.diagnostics(request.optBoolean("enabled",true))
            "spatial-recenter" -> com.droidhost.launcher.glasses.SpatialTracking.recenter(request.optBoolean("screen",false))
            "spatial-floor" -> g.setSpatialFloor()
            "spatial-restart" -> g.restartSpatialTracking()
            "spatial-hand-options" -> {
                AgentAccess.requireCommands(context)
                if(request.has("lite")) com.droidhost.launcher.glasses.SpatialTracking.setHandModel(request.getBoolean("lite"))
                if(request.has("parallel")) com.droidhost.launcher.glasses.SpatialTracking.setHandCpuParallel(request.getBoolean("parallel"))
                if(request.has("halfRate")) com.droidhost.launcher.glasses.SpatialTracking.setHandHalfRate(request.getBoolean("halfRate"))
                if(request.has("adaptive")) com.droidhost.launcher.glasses.SpatialTracking.setHandAdaptiveInput(request.getBoolean("adaptive"))
                if(request.has("scheduled")) com.droidhost.launcher.glasses.SpatialTracking.setHandScheduledTracking(request.getBoolean("scheduled"))
            }
            "spatial-hand-benchmark" -> {
                AgentAccess.requireCommands(context)
                com.droidhost.launcher.glasses.SpatialTracking.benchmarkSavedCapture(request.getString("capture"))
            }
            "spatial-hand-capture" -> {
                AgentAccess.requireCommands(context)
                com.droidhost.launcher.glasses.SpatialTracking.captureDiagnostic()
            }
            "spatial-rgb-capture", "spatial-rgb-status" -> {
                AgentAccess.requireCommands(context)
                return (if (request.getString("op") == "spatial-rgb-capture")
                    com.droidhost.launcher.glasses.LumaRgbDiagnostic.capture(context!!,request.optString("transport","sdk"),
                        request.optInt("width",640),request.optInt("height",480),request.optInt("fps",30),
                        request.optInt("durationMs",5000))
                else com.droidhost.launcher.glasses.LumaRgbDiagnostic.status(context!!)).put("ok", true)
            }
            "query" -> { g.query(); Thread.sleep(600) }
            "mode" -> {
                val mode = hex(request.optString("mode"), 0xFF) ?: return error("INVALID_VALUE", "mode must be one hex byte, e.g. 0x31")
                g.sendMode(mode)
                Thread.sleep(600)
            }
            "imu" -> return g.sampleImu(request.optLong("ms", 2000).coerceIn(200, 20_000)).put("ok", true).put("command", "glasses")
            "raw" -> {
                AgentAccess.requireCommands(context)
                val msgId = hex(request.optString("msgId"), 0xFFFF) ?: return error("INVALID_VALUE", "msgId must be a hex number up to 0xFFFF")
                val digits = request.optString("payload").removePrefix("0x")
                if (digits.length % 2 != 0 || digits.any { Character.digit(it, 16) < 0 }) return error("INVALID_VALUE", "payload must be whole hex bytes")
                val payload = digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!g.sendRaw(msgId, payload)) return error("NOT_CONNECTED", "The glasses' control link is not open")
                Thread.sleep(300)
            }
            else -> return error("INVALID_OP", "Unknown glasses op")
        }
        if (request.optString("op") in setOf("set", "allow")) Thread.sleep(400)
        return g.status().put("ok", true).put("command", "glasses").put("frames", g.recentFrames())
    }

    private fun hex(text: String, max: Int): Int? =
        text.removePrefix("0x").removePrefix("0X").takeIf { it.isNotEmpty() }?.toIntOrNull(16)?.takeIf { it in 0..max }

    private fun logDir(context: Context): File? =
        SessionState.logDirectory ?: SessionState.logFile?.parentFile ?: SessionPaths.sessionFolders(context).lastOrNull()

    private fun cleanupExpiredLogExports(exports: File) {
        val now = System.currentTimeMillis()
        val canonicalRoot = exports.canonicalFile
        exports.listFiles()?.forEach { candidate ->
            val isUuid = runCatching { UUID.fromString(candidate.name).toString() == candidate.name }.getOrDefault(false)
            if (!isUuid || !candidate.isDirectory || Files.isSymbolicLink(candidate.toPath())) return@forEach
            if (candidate.canonicalFile.parentFile != canonicalRoot) return@forEach
            if (now - candidate.lastModified() >= LOG_EXPORT_RETENTION_MS) candidate.deleteRecursively()
        }
    }

    // The session folder is app-private, so adb pulls this copy from external files instead.
    private fun logs(context: Context): JSONObject {
        val dir = logDir(context)?.takeIf { it.isDirectory }
            ?: return error("NO_SESSION_LOGS", "No session artifact directory is available")
        val externalFiles = context.getExternalFilesDir(null)
            ?: return error("NO_SESSION_LOGS", "External app storage is unavailable")
        val exports = File(externalFiles, "agent-logs")
        if ((!exports.exists() && !exports.mkdirs()) || !exports.isDirectory) {
            return error("NO_SESSION_LOGS", "Could not create the session export directory")
        }
        cleanupExpiredLogExports(exports)
        val output = File(exports, UUID.randomUUID().toString())
        val zip = try {
            SessionLogShare.zipFolder(context, dir, output)
        } catch (error: Exception) {
            output.deleteRecursively()
            throw error
        }
            ?: return error("NO_SESSION_LOGS", "The session artifact directory is empty")
        return JSONObject().put("ok", true).put("command", "logs").put("sessionId", dir.name)
            .put("zip", zip.absolutePath).put("cleanupDir", output.absolutePath)
    }

    private fun state(context: Context): JSONObject {
        val runtimeVersion = LinuxRuntimeInstaller.installedVersion(context)
        val dir = logDir(context)
        val session = JSONObject()
            .put("id", SessionState.sessionId ?: dir?.name ?: JSONObject.NULL)
            .put("phase", SessionState.phase.name)
            .put("running", SessionState.running)
            .put("mode", SessionState.mode)
            .put("program", SessionState.program ?: JSONObject.NULL)
            .put("steamUi", SessionState.steamUi ?: JSONObject.NULL)
            .put("steamUrl", SessionState.steamUrl ?: JSONObject.NULL)
            .put("suspended", SessionState.suspended)
            .put("steamVr", SessionState.steamVr)
            .put("pip", SessionState.pipActive)
            .put("firstFrame", SessionState.firstFrameSeen)
            .put("output", JSONArray().put(SessionState.outputSize.first).put(SessionState.outputSize.second))
            .put("refreshHz", SessionState.refreshHz.toDouble())
            .put("lastTransitionAt", SessionState.lastTransitionAt)
            .put("guestPid", SessionState.guestPid.takeIf { it > 1 } ?: JSONObject.NULL)
            .put("installing", SessionState.installing ?: JSONObject.NULL)
            .put("logDir", dir?.absolutePath ?: JSONObject.NULL)
            .put("eventsFile", dir?.let { File(it, "events.jsonl").absolutePath } ?: JSONObject.NULL)
            .put("artifactsAvailable", dir?.isDirectory ?: false)
            .put("artifactsComplete", dir?.let { File(it, ".complete").isFile } ?: false)

        val failure = if (SessionState.failureCode == null) JSONObject.NULL else JSONObject()
            .put("code", SessionState.failureCode)
            .put("message", SessionState.failureMessage ?: JSONObject.NULL)
            .put("status", SessionState.failureStatus ?: JSONObject.NULL)
        session.put("failure", failure)

        val focus = AgentGuest.focus(context)?.let { snap ->
            JSONObject()
                .put("focusedApp", snap.opt("focusedApp") ?: JSONObject.NULL)
                .put("focusedWindow", snap.opt("focusedWindow") ?: JSONObject.NULL)
                .put("focusableApps", snap.optJSONArray("focusableApps") ?: JSONArray())
                .put("baselayerAppIds", snap.optJSONArray("baselayerAppIds") ?: JSONArray())
                .put("seq", snap.optInt("seq"))
                .put("t", snap.optLong("t"))
        }
        session.put("focus", focus ?: JSONObject.NULL)

        val hello = AgentGuest.hello(context)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("build", BuildConfig.BUILD_LABEL)
            .put("apkCommit", com.droidhost.launcher.lab.LabOverlay.apkCommit)
            .put("lab", com.droidhost.launcher.lab.LabOverlay.active?.label ?: JSONObject.NULL)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            .put("runtime", JSONObject()
                .put("installed", LinuxRuntime.isInstalled(context))
                .put("version", runtimeVersion ?: JSONObject.NULL))
            .put("agent", JSONObject()
                .put("guest", hello != null)
                .put("guestFocus", hello?.optBoolean("focus") ?: false)
                .put("commands", AgentAccess.commandsAllowed(context)))
            .put("session", session)
    }

    private fun stop(context: Context): JSONObject {
        if (SessionState.running) {
            SessionService.stop(context)
            return JSONObject().put("ok", true).put("command", "stop")
        }
        if (SessionState.phase == SessionPhase.STOPPING) {
            return JSONObject().put("ok", true).put("command", "stop")
        }
        if (SessionState.phase in STARTING_PHASES) {
            SessionState.stopRequested = true
            SessionEvents.transition(SessionPhase.STOPPING, "session.stop_requested")
            return JSONObject().put("ok", true).put("command", "stop")
        }
        return error("NO_ACTIVE_SESSION", "There is no session to stop")
    }

    private fun resume(context: Context): JSONObject {
        if (!SessionState.running || !SessionState.suspended) {
            return error("SESSION_NOT_SUSPENDED", "There is no suspended session to resume")
        }
        SessionService.resume(context)
        return JSONObject().put("ok", true).put("command", "resume")
    }

    private fun launch(context: Context, request: JSONObject): JSONObject {
        val appId = request.optString("appId")
        if (!GameLaunchLink.validId(appId)) throw AgentException("INVALID_APP_ID", "appId must be a decimal Steam app id")
        if (!SessionState.running || SessionState.mode != SessionService.MODE_STEAM) {
            throw AgentException("NO_STEAM_SESSION", "Games launch inside a running Steam session")
        }
        if (!SessionService.launchGame(context, appId)) throw AgentException("LAUNCH_REJECTED", "The session did not accept the launch")
        SessionEvents.record("agent.launch_requested", mapOf("appId" to appId))
        return JSONObject().put("ok", true).put("command", "launch").put("appId", appId)
    }

    private fun env(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val result = when (val op = request.optString("op", "list")) {
            "list" -> AgentEnv.list(context)
            "set" -> {
                val lines = request.optJSONArray("lines") ?: JSONArray()
                AgentEnv.set(context, List(lines.length()) { lines.optString(it) }, request.optBoolean("persistent"))
            }
            "clear" -> AgentEnv.clear(context, request.optString("scope", "next"))
            else -> throw AgentException("INVALID_REQUEST", "env op '$op' is not list, set or clear")
        }
        return result.put("ok", true).put("command", "env")
    }

    private fun prefs(context: Context, request: JSONObject): JSONObject {
        AgentAccess.requireCommands(context)
        val file = request.optString("file")
        val result = when (val op = request.optString("op", "get")) {
            "get" -> AgentPrefs.get(context, file)
            "set" -> AgentPrefs.set(context, file, request.optString("key"), request.optString("type", "string"),
                if (request.isNull("value")) null else request.optString("value"))
            else -> throw AgentException("INVALID_REQUEST", "prefs op '$op' is not get or set")
        }
        return result.put("ok", true).put("command", "prefs")
    }

    private fun guest(context: Context, request: JSONObject, timeoutMs: Long): JSONObject =
        AgentGuest.call(context, request, timeoutMs)

    /**
     * A program in a machine's own rootfs.
     *
     * This is the way to see what a distribution the user brought really holds, and to let it
     * install what it is missing.
     */
    private fun vm(context: Context, request: JSONObject, timeoutMs: Long): JSONObject {
        val machineId = request.optString("machine")
        if (machineId.isEmpty()) throw AgentException("INVALID_REQUEST", "machine is required")
        val argvJson = request.optJSONArray("argv")
            ?: throw AgentException("INVALID_REQUEST", "argv is required")
        val argv = (0 until argvJson.length()).map { argvJson.getString(it) }
        if (argv.isEmpty()) throw AgentException("INVALID_REQUEST", "argv is empty")
        val env = linkedMapOf<String, String>()
        request.optJSONObject("env")?.let { obj -> obj.keys().forEach { env[it] = obj.getString(it) } }
        val result = com.droidhost.launcher.vm.VmGuest.run(context, machineId, argv,
            request.optBoolean("root", false), timeoutMs, env)
        return JSONObject().put("ok", true).put("command", "vm")
            .put("machine", machineId)
            .put("exitCode", result.exitCode)
            .put("timedOut", result.timedOut)
            .put("output", result.output)
    }

    private fun timeoutMs(request: JSONObject, defaultSeconds: Double, slackMs: Long): Long {
        val seconds = if (request.has("timeout")) request.opt("timeout")?.toString()?.toDoubleOrNull()
            ?: throw AgentException("INVALID_REQUEST", "timeout must be a number") else defaultSeconds
        if (!seconds.isFinite() || seconds <= 0 || seconds > 600) {
            throw AgentException("INVALID_REQUEST", "timeout must be a positive number no greater than 600 seconds")
        }
        return (seconds * 1000).toLong() + slackMs
    }

    private fun decode(encoded: String?): JSONObject {
        if (encoded.isNullOrBlank()) return JSONObject()
        if (encoded.length > AgentAccess.MAX_REQUEST_CHARS) throw AgentException("INVALID_REQUEST", "The encoded request exceeds 256 KiB")
        return try {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        } catch (e: Exception) {
            throw AgentException("INVALID_REQUEST", "The request extra is not base64 JSON")
        }
    }

    private fun error(code: String, message: String) = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val RESULT_JSON = "json"
        const val EXTRA_REQUEST = "request"
        const val SCHEMA = 2
        private const val LOG_EXPORT_RETENTION_MS = 24 * 60 * 60 * 1000L
        private val recoveryComplete = CountDownLatch(1)

        fun awaitRecovery() {
            try {
                if (!recoveryComplete.await(90, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Session artifact recovery did not finish")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while recovering session artifacts", e)
            }
        }

        private val STARTING_PHASES = setOf(
            SessionPhase.PREPARING,
            SessionPhase.INSTALLING_RUNTIME,
            SessionPhase.STARTING_COMPOSITOR,
            SessionPhase.STARTING_GUEST,
            SessionPhase.STARTING_STEAM,
        )
    }
}
