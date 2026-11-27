package com.sukisu.nova.backend

import android.content.Context
import android.util.Log
import com.sukisu.nova.ksuApp

/**
 * The uid of the package this kernel pinned as its manager.
 *
 * The driver only serves `SET_APP_PROFILE` / `GET_APP_PROFILE` to the manager
 * app id baked into the kernel image, and it answers `GET_MANAGER_APPID` with 0
 * for root, so the id has to be looked up from the installed packages: the
 * bridge and the profile writer borrow it for the duration of one call.
 *
 * `PackageManager.getPackageUid` is useless here on Android 11+: this app does
 * not hold QUERY_ALL_PACKAGES, so another manager package is filtered out of the
 * result. The lookup therefore goes through root and reads packages.list,
 * whose second column is the uid.
 *
 * Falls back to this app's own uid, which is what an officially signed build
 * would be using anyway.
 */
object KernelManager {
    private const val TAG = "KernelManager"

    private val CANDIDATES = listOf(
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "com.sukisu.kernelsu",
        "me.weishu.kernelsu",
        "com.rifsxd.kernelsu",
        "com.sukisu.sukisu",
    )

    @Volatile
    private var cached: Int = 0

    fun uid(context: Context = ksuApp): Int {
        cached.takeIf { it != 0 }?.let { return it }
        val resolved = fromPackageManager(context) ?: fromPackagesList()
        if (resolved != null && resolved > 0) {
            Log.i(TAG, "kernel manager uid resolved: $resolved")
            cached = resolved
            return resolved
        }
        val own = android.os.Process.myUid()
        Log.w(TAG, "no manager package found, falling back to self uid $own")
        cached = own
        return own
    }

    private fun fromPackageManager(context: Context): Int? {
        val pm = context.packageManager
        for (name in CANDIDATES) {
            val uid = runCatching { pm.getPackageUid(name, 0) }.getOrNull()
            if (uid != null && uid > 0) return uid
        }
        return null
    }

    private fun fromPackagesList(): Int? {
        for (name in CANDIDATES) {
            val line = runCatching {
                val proc = ProcessBuilder(
                    "/system/bin/sh",
                    "-c",
                    "su -c 'grep -m1 \"^$name \" /data/system/packages.list'",
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                out
            }.getOrNull().orEmpty()
            val uid = line.trim().split(' ').getOrNull(1)?.toIntOrNull()
            if (uid != null && uid > 0) return uid
        }
        return null
    }
}
