package com.sukisu.nova.backend

import android.content.Context
import android.util.Log
import com.sukisu.nova.Natives
import java.io.File
import java.security.SecureRandom

/**
 * Root bridge for a manager the kernel refuses to recognise.
 *
 * A kernel built for another APK certificate answers every manager ioctl with
 * EPERM, yet it happily serves uid 0 (`manager_or_root` is the permission class
 * on nearly every command). So the app asks `su` for one privileged helper -
 * ksud's hidden `proxy` command - which keeps the driver fd open and replays
 * ioctls received over a unix socket. From the kernel's point of view the
 * requests come from root, and everything the manager UI needs works.
 *
 * The socket is bound inside the app's private directory and the helper only
 * accepts a peer with the app's own uid that also presents the random token
 * generated here, so no other app can borrow the session.
 */
object SuBridge {
    private const val TAG = "SuBridge"
    private const val BRIDGE_BIN = "/data/adb/ksud-bridge"
    private const val SOCKET_NAME = ".kbridge.sock"

    @Volatile
    var ready: Boolean = false
        private set

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts the bridge once per process; returns whether it is usable. */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        Log.i(TAG, "ensureReady: ready=$ready started=${started.get()}")
        if (ready) return true
        if (started.get()) return false

        val socket = File(context.filesDir, SOCKET_NAME)
        // unix socket paths are limited to 107 bytes
        if (socket.absolutePath.length > 100) {
            Log.w(TAG, "socket path too long: ${socket.absolutePath}")
            return false
        }

        // The kernel-side commands (module install, soft reboot, ...) are served
        // by the binary the kernel itself installed, which has no `proxy` subcommand.
        // Keep that one as libksud.so and ship a second copy built from this
        // tree purely as the bridge helper.
        val bridge = File(context.applicationInfo.nativeLibraryDir, "libksubridge.so")
        val lib = if (bridge.exists()) bridge
        else File(context.applicationInfo.nativeLibraryDir, "libksud.so")
        if (!lib.exists()) {
            Log.w(TAG, "bundled ksud missing at $lib")
            return false
        }

        val token = randomToken()
        val uid = android.os.Process.myUid()

        // Publish the endpoint before starting the helper so the very first
        // ioctl can already be bridged.
        Natives.setProxyConfig(socket.absolutePath, token)

        started.set(true)

        // A stale helper from an earlier process keeps the old (already unlinked)
        // socket; kill it by comm name, not by command line - the launching shell
        // carries the same path in its own cmdline.
        val install = "rm -f '${socket.absolutePath}'; " +
                "kill \$(pidof ksud-bridge 2>/dev/null) 2>/dev/null; " +
                "cp '${lib.absolutePath}' '$BRIDGE_BIN' && chmod 0755 '$BRIDGE_BIN'"
        // Root-side su requests: the kernel never leaves one pending, so unlisted
        // uids have to be asked about here. The helper raises SuRequestActivity.
        val requestSocket = "/data/local/tmp/.ksu-req.sock"
        val manager = "${context.packageName}/com.sukisu.nova.ui.SuRequestActivity"
        val run = "$BRIDGE_BIN proxy --socket '${socket.absolutePath}' " +
                "--token '$token' --peer-uid $uid --daemonize " +
                "--req-socket '$requestSocket' --manager '$manager'"

        val installed = runSu(context, install, 20_000)
        if (!installed) {
            Log.w(TAG, "cannot stage $BRIDGE_BIN (su denied or copy failed)")
            return false
        }
        if (!runSu(context, run, 10_000)) {
            Log.w(TAG, "cannot start the bridge helper")
            return false
        }

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists() && Natives.probeProxy()) {
                ready = true
                Log.i(TAG, "root bridge ready on ${socket.absolutePath}")
                return true
            }
            Thread.sleep(100)
        }

        Log.w(TAG, "root bridge did not come up")
        return false
    }

    private fun runSu(context: Context, command: String, timeoutMs: Long): Boolean {
        return try {
            val su = resolveSu() ?: return false
            val process = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy()
                return false
            }
            val code = process.exitValue()
            if (code != 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                Log.w(TAG, "su exited $code: $output")
            }
            code == 0
        } catch (t: Throwable) {
            Log.w(TAG, "su failed", t)
            false
        }
    }

    private fun resolveSu(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
        )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
