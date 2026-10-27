package com.smartdialerpro.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.ui.components.EmptyStateView
import com.smartdialerpro.android.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RecordingItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAddedSeconds: Long,
    val durationSeconds: Long
)

private const val RECORDINGS_RELATIVE_PATH = "Music/SmartDialer/Calls"

/**
 * Lists the call recordings this app saved through MediaStore, with
 * playback, delete and share. Only one recording plays at a time and
 * playback stops when the screen is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    fun loadRecordings() {
        coroutineScope.launch {
            isLoading = true
            recordings = queryRecordings(context)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadRecordings() }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.stop()
            } catch (_: Throwable) {
            }
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun togglePlay(item: RecordingItem) {
        if (playingId == item.id && isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            return
        }
        if (playingId == item.id) {
            mediaPlayer?.start()
            isPlaying = true
            return
        }
        coroutineScope.launch {
            try {
                mediaPlayer?.release()
                val player = MediaPlayer()
                mediaPlayer = player
                player.setDataSource(context, item.uri)
                player.setOnPreparedListener { prepared ->
                    prepared.start()
                    playingId = item.id
                    isPlaying = true
                }
                player.setOnCompletionListener {
                    isPlaying = false
                    playingId = null
                }
                player.setOnErrorListener { _, _, _ ->
                    isPlaying = false
                    playingId = null
                    Toast.makeText(context, "Could not play this recording", Toast.LENGTH_SHORT).show()
                    true
                }
                player.prepareAsync()
            } catch (t: Throwable) {
                Toast.makeText(context, "Could not play this recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteRecording(item: RecordingItem) {
        coroutineScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(item.uri, null, null) > 0
                } catch (_: Throwable) {
                    false
                }
            }
            if (playingId == item.id) {
                mediaPlayer?.release()
                mediaPlayer = null
                playingId = null
                isPlaying = false
            }
            if (deleted) {
                Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadRecordings()
            } else {
                Toast.makeText(context, "Could not delete this recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareRecording(item: RecordingItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share recording"))
        } catch (_: Throwable) {
            Toast.makeText(context, "Unable to share this recording", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call recordings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadRecordings() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                recordings.isEmpty() -> {
                    EmptyStateView(
                        icon = Icons.Rounded.FiberManualRecord,
                        title = "No recordings yet",
                        description = "Recorded calls will appear here. Start a recording from the in-call screen or turn on auto-record in Settings."
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recordings, key = { it.uri.toString() }) { item ->
                            val playing = playingId == item.id && isPlaying
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { togglePlay(item) }) {
                                        Icon(
                                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (playing) "Pause" else "Play",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val dateText = Utils.formatCallDate(item.dateAddedSeconds * 1000L)
                                        val durationText = if (item.durationSeconds > 0) {
                                            Utils.formatDuration(item.durationSeconds)
                                        } else {
                                            ""
                                        }
                                        Text(
                                            text = listOf(dateText, durationText).filter { it.isNotBlank() }.joinToString(" • "),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(onClick = { shareRecording(item) }) {
                                        Icon(
                                            Icons.Rounded.Share,
                                            contentDescription = "Share",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { deleteRecording(item) }) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun queryRecordings(context: Context): List<RecordingItem> = withContext(Dispatchers.IO) {
    val items = ArrayList<RecordingItem>()
    try {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val pathIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val relativePath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                val isOurs = name.startsWith("Call_") || relativePath == RECORDINGS_RELATIVE_PATH
                if (!isOurs || name.isBlank()) continue
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                items.add(
                    RecordingItem(
                        id = id,
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        name = name,
                        dateAddedSeconds = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                        durationSeconds = if (durationIdx >= 0) (cursor.getLong(durationIdx) / 1000L) else 0L
                    )
                )
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("Recordings", "Unable to query recordings", t)
    }
    items
}
