package com.harvey.blend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harvey.blend.repo

private data class TabItem(val dest: Dest, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabItem(Dest.Library, "Library", Icons.Rounded.LibraryMusic),
    TabItem(Dest.Mix, "Mix", Icons.Rounded.GraphicEq),
    TabItem(Dest.SetMaker, "Set maker", Icons.Rounded.AutoAwesome),
    TabItem(Dest.Search, "Search", Icons.Rounded.Search),
)

/**
 * Folded (narrow): one screen at a time with a bottom bar.
 * Unfolded (≥ 700 dp): navigation rail, library on the left, the open playlist/track/mix on the right.
 */
@Composable
fun BlendRoot(onPickFolder: () -> Unit) {
    val nav = remember { Nav() }
    CompositionLocalProvider(LocalNav provides nav) {
        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
        BoxWithConstraints(Modifier.fillMaxSize().background(Blend.Bg)) {
            if (maxWidth >= 700.dp) WideLayout(nav, onPickFolder) else CompactLayout(nav, onPickFolder)
        }
    }
}

@Composable
private fun Screen(dest: Dest, wide: Boolean, showBack: Boolean, onPickFolder: () -> Unit) {
    when (dest) {
        Dest.Library -> LibraryScreen(initialFilter = LibraryFilter.PLAYLISTS, onPickFolder = onPickFolder)
        Dest.SetMaker -> LibraryScreen(initialFilter = LibraryFilter.SMART_SETS, onPickFolder = onPickFolder)
        Dest.Mix -> MixScreen(wide)
        Dest.Search -> SearchScreen(showBack)
        Dest.Settings -> SettingsScreen(showBack, onPickFolder)
        is Dest.PlaylistView -> PlaylistScreen(dest.id, wide, showBack)
        is Dest.GenreView -> GenreScreen(dest.name, showBack)
        is Dest.TrackView -> TrackScreen(dest.id, wide, showBack)
    }
}

@Composable
private fun CompactLayout(nav: Nav, onPickFolder: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Screen(nav.current, wide = false, showBack = nav.stack.size > 1, onPickFolder = onPickFolder)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Blend.Bg.copy(alpha = 0.92f), Color.Black)))
                .navigationBarsPadding(),
        ) {
            if (nav.current != Dest.Mix) MiniPlayer(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { nav.push(Dest.Mix) }
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                TABS.forEach { tab ->
                    val selected = nav.tab == tab.dest
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { nav.tab(tab.dest) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tab.icon, null, Modifier.size(26.dp), tint = if (selected) Color.White else Blend.Sub)
                        Text(tab.label, style = Type.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = if (selected) Color.White else Blend.Sub)
                    }
                }
            }
        }
    }
}

@Composable
private fun WideLayout(nav: Nav, onPickFolder: () -> Unit) {
    val repo = LocalContext.current.repo
    val queue by repo.queue.collectAsState()
    val playlists by repo.playlists.collectAsState()
    Row(Modifier.fillMaxSize()) {
        // Navigation rail
        Column(
            Modifier.width(76.dp).fillMaxHeight().background(Blend.Black).statusBarsPadding().navigationBarsPadding().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            (TABS + TabItem(Dest.Settings, "Settings", Icons.Rounded.Settings)).forEach { tab ->
                val selected = nav.tab == tab.dest
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { nav.tab(tab.dest) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, null, Modifier.size(24.dp), tint = if (selected) Color.White else Blend.Sub)
                    Text(tab.label, style = Type.caption.copy(fontSize = Type.caption.fontSize * 0.85f, fontWeight = FontWeight.SemiBold), color = if (selected) Color.White else Blend.Sub)
                }
            }
        }
        // Library pane
        val tab = nav.tab
        if (tab == Dest.Library || tab == Dest.SetMaker || tab == Dest.Mix) {
            Box(Modifier.width(330.dp).fillMaxHeight().statusBarsPadding()) {
                LibraryScreen(
                    initialFilter = if (tab == Dest.SetMaker) LibraryFilter.SMART_SETS else LibraryFilter.PLAYLISTS,
                    compactRows = true, onPickFolder = onPickFolder,
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Blend.Line))
        }
        // Detail pane
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val top = nav.current
            val detail: Dest? = when {
                top is Dest.PlaylistView || top is Dest.GenreView || top is Dest.TrackView -> top
                top == Dest.Mix || top == Dest.Search || top == Dest.Settings -> top
                queue.currentId != null -> Dest.Mix
                else -> playlists.maxByOrNull { it.createdAt }?.let { Dest.PlaylistView(it.id) }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                if (detail != null) Screen(detail, wide = true, showBack = nav.stack.size > 2, onPickFolder = onPickFolder)
                else EmptyHint("Welcome to Blend", "Pick your music folder, then create a playlist or a smart set on the left.", Modifier.align(Alignment.Center))
            }
            if (detail != Dest.Mix) MiniPlayer(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) { nav.push(Dest.Mix) }
        }
    }
}
