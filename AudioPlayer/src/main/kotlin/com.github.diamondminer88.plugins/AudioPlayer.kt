package com.github.diamondminer88.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.widget.FrameLayout.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.discord.api.message.attachment.MessageAttachment
import com.discord.app.AppActivity
import com.discord.stores.StoreMessages
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.google.android.material.card.MaterialCardView
import com.lytefast.flexinput.R
import java.io.File
import java.util.*

@Suppress("unused")
@SuppressLint("SetTextI18n")
@AliucordPlugin
class AudioPlayer : Plugin() {
    private val playerBarId = View.generateViewId()
    private val attachmentCardId = Utils.getResId("chat_list_item_attachment_card", "id")
    private val validFileExtensions = arrayOf(
        "webm", "mp3", "aac", "m4a", "wav", "flac", "wma", "opus", "ogg"
    )

    private val allPlayerBarResets = mutableListOf<() -> Unit>()

    private var globalCurrentPlayer: MediaPlayer? = null
    private var globalCleanup: (() -> Unit)? = null

    private fun isAudioFile(filename: String?): Boolean {
        if (filename == null) return false
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return validFileExtensions.contains(ext)
    }

    private fun msToTime(ms: Long): String {
        val hrs = ms / 3_600_000
        val mins = ms / 60000
        val secs = ms / 1000 % 60

        return if (hrs == 0L)
            String.format("%d:%02d", mins, secs)
        else
            String.format("%d:%d:%02d", hrs, mins, secs)
    }

    private fun stopCurrentPlayer() {
        try { globalCleanup?.invoke() } catch (_: Exception) {}
        try { globalCurrentPlayer?.stop() } catch (_: Exception) {}
        try { globalCurrentPlayer?.release() } catch (_: Exception) {}
        globalCurrentPlayer = null
        globalCleanup = null

        allPlayerBarResets.forEach { it() }
    }

    override fun start(context: Context) {
        patcher.after<WidgetChatListAdapterItemAttachment>(
            "configureFileData",
            MessageAttachment::class.java,
            MessageRenderContext::class.java
        ) {
            val messageAttachment = it.args[0] as MessageAttachment
            val root = WidgetChatListAdapterItemAttachment.`access$getBinding$p`(this).root as ConstraintLayout
            val card = root.findViewById<MaterialCardView>(attachmentCardId)
            val ctx = root.context

            card.findViewById<MaterialCardView>(playerBarId)?.let { card.removeView(it) }
            val loadingBarId = playerBarId + 1
            card.findViewById<ProgressBar>(loadingBarId)?.let { card.removeView(it) }

            if (!isAudioFile(messageAttachment.filename)) return@after

            val loadingBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = loadingBarId
                isIndeterminate = true
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, 6.dp).apply {
                    gravity = Gravity.BOTTOM
                }
            }
            card.addView(loadingBar)

            Utils.threadPool.execute {
                val isOgg = messageAttachment.filename.lowercase(Locale.ROOT).endsWith(".ogg")
                val localOggFile = if (isOgg) File(ctx.cacheDir, "audio.ogg") else null
                if (isOgg) {
                    localOggFile!!.deleteOnExit()
                    Http.simpleDownload(messageAttachment.url, localOggFile)
                }
                val metadataPath = if (isOgg) localOggFile!!.absolutePath else messageAttachment.url

                var duration: Long = try {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(metadataPath)
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        durationStr?.toLong() ?: 0L
                    }
                } catch (e: Throwable) {
                    0L
                }

                Utils.mainThread.post {

                    card.findViewById<ProgressBar>(loadingBarId)?.let { card.removeView(it) }

                    if (duration == -1L) {
                        Toast.makeText(ctx, "Failed to load audio metadata.", Toast.LENGTH_SHORT).show()
                        return@post
                    }

                    val playerCard = MaterialCardView(ctx).apply {
                        id = playerBarId
                        cardElevation = 4.dp.toFloat()
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                            topMargin = 60.dp
                            gravity = Gravity.BOTTOM
                        }

                        isClickable = false
                        isFocusable = false
                        foreground = null
                        stateListAnimator = null
                    }

                    val playerBar = LinearLayout(ctx, null, 0, R.i.UiKit_ViewGroup).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(24, 24, 24, 24)
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        setOnClickListener {  }

                        var mediaPlayer: MediaPlayer? = null
                        var isPrepared = false
                        var isPreparing = false
                        var playing = false
                        var timer: Timer? = null

                        var buttonView: ImageButton? = null
                        var progressView: TextView? = null
                        var sliderView: SeekBar? = null
                        lateinit var playIcon: android.graphics.drawable.Drawable
                        lateinit var pauseIcon: android.graphics.drawable.Drawable
                        lateinit var rewindIcon: android.graphics.drawable.Drawable

                        fun resetBar() {
                            isPrepared = false
                            isPreparing = false
                            playing = false
                            timer?.cancel()
                            timer = null
                            mediaPlayer?.release()
                            mediaPlayer = null
                            Utils.mainThread.post {
                                buttonView?.background = playIcon
                                buttonView?.isEnabled = true
                                sliderView?.progress = 0
                                progressView?.text = "0:00 / ${msToTime(duration)}"
                            }
                        }

                        playIcon = ContextCompat.getDrawable(ctx, com.google.android.exoplayer2.ui.R.b.exo_controls_play)!!
                        pauseIcon = ContextCompat.getDrawable(ctx, com.google.android.exoplayer2.ui.R.b.exo_controls_pause)!!
                        rewindIcon = ContextCompat.getDrawable(ctx, com.yalantis.ucrop.R.c.ucrop_rotate)!!

                        buttonView = ImageButton(ctx).apply {
                            background = playIcon
                            setPadding(16, 16, 16, 16)
                            isEnabled = true
                        }

                        progressView = TextView(ctx, null, 0, R.i.UiKit_TextView).apply {
                            text = "0:00 / ${msToTime(duration)}"
                            setPadding(16, 16, 16, 16)
                        }

                        sliderView = SeekBar(ctx, null, 0, R.i.UiKit_SeekBar).apply {
                            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { weight = 0.5f }
                            val p = 2.dp
                            setPadding(p, p, p, 0)
                            gravity = Gravity.CENTER
                            progress = 0
                            thumb = null
                            max = 500
                        }

                        fun scheduleUpdater() {
                            timer?.cancel()
                            timer = Timer()
                            timer!!.scheduleAtFixedRate(
                                object : TimerTask() {
                                    override fun run() {
                                        if (!playing || duration == 0L || mediaPlayer == null)
                                            return
                                        Utils.mainThread.post {
                                            progressView?.text =
                                                "${msToTime(mediaPlayer!!.currentPosition.toLong())} / ${msToTime(duration)}"
                                            sliderView?.progress =
                                                (500 * mediaPlayer!!.currentPosition / duration).toInt()
                                        }
                                    }
                                },
                                2000,
                                250
                            )
                        }

                        fun updatePlaying() {
                            if (!isPrepared || mediaPlayer == null) return
                            try {
                                if (playing) {
                                    mediaPlayer!!.start()
                                    scheduleUpdater()
                                    buttonView?.background = pauseIcon
                                } else {
                                    mediaPlayer!!.pause()
                                    timer?.cancel()
                                    timer = null
                                    buttonView?.background = playIcon
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Media error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }

                        sliderView?.setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                                override fun onStopTrackingTouch(seekBar: SeekBar) {}
                                var prevProgress = 0
                                override fun onProgressChanged(
                                    seekBar: SeekBar,
                                    progress: Int,
                                    fromUser: Boolean
                                ) {
                                    if (!fromUser) return
                                    if (!isPrepared || mediaPlayer == null) {
                                        seekBar.progress = prevProgress
                                        return
                                    }
                                    prevProgress = progress
                                    mediaPlayer!!.seekTo(
                                        (progress.div(500f) * duration).toInt()
                                    )
                                    progressView?.text =
                                        "${msToTime(mediaPlayer!!.currentPosition.toLong())} / ${msToTime(duration)}"
                                }
                            }
                        )

                        allPlayerBarResets.add(::resetBar)

                        buttonView?.setOnClickListener {
                            val isOggFile = messageAttachment.filename.lowercase(Locale.ROOT).endsWith(".ogg")
                            val url = messageAttachment.url

                            if (isPreparing) {
                                Toast.makeText(ctx, "Please wait, loading audio...", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }

                            if (isPrepared && mediaPlayer != null) {
                                if (playing) {

                                    playing = false
                                    mediaPlayer!!.pause()
                                    timer?.cancel()
                                    timer = null
                                    buttonView?.background = playIcon
                                } else {

                                    playing = true
                                    mediaPlayer!!.start()
                                    scheduleUpdater()
                                    buttonView?.background = pauseIcon
                                }
                                return@setOnClickListener
                            }

                            stopCurrentPlayer()
                            resetBar()
                            isPreparing = true
                            buttonView?.isEnabled = false
                            Utils.mainThread.post { buttonView?.background = null }

                            Utils.threadPool.execute {
                                var playUrl = url
                                if (isOggFile) {
                                    val file = File(ctx.cacheDir, "audio.ogg")
                                    file.deleteOnExit()
                                    Http.simpleDownload(url, file)
                                    playUrl = file.absolutePath
                                }
                                try {
                                    mediaPlayer = MediaPlayer().apply {
                                        setDataSource(playUrl)
                                        setOnPreparedListener {

                                            if (duration == 0L) {
                                                duration = this.duration.toLong()
                                            }
                                            Utils.mainThread.post {
                                                isPrepared = true
                                                isPreparing = false
                                                playing = true
                                                buttonView?.isEnabled = true
                                                progressView?.text =
                                                    "${msToTime(currentPosition.toLong())} / ${msToTime(duration)}"

                                                mediaPlayer?.start()
                                                scheduleUpdater()
                                                buttonView?.background = pauseIcon
                                                globalCurrentPlayer = this
                                                globalCleanup = {
                                                    playing = false
                                                    isPrepared = false
                                                    isPreparing = false
                                                    timer?.cancel()
                                                    timer = null
                                                    Utils.mainThread.post {
                                                        buttonView?.background = playIcon
                                                        buttonView?.isEnabled = true
                                                        sliderView?.progress = 0
                                                        progressView?.text = "0:00 / ${msToTime(duration)}"
                                                    }
                                                    try { stop() } catch (_: Exception) {}
                                                    try { release() } catch (_: Exception) {}
                                                    mediaPlayer = null
                                                }
                                            }
                                        }
                                        setOnCompletionListener {
                                            playing = false
                                            seekTo(0)
                                            Utils.mainThread.post {
                                                buttonView?.background = rewindIcon
                                            }
                                        }
                                        prepareAsync()
                                    }
                                } catch (e: Exception) {
                                    Utils.mainThread.post {
                                        Toast.makeText(ctx, "Failed to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
                                        buttonView?.isEnabled = true
                                        isPreparing = false
                                        isPrepared = false
                                    }
                                    resetBar()
                                }
                            }
                        }

                        addView(buttonView)
                        addView(progressView)
                        addView(sliderView)
                    }

                    playerCard.addView(playerBar)
                    card.addView(playerCard)
                }
            }

        }

        patcher.after<StoreMessages>("handleChannelSelected", Long::class.javaPrimitiveType!!) {
            stopCurrentPlayer()
        }
        patcher.after<AppActivity>("onCreate", Bundle::class.java) {
            stopCurrentPlayer()
        }
        patcher.after<AppActivity>("onPause") {
            stopCurrentPlayer()
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        stopCurrentPlayer()
    }
}