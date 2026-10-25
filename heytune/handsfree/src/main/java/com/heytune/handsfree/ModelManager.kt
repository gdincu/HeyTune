package com.heytune.handsfree

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads + loads the Cactus model files on first use.
 *
 * Weights are NOT bundled in the APK (~45MB). They are fetched once from
 * Hugging Face into app filesDir and mmap'd by the engine:
 *  - whistle.cact  16.9 MB  https://huggingface.co/Cactus-Compute/whistle
 *  - needle3.cact  ~29 MB   https://huggingface.co/Cactus-Compute/needle3
 */
object ModelManager {

    private const val TAG = "HandsFreeModels"

    const val WHISTLE_FILE = "whistle.cact"
    const val NEEDLE_FILE = "needle3.cact"
    const val WHISTLE_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact"
    const val NEEDLE_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact"

    fun dir(context: Context): File = File(context.filesDir, "handsfree").apply { mkdirs() }
    fun whistleFile(context: Context): File = File(dir(context), WHISTLE_FILE)
    fun needleFile(context: Context): File = File(dir(context), NEEDLE_FILE)

    fun modelsReady(context: Context): Boolean =
        whistleFile(context).exists() && needleFile(context).exists()

    /**
     * Load both models into the engine. Returns false when files are missing
     * or this ABI has no prebuilt engine (x86 emulators).
     */
    fun ensureLoaded(context: Context, toolsJson: String): Boolean {
        val w = whistleFile(context)
        val n = needleFile(context)
        if (!w.exists() || !n.exists()) return false
        return try {
            NeedleBridge.load(w.absolutePath, n.absolutePath, toolsJson)
        } catch (e: Exception) {
            Log.w(TAG, "engine load failed", e)
            false
        }
    }

    /**
     * Download any missing .cact files. Progress is 0..1 across both files.
     * Throws on network errors; leaves any .part file for resume on next run.
     */
    suspend fun downloadMissing(
        context: Context,
        onProgress: (fraction: Float, file: String) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        downloadOne(WHISTLE_URL, whistleFile(context), 0f, 0.4f, onProgress)
        downloadOne(NEEDLE_URL, needleFile(context), 0.4f, 1f, onProgress)
    }

    private fun downloadOne(
        url: String,
        dest: File,
        rangeStart: Float,
        rangeEnd: Float,
        onProgress: (Float, String) -> Unit,
    ) {
        if (dest.exists() && dest.length() > 0) {
            onProgress(rangeEnd, dest.name)
            return
        }
        val tmp = File(dest.parent, dest.name + ".part")
        var downloaded = if (tmp.exists()) tmp.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val total = conn.contentLengthLong.let { if (it > 0) it + downloaded else -1L }
            Log.i(TAG, "downloading ${dest.name} (${total / 1048576} MB)")
            conn.inputStream.use { input ->
                FileOutputStream(tmp, downloaded > 0).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onProgress(rangeStart + (rangeEnd - rangeStart) * (downloaded.toFloat() / total), dest.name)
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            onProgress(rangeEnd, dest.name)
        } finally {
            conn.disconnect()
        }
    }
}
