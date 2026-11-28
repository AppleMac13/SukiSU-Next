package com.sukisu.nova.ui.util.module

data class LatestVersionInfo(
    val versionCode: Long = 0L,
    val downloadUrl: String = "",
    val changelog: String = "",
    // The release tag without its leading "v". This app ships one versionCode across every
    // build, so the released version has to be compared as a string.
    val versionName: String = "",
)

/// Whether [latest] is a greater version than [current]. Both are dot-separated numbers
/// ("0.1.0"). Missing or unparsable parts count as 0, so "0.1" beats "0.0.9".
fun isNewerVersionName(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    if (a.isEmpty() || b.isEmpty()) return false
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(version: String): List<Int> =
    version.trim().removePrefix("v").split('.')
        .mapNotNull { it.trim().toIntOrNull() }
