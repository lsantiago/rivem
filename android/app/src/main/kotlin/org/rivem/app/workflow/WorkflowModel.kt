package org.rivem.app.workflow

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.rivem.app.media.ImageCoding
import org.rivem.app.pipeline.PipelineError
import org.rivem.app.pipeline.ProjectFiles
import org.rivem.app.pipeline.UnitDisplay
import org.rivem.core.project.Footage
import org.rivem.core.project.ProjectSettings
import org.rivem.core.project.RiverProject
import org.rivem.core.project.UnitSystem
import java.io.File

/**
 * Steps of the guided workflow, mirroring desktop's wizard (Footage → Video Range → Pixel Size / Control Points →
 * Cross Sections → Processing → Results → Report).
 */
@Suppress("EnumEntryName")
enum class WorkflowStep {
    summary, video, geometry, sections, processing, results, export;

    val id: String get() = name
    val rawValue: String get() = name

    val title: String
        get() = when (this) {
            summary -> "Resumen del proyecto"
            video -> "1 · Video y frames"
            geometry -> "2 · Georreferenciación"
            sections -> "3 · Secciones transversales"
            processing -> "4 · Procesamiento PIV"
            results -> "5 · Resultados"
            export -> "6 · Exportar"
        }

    /** Name for the wizard's previous / next buttons. */
    val shortTitle: String
        get() = when (this) {
            summary -> "Resumen"
            video -> "Video"
            geometry -> "Georreferenciación"
            sections -> "Secciones"
            processing -> "PIV"
            results -> "Resultados"
            export -> "Exportar"
        }

    /** Label under the stepper circle (fits a sixth of a phone width). */
    val stepperTitle: String
        get() = when (this) {
            summary -> "Resumen"
            video -> "Video"
            geometry -> "Georref."
            sections -> "Secciones"
            processing -> "PIV"
            results -> "Resultados"
            export -> "Exportar"
        }

    val icon: ImageVector
        get() = when (this) {
            summary -> Icons.Filled.FindInPage
            video -> Icons.Filled.Movie
            geometry -> Icons.Filled.Straighten
            sections -> Icons.AutoMirrored.Filled.ShowChart
            processing -> Icons.Filled.Air
            results -> Icons.Filled.WaterDrop
            export -> Icons.Filled.Share
        }

    companion object {
        /** Steps of the wizard bar, in order (the summary is reached from the step list). */
        val wizard: List<WorkflowStep> = listOf(video, geometry, sections, processing, results, export)

        fun fromRawValue(raw: String?): WorkflowStep? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * State of one project for the workflow screens. Every step writes to the project folder in desktop format through
 * the pipeline types and then calls [reload], so what is shown is always what is on disk.
 *
 * Scoped to the project's navigation entry (`Routes.PROJECT`): the step list and the step screens share it. All
 * fields are Compose state; [revision] is bumped on every reload so screens that read files can key effects on it.
 */
class WorkflowModel(val folder: File) : ViewModel() {
    /** Reassigned on every reload (never equal), so readers recompose even when it is the same object. */
    var project: RiverProject? by mutableStateOf<RiverProject?>(null, neverEqualPolicy())
        private set
    var loadError: String? by mutableStateOf(null)
        private set
    var frameCount: Int by mutableIntStateOf(0)
        private set

    /** Downscaled first frame for display. */
    var firstFrame: ImageBitmap? by mutableStateOf(null)
        private set

    /** Full-resolution size of the frames (pixel space of the geometry files). */
    var frameSize: IntSize by mutableStateOf(IntSize.Zero)
        private set
    var hasMatrix: Boolean by mutableStateOf(false)
        private set
    var hasSections: Boolean by mutableStateOf(false)
        private set
    var hasMask: Boolean by mutableStateOf(false)
        private set
    var hasResults: Boolean by mutableStateOf(false)
        private set
    var hasVideo: Boolean by mutableStateOf(false)
        private set
    var maskOverlay: ImageBitmap? by mutableStateOf(null)
        private set

    /** Bumped on every reload: views that read files (PIV field…) refresh with `LaunchedEffect(model.revision)`. */
    var revision: Int by mutableIntStateOf(0)
        private set
    private var thumbnailKey: String = ""

    init {
        reload()
    }

    val settings: ProjectSettings? get() = project?.settings
    val footage: Footage? get() = project?.settings?.footage
    val units: UnitDisplay get() = UnitDisplay(project?.settings?.unitSystem ?: UnitSystem.si)
    val displayName: String get() = project?.settings?.riverName ?: (folder.absoluteFile.parentFile?.name ?: folder.name)
    val siteName: String get() = folder.absoluteFile.parentFile?.name ?: ""

    fun reload() {
        revision += 1
        try {
            val p = RiverProject.open(folder)
            loadError = null
            val frames = p.frameFiles
            frameCount = frames.size
            val first = frames.firstOrNull()
            if (first != null) {
                val key = "${first.name}-${first.lastModified()}"
                if (key != thumbnailKey || firstFrame == null) {
                    thumbnailKey = key
                    firstFrame = ImageCoding.thumbnail(first)
                    val size = ImageCoding.pixelSize(first)
                    if (size != null) frameSize = IntSize(size.first, size.second)
                }
            } else {
                firstFrame = null
                frameSize = IntSize.Zero
                thumbnailKey = ""
            }
            hasMatrix = try { p.readMatrix() != null } catch (_: Exception) { false }
            hasSections = try { p.readCrossSections()?.sections?.isNotEmpty() == true } catch (_: Exception) { false }
            hasMask = ProjectFiles.exists(p, ProjectFiles.maskNPY) || ProjectFiles.exists(p, ProjectFiles.finalMaskNPY)
            hasResults = p.settings.pivResultsPath != null && ProjectFiles.exists(p, RiverProject.pivResultsFile)
            hasVideo = ProjectFiles.videoURL(p) != null
            val maskName = if (ProjectFiles.exists(p, ProjectFiles.finalMaskPNG)) ProjectFiles.finalMaskPNG else ProjectFiles.maskPNG
            maskOverlay = if (hasMask) ImageCoding.thumbnail(ProjectFiles.url(p, maskName)) else null
            project = p
        } catch (e: Exception) {
            project = null
            loadError = e.message ?: e.toString()
        }
    }

    /**
     * Applies a change to a copy of the project (pipeline call), then reloads from disk. Swift's
     * `update(_ change: (inout RiverProject) throws -> Void)`.
     */
    fun update(change: (RiverProject) -> Unit) {
        val p = project ?: throw PipelineError("El proyecto no está abierto.")
        val copy = RiverProject(p.folder, p.settings.copy())
        try {
            change(copy)
        } finally {
            reload()
        }
    }

    /** Replaces the project after a background computation, then reloads. */
    fun replace(p: RiverProject) {
        project = p
        reload()
    }

    fun isAvailable(step: WorkflowStep): Boolean {
        if (project == null) return step == WorkflowStep.summary
        return when (step) {
            WorkflowStep.summary, WorkflowStep.video, WorkflowStep.export -> true
            WorkflowStep.geometry -> frameCount > 0
            WorkflowStep.sections -> frameCount > 0 && hasMatrix
            WorkflowStep.processing -> frameCount > 1 && hasSections
            WorkflowStep.results -> hasResults && hasSections
        }
    }

    fun isDone(step: WorkflowStep): Boolean = when (step) {
        WorkflowStep.summary, WorkflowStep.export -> false
        WorkflowStep.video -> frameCount > 0 && project?.settings?.videoRange != null
        WorkflowStep.geometry -> hasMatrix
        WorkflowStep.sections -> hasSections && hasMask
        WorkflowStep.processing, WorkflowStep.results -> hasResults
    }

    /** The step to continue with when the project is opened. */
    val suggestedStep: WorkflowStep?
        get() {
            if (project == null) return null
            val order = listOf(WorkflowStep.video, WorkflowStep.geometry, WorkflowStep.sections, WorkflowStep.processing, WorkflowStep.results)
            for (s in order) if (isAvailable(s) && !isDone(s)) return s
            if (hasResults) return WorkflowStep.results
            return null
        }

    /** Short message for a step that is not available yet. */
    fun requirement(step: WorkflowStep): String = when (step) {
        WorkflowStep.geometry -> "Primero extrae los frames del video."
        WorkflowStep.sections -> "Primero completa la georreferenciación."
        WorkflowStep.processing -> "Primero define al menos una sección."
        WorkflowStep.results -> "Primero ejecuta el procesamiento PIV."
        else -> ""
    }

    companion object {
        fun factory(folder: File): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkflowModel(folder) }
        }
    }
}
