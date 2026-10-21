package com.harvey.blend

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.harvey.blend.ui.BlendRoot
import com.harvey.blend.ui.BlendTheme

class MainActivity : ComponentActivity() {

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Music" } }.getOrNull()
            repo.setMusicFolder(uri, name)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            BlendTheme {
                BlendRoot(onPickFolder = { pickFolder.launch(null) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up files added to the folder since last time.
        repo.rescan(force = false)
    }
}
