package `in`.arasan.xthink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One turn of the conversation. */
data class ChatTurn(val mine: Boolean, val text: String)

/**
 * Chat. Type or speak; the phone answers from its own model, streaming,
 * with the last few turns in mind. Nothing leaves the phone.
 */
@Composable
fun ChatScreen(
    turns: List<ChatTurn>,
    draft: String,
    busy: Boolean,
    listening: Boolean,
    modelLine: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onHome: () -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1)
    }
    Box(modifier = Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Palette.Gutter)
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RoundButton(onClick = onHome) { Glyph("home", Palette.Ink, 20.dp) }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Chat", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = modelLine, color = Palette.InkMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (turns.isEmpty()) {
                    item {
                        Text(
                            text = "Ask anything.\nIt stays on the phone.",
                            color = Palette.Ink,
                            fontFamily = Palette.Display,
                            fontSize = 30.sp,
                            lineHeight = 36.sp,
                            modifier = Modifier.padding(top = 40.dp),
                        )
                    }
                }
                itemsIndexed(turns) { _, t ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (t.mine) Arrangement.End else Arrangement.Start) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (t.mine) Palette.Ink else Palette.GlassStrong)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = if (t.text.isBlank() && !t.mine) "…" else t.text,
                                color = if (t.mine) Color.White else Palette.Ink,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                }
            }
            // The composer.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.GlassStrong)
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (draft.isBlank()) Text(text = if (listening) "Listening…" else "Write or tap the mic…", color = Palette.InkMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        textStyle = TextStyle(color = Palette.Ink, fontSize = 16.sp),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (listening) Palette.AccentSoft else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMic,
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "🎤", fontSize = 20.sp) }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (busy) Palette.InkMuted else Brush.linearGradient(listOf(Palette.Ink, Palette.Ink)).let { Palette.Ink })
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (!busy) onSend() },
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "➤", color = Color.White, fontSize = 18.sp) }
            }
        }
    }
}
