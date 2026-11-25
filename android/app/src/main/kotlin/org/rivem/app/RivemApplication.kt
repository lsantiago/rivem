package org.rivem.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.rivem.app.diagnostics.AppLog
import java.io.File

/** Process-wide state: the workspace of projects and the user's preferences (Swift `RIVEMApp`'s `@State`). */
class RivemApplication : Application() {
    lateinit var workspace: Workspace
        private set
    lateinit var preferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.logLaunch(this)
        preferences = AppPreferences(this)
        // Android/data/org.rivem.app/files/RIVeR: reachable over USB, like desktop's workspace folder.
        val base = getExternalFilesDir(null) ?: filesDir
        workspace = Workspace(File(base, "RIVeR"))
    }
}

/** System / light / dark appearance. */
@Suppress("EnumEntryName")
enum class AppAppearance {
    system, light, dark;

    val rawValue: String get() = name

    val label: String
        get() = when (this) {
            system -> "Sistema"
            light -> "Claro"
            dark -> "Oscuro"
        }

    companion object {
        const val storageKey = "appearance"
        fun fromRawValue(raw: String?): AppAppearance = entries.firstOrNull { it.name == raw } ?: system
    }
}

/** User settings in SharedPreferences (Swift `@AppStorage`), observable from Compose. */
class AppPreferences(context: Context) {
    companion object {
        /** "Calidad e incertidumbre" in Results and the report (Swift `QualityPipeline.enabledKey`). */
        const val showQualityKey = "showQuality"
    }

    private val prefs = context.getSharedPreferences("rivem", Context.MODE_PRIVATE)
    private val appearanceState = mutableStateOf(AppAppearance.fromRawValue(prefs.getString(AppAppearance.storageKey, null)))
    private val showQualityState = mutableStateOf(prefs.getBoolean(showQualityKey, false))

    var appearance: AppAppearance
        get() = appearanceState.value
        set(value) {
            appearanceState.value = value
            prefs.edit().putString(AppAppearance.storageKey, value.rawValue).apply()
        }

    /** CI override for one launch (`--ez showQuality`), not saved. */
    var showQualityOverride: Boolean? = null

    var showQuality: Boolean
        get() = showQualityOverride ?: showQualityState.value
        set(value) {
            showQualityOverride = null
            showQualityState.value = value
            prefs.edit().putBoolean(showQualityKey, value).apply()
        }
}
