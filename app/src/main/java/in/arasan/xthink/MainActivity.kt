package `in`.arasan.xthink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import `in`.arasan.xthink.camera.CameraScreen
import `in`.arasan.xthink.ui.Splash

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // The camera binds underneath the splash, so by the time the
                // promise fades the preview is already live.
                var splash by remember { mutableStateOf(true) }
                Box {
                    // Dev hook: `am start ... --es enhance <content-uri>` runs the
                    // post-shot crop on an existing photo, for testing over adb.
                    CameraScreen(
                        debugEnhanceUri = intent.getStringExtra("enhance"),
                        debugCoachUri = intent.getStringExtra("coach"),
                    )
                    if (splash) Splash(onDone = { splash = false })
                }
            }
        }
    }
}
