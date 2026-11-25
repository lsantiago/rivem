package org.rivem.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * App log: Logcat (tag `RIVEM`) plus a text file in `<files>/logs/rivem.log` that the user can export from the
 * Diagnostics screen (no computer needed to debug). When the file passes 2 MB it is moved to `rivem.old.log`.
 */
object AppLog {
    private const val TAG = "RIVEM"
    private const val MAX_BYTES: Long = 2_000_000
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rivem-applog").apply { isDaemon = true } }

    @Volatile
    private var folderRef: File? = null

    /** `<files>/logs` (set by [init] at application start). */
    val folder: File get() = folderRef ?: throw IllegalStateException("AppLog.init no fue llamado")
    val file: File get() = File(folder, "rivem.log")
    val previousFile: File get() = File(folder, "rivem.old.log")

    fun init(context: Context) {
        folderRef = File(context.applicationContext.filesDir, "logs")
    }

    fun info(message: String) {
        Log.i(TAG, message)
        append("INFO", message)
    }

    fun error(message: String) {
        Log.e(TAG, message)
        append("ERROR", message)
    }

    private fun append(level: String, message: String) {
        val dir = folderRef ?: return
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val line = "${format.format(Date())} [$level] $message\n"
        executor.execute {
            try {
                dir.mkdirs()
                val f = File(dir, "rivem.log")
                if (f.length() > MAX_BYTES) {
                    val old = File(dir, "rivem.old.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /** Waits until pending lines are on disk. */
    fun flush() {
        try {
            executor.submit {}.get()
        } catch (_: Exception) {
        }
    }

    /** Last [maxCharacters] of the log file. */
    fun tail(maxCharacters: Int = 60_000): String {
        flush()
        val f = folderRef?.let { File(it, "rivem.log") } ?: return ""
        if (!f.exists()) return ""
        val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { return "" }
        return if (text.length <= maxCharacters) text else text.takeLast(maxCharacters)
    }

    fun clear() {
        try {
            executor.submit {
                folderRef?.let {
                    File(it, "rivem.log").delete()
                    File(it, "rivem.old.log").delete()
                }
            }.get()
        } catch (_: Exception) {
        }
    }

    /** `versionName (versionCode)` of the installed app. */
    fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "?"} (${info.longVersionCode})"
    } catch (_: Exception) {
        "?"
    }

    /** Device and app information written at launch. */
    fun logLaunch(context: Context) {
        val cores = Runtime.getRuntime().availableProcessors()
        val memory = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem.toDouble() / 1_073_741_824
        } catch (_: Exception) {
            0.0
        }
        info(
            "RIVEM ${appVersion(context)}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, $cores núcleos, ${String.format(Locale.ROOT, "%.1f", memory)} GB",
        )
    }
}
