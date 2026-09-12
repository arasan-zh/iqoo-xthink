package `in`.arasan.xthink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import `in`.arasan.xthink.camera.CameraScreen
import `in`.arasan.xthink.ui.HomeScreen
import `in`.arasan.xthink.ui.Splash

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Home is the front door; the camera is one room, opened
                // straight into a tab when a card asks for one. Dev hooks
                // (--es enhance/genius/ask) go straight to the camera.
                val hooked = intent.getStringExtra("enhance") != null || intent.getStringExtra("genius") != null || intent.getStringExtra("ask") != null
                var screen by remember { mutableStateOf(if (hooked) "CAMERA" else "HOME") }
                var startIn by remember { mutableStateOf<String?>(null) }
                var splash by remember { mutableStateOf(true) }
                Box {
                    when (screen) {
                        "HOME" -> HomeScreen(status = "Everything here runs on the phone.", onOpen = { id ->
                            startIn = if (id == "CAMERA") null else id
                            screen = "CAMERA"
                        })
                        else -> {
                            CameraScreen(
                                debugEnhanceUri = intent.getStringExtra("enhance"),
                                debugGenius = intent.getStringExtra("genius"),
                                debugAsk = intent.getStringExtra("ask"),
                                startIn = startIn,
                                onHome = { screen = "HOME" },
                            )
                            BackHandler { screen = "HOME" }
                        }
                    }
                    if (splash) Splash(onDone = { splash = false })
                }
            }
        }
    }
}
