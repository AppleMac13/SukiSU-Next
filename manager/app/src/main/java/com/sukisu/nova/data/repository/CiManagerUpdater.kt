package com.sukisu.nova.data.repository

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.sukisu.nova.BuildConfig
import com.sukisu.nova.R
import com.sukisu.nova.ksuApp
import com.sukisu.nova.ui.util.module.LatestVersionInfo
import com.sukisu.nova.ui.util.module.isNewerVersionName
import com.sukisu.nova.ui.util.getRootShell
import com.sukisu.nova.ui.util.shellQuote
import com.sukisu.nova.ui.util.withNewRootShell
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.min


class CiManagerUpdater(private val context: Context) {
    /**
     * [keepApk] false installs straight from the private cache copy and never
     * writes the APK to Downloads, so nothing is left behind after the update.
     */
    fun updateAndInstall(
        update: LatestVersionInfo,
        keepApk: Boolean = true,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        if (RELEASE_ASSET_PREFIXES.none { update.downloadUrl.startsWith(it) }) {
            throw IOException(context.getString(R.string.ci_update_request_failed))
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it in SUPPORTED_ABIS }
            ?: throw IOException(context.getString(R.string.ci_update_unsupported_arch))
        val apk = File.createTempFile("manager-ci-", ".apk", context.cacheDir)
        try {
            download(update.downloadUrl, apk, onProgress)
            verifyApk(apk, abi, update)
            if (keepApk) {
                saveToDownloads(apk, update.downloadUrl)
            }
            if (getRootShell().isRoot) {
                val result = withNewRootShell {
                    if (!isRoot) throw IOException(context.getString(R.string.ci_update_install_failed))
                    // Stream via stdin: system_server cannot open an app-private cache path on SELinux devices.
                    newJob().add("cat ${apk.absolutePath.shellQuote()} | pm install -r -S ${apk.length()}").exec()
                }
                if (!result.isSuccess) {
                    throw IOException((result.err + result.out).joinToString("\n").ifBlank {
                        context.getString(R.string.ci_update_install_failed)
                    })
                }
                return true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    throw IOException(context.getString(R.string.ci_update_install_permission))
                }
                submitInstall(apk)
                return false
            }
        } finally {
            apk.delete()
        }
    }

    private fun saveToDownloads(apk: File, url: String) {
        val name = url.toUri().lastPathSegment
            ?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.endsWith(".apk") } ?: "SukiSU_Nova-update.apk"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create APK in Downloads")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot write APK to Downloads")
                output.use { out -> apk.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) throw IOException("Cannot publish APK in Downloads")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create Downloads directory")
            // Never overwrite another user's download; an incomplete copy is deleted on failure.
            var destination = File(directory, name)
            var index = 1
            while (!destination.createNewFile()) {
                destination = File(directory, "${name.removeSuffix(".apk")} (${index++}).apk")
            }
            try {
                apk.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath),
                arrayOf("application/vnd.android.package-archive"), null)
        }
    }

    /**
     * A dedicated client is required: the shared one inherits a zero read timeout, so a
     * stalled CDN connection blocks the transfer forever instead of being retried.
     */
    private fun httpClient(): OkHttpClient = ksuApp.okhttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Ranged workers must not inherit a per-call deadline: one megabyte over a throttled
     * connection can legitimately take a minute, and the stall watchdog below is what
     * decides a connection is dead, not a fixed timeout.
     */
    private fun rangedClient(): OkHttpClient = ranged ?: synchronized(this) {
        ranged ?: ksuApp.okhttpClient.newBuilder()
            // Workers reuse their sockets instead of a TLS handshake per chunk.
            .connectionPool(okhttp3.ConnectionPool(PARALLEL_CONNECTIONS, 90, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .also { ranged = it }
    }

    private var ranged: OkHttpClient? = null

    /** The repair pass may crawl for a while on one slow socket; wait it out. */
    private fun repairClient(): OkHttpClient = repair ?: synchronized(this) {
        repair ?: ksuApp.okhttpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .also { repair = it }
    }

    private var repair: OkHttpClient? = null

    private fun download(url: String, destination: File, onProgress: ((Float) -> Unit)? = null) {
        // The CDN caps every single connection (measured 40 KB/s on one, 356 KB/s at 8,
        // 1.4 MB/s at 32), so ranged workers are the only way to use the link.
        val total = probeSize(url)
        if (total >= PARALLEL_MIN_BYTES && total <= MAX_APK_BYTES) {
            // Keep the partial file between attempts: deleting it here is what turned a
            // throttled tail into a restart from zero last time.
            repeat(PARALLEL_ATTEMPTS) {
                if (runCatching { downloadParallel(url, destination, total, onProgress) }.isSuccess) {
                    return
                }
            }
            destination.delete()
        }
        downloadSingle(url, destination, total, onProgress)
    }

    /** Total byte length from a one-byte ranged probe, or -1 when ranges are unsupported. */
    private fun probeSize(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            httpClient().newCall(request).execute().use { response ->
                if (response.code != 206) return@use -1L
                val range = response.header("Content-Range").orEmpty()
                range.substringAfterLast('/', "").toLongOrNull() ?: -1L
            }
        }.getOrDefault(-1L)
    }

    private class Tracker {
        @Volatile var call: okhttp3.Call? = null
        @Volatile var bytes = 0L
        var checkBytes = 0L
        var checkTs = SystemClock.elapsedRealtime()
        var startedTs = SystemClock.elapsedRealtime()
        var stalls = 0
    }

    /**
     * Pulls [from]..[end] incl. into [destination], reporting progress as it lands. Throws
     * when the connection dies, is cancelled, or comes up short; the caller requeues the
     * unsent remainder instead of restarting the whole file.
     */
    private fun fetchRange(
        url: String,
        destination: File,
        from: Long,
        end: Long,
        tracker: Tracker,
        got: AtomicLong,
        client: OkHttpClient? = null,
        onBytes: (Long) -> Unit,
    ) {
        val expected = end - from + 1
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$end")
            .build()
        val call = (client ?: rangedClient()).newCall(request)
        tracker.call = call
        tracker.bytes = 0
        tracker.checkBytes = 0
        tracker.stalls = 0
        tracker.startedTs = SystemClock.elapsedRealtime()
        tracker.checkTs = tracker.startedTs
        try {
            call.execute().use { response ->
                if (response.code != 206) {
                    throw IOException(context.getString(R.string.ci_update_http_error, response.code))
                }
                // Each worker owns its handle: concurrent seeks on one RandomAccessFile
                // would interleave the offsets.
                RandomAccessFile(destination, "rw").use { output ->
                    output.seek(from)
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (written < expected) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val room = expected - written
                        val use = if (read > room) room.toInt() else read
                        output.write(buffer, 0, use)
                        written += use
                        tracker.bytes += use
                        got.set(written)
                        onBytes(use.toLong())
                        if (use < read) break
                    }
                    output.fd.sync()
                }
            }
        } finally {
            tracker.call = null
        }
    }

    /** Sequential fallback: resumes the same way, one connection at a time. */
    private fun downloadRange(
        url: String,
        destination: File,
        start: Long,
        end: Long,
        client: OkHttpClient? = null,
        onBytes: (Long) -> Unit,
    ) {
        val expected = end - start + 1
        var written = 0L
        var attempts = 0
        val tracker = Tracker()
        while (written < expected) {
            val got = AtomicLong(0)
            try {
                fetchRange(url, destination, start + written, end, tracker, got, client, onBytes)
                written += got.get()
                attempts = 0
            } catch (e: IOException) {
                written += got.get()
                if (++attempts > RANGE_RETRIES) throw e
                Thread.sleep((500L * attempts).coerceAtMost(3_000L))
            }
            if (got.get() == 0L && written < expected && attempts == 0) {
                throw IOException("short read: $written of $expected")
            }
        }
        if (written != expected) throw IOException("short read: $written of $expected")
    }

    /**
     * Workers take one-megabyte chunks off a shared queue instead of owning a fixed slice.
     * A throttled connection therefore only ever holds the chunk it is on: when it crawls,
     * the watchdog cancels it and the unsent remainder goes back to the queue for another
     * connection, so the tail of the file is never left to a single crawler.
     */
    private fun downloadParallel(
        url: String,
        destination: File,
        total: Long,
        onProgress: ((Float) -> Unit)?,
    ) {
        val chunks = ((total + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val nextChunk = AtomicInteger(0)
        val retryQueue = ConcurrentLinkedQueue<LongArray>()
        val inFlight = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val written = AtomicLong(0)
        // Bytes still missing per base chunk: whatever is left here after the parallel
        // pass is fetched again on a single connection, which is what survives a NAT or
        // Wi-Fi path that stops accepting new connections near the end of the file.
        val chunkMissing = AtomicLongArray(chunks).also { array ->
            for (i in 0 until chunks) {
                val start = i.toLong() * CHUNK_BYTES
                array.set(i, min(start + CHUNK_BYTES, total) - start)
            }
        }
        val failure = AtomicReference<Throwable?>(null)
        val trackers = ConcurrentHashMap<Int, Tracker>()
        val workers = min(PARALLEL_CONNECTIONS, chunks).coerceAtLeast(1)
        // When the whole fleet stops making progress — the path starts refusing new
        // connections mid-file — the parallel pass is abandoned and the gaps are pulled
        // on one connection instead of retrying forever at a few bytes a minute.
        val aborted = AtomicBoolean(false)
        val lastProgressTs = AtomicLong(SystemClock.elapsedRealtime())
        var lastReported = -1

        onProgress?.invoke(0f)

        fun report(count: Long) {
            val done = written.addAndGet(count)
            lastProgressTs.set(SystemClock.elapsedRealtime())
            if (onProgress == null) return
            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
            if (percent != lastReported) {
                lastReported = percent
                onProgress(percent / 100f)
            }
        }

        fun takeTask(): LongArray? {
            retryQueue.poll()?.let { return it }
            val index = nextChunk.get()
            if (index >= chunks) return null
            if (!nextChunk.compareAndSet(index, index + 1)) return takeTask()
            val start = index.toLong() * CHUNK_BYTES
            return longArrayOf(start, min(start + CHUNK_BYTES - 1, total - 1), 0L, index.toLong())
        }

        fun requeue(from: Long, end: Long, attempts: Int, chunk: Int) {
            if (failure.get() != null || aborted.get() || from > end) return
            val length = end - from + 1
            // A region that keeps dropping its connection is served to us a few KB at
            // a time, so cut it in half: the pieces ran on separate connections in
            // parallel is what actually gets the tail moving.
            if (length > MIN_SPLIT_BYTES) {
                val middle = from + length / 2
                retryQueue.add(longArrayOf(from, middle - 1, (attempts + 1).toLong(), chunk.toLong()))
                retryQueue.add(longArrayOf(middle, end, (attempts + 1).toLong(), chunk.toLong()))
                return
            }
            if (attempts + 1 > RANGE_RETRIES) {
                failure.compareAndSet(null, IOException("short read: $from..$end"))
                return
            }
            retryQueue.add(longArrayOf(from, end, (attempts + 1).toLong(), chunk.toLong()))
        }

        val watchdog = Thread {
            var windowTs = SystemClock.elapsedRealtime()
            var windowBytes = 0L
            while (failure.get() == null && written.get() < total && finished.get() < workers) {
                Thread.sleep(STALL_CHECK_MS)
                val now = SystemClock.elapsedRealtime()
                if (now - windowTs >= STALL_ABORT_MS) {
                    val gained = written.get() - windowBytes
                    windowBytes = written.get()
                    windowTs = now
                    // A trickle of a few KB per window is as dead as silence: hand the
                    // rest to the single-connection repair pass instead of starving.
                    if (gained < STALL_ABORT_BYTES) {
                        aborted.set(true)
                        trackers.forEach { (_, tracker) -> tracker.call?.cancel() }
                        break
                    }
                }
                trackers.forEach { (_, tracker) ->
                    if (now - tracker.startedTs < STALL_GRACE_MS) return@forEach
                    if (now - tracker.checkTs < STALL_CHECK_MS) return@forEach
                    val delta = tracker.bytes - tracker.checkBytes
                    tracker.checkBytes = tracker.bytes
                    tracker.checkTs = now
                    // A single quiet window is normal on a throttled link; only a call
                    // that stays under the floor twice in a row counts as dead.
                    if (delta < STALL_MIN_BYTES) {
                        tracker.stalls += 1
                        if (tracker.stalls >= 2) {
                            tracker.stalls = 0
                            tracker.call?.cancel()
                        }
                    } else {
                        tracker.stalls = 0
                    }
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        repeat(workers) { slot ->
            Thread {
                val tracker = Tracker()
                trackers[slot] = tracker
                try {
                    while (failure.get() == null && !aborted.get()) {
                        val task = takeTask()
                        if (task == null) {
                            // Another worker may still come back with a remainder.
                            if (inFlight.get() == 0) break
                            Thread.sleep(25)
                            continue
                        }
                        val from = task[0]
                        val end = task[1]
                        val attempts = task[2].toInt()
                        val chunk = task[3].toInt()
                        inFlight.incrementAndGet()
                        val got = AtomicLong(0)
                        var crashed = false
                        try {
                            fetchRange(url, destination, from, end, tracker, got, null, ::report)
                        } catch (e: IOException) {
                            if (failure.get() != null) throw e
                            crashed = true
                            requeue(from + got.get(), end, attempts, chunk)
                        } finally {
                            inFlight.decrementAndGet()
                            chunkMissing.addAndGet(chunk, -got.get())
                        }
                        if (!crashed && got.get() < end - from + 1) {
                            requeue(from + got.get(), end, attempts, chunk)
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    tracker.call = null
                    trackers.remove(slot)
                    finished.incrementAndGet()
                }
            }.start()
        }

        while (finished.get() < workers && failure.get() == null) {
            Thread.sleep(50)
        }
        failure.get()?.let { throw IOException(context.getString(R.string.ci_update_download_failed), it) }

        // Whatever the parallel pass could not land gets a quieter second round: far
        // fewer connections, no stall cancels, so a link that is throttling instead of
        // dead still finishes the file.
        if (written.get() < total) {
            val repairQueue = ConcurrentLinkedQueue<Int>()
            for (index in 0 until chunks) {
                if (chunkMissing.get(index) > 0) repairQueue.add(index)
            }
            val repairWorkers = min(REPAIR_CONNECTIONS, repairQueue.size).coerceAtLeast(1)
            val repairDone = CountDownLatch(repairWorkers)
            val repairError = AtomicReference<Throwable?>(null)
            repeat(repairWorkers) {
                Thread {
                    try {
                        while (repairError.get() == null) {
                            val index = repairQueue.poll() ?: break
                            val start = index.toLong() * CHUNK_BYTES
                            val end = min(start + CHUNK_BYTES - 1, total - 1)
                            downloadRange(url, destination, start, end, repairClient()) { count ->
                                report(count)
                            }
                            chunkMissing.set(index, 0)
                        }
                    } catch (e: Throwable) {
                        repairError.compareAndSet(null, e)
                    } finally {
                        repairDone.countDown()
                    }
                }.start()
            }
            repairDone.await()
            repairError.get()?.let {
                throw IOException(context.getString(R.string.ci_update_download_failed), it)
            }
        }
        if (destination.length() < total) {
            throw IOException("short read: ${destination.length()} of $total")
        }
        onProgress?.invoke(1f)
    }

    private fun downloadSingle(
        url: String,
        destination: File,
        total: Long,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        if (total > 0) {
            var lastReported = -1
            downloadRange(url, destination, 0, total - 1) { count ->
                if (onProgress == null) return@downloadRange
                val percent = ((destination.length() * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(percent / 100f)
                }
            }
            onProgress?.invoke(1f)
            return
        }
        // Unknown length: plain GET, no resume, still bounded by the client timeouts.
        httpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(context.getString(R.string.ci_update_http_error, response.code))
            }
            val body = response.body
            onProgress?.invoke(0f)
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > MAX_APK_BYTES) {
                            throw IOException(context.getString(R.string.ci_update_archive_too_large))
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            onProgress?.invoke(1f)
        }
    }

    private fun verifyApk(apk: File, abi: String, update: LatestVersionInfo) {
        val candidate = archivePackageInfo(apk)
            ?: throw IOException(context.getString(R.string.ci_update_invalid_apk))
        if (candidate.packageName != BuildConfig.APPLICATION_ID || abi !in apkAbis(apk)) {
            throw IOException(context.getString(R.string.ci_update_invalid_apk))
        }
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, packageInfoFlags())
        } catch (e: Exception) {
            throw IOException(context.getString(R.string.ci_update_invalid_apk), e)
        }
        // Our builds all carry the same versionCode, so when the release provides a version name
        // the comparison has to follow that instead.
        if (update.versionName.isNotBlank()) {
            if (candidate.versionName != update.versionName ||
                !isNewerVersionName(candidate.versionName.orEmpty(), installed.versionName.orEmpty())
            ) {
                throw IOException(context.getString(R.string.ci_update_not_newer))
            }
        } else {
            if (candidate.longVersionCodeCompat() != update.versionCode) {
                throw IOException(context.getString(R.string.ci_update_invalid_apk))
            }
            if (candidate.longVersionCodeCompat() <= installed.longVersionCodeCompat()) {
                throw IOException(context.getString(R.string.ci_update_not_newer))
            }
        }
        if (!sameSignature(candidate, installed)) {
            throw IOException(context.getString(R.string.ci_update_signature_mismatch))
        }
    }

    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    }

    private fun archivePackageInfo(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, packageInfoFlags())

    private fun apkAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        val entries = zip.entries()
        buildSet {
            while (entries.hasMoreElements()) {
                val parts = entries.nextElement().name.split('/')
                if (parts.size >= 3 && parts[0] == "lib" && parts[1] in SUPPORTED_ABIS) add(parts[1])
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun sameSignature(candidate: PackageInfo, installed: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signers = candidate.signingInfo?.apkContentsSigners ?: return false
            return signers.isNotEmpty() && signers.all { signer ->
                context.packageManager.hasSigningCertificate(
                    context.packageName,
                    signer.toByteArray(),
                    PackageManager.CERT_INPUT_RAW_X509,
                )
            }
        }
        val candidateSignatures = candidate.signatures ?: return false
        val installedSignatures = installed.signatures ?: return false
        return candidateSignatures.isNotEmpty() && candidateSignatures.all { signature ->
            installedSignatures.any { it == signature }
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private fun submitInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
        )
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("manager.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, CiPackageInstallReceiver::class.java)
                    .setAction(CiPackageInstallReceiver.ACTION_INSTALL_RESULT)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE
                } else 0
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
                session.commit(pending.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(sessionId)
        }
    }

    companion object {
        private val RELEASE_ASSET_PREFIXES = listOf(
            "https://applemac13.github.io/SukiSU-Next/apks/",
            "https://github.com/AppleMac13/SukiSU-Next/releases/download/",
            "https://git.zako.shirkneko.top:20004/shirkneko/SukiSU/releases/download/",
        )
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        // The CDN throttles each connection, not the host, so ranged workers stack up.
        private const val PARALLEL_CONNECTIONS = 16
        private const val PARALLEL_ATTEMPTS = 2
        private const val CHUNK_BYTES = 512L * 1024
        private const val MIN_SPLIT_BYTES = 6L * 1024
        private const val REPAIR_CONNECTIONS = 8
        private const val STALL_CHECK_MS = 2_500L
        private const val STALL_GRACE_MS = 8_000L
        private const val STALL_MIN_BYTES = 8L * 1024
        private const val STALL_ABORT_MS = 20_000L
        private const val STALL_ABORT_BYTES = 200L * 1024
        private const val PARALLEL_MIN_BYTES = 2L * 1024 * 1024
        private const val RANGE_RETRIES = 10
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64")
    }
}
