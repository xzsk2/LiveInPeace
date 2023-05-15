package com.maary.liveinpeace

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.maary.liveinpeace.Constants.Companion.ACTION_NAME_SETTINGS
import com.maary.liveinpeace.Constants.Companion.BROADCAST_ACTION_MUTE
import com.maary.liveinpeace.Constants.Companion.CHANNEL_ID_DEFAULT
import com.maary.liveinpeace.Constants.Companion.ID_NOTIFICATION_FOREGROUND
import com.maary.liveinpeace.Constants.Companion.MODE_IMG
import com.maary.liveinpeace.Constants.Companion.MODE_NUM
import com.maary.liveinpeace.Constants.Companion.PREF_ICON
import com.maary.liveinpeace.Constants.Companion.SHARED_PREF
import kotlin.properties.Delegates

class ForegroundService: Service() {

    private val volumeDrawableIds = intArrayOf(
        R.drawable.ic_volume_silent,
        R.drawable.ic_volume_low,
        R.drawable.ic_volume_middle,
        R.drawable.ic_volume_high,
        R.drawable.ic_volume_mega
    )

    private var iconMode by Delegates.notNull<Int>()

    private lateinit var volumeComment: Array<String>

    private lateinit var audioManager: AudioManager

    companion object {
        private var isForegroundServiceRunning = false

        @JvmStatic
        fun isForegroundServiceRunning(): Boolean {
            return isForegroundServiceRunning
        }
    }

    private fun getVolumePercentage(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.v("MUTE_", currentVolume.toString())
        Log.v("MUTE_", maxVolume.toString())
        return 100 * currentVolume / maxVolume
    }

    private fun getVolumeLevel(percent: Int): Int {
        return when(percent) {
            in 0..0 -> 0
            in 1..25 -> 1
            in 26..50 -> 2
            in 50..80 -> 3
            else -> 4
        }
    }

    private val volumeChangeReceiver = object : VolumeReceiver() {
        @SuppressLint("MissingPermission")
        override fun updateNotification(context: Context) {
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createNotification(applicationContext))
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @SuppressLint("MissingPermission")
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            // Handle newly added audio devices
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createNotification(applicationContext))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            // Handle removed audio devices
            with(NotificationManagerCompat.from(applicationContext)){
                notify(ID_NOTIFICATION_FOREGROUND, createNotification(applicationContext))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        iconMode = getSharedPreferences(SHARED_PREF, Context.MODE_PRIVATE).getInt(PREF_ICON, MODE_IMG)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        // 注册音量变化广播接收器
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        registerReceiver(volumeChangeReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 取消注册音量变化广播接收器
        unregisterReceiver(volumeChangeReceiver)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        isForegroundServiceRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_NOTIFICATION_FOREGROUND, createNotification(context = applicationContext))
        isForegroundServiceRunning = true

        // 返回 START_STICKY，以确保 Service 在被终止后能够自动重启
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun createNotification(context: Context): Notification {
        val currentVolume = getVolumePercentage(context)
        val currentVolumeLevel = getVolumeLevel(currentVolume)
        volumeComment = resources.getStringArray(R.array.array_volume_comment)
        val nIcon = generateNotificationIcon(context, iconMode)

        val settingsIntent = Intent(this, SettingsReceiver::class.java).apply {
            action = ACTION_NAME_SETTINGS
        }
        val snoozePendingIntent: PendingIntent =
            PendingIntent.getBroadcast(this, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE)

        val actionSettings : NotificationCompat.Action = NotificationCompat.Action.Builder(
            R.drawable.ic_baseline_settings_24,
            resources.getString(R.string.settings),
            snoozePendingIntent
        ).build()

        val muteMediaIntent = Intent(context, MuteMediaReceiver::class.java)
        muteMediaIntent.action = BROADCAST_ACTION_MUTE
        val pendingMuteIntent = PendingIntent.getBroadcast(context, 0, muteMediaIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 将 Service 设置为前台服务，并创建一个通知
        return NotificationCompat.Builder(this, CHANNEL_ID_DEFAULT)
            .setContentTitle(getString(R.string.to_be_or_not))
            .setOnlyAlertOnce(true)
            .setContentText(String.format(
                resources.getString(R.string.current_volume_percent),
                volumeComment[currentVolumeLevel],
                currentVolume))
            .setSmallIcon(nIcon)
            .setContentIntent(pendingMuteIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(actionSettings)
            .build()
    }

    private fun generateNotificationIcon(context: Context, iconMode: Int): IconCompat {
        val currentVolume = getVolumePercentage(context)
        val currentVolumeLevel = getVolumeLevel(currentVolume)
        if (iconMode == MODE_NUM){
            var count = currentVolume
            var isCountLow = false
            var isCountHigh = false
            val immutableBackground = BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification_background)
            val background = immutableBackground.copy(Bitmap.Config.ARGB_8888, true)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 100f
                typeface = context.resources.getFont(R.font.ndot_45)
                isFakeBoldText = true
                isAntiAlias = true
            }

            if (count == 100){
                count = 99
                isCountHigh = true
            }

            if (count < 10) {
                count += 10
                isCountLow = true
            }

            val textBounds = Rect()
            paint.getTextBounds(count.toString(), 0, count.toString().length, textBounds)
            val textWidth = textBounds.width()
            val textHeight = textBounds.height()

            val canvas = Canvas(background)
            val canvasWidth = canvas.width
            val canvasHeight = canvas.height
            val textSize = (canvasWidth / textWidth * textHeight).coerceAtMost(canvasHeight)
            paint.textSize = textSize.toFloat()

            if (isCountLow){
                count-=10
            }

            var textToDraw = count.toString()
            if (isCountHigh) textToDraw = "!!"
            paint.getTextBounds(count.toString(), 0, count.toString().length, textBounds)
            canvas.drawText(textToDraw, (canvasWidth - textBounds.width()) / 2f, (canvasHeight + textBounds.height()) / 2f, paint)

            return IconCompat.createWithBitmap(background)
        }
        else {
            return IconCompat.createWithResource(context, volumeDrawableIds[currentVolumeLevel])
        }
    }
}