package `in`.arasan.xthink.probe

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Debug-only screen. Prints what this phone actually exposes and dumps the same
 * text to logcat under the tag [TAG], so `scripts/dev.sh caps` can capture it.
 *
 * This is NOT product UI - it is deliberately plain and is not the launcher
 * activity. The stock-camera styling in CLAUDE.md applies to the real screens,
 * which land in v0.3-frame.
 *
 * Exported so `adb shell am start` can reach it. It reads only public device
 * characteristics: no user data, no capture, no camera is ever opened.
 */
class DebugCapsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CapabilityReport()
            }
        }
    }

    companion object {
        const val TAG = "xThink-CAPS"
    }
}

@Composable
private fun CapabilityReport() {
    val context = LocalContext.current
    var report by remember { mutableStateOf("") }

    // Characteristics are readable without CAMERA, but some values can come
    // back redacted. Ask first, then read whatever the answer was - the report
    // states which case it is, so a redacted run is never mistaken for a real one.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        report = dumpAndReturn(DeviceCapabilities.report(context))
    }

    LaunchedEffect(Unit) {
        permission.launch(Manifest.permission.CAMERA)
    }

    SelectionContainer {
        Text(
            text = report.ifEmpty { "reading device capabilities..." },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            color = Color(0xFF4ADE80),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
    }
}

/**
 * Logcat truncates a single entry, so emit the report a line at a time. That
 * also makes `adb logcat -d -s xThink-CAPS` reassemble cleanly into a file.
 */
private fun dumpAndReturn(report: String): String {
    Log.i(DebugCapsActivity.TAG, "---- BEGIN xThink capability report ----")
    report.lineSequence().forEach { Log.i(DebugCapsActivity.TAG, it) }
    Log.i(DebugCapsActivity.TAG, "---- END xThink capability report ----")
    return report
}
