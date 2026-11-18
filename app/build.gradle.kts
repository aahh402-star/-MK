package com.example.videograb

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MusicPlayerActivity : AppCompatActivity() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var txtSongTitle: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var songListView: ListView

    private val songList = mutableListOf<SongModel>()
    private var currentSongIndex = 0

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    data class SongModel(val title: String, val uri: Uri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        txtSongTitle = findViewById(R.id.txtSongTitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        songListView = findViewById(R.id.songListView)

        exoPlayer = ExoPlayer.Builder(this).build()

        checkAndRequestPermissions()

        btnPlayPause.setOnClickListener {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
                btnPlayPause.text = "▶ تشغيل"
            } else {
                exoPlayer.play()
                btnPlayPause.text = "⏸ إيقاف مؤقت"
            }
        }

        btnNext.setOnClickListener {
            if (songList.isNotEmpty()) {
                currentSongIndex = (currentSongIndex + 1) % songList.size
                playSong(currentSongIndex)
            }
        }

        btnPrev.setOnClickListener {
            if (songList.isNotEmpty()) {
                currentSongIndex = if (currentSongIndex - 1 < 0) songList.size - 1 else currentSongIndex - 1
                playSong(currentSongIndex)
            }
        }

        songListView.setOnItemClickListener { _, _, position, _ ->
            currentSongIndex = position
            playSong(currentSongIndex)
        }
    }

    private fun checkAndRequestPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_CODE)
        } else {
            loadAudioFiles()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAudioFiles()
            } else {
                Toast.makeText(this, "تم رفض الصلاحية، لا يمكن عرض الأغاني", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadAudioFiles() {
        songList.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, null)
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn) ?: "بدون عنوان"
                val audioUri = ContentUris.withAppendedId(collection, id)
                songList.add(SongModel(title, audioUri))
            }
        }

        val titles = songList.map { it.title }
        songListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)

        if (songList.isNotEmpty()) {
            Toast.makeText(this, "تم العثور على ${songList.size} مقطع صوتي", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لم يتم العثور على ملفات صوتية في الذاكرة", Toast.LENGTH_LONG).show()
        }
    }

    private fun playSong(index: Int) {
        if (songList.isEmpty()) return
        val song = songList[index]
        txtSongTitle.text = song.title

        exoPlayer.setMediaItem(MediaItem.fromUri(song.uri))
        exoPlayer.prepare()
        exoPlayer.play()
        btnPlayPause.text = "⏸ إيقاف مؤقت"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
    }
}
