package com.stellar.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val preferences by lazy {
        getSharedPreferences("stellar_collection", MODE_PRIVATE)
    }

    private val persistenceListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            saveCollection()
        }
    }

    private val saveTimer = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) {
                saveCollection()
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val musicPlayer = ExoPlayer.Builder(this).build()
        player = musicPlayer

        musicPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

        musicPlayer.setHandleAudioBecomingNoisy(true)
        musicPlayer.setWakeMode(C.WAKE_MODE_LOCAL)

        restoreCollection(musicPlayer)
        musicPlayer.addListener(persistenceListener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaSession.Builder(this, musicPlayer)
            .setSessionActivity(openApp)
            .build()

        handler.postDelayed(saveTimer, 5000)
    }

    private fun saveCollection() {
        val musicPlayer = player ?: return

        try {
            val tracks = JSONArray()

            for (index in 0 until musicPlayer.mediaItemCount) {
                val item = musicPlayer.getMediaItemAt(index)
                val uri = item.localConfiguration?.uri ?: continue

                val track = JSONObject()
                    .put("uri", uri.toString())
                    .put("title", item.mediaMetadata.title?.toString() ?: "Audio")
                    .put("artist", item.mediaMetadata.artist?.toString() ?: "")

                tracks.put(track)
            }

            val collection = JSONObject()
                .put("version", 1)
                .put("tracks", tracks)
                .put(
                    "currentIndex",
                    musicPlayer.currentMediaItemIndex.coerceAtLeast(0)
                )
                .put(
                    "positionMs",
                    musicPlayer.currentPosition.coerceAtLeast(0L)
                )
                .put("shuffle", musicPlayer.shuffleModeEnabled)
                .put("repeat", musicPlayer.repeatMode)

            preferences.edit()
                .putString("collection", collection.toString())
                .apply()
        } catch (error: Exception) {
            android.util.Log.w(
                "Stellar",
                "Could not save music collection",
                error
            )
        }
    }

    private fun restoreCollection(musicPlayer: ExoPlayer) {
        val saved = preferences.getString("collection", null) ?: return

        try {
            val collection = JSONObject(saved)
            val tracks = collection.optJSONArray("tracks") ?: return
            val items = mutableListOf<MediaItem>()

            for (index in 0 until tracks.length()) {
                val track = tracks.optJSONObject(index) ?: continue
                val address = track.optString("uri")

                if (address.isBlank()) continue

                val item = MediaItem.Builder()
                    .setUri(address)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.optString("title", "Audio"))
                            .setArtist(
                                track.optString("artist")
                                    .takeIf { it.isNotBlank() }
                            )
                            .build()
                    )
                    .build()

                items.add(item)
            }

            musicPlayer.shuffleModeEnabled =
                collection.optBoolean("shuffle", false)

            val savedRepeat = collection.optInt(
                "repeat",
                Player.REPEAT_MODE_OFF
            )

            musicPlayer.repeatMode = when (savedRepeat) {
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }

            if (items.isNotEmpty()) {
                val currentIndex = collection.optInt("currentIndex", 0)
                    .coerceIn(0, items.lastIndex)

                val position = collection.optLong("positionMs", 0L)
                    .coerceAtLeast(0L)

                musicPlayer.setMediaItems(items, currentIndex, position)
            }

            // Restore the queue without starting playback.
            // The activity prepares the player when Play is tapped.
            musicPlayer.playWhenReady = false

        } catch (error: Exception) {
            android.util.Log.w(
                "Stellar",
                "Could not restore music collection",
                error
            )
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacks(saveTimer)

        player?.let { musicPlayer ->
            saveCollection()
            musicPlayer.removeListener(persistenceListener)
            musicPlayer.release()
        }

        session?.release()
        session = null
        player = null

        super.onDestroy()
    }
}
