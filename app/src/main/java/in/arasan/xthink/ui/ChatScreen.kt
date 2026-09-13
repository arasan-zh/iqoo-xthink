package `in`.arasan.xthink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One turn of the conversation. */
data class ChatTurn(val mine: Boolean, val text: String, val image: ImageBitmap? = null)

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
    attachment: ImageBitmap?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onAttach: () -> Unit,
    onClearAttach: () -> Unit,
    onHome: () -> Unit,
    /** The camera button: take a picture now. */
    onCapture: () -> Unit = {},
    /** Always on offer: the text in a photo, in English; the text in a photo, read and cleaned. Without a photo attached, the camera is opened first. */
    onTranslate: () -> Unit = {},
    onScan: () -> Unit = {},
) {
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1)
    }
    Box(modifier = Modifier.fillMaxSize().background(Palette.Night)) {
        ParticleField()
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
                NightRound(onClick = onHome) { Glyph("home", Palette.NightInk, 20.dp) }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Palette.NightCard).padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Mark(size = 18.dp, color = Palette.NightInk)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(text = "xThink", color = Palette.NightInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = modelLine, color = Palette.NightMuted, fontSize = 10.sp)
                    }
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
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 390.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Hi, I'm xThink.", color = Palette.NightMuted, fontSize = 14.sp)
                            Text(
                                text = "How can I help\nyou today?",
                                color = Palette.NightInk,
                                fontFamily = Palette.Display,
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                itemsIndexed(turns) { _, t ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (t.mine) Arrangement.End else Arrangement.Start) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (t.mine) Brush.linearGradient(listOf(Palette.Violet, Palette.Rose)) else Brush.linearGradient(listOf(Palette.NightCardStrong, Palette.NightCardStrong)))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (t.image != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = t.image,
                                    contentDescription = "photo",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.size(width = 200.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            }
                            if (t.text.isNotBlank() || !t.mine) Text(
                                text = if (t.text.isBlank() && !t.mine) "…" else t.text,
                                color = Palette.NightInk,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                }
            }
            // The composer: a photo, words, the mic, send.
            // What can be done with a photo, always on offer: with one
            // attached they act on it; without, the camera opens first.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                if (attachment != null) {
                    androidx.compose.foundation.Image(
                        bitmap = attachment, contentDescription = "attached",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
                NightPill(text = "Translate", onClick = { if (!busy) onTranslate() })
                NightPill(text = "Scan text", onClick = { if (!busy) onScan() })
                Spacer(Modifier.weight(1f))
                if (attachment != null) NightRound(onClick = onClearAttach, size = 32.dp) { Text("✕", color = Palette.NightInk, fontSize = 13.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Palette.NightCard)
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    // The camera takes one now; the gallery brings one in.
                    NightRound(onClick = onCapture, size = 40.dp) { Glyph("camera", Palette.NightInk, 18.dp) }
                    NightRound(onClick = onAttach, size = 40.dp) { Glyph("gallery", Palette.NightInk, 18.dp) }
                    Box(modifier = Modifier.weight(1f)) {
                        if (draft.isBlank()) Text(text = if (listening) "Listening…" else if (attachment == null) "Message…" else "Ask about the photo…", color = Palette.NightMuted, fontSize = 16.sp, maxLines = 1)
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraft,
                            textStyle = TextStyle(color = Palette.NightInk, fontSize = 16.sp),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (busy) Palette.NightCard else Palette.NightCardStrong)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (!busy) onSend() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text(text = "➤", color = Palette.NightInk, fontSize = 16.sp) }
                }
                Box(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onMic,
                    ),
                ) { GlowMic(size = 56.dp, active = listening) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NightRound(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.NightCard)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A small dark pill with a word on it, for what can be done with a photo. */
@Composable
fun NightPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.NightCardStrong)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text = text, color = Palette.NightInk, fontSize = 12.sp) }
}
