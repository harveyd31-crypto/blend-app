package com.harvey.blend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.harvey.blend.analysis.TrackAnalyzer
import com.harvey.blend.audio.AnalysisSink
import com.harvey.blend.audio.AudioDecoder
import com.harvey.blend.audio.DeckTrack
import com.harvey.blend.audio.MixEngine
import com.harvey.blend.audio.MixState
import com.harvey.blend.audio.PcmCache
import com.harvey.blend.audio.PcmTrack
import com.harvey.blend.export.DjExport
import com.harvey.blend.export.PathMapper
import com.harvey.blend.library.LibraryScanner
import com.harvey.blend.library.LibraryStore
import com.harvey.blend.library.TagReader
import com.harvey.blend.mix.PlaybackService
import com.harvey.blend.model.EnergyArc
import com.harvey.blend.model.Playlist
import com.harvey.blend.model.PlaylistKind
import com.harvey.blend.model.Settings
import com.harvey.blend.model.Track
import com.harvey.blend.setmaker.SetMaker
import com.harvey.blend.setmaker.TransitionScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class LibraryProgress(
    val scanning: Boolean = false,
    /** Outcome of the last scan, shown when the library is empty. */
    val lastScanAudio: Int? = null,
    val lastScanOther: Int = 0,
    val scanError: String? = null,
    /** What's happening right now (e.g. a Drive download), shown in the progress card. */
    val status: String? = null,
    val found: Int = 0,
    val toAnalyze: Int = 0,
    val analyzed: Int = 0,
    val current: String? = null,
) {
    val active: Boolean get() = scanning || analyzed < toAnalyze
    val fraction: Float get() = if (toAnalyze == 0) 0f else analyzed.toFloat() / toAnalyze
}

/** What is playing and what comes next. */
data class QueueState(
    val contextName: String? = null,
    val contextPlaylistId: String? = null,
    val ids: List<String> = emptyList(),
    val index: Int = -1,
    /** A track the user picked to play next, ahead of the list order. */
    val upNext: String? = null,
    val preparing: Boolean = false,
) {
    val currentId: String? get() = ids.getOrNull(index)
    val nextId: String? get() = upNext ?: ids.getOrNull(index + 1)
}

/** Single source of truth for the library, playlists, settings and playback. */
class BlendRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = LibraryStore(context)

    private val _tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val tracks: StateFlow<Map<String, Track>> = _tracks.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _progress = MutableStateFlow(LibraryProgress())
    val progress: StateFlow<LibraryProgress> = _progress.asStateFlow()
    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()
    private val _artVersion = MutableStateFlow(0)
    /** Bumped when new artwork files are written, so covers reload. */
    val artVersion: StateFlow<Int> = _artVersion.asStateFlow()

    private val engine = MixEngine(
        onMasterChanged = { id -> scope.launch { onMasterChanged(id) } },
        onEnded = { scope.launch { onTrackEnded() } },
    )
    val mix: StateFlow<MixState> = engine.state

    // ---- Persistence -------------------------------------------------------------------------------

    private var saveJob: Job? = null
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            val snap = LibraryStore.Snapshot(_tracks.value.values.toList(), _playlists.value, _settings.value)
            withContext(Dispatchers.IO) { store.save(snap) }
        }
    }

    // ---- Settings & folder ------------------------------------------------------------------------

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        engine.bars = _settings.value.transitionBars
        engine.style = _settings.value.transitionStyle
        replanCurrent()
        scheduleSave()
    }

    fun setMusicFolder(uri: Uri, name: String?) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        updateSettings { it.copy(musicFolderUri = uri.toString(), musicFolderName = name) }
        rescan()
    }

    private var scanJob: Job? = null
    private var lastScan = 0L

    /** Rescans the folder. Without [force], skips if it was scanned in the last few minutes. */
    fun rescan(force: Boolean = true) {
        val folder = _settings.value.musicFolderUri
        if (scanJob?.isActive == true) return
        // An empty library is always worth another look (e.g. files copied after choosing the folder).
        if (!force && _tracks.value.isNotEmpty() && System.currentTimeMillis() - lastScan < 5 * 60_000) return
        lastScan = System.currentTimeMillis()
        scanJob = scope.launch {
            _progress.update { it.copy(scanning = true, found = 0, scanError = null) }
            val imported = withContext(Dispatchers.IO) { runCatching { LibraryScanner.scanImported(context) }.getOrDefault(emptyList()) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (folder == null) com.harvey.blend.library.ScanResult(emptyList(), 0, 0)
                    else LibraryScanner.scan(context, Uri.parse(folder)) { n -> _progress.update { p -> p.copy(found = n) } }
                }
            }
            val scan = result.getOrNull()?.let { it.copy(audio = it.audio + imported) }
            if (scan == null) {
                val err = result.exceptionOrNull()
                _progress.update {
                    it.copy(scanning = false, scanError = when (err) {
                        is SecurityException -> "Blend lost permission to read this folder. Choose it again."
                        else -> err?.message ?: "Couldn't read the folder."
                    })
                }
                return@launch
            }
            _progress.update { it.copy(lastScanAudio = scan.audio.size - imported.size, lastScanOther = scan.otherFiles) }
            val found = scan.audio
            val existing = _tracks.value
            val now = System.currentTimeMillis()
            val merged = LinkedHashMap<String, Track>()
            for (f in found) {
                val id = LibraryScanner.idFor(f.relativePath, f.size)
                val old = existing[id]
                merged[id] = old?.copy(uri = f.uri.toString(), relativePath = f.relativePath, fileName = f.name)
                    ?: Track(
                        id = id, uri = f.uri.toString(), relativePath = f.relativePath, fileName = f.name, sizeBytes = f.size,
                        title = f.name.substringBeforeLast('.').substringAfter(" - "),
                        artist = if (" - " in f.name) f.name.substringBefore(" - ") else "",
                        genre = f.relativePath.substringBeforeLast('/', "").substringAfterLast('/'),
                        addedAt = now,
                    )
            }
            // Keep tracks that are in playlists even if the file is temporarily missing.
            val inPlaylists = _playlists.value.flatMap { it.trackIds }.toSet()
            for ((id, t) in existing) if (id !in merged && id in inPlaylists) merged[id] = t
            _tracks.value = merged
            _progress.update { it.copy(scanning = false) }
            scheduleSave()
            wakeAnalysis()
        }
    }

    /**
     * Copies songs the user picked (Downloads, Drive, another app…) into Blend's own storage,
     * so they work without choosing a music folder.
     */
    fun importSongs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            _progress.update { it.copy(scanning = true, found = 0, scanError = null) }
            val dir = LibraryScanner.importDir(context)
            var done = 0
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    runCatching {
                        val name = context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            ?: "Song ${System.currentTimeMillis()}.mp3"
                        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val target = java.io.File(dir, safe)
                        if (!target.exists()) {
                            context.contentResolver.openInputStream(u)?.use { ins -> target.outputStream().use { ins.copyTo(it) } }
                        }
                        done++
                        _progress.update { p -> p.copy(found = done) }
                    }
                }
            }
            _progress.update { it.copy(scanning = false) }
            rescan(force = true)
        }
    }

    /**
     * First launch with an empty library: download David's trial folder from Google Drive so the
     * app has music to work with straight away. Runs once (and again if it failed).
     */
    private fun loadStarterLibrary() {
        val prefs = context.getSharedPreferences("blend", Context.MODE_PRIVATE)
        if (prefs.getBoolean("starterDone", false)) return
        if (_tracks.value.isNotEmpty() || LibraryScanner.scanImported(context).isNotEmpty()) {
            prefs.edit().putBoolean("starterDone", true).apply()
            if (_tracks.value.isEmpty()) rescan(force = true)
            return
        }
        importFromDrive(STARTER_DRIVE_LINK) { ok -> if (ok) prefs.edit().putBoolean("starterDone", true).apply() }
    }

    /** Downloads the songs of a public Google Drive folder (or one file) into Blend's storage. */
    fun importFromDrive(link: String, onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            _progress.update { it.copy(scanning = true, found = 0, scanError = null, status = "Opening Drive link…") }
            val dir = LibraryScanner.importDir(context)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val folder = com.harvey.blend.library.DriveImporter.folderId(link)
                    val files = if (folder != null) com.harvey.blend.library.DriveImporter.listFolder(folder)
                    else {
                        val id = com.harvey.blend.library.DriveImporter.fileId(link)
                            ?: throw IllegalArgumentException("That doesn't look like a Google Drive link.")
                        listOf(com.harvey.blend.library.DriveImporter.DriveFile(id, ""))
                    }
                    if (files.isEmpty()) throw IllegalStateException("No music files found in that Drive folder.")
                    var ok = 0
                    val failed = ArrayList<String>()
                    files.forEachIndexed { i, f ->
                        _progress.update { it.copy(status = "Downloading ${i + 1} of ${files.size}: ${f.name.ifBlank { "song" }}", found = ok) }
                        runCatching { com.harvey.blend.library.DriveImporter.download(f.id, dir, f.name.ifBlank { null }) }
                            .onSuccess { ok++ }
                            .onFailure { failed += f.name.ifBlank { f.id } }
                    }
                    if (ok == 0) throw IllegalStateException("Couldn't download any song. Check the link is shared with “Anyone with the link”.")
                    ok to failed
                }
            }
            result.fold(
                onSuccess = { (ok, failed) ->
                    _progress.update {
                        it.copy(scanning = false, status = null,
                            scanError = if (failed.isEmpty()) null else "Downloaded $ok songs; ${failed.size} failed: ${failed.take(3).joinToString()}")
                    }
                },
                onFailure = { e ->
                    _progress.update {
                        it.copy(scanning = false, status = null, scanError = when (e) {
                            is java.net.UnknownHostException -> "No internet connection — couldn't reach Google Drive."
                            else -> e.message ?: "Drive download failed."
                        })
                    }
                },
            )
            onDone(result.isSuccess)
            rescan(force = true)
        }
    }

    // ---- Analysis queue -----------------------------------------------------------------------------

    private val analysisWake = MutableStateFlow(0)
    private fun wakeAnalysis() { analysisWake.update { it + 1 } }
    private val priority = MutableStateFlow<Set<String>>(emptySet())
    private val tagged = HashSet<String>()

    /** Analyse these first (e.g. tracks of the playlist being played). */
    fun prioritize(ids: Collection<String>) { priority.value = ids.toSet(); wakeAnalysis() }

    private fun startAnalysisQueue() {
        scope.launch(Dispatchers.Default) {
            analysisWake.collect {
                while (isActive) {
                    val all = _tracks.value.values
                    val pending = all.filter { it.analysis == null && it.analysisError == null }
                    _progress.update { p ->
                        val total = all.count { it.analysis != null || it.analysisError != null } + pending.size
                        p.copy(toAnalyze = total, analyzed = total - pending.size)
                    }
                    if (pending.isEmpty()) { _progress.update { it.copy(current = null) }; break }
                    val pr = priority.value
                    val next = pending.minWith(compareBy<Track>({ it.id !in pr }, { it.addedAt }, { it.relativePath }))
                    _progress.update { it.copy(current = next.title) }
                    analyzeOne(next)
                }
            }
        }
    }

    private suspend fun analyzeOne(t: Track) {
        val uri = Uri.parse(t.uri)
        // Tags + artwork first (fast), so the library looks right while audio analysis runs.
        if (t.id !in tagged) {
            tagged += t.id
            val tags = withContext(Dispatchers.IO) { runCatching { TagReader.read(context, uri, t.fileName) }.getOrNull() }
            if (tags != null) {
                val art = withContext(Dispatchers.IO) { TagReader.saveArtwork(context, t.id, tags.artwork) }
                updateTrackInternal(t.id) {
                    it.copy(
                        title = tags.title ?: it.title, artist = tags.artist ?: it.artist, album = tags.album ?: it.album,
                        genre = tags.genre ?: it.genre, tagKey = tags.key, tagBpm = tags.bpm,
                    )
                }
                if (art) _artVersion.update { it + 1 }
            }
        }
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val sink = AnalysisSink(TrackAnalyzer.SAMPLE_RATE)
                AudioDecoder.decode(context, uri, sink)
                val samples = sink.samples()
                if (samples.size < TrackAnalyzer.SAMPLE_RATE * 5) error("Too short or unreadable")
                TrackAnalyzer().analyze(samples)
            }
        }
        updateTrackInternal(t.id) { cur ->
            result.fold(
                onSuccess = { cur.copy(analysis = it, analysisError = null) },
                onFailure = { cur.copy(analysisError = it.message ?: "Could not read this file") },
            )
        }
        if (_queue.value.currentId == t.id || _queue.value.nextId == t.id) refreshEngineTracks()
    }

    fun reanalyze(id: String) {
        tagged -= id
        updateTrack(id) { it.copy(analysis = null, analysisError = null) }
        wakeAnalysis()
    }

    // ---- Track edits ----------------------------------------------------------------------------------

    private fun updateTrackInternal(id: String, f: (Track) -> Track) {
        _tracks.update { m -> val t = m[id] ?: return@update m; m + (id to f(t)) }
        scheduleSave()
    }

    fun updateTrack(id: String, f: (Track) -> Track) {
        scope.launch {
            updateTrackInternal(id, f)
            if (_queue.value.currentId == id || _queue.value.nextId == id) refreshEngineTracks()
        }
    }

    // ---- Playlists ---------------------------------------------------------------------------------------

    fun createPlaylist(name: String, kind: PlaylistKind, trackIds: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        _playlists.update { it + Playlist(id, name.ifBlank { "New playlist" }, kind, trackIds, createdAt = System.currentTimeMillis()) }
        scheduleSave()
        return id
    }

    fun updatePlaylist(id: String, f: (Playlist) -> Playlist) {
        _playlists.update { list -> list.map { if (it.id == id) f(it) else it } }
        scheduleSave()
        val q = _queue.value
        if (q.contextPlaylistId == id) {
            val p = _playlists.value.first { it.id == id }
            val cur = q.currentId
            _queue.value = q.copy(ids = p.trackIds, index = cur?.let { p.trackIds.indexOf(it) } ?: q.index)
            refreshNext()
        }
    }

    fun deletePlaylist(id: String) {
        _playlists.update { l -> l.filterNot { it.id == id } }
        scheduleSave()
    }

    fun addToPlaylist(id: String, trackIds: List<String>) =
        updatePlaylist(id) { p -> p.copy(trackIds = p.trackIds + trackIds.filterNot { it in p.trackIds }) }

    fun removeFromPlaylist(id: String, trackId: String) =
        updatePlaylist(id) { p -> p.copy(trackIds = p.trackIds - trackId, locked = p.locked - trackId) }

    fun moveInPlaylist(id: String, from: Int, to: Int) = updatePlaylist(id) { p ->
        val l = p.trackIds.toMutableList()
        if (from !in l.indices || to !in l.indices) return@updatePlaylist p
        l.add(to, l.removeAt(from))
        p.copy(trackIds = l)
    }

    fun toggleLock(id: String, trackId: String) = updatePlaylist(id) { p ->
        p.copy(locked = if (trackId in p.locked) p.locked - trackId else p.locked + trackId)
    }

    /** Re-orders the playlist as a story following its energy arc. */
    fun reshuffleStory(id: String, arc: EnergyArc? = null) = updatePlaylist(id) { p ->
        val a = arc ?: p.arc
        val ts = p.trackIds.mapNotNull { _tracks.value[it] }
        // Keep the playing track where it is so the music doesn't jump.
        val playing = _queue.value.takeIf { it.contextPlaylistId == id }?.currentId
        val locked = if (playing != null) p.locked + playing else p.locked
        p.copy(trackIds = SetMaker.buildOrder(ts, a, locked).map { it.id }, arc = a)
    }

    fun transitionScore(fromId: String, toId: String): TransitionScore? {
        val a = _tracks.value[fromId] ?: return null
        val b = _tracks.value[toId] ?: return null
        return SetMaker.score(a, b)
    }

    // ---- Export --------------------------------------------------------------------------------------------

    fun exportXml(format: ExportFormat, playlistIds: List<String>): String {
        val ps = _playlists.value.filter { it.id in playlistIds }
        val mapper = PathMapper(_settings.value.computerMusicPath)
        return when (format) {
            ExportFormat.REKORDBOX -> DjExport.rekordboxXml(ps, _tracks.value, mapper)
            ExportFormat.TRAKTOR -> DjExport.traktorNml(ps, _tracks.value, mapper)
        }
    }

    // ---- Playback --------------------------------------------------------------------------------------------

    private val prepMutex = Mutex()

    private fun deckTrack(t: Track) = DeckTrack(t.id, t.bpm, t.gridStart, t.mixIn, t.mixOut)

    private suspend fun pcmFor(t: Track): PcmTrack? = prepMutex.withLock {
        withContext(Dispatchers.IO) { runCatching { PcmCache.prepare(context, t.id, Uri.parse(t.uri)) }.getOrNull() }
    }

    /** Plays [ids] starting at [index], from [fromSec] seconds into the track. */
    fun play(ids: List<String>, index: Int, contextName: String?, playlistId: String?, fromSec: Double = 0.0) {
        val id = ids.getOrNull(index) ?: return
        val t = _tracks.value[id] ?: return
        _queue.value = QueueState(contextName, playlistId, ids, index, preparing = true)
        prioritize(ids)
        startService()
        scope.launch {
            val pcm = pcmFor(t)
            if (pcm == null) {
                updateTrackInternal(id) { it.copy(analysisError = "Could not decode this file") }
                _queue.update { it.copy(preparing = false) }
                return@launch
            }
            engine.play(deckTrack(_tracks.value[id] ?: t), pcm, fromSec)
            _queue.update { it.copy(preparing = false) }
            refreshNext()
        }
    }

    fun togglePlay() {
        if (mix.value.masterId == null) {
            val q = _queue.value
            if (q.currentId != null) play(q.ids, q.index, q.contextName, q.contextPlaylistId)
            return
        }
        engine.togglePlay()
    }

    fun seek(sec: Double) = engine.seek(sec)
    fun mixNow() = engine.mixNow()

    fun skipNext() {
        if (mix.value.nextReady) engine.skip() else {
            val q = _queue.value
            val nid = q.nextId ?: return
            val ni = q.ids.indexOf(nid).takeIf { it >= 0 }
            if (ni != null) play(q.ids, ni, q.contextName, q.contextPlaylistId) else play(listOf(nid), 0, q.contextName, null)
        }
    }

    fun previous() {
        val q = _queue.value
        if (mix.value.masterPos > 5 || q.index <= 0) engine.seek(0.0)
        else play(q.ids, q.index - 1, q.contextName, q.contextPlaylistId)
    }

    fun stop() {
        engine.stop()
        _queue.value = QueueState()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    /** Play [id] next instead of the list's next track. */
    fun setUpNext(id: String?) {
        _queue.update { it.copy(upNext = id) }
        refreshNext()
    }

    fun suggestions(limit: Int = 4): List<Pair<Track, TransitionScore>> {
        val q = _queue.value
        val cur = q.currentId?.let { _tracks.value[it] } ?: return emptyList()
        val pool = (q.ids.drop(q.index + 1).takeIf { it.isNotEmpty() } ?: _tracks.value.keys.toList())
            .mapNotNull { _tracks.value[it] }.filter { it.isAnalyzed }
        val arc = q.contextPlaylistId?.let { pid -> _playlists.value.firstOrNull { it.id == pid }?.arc } ?: EnergyArc.JOURNEY
        val target = arc.targetAt(q.index + 1, q.ids.size.coerceAtLeast(2))
        return SetMaker.suggestNext(cur, pool, target, limit)
    }

    private var nextJob: Job? = null
    private fun refreshNext() {
        nextJob?.cancel()
        nextJob = scope.launch {
            val q = _queue.value
            val nid = q.nextId
            val nt = nid?.let { _tracks.value[it] }
            if (nt == null || !_settings.value.autoMix) {
                engine.setNext(null, null); return@launch
            }
            val pcm = pcmFor(nt) ?: run { engine.setNext(null, null); return@launch }
            engine.setNext(deckTrack(_tracks.value[nt.id] ?: nt), pcm)
            withContext(Dispatchers.IO) { PcmCache.trim(context, setOfNotNull(q.currentId, nid)) }
        }
    }

    private fun refreshEngineTracks() {
        replanCurrent()
        refreshNext()
    }

    private fun replanCurrent() {
        val cur = _queue.value.currentId?.let { _tracks.value[it] } ?: return
        engine.replan(deckTrack(cur))
    }

    private fun onMasterChanged(id: String) {
        val q = _queue.value
        val idx = q.ids.indexOf(id)
        _queue.value = if (idx >= 0) q.copy(index = idx, upNext = null)
        else {
            // A picked track is now playing: continue the list after the track we left.
            val ids = q.ids.toMutableList().apply { add(q.index + 1, id) }
            q.copy(ids = ids, index = q.index + 1, upNext = null)
        }
        refreshNext()
    }

    private fun onTrackEnded() {
        val q = _queue.value
        val nid = q.nextId
        if (nid == null) { stop(); return }
        val ni = q.ids.indexOf(nid)
        if (ni >= 0) play(q.ids, ni, q.contextName, q.contextPlaylistId)
        else play(listOf(nid), 0, q.contextName, null)
    }

    private fun startService() {
        val i = Intent(context, PlaybackService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    // Must stay at the very end of the class: Kotlin runs property initialisers and init blocks in
    // order, so everything above has to exist before background work starts.
    init {
        val snap = store.load()
        _tracks.value = snap.tracks.associateBy { it.id }
        _playlists.value = snap.playlists
        _settings.value = snap.settings
        engine.bars = snap.settings.transitionBars
        engine.style = snap.settings.transitionStyle
        engine.start()
        startAnalysisQueue()
        runCatching { loadStarterLibrary() }
    }
}

/** Trial library: a shared Google Drive folder downloaded on first launch. */
const val STARTER_DRIVE_LINK = "https://drive.google.com/drive/folders/1_OODXYnKwuh9UWl07SGzMF7fDiWjG9MF"

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    REKORDBOX("Rekordbox XML", "xml", "text/xml"),
    TRAKTOR("Traktor NML", "nml", "application/xml"),
}

/** Holds the repository for the whole app process. */
class BlendApp : android.app.Application() {
    val repo: BlendRepository by lazy { BlendRepository(this) }
}

val Context.repo: BlendRepository get() = (applicationContext as BlendApp).repo
