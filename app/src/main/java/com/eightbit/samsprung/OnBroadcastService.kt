/*
 * ====================================================================
 * Copyright (c) 2021-2023 AbandonedCart.  All rights reserved.
 *
 * See https://github.com/SamSprung/.github/blob/main/LICENSE#L5
 * ====================================================================
 *
 * The license and distribution terms for any publicly available version or
 * derivative of this code cannot be changed.  i.e. this code cannot simply be
 * copied and put under another distribution license
 * [including the GNU Public License.] Content not subject to these terms is
 * subject to to the terms and conditions of the Apache License, Version 2.0.
 */

package com.eightbit.samsprung

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eightbit.app.CoverOptions
import com.eightbit.content.ScaledContext
import com.eightbit.os.Version
import com.eightbit.samsprung.drawer.OrientationManager
import com.eightbit.samsprung.settings.Preferences

class OnBroadcastService : Service() {

    private val onReceiver = object : BroadcastReceiver() {
        @SuppressLint("NotifyDataSetChanged")
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_USER_PRESENT == intent.action
                || Intent.ACTION_SCREEN_ON == intent.action) {
                if (getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
                        .getBoolean(Preferences.prefRotate, false)) {
                    OrientationManager(context).removeOrientationLayout()
                }
                ScaledContext(context).cover().startActivity(
                    Intent(context.applicationContext, SamSprungOverlay::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    CoverOptions(null).getActivityOptions(1).toBundle()
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("InflateParams")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        showForegroundNotification(startId)

        if (!Settings.canDrawOverlays(applicationContext) || SamSprung.updating == intent?.action) {
            return stopOverlayService()
        } else if (SamSprung.services == intent?.action) {
            startActivity(
                Intent(applicationContext, SamSprungOverlay::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                CoverOptions(null).getActivityOptions(1).toBundle()
            )
        } else if (SamSprung.launcher == intent?.action) {
            startActivity(
                Intent(applicationContext, SamSprungOverlay::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setAction(SamSprung.launcher),
                CoverOptions(null).getActivityOptions(1).toBundle()
            )
        }
        try { applicationContext.unregisterReceiver(onReceiver) } catch (_: Exception) { }
        IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            priority = 999
        }.also {
            registerReceiver(onReceiver, it)
        }
        return START_STICKY
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun showForegroundNotification(startId: Int) {
        val mNotificationManager: NotificationManager = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager
        val pendingIntent = PendingIntent.getService(this, 0,
            Intent(this, OnBroadcastService::class.java).setAction(SamSprung.updating),
            if (Version.isSnowCone) PendingIntent.FLAG_IMMUTABLE else 0)
        val iconNotification = BitmapFactory.decodeResource(resources, R.mipmap.sprung_icon)
        var group = mNotificationManager.getNotificationChannelGroup("tooui_services")
        if (null == group) {
            mNotificationManager.createNotificationChannelGroup(
                NotificationChannelGroup("tooui_services", "SamSprung Services")
            )
            group = mNotificationManager.getNotificationChannelGroup("tooui_services")
        }
        val notificationChannel = NotificationChannel("tooui_overlay_channel",
            "TooUI Overlay Notification", NotificationManager.IMPORTANCE_LOW)
        notificationChannel.enableLights(false)
        notificationChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        mNotificationManager.createNotificationChannel(notificationChannel)
        val builder = NotificationCompat.Builder(this, "tooui_overlay_channel")

        val notificationText = getString(R.string.overlay_service, organization)
        builder.setContentTitle(notificationText).setTicker(notificationText)
            .setSmallIcon(R.drawable.ic_samsprung_24dp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(0).setOnlyAlertOnce(true).setGroup(group.id)
            .setContentIntent(pendingIntent).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (null != iconNotification) {
            builder.setLargeIcon(Bitmap.createScaledBitmap(
                iconNotification, 128, 128, false
            ))
        }
        builder.color = ContextCompat.getColor(this, R.color.primary_dark)
        startForeground(startId, builder.build())
    }

    private fun stopOverlayService(): Int {
        try { unregisterReceiver(onReceiver) } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.canDrawOverlays(applicationContext)) {
            startForegroundService(Intent(application, OnBroadcastService::class.java))
        }
    }
}
