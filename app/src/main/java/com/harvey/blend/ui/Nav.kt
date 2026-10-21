package com.harvey.blend.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface Dest {
    data object Library : Dest
    data object SetMaker : Dest
    data object Mix : Dest
    data object Search : Dest
    data object Settings : Dest
    data class PlaylistView(val id: String) : Dest
    data class GenreView(val name: String) : Dest
    data class TrackView(val id: String) : Dest
}

/** Simple back stack: tabs reset it, screens push onto it. */
class Nav {
    var stack by mutableStateOf(listOf<Dest>(Dest.Library))
        private set
    val current: Dest get() = stack.last()
    val tab: Dest get() = stack.first()

    fun push(d: Dest) { if (current != d) stack = stack + d }
    fun pop(): Boolean { if (stack.size <= 1) return false; stack = stack.dropLast(1); return true }
    fun tab(d: Dest) { stack = listOf(d) }
    fun replaceDetail(d: Dest) { stack = listOf(stack.first(), d) }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No Nav") }
