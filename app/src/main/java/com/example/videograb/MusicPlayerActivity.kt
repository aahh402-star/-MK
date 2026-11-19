package com.example.videograb

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MusicPlayerActivity : AppCompatActivity() {

    data class Item(val title: String, val uri: Uri)

    private lateinit var txtHeader: TextView
    private lateinit var txtSongTitle: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var listView: ListView

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var mode = 0 // 0 = المجلدات، 1 = الموسيقى، 2 = الفيديوات
    private var music = listOf<Item>()
    private var videos = listOf<Item>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        txtHeader = findViewById(R.id.txtHeader)
        txtSongTitle = findViewById(R.id.txtSongTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        listView = findViewById(R.id.songListView)

        if (!hasPermission()) {
            ActivityCompat.requestPermissions(this, neededPermissions(), 100)
        }

        showFolders()

        listView.setOnItemClickListener { _, _, pos, _ ->
            when (mode) {
                0 -> openFolder(if (pos == 0) 1 else 2)
                1 -> playMusic(pos)
                2 -> playVideo(pos)
            }
        }

        btnPlayPause.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode != 0) {
                    showFolders()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
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
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        super.onStop()
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasPermission(): Boolean = neededPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showFolders() {
        mode = 0
        txtHeader.text = "📁 المكتبة"
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            listOf("🎵 الموسيقى", "🎬 الفيديوات")
        )
    }

    private fun openFolder(m: Int) {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(this, neededPermissions(), 100)
            toast("اسمح بالوصول للملفات ثم افتح المجلد مرة ثانية")
            return
        }
        mode = m
        if (m == 1) {
            music = loadItems(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_ALARM} = 0"
            )
            txtHeader.text = "🎵 الموسيقى (${music.size})"
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, music.map { it.title }
            )
        } else {
            videos = loadItems(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            txtHeader.text = "🎬 الفيديوات (${videos.size})"
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, videos.map { it.title }
            )
        }
    }

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

    private fun playMusic(index: Int) {
        val c = controller
        if (c == null) {
            toast("المشغل لسا يتجهز، جرب مرة ثانية")
            return
        }
        val items = music.map {
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
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

    private fun updateUi() {
        val c = controller ?: return
        txtSongTitle.text = c.mediaMetadata.title ?: "اختر أغنية"
        btnPlayPause.text = if (c.isPlaying) "⏸ إيقاف مؤقت" else "▶ تشغيل"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}