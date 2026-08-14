package com.idk.frameinterp

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuCaptureEngine(private val ctx: Context) {

    fun interface ErrorListener { fun onError(message: String) }
    fun interface FrameListener { fun onFrame(buffer: HardwareBuffer, timestampNs: Long) }

    @Volatile private var service: IShizukuCaptureService? = null
    @Volatile private var pendingStart: StartArgs? = null
    @Volatile private var errorListener: ErrorListener? = null
    @Volatile private var frameListener: FrameListener? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IShizukuCaptureService.Stub.asInterface(it) }
            pendingStart?.let { startCaptureInternal(it.targetUid, it.width, it.height, it.maxFps) }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    fun setErrorListener(listener: ErrorListener?) { errorListener = listener }
    fun setFrameListener(listener: FrameListener?) { frameListener = listener }

    fun isReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int, onResult: (Boolean) -> Unit) {
        if (isReady()) { onResult(true); return }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                if (code == requestCode) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(requestCode)
    }

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            errorListener?.onError("Target package not found: $targetPackage")
            return
        }
        startCaptureInternal(targetUid, width, height, maxFps)
    }

    private fun startCaptureInternal(targetUid: Int, width: Int, height: Int, maxFps: Int) {
        pendingStart = StartArgs(targetUid, width, height, maxFps)
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
        }.onFailure {
            Log.w(TAG, "startCapture failed", it)
            errorListener?.onError("Start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        runCatching { service?.stopCapture() }
        runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        service = null
    }

    private fun bind() {
        if (!isReady()) {
            errorListener?.onError("Shizuku is not running or permission is missing")
            return
        }
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            Log.w(TAG, "bindUserService failed", it)
            errorListener?.onError("Bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, CaptureUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(false)
            .version(1)

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long) {
            try {
                frameListener?.onFrame(buffer, timestampNs)
            } catch (t: Throwable) {
                Log.w(TAG, "frame listener failed", t)
            }
        }
        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown capture error")
        }
    }

    private data class StartArgs(val targetUid: Int, val width: Int, val height: Int, val maxFps: Int)

    companion object {
        private const val TAG = "ShizukuCaptureEngine"
    }
}
