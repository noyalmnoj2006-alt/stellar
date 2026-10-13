package com.stellar.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class MainActivity : Activity() {
    private var controller: MediaController? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var time: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var shuffle: Button
    private lateinit var repeat: Button
    private lateinit var queue: LinearLayout

    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                300
            )
        }

        val token = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(this, token).buildAsync()

        Futures.addCallback(
            controllerFuture,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController?) {
                    if (isDestroyed) return
                    controller = result
                    result?.addListener(object : Player.Listener {
                        override fun onEvents(
                            player: Player,
                            events: Player.Events
                        ) {
                            refreshPlayer()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            toast("Unable to play: ${error.errorCodeName}")
                        }
                    })
                    refreshPlayer()
                }

                override fun onFailure(t: Throwable) {
                    if (!isDestroyed) {
                        status.text = "Player connection failed. Reopen Stellar."
                        toast(t.message ?: "Could not connect to player")
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )

        handler.post(ticker)
    }

    private fun createInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D17"))
            isFillViewport = true
            clipToPadding = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        root.addView(label("STELLAR", 30f, "#F5F2FF", true))
        root.addView(label("Your music. Your universe.", 14f, "#A6ABC1"))

        val sourceRow = row()
        sourceRow.addView(button("＋ Local music") { importMusic() }, weighted())
        sourceRow.addView(button("↗ Online link") { showStreamDialog() }, weighted())
        root.addView(sourceRow)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(20))
            background = rounded("#181B2D", 28f)
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })

        val emblem = label("✦", 82f, "#C4B5FD", true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#30234D"),
                    Color.parseColor("#1A3445")
                )
            ).apply { cornerRadius = dp(28).toFloat() }
        }
        card.addView(emblem, LinearLayout.LayoutParams(dp(170), dp(170)))

        title = label("Find your next favourite", 22f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(6))
        }
        card.addView(title)

        subtitle = label("Add music to begin", 14f, "#A6ABC1").apply {
            gravity = Gravity.CENTER
        }
        card.addView(subtitle)

        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    controller?.let { player ->
                        val duration = player.duration
                        if (duration > 0 && duration != C.TIME_UNSET &&
                            player.isCurrentMediaItemSeekable
                        ) {
                            player.seekTo(duration * bar.progress / 1000L)
                        }
                    }
                    dragging = false
                }

                override fun onProgressChanged(
                    bar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {}
            })
        }
        card.addView(seek, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(20)
        })

        time = label("0:00 / 0:00", 12f, "#A6ABC1").apply {
            gravity = Gravity.CENTER
        }
        card.addView(time)

        val transport = row()
        transport.addView(button("Previous") {
            withPlayer { it.seekToPreviousMediaItem() }
        }, weighted())

        play = button("Play") {
            withPlayer {
                if (it.isPlaying) {
                    it.pause()
                } else if (it.mediaItemCount > 0) {
                    if (it.playbackState == Player.STATE_ENDED) {
                        it.seekToDefaultPosition()
                    }
                    it.prepare()
                    it.play()
                } else {
                    toast("Add music first")
                }
            }
        }
        play.setTextColor(Color.parseColor("#141020"))
        play.background = rounded("#B6A1FF", 18f)
        transport.addView(play, weighted())

        transport.addView(button("Next") {
            withPlayer { it.seekToNextMediaItem() }
        }, weighted())
        card.addView(transport)

        val modes = row()
        shuffle = button("Shuffle: off") {
            withPlayer { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        repeat = button("Repeat: off") {
            withPlayer {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        modes.addView(shuffle, weighted())
        modes.addView(repeat, weighted())
        root.addView(modes)

        status = label("Connecting player…", 13f, "#A6ABC1")
        root.addView(status)

        root.addView(label("UP NEXT", 15f, "#C4B5FD", true).apply {
            setPadding(0, dp(22), 0, dp(10))
        })

        queue = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(queue)

        root.addView(button("Clear queue") {
            withPlayer {
                it.stop()
                it.clearMediaItems()
            }
        })

        root.addView(label(
            "Starter edition • Queue is not saved after the player closes.",
            12f,
            "#7E849E"
        ))
    }

    private fun importMusic() {
        if (controller == null) {
            toast("Player is connecting. Try again shortly.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, 100)
    }

    @Deprecated("Uses the platform activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        val clips = data.clipData
        if (clips != null) {
            for (i in 0 until clips.itemCount) {
                uris.add(clips.getItemAt(i).uri)
            }
        } else {
            data.data?.let { uris.add(it) }
        }

        val items = uris.distinct().map { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}

            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(fileName(uri))
                        .setArtist("Local music")
                        .build()
                )
                .build()
        }
        addMusic(items)
    }

    private fun fileName(uri: Uri): String {
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0) {
                        return cursor.getString(column) ?: "Audio"
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Audio"
    }

    private fun showStreamDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/song.mp3"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Play an online stream")
            .setMessage(
                "Paste a direct HTTPS audio or HLS stream URL. " +
                "Web pages and Spotify/YouTube links are not supported."
            )
            .setView(input)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text.toString().trim()
                val uri = Uri.parse(address)

                if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                    input.error = "Enter a valid HTTPS audio URL"
                    return@setOnClickListener
                }

                addMusic(listOf(
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Online stream")
                                .setArtist(uri.host)
                                .build()
                        )
                        .build()
                ))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addMusic(items: List<MediaItem>) {
        if (items.isEmpty()) return

        withPlayer { player ->
            val wasEmpty = player.mediaItemCount == 0
            player.addMediaItems(items)
            if (wasEmpty) {
                player.prepare()
                player.play()
            }
            toast("Added ${items.size} audio item(s)")
            refreshPlayer()
        }
    }

    private fun refreshPlayer() {
        val player = controller ?: return

        title.text = player.mediaMetadata.title ?: "Find your next favourite"
        subtitle.text = player.mediaMetadata.artist ?: "Add music to begin"
        play.text = if (player.isPlaying) "Pause" else "Play"
        shuffle.text = if (player.shuffleModeEnabled) {
            "Shuffle: on"
        } else {
            "Shuffle: off"
        }

        repeat.text = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> "Repeat: all"
            Player.REPEAT_MODE_ONE -> "Repeat: one"
            else -> "Repeat: off"
        }

        status.text = when {
            player.playerError != null -> "Playback failed. Check the file or link."
            player.mediaItemCount == 0 -> "Ready • Add local music or an online link"
            player.playbackState == Player.STATE_BUFFERING -> "Buffering…"
            player.isPlaying -> "Playing • ${player.mediaItemCount} item(s) in queue"
            else -> "Paused • ${player.mediaItemCount} item(s) in queue"
        }

        queue.removeAllViews()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val prefix = if (i == player.currentMediaItemIndex) "♫ " else "${i + 1}. "
            val itemButton = button(
                prefix + (item.mediaMetadata.title ?: "Audio")
            ) {
                player.seekToDefaultPosition(i)
                player.prepare()
                player.play()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            queue.addView(itemButton)
        }

        updateProgress()
    }

    private fun updateProgress() {
        val player = controller ?: return
        val duration = player.duration
        val knownDuration = duration > 0 && duration != C.TIME_UNSET

        seek.isEnabled = knownDuration && player.isCurrentMediaItemSeekable

        if (!dragging) {
            seek.progress = if (knownDuration) {
                ((player.currentPosition * 1000L) / duration)
                    .toInt()
                    .coerceIn(0, 1000)
            } else {
                0
            }
        }

        time.text = "${formatTime(player.currentPosition)} / " +
            if (knownDuration) formatTime(duration) else "LIVE / unknown"
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private fun withPlayer(action: (MediaController) -> Unit) {
        val player = controller
        if (player == null) {
            toast("Player is connecting. Try again shortly.")
        } else {
            action(player)
        }
    }

    private fun label(
        text: String,
        size: Float,
        color: String,
        bold: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(
        text: String,
        action: () -> Unit
    ) = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.parseColor("#F5F2FF"))
        background = rounded("#24283E", 16f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minimumHeight = dp(50)
        minimumWidth = 0
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        }
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(54), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun rounded(color: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        controller = null
        super.onDestroy()
    }
}
