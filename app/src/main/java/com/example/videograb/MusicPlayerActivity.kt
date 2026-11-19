package com.example.videograb

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

class MusicPlayerActivity : AppCompatActivity() {

    data class Item(val title: String, val uri: Uri)

    private lateinit var txtHeader: TextView
    private lateinit var txtSongTitle: TextView
    private lateinit var txtTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var listView: ListView

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // 0 = الرئيسية، 1 = الموسيقى، 2 = الفيديوات، 3 = قوائم التشغيل، 4 = داخل قائمة
    private var mode = 0
    private var curPlaylist = ""
    private var music = listOf<Item>()
    private var videos = listOf<Item>()
    private var shown = listOf<Item>()
    private var playlistNames = listOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false

    private val prefs by lazy { getSharedPreferences("music_lib", MODE_PRIVATE) }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateUi()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        txtHeader = findViewById(R.id.txtHeader)
        txtSongTitle = findViewById(R.id.txtSongTitle)
        txtTime = findViewById(R.id.txtTime)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        listView = findViewById(R.id.songListView)

        if (!hasPermission()) {
            ActivityCompat.requestPermissions(this, neededPermissions(), 100)
        }

        showRoot()

        listView.setOnItemClickListener { _, _, pos, _ -> onItemClick(pos) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            onItemLongClick(pos)
            true
        }

        btnPlayPause.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) txtTime.text = "${fmt(p.toLong())} / ${fmt(seekBar.max.toLong())}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                controller?.seekTo(seekBar.progress.toLong())
                dragging = false
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (mode) {
                    0 -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    4 -> showPlaylists()
                    else -> showRoot()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                updateUi()
            } catch (e: Exception) {
                toast("تعذر الاتصال بالمشغل")
            }
        }, ContextCompat.getMainExecutor(this))
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    // ---------------- الصلاحيات ----------------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasPermission(): Boolean = neededPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermission(): Boolean {
        if (hasPermission()) return true
        ActivityCompat.requestPermissions(this, neededPermissions(), 100)
        toast("اسمح بالوصول للملفات ثم افتحها مرة ثانية")
        return false
    }

    // ---------------- التخزين (قوائم التشغيل والأسماء) ----------------

    private fun loadPlaylists(): LinkedHashMap<String, MutableList<String>> {
        val o = JSONObject(prefs.getString("playlists", "{}") ?: "{}")
        val m = LinkedHashMap<String, MutableList<String>>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = o.getJSONArray(k)
            val l = mutableListOf<String>()
            for (i in 0 until a.length()) l.add(a.getString(i))
            m[k] = l
        }
        return m
    }

    private fun savePlaylists(m: Map<String, List<String>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs.edit().putString("playlists", o.toString()).apply()
    }

    private fun loadNames(): JSONObject = JSONObject(prefs.getString("names", "{}") ?: "{}")

    private fun nameOf(i: Item): String = loadNames().optString(i.uri.toString(), i.title)

    private fun setName(i: Item, newName: String) {
        val n = loadNames()
        n.put(i.uri.toString(), newName)
        prefs.edit().putString("names", n.toString()).apply()
    }

    // ---------------- الشاشات ----------------

    private fun setList(rows: List<String>) {
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun showRoot() {
        mode = 0
        txtHeader.text = "📁 المكتبة"
        setList(listOf("🎵 الموسيقى", "🎬 الفيديوات", "📋 قوائم التشغيل"))
    }

    private fun showMusic() {
        if (!checkPermission()) return
        mode = 1
        music = loadAudio()
        shown = music
        txtHeader.text = "🎵 الموسيقى (${music.size})  •  اضغط مطولاً للخيارات"
        setList(shown.map { nameOf(it) })
    }

    private fun showVideos() {
        if (!checkPermission()) return
        mode = 2
        videos = loadItems(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
        txtHeader.text = "🎬 الفيديوات (${videos.size})"
        setList(videos.map { it.title })
    }

    private fun showPlaylists() {
        mode = 3
        val pl = loadPlaylists()
        playlistNames = pl.keys.toList()
        txtHeader.text = "📋 قوائم التشغيل  •  اضغط مطولاً للخيارات"
        setList(playlistNames.map { "📋 $it (${pl[it]?.size ?: 0})" } + "➕ قائمة جديدة")
    }

    private fun showPlaylist(name: String) {
        if (!checkPermission()) return
        mode = 4
        curPlaylist = name
        if (music.isEmpty()) music = loadAudio()
        val uris = loadPlaylists()[name] ?: mutableListOf()
        shown = uris.mapNotNull { u -> music.find { it.uri.toString() == u } }
        txtHeader.text = "📋 $name (${shown.size})"
        setList(shown.map { nameOf(it) })
    }

    private fun refreshList() {
        when (mode) {
            1 -> showMusic()
            3 -> showPlaylists()
            4 -> showPlaylist(curPlaylist)
        }
    }

    // ---------------- النقر ----------------

    private fun onItemClick(pos: Int) {
        when (mode) {
            0 -> when (pos) {
                0 -> showMusic()
                1 -> showVideos()
                else -> showPlaylists()
            }
            1, 4 -> playFrom(pos)
            2 -> playVideo(pos)
            3 -> {
                if (pos == playlistNames.size) {
                    askText("اسم القائمة الجديدة", "") { n ->
                        if (createPlaylist(n) != null) showPlaylists()
                    }
                } else {
                    showPlaylist(playlistNames[pos])
                }
            }
        }
    }

    private fun onItemLongClick(pos: Int) {
        when (mode) {
            1 -> {
                val item = shown.getOrNull(pos) ?: return
                AlertDialog.Builder(this)
                    .setTitle(nameOf(item))
                    .setItems(arrayOf("✏️ إعادة تسمية", "➕ إضافة إلى قائمة")) { _, i ->
                        if (i == 0) renameSong(item) else addToPlaylist(item)
                    }
                    .show()
            }
            4 -> {
                val item = shown.getOrNull(pos) ?: return
                AlertDialog.Builder(this)
                    .setTitle(nameOf(item))
                    .setItems(arrayOf("✏️ إعادة تسمية", "➖ إزالة من القائمة")) { _, i ->
                        if (i == 0) renameSong(item) else removeFromPlaylist(curPlaylist, item)
                    }
                    .show()
            }
            3 -> {
                if (pos >= playlistNames.size) return
                val name = playlistNames[pos]
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setItems(arrayOf("✏️ إعادة تسمية القائمة", "🗑 حذف القائمة")) { _, i ->
                        if (i == 0) renamePlaylist(name) else deletePlaylist(name)
                    }
                    .show()
            }
        }
    }

    // ---------------- عمليات القوائم والأسماء ----------------

    private fun renameSong(item: Item) {
        askText("الاسم الجديد", nameOf(item)) { n ->
            val t = n.trim()
            if (t.isNotEmpty()) {
                setName(item, t)
                refreshList()
            }
        }
    }

    private fun createPlaylist(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val pl = loadPlaylists()
        if (!pl.containsKey(n)) {
            pl[n] = mutableListOf()
            savePlaylists(pl)
        }
        return n
    }

    private fun addToPlaylist(item: Item) {
        val names = loadPlaylists().keys.toList()
        val labels = (names + "➕ قائمة جديدة").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("إضافة إلى قائمة")
            .setItems(labels) { _, i ->
                if (i == names.size) {
                    askText("اسم القائمة الجديدة", "") { n ->
                        createPlaylist(n)?.let { addTo(it, item) }
                    }
                } else {
                    addTo(names[i], item)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun addTo(name: String, item: Item) {
        val pl = loadPlaylists()
        val l = pl.getOrPut(name) { mutableListOf() }
        val u = item.uri.toString()
        if (l.contains(u)) {
            toast("موجودة بالقائمة")
        } else {
            l.add(u)
            savePlaylists(pl)
            toast("انضافت إلى $name")
        }
    }

    private fun removeFromPlaylist(name: String, item: Item) {
        val pl = loadPlaylists()
        pl[name]?.remove(item.uri.toString())
        savePlaylists(pl)
        showPlaylist(name)
    }

    private fun renamePlaylist(old: String) {
        askText("الاسم الجديد للقائمة", old) { n ->
            val t = n.trim()
            val pl = loadPlaylists()
            if (t.isEmpty()) return@askText
            if (pl.containsKey(t) && t != old) {
                toast("يوجد قائمة بهذا الاسم")
                return@askText
            }
            val out = LinkedHashMap<String, MutableList<String>>()
            pl.forEach { (k, v) -> out[if (k == old) t else k] = v }
            savePlaylists(out)
            showPlaylists()
        }
    }

    private fun deletePlaylist(name: String) {
        AlertDialog.Builder(this)
            .setTitle("حذف القائمة؟")
            .setMessage(name)
            .setPositiveButton("حذف") { _, _ ->
                val pl = loadPlaylists()
                pl.remove(name)
                savePlaylists(pl)
                showPlaylists()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSelection(et.text.length)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("حفظ") { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- جلب الملفات ----------------

    private fun loadAudio(): List<Item> = loadItems(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"
    )

    private fun loadItems(collection: Uri, selection: String?): List<Item> {
        val out = mutableListOf<Item>()
        val projection = arrayOf(BaseColumns._ID, MediaStore.MediaColumns.TITLE)
        contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = c.getString(titleCol) ?: "بدون عنوان"
                out.add(Item(title, ContentUris.withAppendedId(collection, id)))
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    // ---------------- التشغيل ----------------

    private fun playFrom(index: Int) {
        val c = controller
        if (c == null) {
            toast("المشغل لسا يتجهز، جرب مرة ثانية")
            return
        }
        val items = shown.map {
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(nameOf(it)).build())
                .build()
        }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    private fun playVideo(index: Int) {
        val v = videos[index]
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(v.uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("ما لقيت تطبيق يشغّل الفيديو")
        }
    }

    // ---------------- الواجهة وشريط التقدم ----------------

    private fun updateUi() {
        val c = controller ?: return
        val has = c.mediaItemCount > 0 && c.currentMediaItem != null
        val t = c.mediaMetadata.title
        if (has && !t.isNullOrEmpty()) {
            txtSongTitle.text = t
            txtSongTitle.visibility = View.VISIBLE
        } else {
            txtSongTitle.visibility = View.GONE
        }
        seekBar.isEnabled = has
        btnPlayPause.text = if (c.isPlaying) "⏸ إيقاف مؤقت" else "▶ تشغيل"
        tick()
    }

    private fun tick() {
        val c = controller
        if (c == null || c.mediaItemCount == 0 || c.currentMediaItem == null) {
            if (!dragging) {
                seekBar.progress = 0
                txtTime.text = "0:00 / 0:00"
            }
            return
        }
        val dur = c.duration
        if (dur > 0 && !dragging) {
            seekBar.max = dur.toInt()
            seekBar.progress = c.currentPosition.toInt().coerceAtMost(dur.toInt())
            txtTime.text = "${fmt(c.currentPosition)} / ${fmt(dur)}"
        }
    }

    private fun fmt(ms: Long): String {
        if (ms < 0) return "0:00"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}