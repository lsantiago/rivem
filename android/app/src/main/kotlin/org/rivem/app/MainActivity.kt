package org.rivem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RivemApplication
        if (savedInstanceState == null) {
            Autotest.read(intent)
            Autotest.showQuality?.let { app.preferences.showQualityOverride = it }
        }
        setContent {
            RivemTheme(Autotest.appearance ?: app.preferences.appearance) {
                RivemNavHost(app)
            }
        }
        if (savedInstanceState == null && Autotest.isExampleRequested) {
            lifecycleScope.launch {
                withContext(Dispatchers.Default) { Autotest.runExample(applicationContext, app.workspace.root) }
                app.workspace.reload()
            }
        }
    }
}
