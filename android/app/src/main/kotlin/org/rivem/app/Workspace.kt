package org.rivem.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.documentfile.provider.DocumentFile
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.PipelineError
import org.rivem.core.project.Footage
import org.rivem.core.project.ProjectError
import org.rivem.core.project.ProjectStage
import org.rivem.core.project.RiverProject
import org.rivem.core.project.RiverWorkspace
import java.io.File
import java.io.FileOutputStream

/** Summary of a project folder for the list (read once, cheap to display). */
class ProjectSummary(val folder: File) {
    val name: String = folder.absoluteFile.parentFile?.name ?: ""
    val stamp: String = folder.name
    var footage: Footage? = null
        private set
    var stage: ProjectStage = ProjectStage.created
        private set
    var riverName: String? = null
        private set
    var site: String? = null
        private set
    var sectionCount: Int = 0
        private set
    var issues: List<String> = emptyList()
        private set
    var loadError: String? = null
        private set

    val id: String get() = folder.path

    init {
        try {
            val p = RiverProject.open(folder)
            footage = p.settings.footage
            stage = p.settings.stage
            riverName = p.settings.riverName
            site = p.settings.site
            sectionCount = try { p.readCrossSections()?.sections?.size ?: 0 } catch (_: Exception) { 0 }
            issues = p.compatibilityIssues().map { it.message }
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        }
    }

    override fun equals(other: Any?): Boolean = other is ProjectSummary && other.folder == folder
    override fun hashCode(): Int = folder.hashCode()

    /** "sitio · nombre" if set, then the stamp and the stage reached. */
    val subtitle: String
        get() {
            val parts = ArrayList<String>()
            site?.let { if (it.isNotEmpty()) parts.add(it) }
            if (riverName != null) parts.add(name)
            parts.add(stamp)
            parts.add(stage.label)
            return parts.joinToString(" · ")
        }
}

/**
 * Projects live in `<external files>/RIVeR/<name>/<YYYYMMDDTHHMM>/`, the same layout as RIVeR desktop's workspace.
 * The folder is `Android/data/org.rivem.app/files/RIVeR` on the phone's storage, reachable over USB from a computer,
 * so projects can be copied either way.
 */
class Workspace(val root: File) {
    var projects: List<ProjectSummary> by mutableStateOf(emptyList())
        private set
    var lastError: String? by mutableStateOf(null)

    init {
        root.mkdirs()
        reload()
    }

    fun reload() {
        projects = RiverWorkspace.projectFolders(root).map { ProjectSummary(it) }
    }

    /**
     * Copies a project picked with the system folder picker (Storage Access Framework) into the workspace. Accepts a
     * dated project folder (with `settings.json`) or a project-name folder that contains dated folders. Returns how
     * many were imported. Blocking: call it off the main thread.
     */
    fun importProjects(context: Context, tree: Uri): Int {
        val picked = DocumentFile.fromTreeUri(context, tree) ?: throw PipelineError("No se pudo abrir la carpeta elegida.")
        val segments = treePath(tree)
        val pickedName = picked.name ?: segments.lastOrNull() ?: "Importado"
        // (name folder in the workspace, dated folder to copy)
        val sources = ArrayList<Pair<String, DocumentFile>>()
        if (picked.findFile(RiverProject.settingsFile) != null) {
            val parentName = if (segments.size >= 2) segments[segments.size - 2] else "Importado"
            sources.add(parentName to picked)
        } else {
            for (child in picked.listFiles()) {
                if (child.isDirectory && child.findFile(RiverProject.settingsFile) != null) sources.add(pickedName to child)
            }
        }
        if (sources.isEmpty()) throw ProjectError.NoSettingsFile()
        for ((name, src) in sources) {
            val nameFolder = File(root, name)
            nameFolder.mkdirs()
            val stamp = src.name ?: "importado"
            var dest = File(nameFolder, stamp)
            var n = 2
            while (dest.exists()) {
                dest = File(nameFolder, "$stamp-$n")
                n += 1
            }
            try {
                copyTree(context, src, dest)
            } catch (e: Exception) {
                dest.deleteRecursively()
                throw e
            }
            // Desktop needs frames/ even when empty; some copies (e.g. through git or zip tools) drop empty folders.
            File(dest, RiverProject.framesFolder).mkdirs()
            AppLog.info("Proyecto importado: $name/${dest.name}")
        }
        reload()
        return sources.size
    }

    /** Path segments of a tree URI's document id (`primary:Download/RIVeR/rio/20250101T1200`), for folder names. */
    private fun treePath(tree: Uri): List<String> = try {
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').split('/').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun copyTree(context: Context, src: DocumentFile, dest: File) {
        if (!dest.mkdirs() && !dest.isDirectory) throw PipelineError("No se pudo crear ${dest.path}")
        for (child in src.listFiles()) {
            val name = child.name ?: continue
            val target = File(dest, name)
            if (child.isDirectory) {
                copyTree(context, child, target)
            } else {
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw PipelineError("No se pudo leer $name.")
                input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
            }
        }
    }

    fun delete(project: ProjectSummary) {
        if (!project.folder.deleteRecursively()) throw PipelineError("No se pudo borrar ${project.folder.name}.")
        val parent = project.folder.absoluteFile.parentFile
        if (parent != null && parent.list()?.isEmpty() == true) parent.delete()
        AppLog.info("Proyecto eliminado: ${project.name}/${project.stamp}")
        reload()
    }
}

val Footage.label: String
    get() = when (this) {
        Footage.uav -> "Dron (UAV)"
        Footage.oblique -> "Oblicua"
        Footage.ipcam -> "Estación fija (3D)"
    }

val Footage.icon: ImageVector
    get() = when (this) {
        Footage.uav -> Icons.Filled.Flight
        Footage.oblique -> Icons.Filled.PhotoCamera
        Footage.ipcam -> Icons.Filled.Videocam
    }

val ProjectStage.label: String
    get() = when (this) {
        ProjectStage.created -> "Creado"
        ProjectStage.frames -> "Frames extraídos"
        ProjectStage.geometry -> "Georreferenciado"
        ProjectStage.crossSections -> "Secciones definidas"
        ProjectStage.results -> "Con resultados"
    }
