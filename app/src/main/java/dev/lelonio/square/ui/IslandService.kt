package dev.lelonio.square.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import dev.lelonio.square.R
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A floating pill near the camera, shown over other apps while music plays and
 * Wave itself is not on screen. Tap the pill to open the app, tap the button
 * to play or pause.
 *
 * Needs the "Display over other apps" permission.
 */
class IslandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var pill: LinearLayout? = null
    private var cover: ImageView? = null
    private var title: TextView? = null
    private var button: ImageView? = null
    private var attached = false

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var lastArtwork: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPill()

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener(
                {
                    controller = runCatching { f.get() }.getOrNull()
                    controller?.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = refresh()
                    })
                    refresh()
                },
                ContextCompat.getMainExecutor(this),
            )
        }

        scope.launch { visible.collect { refresh() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("Wave")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildPill() {
        val coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
        }
        val titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(150)
        }
        val buttonView = ImageView(this).apply {
            setColorFilter(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener {
                controller?.let { if (it.playWhenReady) it.pause() else it.play() }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(100).toFloat()
            }
            addView(coverView, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(
                titleView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(10), 0, dp(10), 0) },
            )
            addView(buttonView, LinearLayout.LayoutParams(dp(32), dp(32)))
            setOnClickListener {
                startActivity(
                    Intent(this@IslandService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }

        pill = row
        cover = coverView
        title = titleView
        button = buttonView
    }

    private fun refresh() {
        val player = controller
        val hasItem = player?.currentMediaItem != null
        val wantsPlay = player?.playWhenReady == true
        val show = !visible.value && hasItem && wantsPlay && Settings.canDrawOverlays(this)

        if (player != null && hasItem) {
            title?.text = player.mediaMetadata.title?.toString().orEmpty()
            button?.setImageResource(
                if (wantsPlay) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            )
            val art = player.mediaMetadata.artworkUri?.toString()
            if (art != lastArtwork) {
                lastArtwork = art
                if (art != null) {
                    ImageLoader(this).enqueue(
                        ImageRequest.Builder(this)
                            .data(art)
                            .size(96)
                            .target { drawable -> cover?.setImageDrawable(drawable) }
                            .build(),
                    )
                } else {
                    cover?.setImageDrawable(null)
                }
            }
        }

        if (show) attach() else detach()

        // Nothing playing and the app out of sight: no reason to stay alive.
        // The app starts this again the next time it is opened.
        if (player != null && !visible.value && !wantsPlay) stopSelf()
    }

    private fun attach() {
        val view = pill ?: return
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            windowManager?.addView(view, params)
            attached = true
        }
    }

    private fun detach() {
        val view = pill ?: return
        if (!attached) return
        runCatching { windowManager?.removeView(view) }
        attached = false
    }

    override fun onDestroy() {
        detach()
        controller?.release()
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "island"
        private const val NOTIFICATION_ID = 7701

        /** True while Wave itself is on screen; the pill is only for when it is not. */
        val visible = MutableStateFlow(true)
    }
}
