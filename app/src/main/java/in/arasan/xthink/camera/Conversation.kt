package `in`.arasan.xthink.camera

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import `in`.arasan.xthink.ui.ChatTurn

/**
 * The session's one conversation, on the phone. Voice and Chat are two
 * faces of it: a turn spoken in Voice shows in Chat, a turn typed in Chat
 * is remembered when you next speak. The model is given the last few
 * turns every time, so "what did I just say?" has an answer. It lives as
 * long as the app does; nothing is written anywhere.
 */
class Conversation {
    val turns: SnapshotStateList<ChatTurn> = mutableStateListOf()

    /** The turns as the prompt wants them: (mine, text), oldest first, blanks dropped. */
    fun history(): List<Pair<Boolean, String>> = turns.filter { it.text.isNotBlank() }.map { it.mine to it.text }

    fun add(mine: Boolean, text: String, image: ImageBitmap? = null): Int {
        turns.add(ChatTurn(mine, text, image))
        return turns.size - 1
    }

    fun set(index: Int, text: String) {
        if (index in turns.indices) turns[index] = turns[index].copy(text = text)
    }

    fun clear() = turns.clear()
}
