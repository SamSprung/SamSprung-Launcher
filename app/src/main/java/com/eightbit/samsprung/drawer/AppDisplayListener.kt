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

package com.eightbit.samsprung.drawer

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.eightbit.app.CoverOptions
import com.eightbit.content.ScaledContext
import com.eightbit.os.Version
import com.eightbit.samsprung.*
import com.eightbit.samsprung.settings.Preferences
import com.eightbit.view.OnSwipeTouchListener
import com.google.android.material.bottomsheet.BottomSheetBehavior

class AppDisplayListener : Service() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
    }

    private var offReceiver: BroadcastReceiver? = null
    private var mDisplayListener: DisplayManager.DisplayListener? = null
    private lateinit var floatView: View
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val vibrator by lazy {
        if (Version.isSnowCone)
           with (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager) { defaultVibrator }
        else
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
    }
    private val effectClick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        var launchPackage: String? = intent.getStringExtra("launchPackage")
        val launchActivity: String? = if (intent.hasExtra("launchActivity"))
            intent.getStringExtra("launchActivity") else null
        var componentName: ComponentName? = if (null == launchActivity && null != launchPackage)
            packageManager.getLaunchIntentForPackage(launchPackage)?.component else null

//        val pendingIntent: ByteArray? = intent.getByteArrayExtra("pendingIntent")
//        if (null != pendingIntent) {
//            val parcel = Parcel.obtain()
//            parcel.unmarshall(pendingIntent, 0, pendingIntent.size)
//            parcel.setDataPosition(0)
//            parcel.recycle()
//        }

        offReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    componentName?.let {
                        restoreActivityDisplay(it, 0)
                    } ?: launchPackage?.let {
                        restoreActivityDisplay(it, launchActivity, 0)
                    }
                    componentName = null
                    launchPackage = null
                    onDismissOverlay()
                }
            }
        }
        @Suppress("UnspecifiedRegisterReceiverFlag")
        IntentFilter(Intent.ACTION_SCREEN_OFF).also {
            if (Version.isTiramisu) {
                registerReceiver(offReceiver, it, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(offReceiver, it)
            }
        }

        with (getSystemService(DISPLAY_SERVICE) as DisplayManager) {
            mDisplayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(display: Int) {}
                override fun onDisplayChanged(display: Int) {
                    if (display == 0 && getDisplay(0).state == Display.STATE_ON) {
                        componentName?.let {
                            restoreActivityDisplay(it, display)
                        } ?: launchPackage?.let {
                            restoreActivityDisplay(it, launchActivity, display)
                        }
                        componentName = null
                        launchPackage = null
                        onDismissOverlay()
                    }
                }

                override fun onDisplayRemoved(display: Int) {}
            }
            registerDisplayListener(mDisplayListener, null)
        }

        showForegroundNotification(startId)

        val displayContext = ScaledContext(ScaledContext(applicationContext).cover()).internal(1.5f)
        floatView = LayoutInflater.from(displayContext).inflate(R.layout.navigation_menu, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        floatView.keepScreenOn = true

        val icons = floatView.findViewById<LinearLayout>(R.id.icons_layout)
        val menuClose = icons.findViewById<AppCompatImageView>(R.id.retract_drawer)

        icons.findViewById<ImageView>(R.id.button_back).isVisible =
            AccessibilityObserver.hasEnabledService(this)

        bottomSheetBehavior = BottomSheetBehavior.from(floatView.findViewById(R.id.bottom_sheet_nav)!!)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val color = prefs.getInt(
                        Preferences.prefColors,
                        Color.rgb(255, 255, 255))
                    icons.isVisible = true
                    for (i in 0 until icons.childCount) {
                        (icons.getChildAt(i) as AppCompatImageView).setColorFilter(color)
                    }
                    menuClose.setColorFilter(color)
                    setClickListeners(icons, componentName, launchPackage, launchActivity)
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    icons.isVisible = false
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) { }
        })

        val color = prefs.getInt(
            Preferences.prefColors,
            Color.rgb(255, 255, 255))
        icons.isVisible = true
        for (i in 0 until icons.childCount) {
            (icons.getChildAt(i) as AppCompatImageView).setColorFilter(color)
        }
        menuClose.setColorFilter(color)
        setClickListeners(icons, componentName, launchPackage, launchActivity)

        floatView.findViewById<View>(R.id.bottom_sheet_nav).setOnTouchListener(
            object: OnSwipeTouchListener(this@AppDisplayListener) {
            override fun onSwipeTop() : Boolean {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                return true
            }
            override fun onSwipeBottom() : Boolean {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                return true
            }
        })

        with (displayContext.getSystemService(WINDOW_SERVICE) as WindowManager) {
            addView(floatView, params)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }, 500)
        return START_NOT_STICKY
    }

    private fun restoreActivityDisplay(componentName: ComponentName?, display: Int) {
        ScaledContext(applicationContext).restore(display).run {
            with (getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps) {
                startMainActivity(
                    componentName,
                    Process.myUserHandle(),
                    with (getSystemService(WINDOW_SERVICE) as WindowManager) { maximumWindowMetrics.bounds },
                    CoverOptions(null).getActivityOptions(display).toBundle()
                )
            }
        }
    }

    private fun restoreActivityDisplay(pkg: String?, cls: String?, display: Int) {
        if (null != pkg && null != cls) {
            restoreActivityDisplay(ComponentName(pkg, cls), display)
        }
    }

    private fun resetRecentActivities(componentName: ComponentName?, display: Int) {
        restoreActivityDisplay(componentName, 0)

        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_FORWARD_RESULT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
        }, CoverOptions(null).getActivityOptions(display).toBundle())
    }

    private fun resetRecentActivities(pkg: String?, cls: String?, display: Int) {
        if (null != pkg && null != cls) {
            resetRecentActivities(ComponentName(pkg, cls), display)
        }
    }

    private fun setClickListeners(
        menu: LinearLayout, componentName: ComponentName?,
        launchPackage: String?, launchActivity: String?) {
        menu.findViewById<AppCompatImageView>(R.id.retract_drawer).setOnClickListener {
            if (prefs.getBoolean(Preferences.prefReacts, true))
                vibrator.vibrate(effectClick)
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        menu.findViewById<ImageView>(R.id.button_recent).setOnClickListener {
            if (prefs.getBoolean(Preferences.prefReacts, true))
                vibrator.vibrate(effectClick)
            componentName?.let {
                resetRecentActivities(it, 1)
            } ?: resetRecentActivities(launchPackage, launchActivity, 1)
            onDismissOverlay()
            startForegroundService(Intent(
                applicationContext, OnBroadcastService::class.java
            ).setAction(SamSprung.launcher))
        }
        menu.findViewById<ImageView>(R.id.button_home).setOnClickListener {
            if (prefs.getBoolean(Preferences.prefReacts, true))
                vibrator.vibrate(effectClick)
            componentName?.let {
                resetRecentActivities(it, 1)
            } ?: resetRecentActivities(launchPackage, launchActivity, 1)
            onDismissOverlay()
            startForegroundService(
                Intent(
                    applicationContext, OnBroadcastService::class.java
                ).setAction(SamSprung.services)
            )
        }
        menu.findViewById<ImageView>(R.id.button_back).setOnClickListener {
            if (prefs.getBoolean(Preferences.prefReacts, true))
                vibrator.vibrate(effectClick)
            AccessibilityObserver.performBackAction()
        }
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun showForegroundNotification(startId: Int) {
        val mNotificationManager: NotificationManager = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager
        val pendingIntent = PendingIntent.getService(this, 0,
            Intent(this, AppDisplayListener::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE else 0)
        val iconNotification = BitmapFactory.decodeResource(resources, R.mipmap.sprung_icon)
        var group = mNotificationManager.getNotificationChannelGroup("tooui_services")
        if (null == group) {
            mNotificationManager.createNotificationChannelGroup(
                NotificationChannelGroup("tooui_services", "SamSprung Services")
            )
            group = mNotificationManager.getNotificationChannelGroup("tooui_services")
        }
        val notificationChannel = NotificationChannel("display_channel",
            "Display Notification", NotificationManager.IMPORTANCE_LOW)
        notificationChannel.enableLights(false)
        notificationChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        mNotificationManager.createNotificationChannel(notificationChannel)
        val builder = NotificationCompat.Builder(this, "display_channel")

        val notificationText = getString(R.string.display_service, organization)
        builder.setContentTitle(notificationText).setTicker(notificationText)
            .setSmallIcon(R.drawable.ic_samsprung_24dp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(0).setOnlyAlertOnce(true).setGroup(group.id)
            .setContentIntent(pendingIntent).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        iconNotification?.let {
            builder.setLargeIcon(Bitmap.createScaledBitmap(it, 128, 128, false))
        }
        builder.color = ContextCompat.getColor(this, R.color.primary_light)
        startForeground(startId, builder.build())
    }

    fun onDismissOverlay() {
        if (prefs.getBoolean(Preferences.prefRotate, false))
            OrientationManager(this).removeOrientationLayout()
        try {
            with (getSystemService(DISPLAY_SERVICE) as DisplayManager) {
                unregisterDisplayListener(mDisplayListener)
            }
        } catch (_: Exception) { }
        try { unregisterReceiver(offReceiver) } catch (_: Exception) { }
        ScaledContext(
            ScaledContext(applicationContext).cover(R.style.Theme_SecondScreen)
        ).internal(1.5f).run {
            with (getSystemService(WINDOW_SERVICE) as WindowManager) {
                try {
                    removeViewImmediate(floatView)
                } catch (_: Exception) {
                    try { removeView(floatView) } catch (_: Exception) { }
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        onDismissOverlay()
    }
}
