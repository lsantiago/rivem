package org.rivem.app

import android.content.Context
import android.content.Intent
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.pipeline.ExampleAutotest
import org.rivem.app.workflow.WorkflowStep
import java.io.File

/**
 * CI hooks (iOS `Autotest`), read from the launch intent's extras:
 *
 * - `--ez autotest_example true`: installs RIVeR's pisco example, runs PIV + discharge + quality + PDF + site checks
 *   ([ExampleAutotest]) in the background and writes `<external files>/autotest_summary.txt` (one `PASS`/`FAIL` line
 *   per check) and `autotest_done` (`ok` / `fail`), read by `android/ci/emulator-tests.sh`.
 * - `--es autotest_open_project <name> --es autotest_step <step|list|site>`: opens the newest project of the name
 *   folder `<name>` at a workflow step (`WorkflowStep` raw values), its step list, or its site screen.
 * - `--ei autotest_station <n>`: Results opens with station n (1-based) of the first section selected.
 * - `--ez autotest_results_vectors true`: Results opens on the PIV field.
 * - `--es autotest_results_scroll quality`: Results scrolls to the quality section.
 * - `--ez showQuality true|false`: overrides the "Calidad e incertidumbre" preference for this launch.
 * - `--es appearance dark|light`: overrides the appearance for this launch (not saved).
 */
object Autotest {
    var isExampleRequested: Boolean = false
        private set
    var projectToOpen: String? = null
        private set
    var step: String? = null
        private set
    var stationToSelect: Int? = null
        private set
    var resultsVectors: Boolean = false
        private set
    var resultsScroll: String? = null
        private set
    var showQuality: Boolean? = null
        private set
    var appearance: AppAppearance? = null
        private set

    /** The project folder opened for a screenshot (consumed once by the navigation host). */
    var pendingOpen: Boolean = false

    val showStepList: Boolean get() = step == "list"
    val showSite: Boolean get() = step == "site"
    val stepToOpen: WorkflowStep? get() = WorkflowStep.fromRawValue(step)

    fun read(intent: Intent?) {
        val e = intent?.extras ?: return
        isExampleRequested = e.getBoolean("autotest_example", false)
        projectToOpen = e.getString("autotest_open_project")
        step = e.getString("autotest_step")
        stationToSelect = e.getInt("autotest_station", 0).takeIf { it > 0 }
            ?: e.getString("autotest_station")?.toIntOrNull()?.takeIf { it > 0 }
        resultsVectors = e.getBoolean("autotest_results_vectors", false)
        resultsScroll = e.getString("autotest_results_scroll")
        showQuality = if (e.containsKey("showQuality")) e.getBoolean("showQuality", false) else null
        appearance = e.getString("appearance")?.let { AppAppearance.fromRawValue(it) }
        pendingOpen = projectToOpen != null
        if (isExampleRequested || projectToOpen != null) {
            AppLog.info(
                "autotest: ejemplo $isExampleRequested, proyecto $projectToOpen, paso $step, estación $stationToSelect, " +
                    "vectores $resultsVectors, scroll $resultsScroll, calidad $showQuality, apariencia $appearance",
            )
        }
    }

    fun outputFolder(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** Blocking: runs the example checks and writes the summary files. Call it off the main thread. */
    fun runExample(context: Context, root: File) {
        val out = outputFolder(context)
        File(out, "autotest_done").delete()
        File(out, "autotest_summary.txt").delete()
        val lines: List<String> = try {
            ExampleAutotest.run(context, root)
        } catch (t: Throwable) {
            AppLog.error("autotest: $t")
            listOf("FAIL ejemplo ejecucion: $t")
        }
        for (line in lines) AppLog.info("autotest: $line")
        AppLog.flush()
        File(out, "autotest_summary.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        val ok = lines.isNotEmpty() && lines.all { it.startsWith("PASS") }
        File(out, "autotest_done").writeText(if (ok) "ok" else "fail", Charsets.UTF_8)
    }
}
