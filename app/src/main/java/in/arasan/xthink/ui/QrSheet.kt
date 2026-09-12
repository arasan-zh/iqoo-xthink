package `in`.arasan.xthink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Where the QR sends people. */
object Links {
    /**
     * GitHub's latest-asset redirect: always the newest release's APK, so the
     * code printed on a slide or scanned at a demo never goes stale as builds
     * are pushed. CI names the asset app-debug.apk on every build.
     */
    const val INSTALL_APK = "https://github.com/arasan-zh/iqoo-xthink/releases/latest/download/app-debug.apk"
    const val RELEASES = "github.com/arasan-zh/iqoo-xthink/releases"
}

/**
 * A full-screen sheet with a scannable install link. Tap anywhere to close.
 *
 * The matrix comes from ZXing's reference encoder; drawing it ourselves keeps
 * it crisp at any size and lets it sit on a true white card, which is what a
 * phone camera needs to read it from a screen across a table.
 */
@Composable
fun QrSheet(url: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val matrix = remember(url) {
        QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            0, 0,
            mapOf(
                // M survives a smudged screen and a shaky scan; H would make
                // the code denser than a screen across a table can resolve.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Install xThink",
                color = XT.OnChip,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Scan with any camera. Opens the latest build on GitHub - download, tap, install.",
                color = XT.OnChipMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            // A white card with a quiet zone: the QR spec wants 4 modules of
            // clear space around the code, and the card's padding provides it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(XT.Corner))
                    .background(Color.White)
                    .padding(22.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val n = matrix.width
                    val cell = size.minDimension / n
                    // Draw modules as slightly overlapping squares so no hairline
                    // gaps appear between them at fractional cell sizes.
                    val side = cell + 0.5f
                    for (y in 0 until n) {
                        for (x in 0 until n) {
                            if (matrix.get(x, y)) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = Offset(x * cell, y * cell),
                                    size = Size(side, side),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = Links.RELEASES,
                color = XT.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Tap anywhere to close",
                color = XT.OnChipMuted,
                fontSize = 12.sp,
            )
        }
    }
}
