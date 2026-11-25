package org.rivem.app.workflow

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rivem.app.ComingSoon
import org.rivem.app.ComingSoonScreen
import org.rivem.app.ProjectDetailView
import org.rivem.app.ProjectSummary
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.FormText
import org.rivem.app.common.LabeledContent
import org.rivem.app.label
import org.rivem.app.steps.geometry.GeometryStepView
import org.rivem.app.steps.sections.SectionsStepView
import org.rivem.app.steps.videorange.VideoRangeView
import org.rivem.core.RiverCore
import java.io.File

const val workflowFormatNote: String = "Cada paso se guarda en la carpeta del proyecto con el formato de RIVeR escritorio " +
    RiverCore.DESKTOP_COMPATIBILITY +
    ": copiando la carpeta al espacio de trabajo RIVeR del PC, el aforo se abre allí en el paso alcanzado."

/**
 * Guided workflow of one project: the list of steps with their state; opening a project jumps to the step to
 * continue with (once per visit of this screen).
 */
@Composable
fun WorkflowView(
    model: WorkflowModel,
    onBack: () -> Unit,
    openStep: (WorkflowStep) -> Unit,
    openSite: () -> Unit,
    newVisit: () -> Unit,
    autoOpen: Boolean = true,
) {
    var didAutoOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        model.reload()
        if (!didAutoOpen) {
            didAutoOpen = true
            val step = model.suggestedStep
            if (autoOpen && step != null && model.isAvailable(step)) openStep(step)
        }
    }

    Scaffold(topBar = { BackTopBar(model.displayName, onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            WorkflowHeader(model)
            SiteSection(model, openSite, newVisit)
            FormSection("Pasos") {
                WorkflowStep.entries.forEachIndexed { i, step ->
                    if (i > 0) FormDivider()
                    WorkflowStepRow(step, model) { openStep(step) }
                }
            }
            Text(
                workflowFormatNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun WorkflowHeader(model: WorkflowModel) {
    val error = model.loadError
    val s = model.settings
    FormSection {
        if (error != null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        } else if (s != null) {
            s.riverName?.takeIf { it.isNotEmpty() }?.let { LabeledContent("Río", it) }
            s.site?.takeIf { it.isNotEmpty() }?.let { LabeledContent("Sitio", it) }
            LabeledContent("Carpeta", model.siteName + "/" + model.folder.name)
            LabeledContent("Tipo de toma", s.footage?.label ?: "desconocido")
            LabeledContent("Etapa", s.stage.label)
            LabeledContent("Frames", "${model.frameCount}")
            if (!model.hasVideo) {
                FormText(
                    "El video no está en la carpeta del proyecto: se pueden usar los frames existentes pero no volver a extraerlos.",
                    small = true,
                )
            }
        }
    }
}

/** The site this aforo belongs to (`RIVeR/<name>/`): its rating curve and a new visit with the same configuration. */
@Composable
private fun SiteSection(model: WorkflowModel, openSite: () -> Unit, newVisit: () -> Unit) {
    FormSection(
        "Sitio · ${model.siteName}",
        footer = "Una visita nueva reutiliza la georreferenciación, las secciones, la batimetría y los parámetros PIV: " +
            "en campo solo grabas el video y lees el nivel.",
    ) {
        FormButton("Aforos y curva nivel–caudal", Icons.AutoMirrored.Filled.TrendingUp) { openSite() }
        if (model.hasMatrix && model.hasSections) {
            FormDivider()
            FormButton("Nueva visita con esta configuración", Icons.Filled.Cameraswitch) { newVisit() }
        }
    }
}

@Composable
private fun WorkflowStepRow(step: WorkflowStep, model: WorkflowModel, open: () -> Unit) {
    val available = model.isAvailable(step)
    val done = model.isDone(step)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = available, onClick = open)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            step.icon, contentDescription = null,
            tint = if (available) MaterialTheme.colorScheme.primary else secondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, color = if (available) MaterialTheme.colorScheme.onSurface else secondary)
            if (!available) Text(model.requirement(step), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        if (done) Icon(Icons.Filled.CheckCircle, contentDescription = "completado", tint = Color(0xFF34C759))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = secondary.copy(alpha = 0.5f),
        )
    }
}

/**
 * The screen of one step, as a wizard like desktop's: a numbered stepper on top (tap a step to jump), previous / next
 * at the bottom (next is highlighted once the step is done, and says what is missing when it is not available yet).
 * The step list is the screen behind (back). Moving between steps replaces the content in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkflowStepScreen(initialStep: WorkflowStep, model: WorkflowModel, onBack: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(initialStep) }
    val go: (WorkflowStep) -> Unit = { step ->
        model.reload()
        if (step != current && model.isAvailable(step)) current = step
    }
    val inWizard = current in WorkflowStep.wizard
    Scaffold(
        topBar = {
            Column {
                BackTopBar(current.title, onBack)
                if (inWizard) StepProgressBar(current, model, go)
            }
        },
        bottomBar = { if (inWizard) StepNavigationBar(current, model, go) },
    ) { padding ->
        Crossfade(
            targetState = current,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            label = "step",
        ) { step ->
            StepContent(step, model)
        }
    }
}

/**
 * Content of each step. Steps not ported yet show [ComingSoon]; the next phases replace those branches with their
 * screens (`GeometryStepView(model)`, `SectionsStepView(model)`, …).
 */
@Composable
fun StepContent(step: WorkflowStep, model: WorkflowModel) {
    when (step) {
        WorkflowStep.summary -> {
            val summary = remember(model.revision) { ProjectSummary(model.folder) }
            ProjectDetailView(summary)
        }
        WorkflowStep.video -> VideoRangeView(model)
        WorkflowStep.geometry -> GeometryStepView(model)
        WorkflowStep.sections -> SectionsStepView(model)
        WorkflowStep.processing, WorkflowStep.results, WorkflowStep.export ->
            ComingSoon()
    }
}

private enum class StepState { Current, Done, Pending, Locked }

/**
 * Stepper of the six steps: numbered circles joined by a line, with a short name under each. The current step is a
 * large filled circle with its name in bold accent colour; done steps show a check on a light accent circle (opaque
 * base) and the line up to them is coloured; pending steps are grey outlines, dashed when not available yet.
 */
@Composable
private fun StepProgressBar(current: WorkflowStep, model: WorkflowModel, go: (WorkflowStep) -> Unit) {
    val steps = WorkflowStep.wizard
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
            steps.forEachIndexed { i, s ->
                StepItem(s, i + 1, isFirst = i == 0, isLast = i == steps.size - 1, current, model, go, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StepItem(
    s: WorkflowStep,
    number: Int,
    isFirst: Boolean,
    isLast: Boolean,
    current: WorkflowStep,
    model: WorkflowModel,
    go: (WorkflowStep) -> Unit,
    modifier: Modifier,
) {
    val st = when {
        s == current -> StepState.Current
        model.isDone(s) -> StepState.Done
        model.isAvailable(s) -> StepState.Pending
        else -> StepState.Locked
    }
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val faint = secondary.copy(alpha = 0.25f)
    val index = WorkflowStep.wizard.indexOf(s)
    val leftColor = if (isFirst) Color.Transparent else if (index > 0 && model.isDone(WorkflowStep.wizard[index - 1])) accent else faint
    val rightColor = if (isLast) Color.Transparent else if (model.isDone(s)) accent else faint
    val stateText = when (st) {
        StepState.Current -> "paso actual"
        StepState.Done -> "completado"
        StepState.Pending -> "pendiente"
        StepState.Locked -> "aún no disponible"
    }
    Column(
        modifier.clickable { go(s) }.semantics(mergeDescendants = true) {
            contentDescription = "Paso $number, ${s.shortTitle}, $stateText"
            role = Role.Button
            selected = st == StepState.Current
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).height(2.dp).background(leftColor))
                Box(Modifier.weight(1f).height(2.dp).background(rightColor))
            }
            StepCircle(st, number, Modifier.size(if (st == StepState.Current) 30.dp else 24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            s.stepperTitle,
            fontSize = 10.sp,
            fontWeight = if (st == StepState.Current) FontWeight.Bold else FontWeight.Normal,
            color = when (st) {
                StepState.Current -> accent
                StepState.Locked -> secondary.copy(alpha = 0.5f)
                else -> secondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepCircle(st: StepState, number: Int, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.surface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    when (st) {
        StepState.Current -> Box(
            modifier.shadow(4.dp, CircleShape, ambientColor = accent, spotColor = accent).background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        StepState.Done -> Box(
            // Opaque base so the connector line does not show through the check.
            modifier.background(base, CircleShape).background(accent.copy(alpha = 0.18f), CircleShape).border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        StepState.Pending -> Box(
            modifier.background(base, CircleShape).border(1.5.dp, secondary.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = secondary)
        }
        StepState.Locked -> Box(
            modifier.background(base, CircleShape).drawBehind {
                val w = 1.5.dp.toPx()
                drawCircle(
                    secondary.copy(alpha = 0.25f),
                    radius = size.minDimension / 2 - w / 2,
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 11.sp, color = secondary.copy(alpha = 0.5f))
        }
    }
}

/**
 * Previous / next buttons. Next is prominent when the current step is done; when the next step is not available yet,
 * it is disabled and the requirement is shown.
 */
@Composable
private fun StepNavigationBar(current: WorkflowStep, model: WorkflowModel, go: (WorkflowStep) -> Unit) {
    val steps = WorkflowStep.wizard
    val i = steps.indexOf(current).coerceAtLeast(0)
    val previous = if (i > 0) steps[i - 1] else null
    val next = if (i + 1 < steps.size) steps[i + 1] else null
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (previous != null) {
                OutlinedButton(onClick = { go(previous) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(previous.shortTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            if (next != null) {
                val available = model.isAvailable(next)
                Column(Modifier.widthIn(max = 230.dp), horizontalAlignment = Alignment.End) {
                    if (model.isDone(current) && available) {
                        Button(onClick = { go(next) }) { NextLabel(next) }
                    } else {
                        OutlinedButton(onClick = { go(next) }, enabled = available) { NextLabel(next) }
                    }
                    if (!available) {
                        Text(
                            model.requirement(next),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NextLabel(n: WorkflowStep) {
    Text(n.shortTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(4.dp))
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
}

/**
 * Site screen (visits of `RIVeR/<name>/` and their rating curve, `SitePipeline.gaugings`). Placeholder until the
 * results phase ports the rating curve.
 */
@Composable
fun SiteView(siteFolder: File, onBack: () -> Unit) {
    ComingSoonScreen(siteFolder.name, onBack)
}
