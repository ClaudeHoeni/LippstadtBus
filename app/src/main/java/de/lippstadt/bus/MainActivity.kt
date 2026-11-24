package de.lippstadt.bus

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import de.lippstadt.bus.ui.AppTheme
import de.lippstadt.bus.ui.TransitScreen

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_VOICE_SEARCH = "de.lippstadt.bus.ACTION_VOICE_SEARCH"
        private const val KEY_VOICE_HANDLED = "voice_handled"
    }

    private val viewModel: TransitViewModel by viewModels()
    private var voiceTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Nur beim echten Start per Widget zuhören – nicht erneut nach jeder Bildschirmdrehung.
        if (savedInstanceState?.getBoolean(KEY_VOICE_HANDLED) != true && intent?.action == ACTION_VOICE_SEARCH) {
            voiceTrigger++
        }
        setContent {
            AppTheme {
                TransitScreen(vm = viewModel, voiceTrigger = voiceTrigger)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VOICE_HANDLED, true)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        viewModel.touch()
    }

    override fun onStop() {
        super.onStop()
        // Im Hintergrund kein GPS – spart Akku
        viewModel.stopGps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_VOICE_SEARCH) voiceTrigger++
    }
}
