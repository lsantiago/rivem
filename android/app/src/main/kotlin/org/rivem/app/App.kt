package org.rivem.app

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rivem.app.common.BackTopBar
import org.rivem.app.common.FormButton
import org.rivem.app.common.FormDivider
import org.rivem.app.common.FormSection
import org.rivem.app.common.LabeledContent
import org.rivem.app.diagnostics.AppLog
import org.rivem.app.diagnostics.DiagnosticsView
import org.rivem.app.pipeline.ExampleProject
import org.rivem.app.pipeline.userMessage
import org.rivem.app.steps.newproject.NewProjectView
import org.rivem.app.workflow.SiteView
import org.rivem.app.workflow.WorkflowModel
import org.rivem.app.workflow.WorkflowStep
import org.rivem.app.workflow.WorkflowStepScreen
import org.rivem.app.workflow.WorkflowView
import org.rivem.core.RiverCore
import java.io.File

// MARK: Theme

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF001B3E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003A75),
    onPrimaryContainer = Color(0xFFD6E6FF),
)

/** Material 3 theme with the iOS accent colour; [appearance] forces light or dark like the iOS setting. */
@Composable
fun RivemTheme(appearance: AppAppearance = AppAppearance.system, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppAppearance.system -> isSystemInDarkTheme()
        AppAppearance.light -> false
        AppAppearance.dark -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

// MARK: Navigation

/**
 * Navigation routes. Folders travel as URL-encoded absolute paths. The `WorkflowModel` of a project lives in the
 * [PROJECT] entry; [STEP] and [SITE] screens above it share it (`navController.getBackStackEntry(PROJECT)`).
 */
object Routes {
    const val HOME = "home"
    const val DIAGNOSTICS = "diagnostics"

    /** New aforo, or a new visit of `template` (folder of the aforo whose configuration is reused). */
    const val NEW = "new?template={template}"

    /** Step list of one project (`WorkflowView`). */
    const val PROJECT = "project/{folder}"

    /** Wizard screen of one project opened at `step` (`WorkflowStep.rawValue`). */
    const val STEP = "project/{folder}/step/{step}"

    /** Site of a project (visits and rating curve). */
    const val SITE = "project/{folder}/site"

    private fun enc(file: File): String = Uri.encode(file.absolutePath)

    fun newProject(template: File? = null): String = if (template == null) "new" else "new?template=${enc(template)}"
    fun project(folder: File): String = "project/${enc(folder)}"
    fun step(folder: File, step: WorkflowStep): String = "project/${enc(folder)}/step/${step.rawValue}"
    fun site(folder: File): String = "project/${enc(folder)}/site"
}

@Composable
fun RivemNavHost(app: RivemApplication) {
    val nav = rememberNavController()
    // CI screenshot hook: `--es autotest_open_project <name> [--es autotest_step <step|list|site>]` (iOS
    // `openForAutotest`): the newest dated folder of that name, at the step, its step list or its site.
    LaunchedEffect(Unit) {
        val name = Autotest.projectToOpen
        if (!Autotest.pendingOpen || name == null) return@LaunchedEffect
        Autotest.pendingOpen = false
        app.workspace.reload()
        val match = app.workspace.projects.filter { it.name == name }.maxByOrNull { it.folder.name }
        if (match == null) {
            AppLog.error("autotest: no hay proyecto $name")
            return@LaunchedEffect
        }
        nav.navigate(Routes.project(match.folder))
        val step = Autotest.stepToOpen
        when {
            Autotest.showSite -> nav.navigate(Routes.site(match.folder))
            step != null -> nav.navigate(Routes.step(match.folder, step))
        }
    }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeView(
                workspace = app.workspace,
                preferences = app.preferences,
                openProject = { nav.navigate(Routes.project(it)) },
                newProject = { nav.navigate(Routes.newProject()) },
                openDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsView(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.NEW,
            arguments = listOf(navArgument("template") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val template = entry.arguments?.getString("template")?.let { File(it) }
            NewProjectView(
                workspace = app.workspace,
                templateFolder = template,
                onCancel = { nav.popBackStack() },
                onCreated = { folder ->
                    app.workspace.reload()
                    nav.navigate(Routes.project(folder)) { popUpTo(Routes.NEW) { inclusive = true } }
                },
            )
        }
        composable(Routes.PROJECT, arguments = listOf(navArgument("folder") { type = NavType.StringType })) { entry ->
            val folder = File(entry.arguments?.getString("folder") ?: "")
            val model: WorkflowModel = viewModel(viewModelStoreOwner = entry, key = folder.path, factory = WorkflowModel.factory(folder))
            WorkflowView(
                model = model,
                onBack = { nav.popBackStack() },
                openStep = { step -> nav.navigate(Routes.step(folder, step)) },
                openSite = { nav.navigate(Routes.site(folder)) },
                newVisit = { nav.navigate(Routes.newProject(folder)) },
                // The CI opens its own step (or none, for the step list / site screenshots).
                autoOpen = Autotest.projectToOpen == null,
            )
        }
        composable(
            Routes.STEP,
            arguments = listOf(
                navArgument("folder") { type = NavType.StringType },
                navArgument("step") { type = NavType.StringType },
            ),
        ) { entry ->
            val folder = File(entry.arguments?.getString("folder") ?: "")
            val step = WorkflowStep.fromRawValue(entry.arguments?.getString("step")) ?: WorkflowStep.summary
            val parent = remember(entry) { nav.getBackStackEntry(Routes.PROJECT) }
            val model: WorkflowModel = viewModel(viewModelStoreOwner = parent, key = folder.path, factory = WorkflowModel.factory(folder))
            WorkflowStepScreen(initialStep = step, model = model, onBack = { nav.popBackStack() })
        }
        composable(Routes.SITE, arguments = listOf(navArgument("folder") { type = NavType.StringType })) { entry ->
            val folder = File(entry.arguments?.getString("folder") ?: "")
            SiteView(siteFolder = folder.absoluteFile.parentFile ?: folder, onBack = { nav.popBackStack() })
        }
    }
}

// MARK: Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
    workspace: Workspace,
    preferences: AppPreferences,
    openProject: (File) -> Unit,
    newProject: () -> Unit,
    openDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var installingExample by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val exampleAvailable = remember { ExampleProject.isAvailable(context) }

    LaunchedEffect(Unit) { workspace.reload() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { workspace.importProjects(context, uri) }
            } catch (e: Exception) {
                AppLog.error("Importar proyecto: $e")
                workspace.lastError = e.userMessage()
            }
            workspace.reload()
            importing = false
        }
    }

    /** RIVeR's pisco example (`ExampleProject`); opens it once installed. */
    fun installExample() {
        if (installingExample) return
        installingExample = true
        scope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { ExampleProject.install(context, workspace.root).folder }
                workspace.reload()
                openProject(folder)
            } catch (e: Exception) {
                AppLog.error("Instalar ejemplo: $e")
                workspace.lastError = "No se pudo instalar el ejemplo: ${e.userMessage()}"
            }
            installingExample = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RIVEM") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "Agregar") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Nuevo aforo") },
                                leadingIcon = { Icon(Icons.Filled.VideoCall, null) },
                                onClick = { menuOpen = false; newProject() },
                            )
                            DropdownMenuItem(
                                text = { Text("Importar proyecto de escritorio") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                onClick = { menuOpen = false; importLauncher.launch(null) },
                            )
                            if (exampleAvailable) {
                                DropdownMenuItem(
                                    text = { Text("Instalar ejemplo (río Pisco)") },
                                    leadingIcon = { Icon(Icons.Filled.School, null) },
                                    enabled = !installingExample,
                                    onClick = { menuOpen = false; installExample() },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                workspace.reload()
                refreshing = false
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                FormSection("Aforos") {
                    if (workspace.projects.isEmpty()) EmptyWorkspaceView()
                    workspace.projects.forEachIndexed { i, project ->
                        if (i > 0) FormDivider()
                        SwipeToDeleteRow(onDelete = { pendingDelete = project }) {
                            ProjectRow(project, onClick = { openProject(project.folder) })
                        }
                    }
                    if (importing) {
                        if (workspace.projects.isNotEmpty()) FormDivider()
                        BusyRow("Importando el proyecto…")
                    }
                }
                FormSection {
                    FormButton("Nuevo aforo", Icons.Filled.AddCircle) { newProject() }
                    if (exampleAvailable) {
                        FormDivider()
                        if (installingExample) {
                            BusyRow("Preparando el ejemplo…")
                        } else {
                            FormButton("Instalar ejemplo: río Pisco (RIVeR)", Icons.Filled.School) { installExample() }
                        }
                    }
                    FormDivider()
                    FormButton("Diagnóstico y registro", Icons.Filled.MedicalServices) { openDiagnostics() }
                }
                FormSection("Apariencia") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val all = AppAppearance.entries
                        all.forEachIndexed { i, a ->
                            SegmentedButton(
                                selected = preferences.appearance == a,
                                onClick = { preferences.appearance = a },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = all.size),
                            ) { Text(a.label) }
                        }
                    }
                }
                FormSection(
                    "Resultados",
                    footer = "Semáforo de calidad del aforo, estaciones dudosas y caudal ± incertidumbre (95 %) en Resultados y en el informe. " +
                        "Es un cálculo propio de RIVEM; RIVeR escritorio no lo tiene.",
                ) {
                    LabeledContent("Calidad e incertidumbre") {
                        Switch(checked = preferences.showQuality, onCheckedChange = { preferences.showQuality = it })
                    }
                }
                FormSection("Núcleo") {
                    LabeledContent("RiverCore", RiverCore.VERSION)
                    LabeledContent("Compatible con RIVeR", RiverCore.DESKTOP_COMPATIBILITY)
                }
            }
        }
    }

    workspace.lastError?.let { message ->
        AlertDialog(
            onDismissRequest = { workspace.lastError = null },
            title = { Text("No se pudo importar") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { workspace.lastError = null }) { Text("Aceptar") } },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("¿Eliminar este aforo del teléfono?") },
            text = { Text("Se borra la carpeta del proyecto en el teléfono. La copia de escritorio no se toca.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        workspace.delete(project)
                    } catch (e: Exception) {
                        AppLog.error("Eliminar proyecto: $e")
                    }
                    pendingDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } },
        )
    }
}

/** Swipe left to ask for deletion (the row springs back; a dialog confirms). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Eliminar", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
    }
}

@Composable
private fun BusyRow(text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyWorkspaceView() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Water, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text("Sin aforos todavía", style = MaterialTheme.typography.titleMedium)
        Text(
            "Crea un aforo nuevo grabando o eligiendo un video, importa un proyecto de RIVeR escritorio, instala el ejemplo " +
                "del río Pisco para ver el flujo completo, o cópialo por USB a la carpeta Android/data/org.rivem.app/files/RIVeR.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ProjectRow(project: ProjectSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            project.footage?.icon ?: Icons.Filled.FolderOpen,
            contentDescription = project.footage?.label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.riverName ?: project.name, style = MaterialTheme.typography.titleMedium)
            Text(project.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (project.loadError != null || project.issues.isNotEmpty()) {
            Icon(Icons.Filled.Warning, contentDescription = "Problemas de compatibilidad", tint = Color(0xFFFF9500))
        }
    }
}

/** Placeholder of the screens that come in the next Android versions. */
@Composable
fun ComingSoon(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            "Este paso llega en la próxima versión de Android",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Screen with a back bar and [ComingSoon] (site screen for now). */
@Composable
fun ComingSoonScreen(title: String, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        ComingSoon(Modifier.padding(padding))
    }
}
