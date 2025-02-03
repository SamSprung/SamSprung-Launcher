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

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.*
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.animation.TranslateAnimation
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.WindowInfoTracker
import com.eightbit.content.ScaledContext
import com.eightbit.io.Debug
import com.eightbit.material.IconifiedSnackbar
import com.eightbit.os.Version
import com.eightbit.samsprung.drawer.CoverStateAdapter
import com.eightbit.samsprung.drawer.LauncherManager
import com.eightbit.samsprung.drawer.PanelWidgetManager
import com.eightbit.samsprung.drawer.panels.*
import com.eightbit.samsprung.settings.Preferences
import com.eightbit.samsprung.speech.VoiceRecognizer
import com.eightbit.samsprung.update.UpdateManager
import com.eightbit.view.AnimatedLinearLayout
import com.eightbit.viewpager.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.*


class SamSprungOverlay : AppCompatActivity() {

    private val Number.toPx get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(),
        ScaledContext(this@SamSprungOverlay).resources.displayMetrics
    ).toInt()

    private val CharSequence.toPref get() = this.toString().lowercase().replace(" ", "_")

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
    }

    private var windowInfoTracker: WindowInfoTrackerCallbackAdapter? = null
    private val layoutStateCallback: LayoutStateChangeCallback = LayoutStateChangeCallback()

    private var launcherManager: LauncherManager? = null
    private var widgetManager: PanelWidgetManager? = null
    val model = WidgetModel()
    private var appWidgetHost: WidgetHost? = null
    private var updateManager : UpdateManager? = null

    private var background: Drawable? = null

    private lateinit var toolbar: Toolbar
    private lateinit var wifiManager: WifiManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var camManager: CameraManager
    private lateinit var torchCallback: CameraManager.TorchCallback
    private var isTorchEnabled = false

    private var offReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("NotifyDataSetChanged")
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onStopOverlay()
        }
    }
    private lateinit var battReceiver: BroadcastReceiver

    private lateinit var bottomSheetBehaviorMain: BottomSheetBehavior<View>
    private lateinit var viewPager: ViewPager2
    private var pagerAdapter: FragmentStateAdapter = CoverStateAdapter(this)

    private val vibrator: Vibrator by lazy {
        if (Version.isSnowCone)
            with (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager) { defaultVibrator }
        else
            @Suppress("deprecation") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }
    private val effectClick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val effectLongClick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private var mBinder: DesktopBinder? = null

    private var mWidgetPreviewCacheDb: WidgetPreviews.CacheDb? = null
    private fun recreateWidgetPreviewDb() {
        mWidgetPreviewCacheDb = WidgetPreviews.CacheDb(this)
    }
    fun getWidgetPreviewCacheDb(): WidgetPreviews.CacheDb? {
        return mWidgetPreviewCacheDb
    }

    private inner class FavoritesChangeObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onFavoritesChanged()
        }
    }
    private val mObserver: ContentObserver = FavoritesChangeObserver()

    val requestCreateAppWidget = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        val appWidgetId = result.data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == RESULT_CANCELED) {
            if (appWidgetId != -1) {
                appWidgetHost?.deleteAppWidgetId(appWidgetId)
            }
        } else {
            widgetManager?.completeAddAppWidget(appWidgetId, viewPager)
        }
    }

    private fun hasPermissions(vararg permissions: String): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(
                this@SamSprungOverlay, it
            ) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        // setTurnScreenOn(true)

        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND

        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT
        )
//        if (Version.isSnowCone) {
//            (getSystemService(INPUT_SERVICE) as InputManager).run {
//                window.attributes.alpha = maximumObscuringOpacityForTouch
//            }
//        }
        window.attributes.gravity = Gravity.BOTTOM

        ScaledContext(this).internal(1.5f).setTheme(R.style.Theme_Launcher_NoActionBar)
        setContentView(R.layout.home_main_view)

        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }.also {
            ContextCompat.registerReceiver(
                this, offReceiver, it, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        val coordinator = findViewById<CoordinatorLayout>(R.id.coordinator)
        val animated = File(filesDir, "wallpaper.gif")
        if (animated.exists()) {
            try {
                val source: ImageDecoder.Source = ImageDecoder.createSource(
                    this.contentResolver, Uri.fromFile(animated)
                )
                CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                    background = ImageDecoder.decodeDrawable(source) as AnimatedImageDrawable
                    withContext(Dispatchers.Main) {
                        coordinator.background = background
                        val animation = background as AnimatedImageDrawable
                        animation.start()
                    }
                }
            } catch (_: Exception) { }
        } else {
            val wallpaper = File(filesDir, "wallpaper.png")
            if (wallpaper.exists()) {
                try {
                    background = Drawable.createFromPath(wallpaper.absolutePath)
                } catch (_: Exception) { }
            }
            val permissions: Array<String> =
                if (Version.isTiramisu)
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                else
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (null == background && hasPermissions(*permissions)) {
                try {
                    background = try {
                        WallpaperManager.getInstance(
                            ScaledContext(applicationContext).cover()
                        ).drawable
                    } catch (_: SecurityException) {
                        WallpaperManager.getInstance(
                            ScaledContext(applicationContext).cover()
                        ).peekDrawable()
                    }
                } catch (_: SecurityException) { }
            }
            if (null != background) coordinator.background = background
        }

        val blurView = findViewById<BlurView>(R.id.blurContainer)
        if (prefs.getBoolean(Preferences.prefRadius, true)) {
            blurView.setupWith(coordinator)
                .setFrameClearDrawable(coordinator.background)
                .setBlurRadius(1f).setBlurAutoUpdate(true)
        }

        val mAppWidgetManager = AppWidgetManager.getInstance(applicationContext)
        val appWidgetHost = WidgetHost(applicationContext, APPWIDGET_HOST_ID)
        if (prefs.getBoolean(getString(R.string.toggle_widgets).toPref, true)) {
            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                recreateWidgetPreviewDb()
            }
            appWidgetHost.startListening()
        }

        wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        bluetoothAdapter = with (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager) { adapter }
        nfcAdapter = with (getSystemService(NFC_SERVICE) as NfcManager) { defaultAdapter }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        camManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCallback = object: CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                super.onTorchModeChanged(cameraId, enabled)
                if (cameraId == camManager.cameraIdList[0]) isTorchEnabled = enabled
                val torchMenu = toolbar.menu.findItem(R.id.toggle_torch)
                if (isTorchEnabled) {
                    torchMenu.setIcon(R.drawable.ic_flashlight_on_24dp)
                } else {
                    torchMenu.setIcon(R.drawable.ic_flashlight_off_24dp)
                }
                torchMenu.icon?.setTint(prefs.getInt(Preferences.prefColors,
                    Color.rgb(255, 255, 255)))
            }
        }
        camManager.registerTorchCallback(torchCallback, null)

        val batteryLevel = findViewById<TextView>(R.id.battery_status)
        battReceiver = object : BroadcastReceiver() {
            @SuppressLint("NotifyDataSetChanged")
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    batteryLevel.post {
                        batteryLevel.text = String.format(Locale.ROOT, "%d%%",
                            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100))
                    }
                }
            }
        }

        IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }.also {
            ContextCompat.registerReceiver(
                this, battReceiver, it, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        val clock = findViewById<TextClock>(R.id.clock_status)
        toolbar = findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.cover_quick_toggles)

        var color = configureMenuIcons(toolbar)
        batteryLevel.setTextColor(color)
        clock.setTextColor(color)

        val bottomSheet = findViewById<View>(R.id.bottom_sheet)

        val buttonRotation = findViewById<AppCompatImageView>(R.id.button_rotation)
        buttonRotation.setOnClickListener {
            if (prefs.getBoolean(Preferences.prefReacts, true))
                vibrator.vibrate(effectClick)
            val unlocked = !prefs.getBoolean(Preferences.prefRotate, false)
            if (unlocked) {
                val lockBar: Snackbar = IconifiedSnackbar(
                    this@SamSprungOverlay, bottomSheet as ViewGroup
                ).buildTickerBar(
                    getString(R.string.rotation_lock),
                    R.drawable.ic_screen_lock_rotation_24
                )
                lockBar.setAction(R.string.rotation_lock_action) {
                    with(prefs.edit()) {
                        putBoolean(Preferences.prefRotate, true)
                        apply()
                    }
                    buttonRotation.setImageResource(R.drawable.ic_screen_lock_rotation_24)
                }
                lockBar.show()
            } else {
                with(prefs.edit()) {
                    putBoolean(Preferences.prefRotate, false)
                    apply()
                }
                buttonRotation.setImageResource(R.drawable.ic_screen_rotation_24)
            }
        }
        buttonRotation.setOnLongClickListener { view ->
            Toast.makeText(
                view.context, view.contentDescription, Toast.LENGTH_SHORT
            ).show()
            true
        }
        buttonRotation.setImageResource(
            if (prefs.getBoolean(Preferences.prefRotate, false))
                R.drawable.ic_screen_lock_rotation_24
            else
                R.drawable.ic_screen_rotation_24
        )

        val keyguardManager = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager)
        val buttonAuth = findViewById<AppCompatImageView>(R.id.button_auth)
        buttonAuth.setOnClickListener {
            onKeyguardClicked(keyguardManager, buttonAuth)
        }
        buttonAuth.setOnLongClickListener { view ->
            Toast.makeText(
                view.context, view.contentDescription, Toast.LENGTH_SHORT
            ).show()
            true
        }
        setKeyguardStatus(keyguardManager, buttonAuth)

        val toggleStats = findViewById<LinearLayout>(R.id.toggle_status)
        val info = findViewById<LinearLayout>(R.id.bottom_info)
        val bottomSheetBehavior: BottomSheetBehavior<View> = BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                var hasConfigured = false

                @SuppressLint("MissingPermission")
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        toolbar.setOnMenuItemClickListener { item: MenuItem ->
                            when (item.itemId) {
                                R.id.toggle_wifi -> {
                                    if (buttonAuth.isInvisible) {
                                        wifiEnabler.launch(
                                            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)/*,
                                            CoverOptions(null).getActivityOptionsCompat(1)*/
                                        )
                                    } else {
                                        val authBar: Snackbar = IconifiedSnackbar(
                                            this@SamSprungOverlay, bottomSheet as ViewGroup
                                        ).buildTickerBar(
                                            getString(R.string.auth_required),
                                            R.drawable.ic_fingerprint_24dp
                                        )
                                        authBar.setAction(R.string.auth_required_action) {
                                            onKeyguardClicked(keyguardManager, buttonAuth)
                                        }
                                        authBar.show()
                                    }
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_bluetooth -> {
                                    @Suppress("deprecation")
                                    if (bluetoothAdapter.isEnabled) {
                                        bluetoothAdapter.disable()
                                        item.setIcon(R.drawable.ic_bluetooth_off_24dp)
                                    } else {
                                        bluetoothAdapter.enable()
                                        item.setIcon(R.drawable.ic_bluetooth_on_24dp)
                                    }
                                    item.icon?.setTint(color)
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_nfc -> {
                                    if (buttonAuth.isInvisible) {
                                        nfcEnabler.launch(
                                            Intent(Settings.Panel.ACTION_NFC)/*,
                                            CoverOptions(null).getActivityOptionsCompat(1)*/
                                        )
                                    } else {
                                        val authBar: Snackbar = IconifiedSnackbar(
                                            this@SamSprungOverlay, bottomSheet as ViewGroup
                                        ).buildTickerBar(
                                            getString(R.string.auth_required),
                                            R.drawable.ic_fingerprint_24dp
                                        )
                                        authBar.setAction(R.string.auth_required_action) {
                                            onKeyguardClicked(keyguardManager, buttonAuth)
                                        }
                                        authBar.show()
                                    }
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_sound -> {
                                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                                        item.setIcon(R.drawable.ic_sound_off_24dp)
                                    } else {
                                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                        item.setIcon(R.drawable.ic_sound_on_24dp)
                                    }
                                    item.icon?.setTint(color)
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_dnd -> {
                                    if (notificationManager.currentInterruptionFilter ==
                                        NotificationManager.INTERRUPTION_FILTER_ALL
                                    ) {
                                        notificationManager.setInterruptionFilter(
                                            NotificationManager.INTERRUPTION_FILTER_NONE
                                        )
                                        item.setIcon(R.drawable.ic_do_not_disturb_on_24dp)
                                    } else {
                                        notificationManager.setInterruptionFilter(
                                            NotificationManager.INTERRUPTION_FILTER_ALL
                                        )
                                        item.setIcon(R.drawable.ic_do_not_disturb_off_24dp)
                                    }
                                    item.icon?.setTint(color)
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_torch -> {
                                    camManager.setTorchMode(
                                        camManager.cameraIdList[0],
                                        !isTorchEnabled
                                    )
                                    return@setOnMenuItemClickListener true
                                }

                                R.id.toggle_widgets -> {
                                    widgetManager?.showAddDialog(viewPager)
                                    state = BottomSheetBehavior.STATE_COLLAPSED
                                    return@setOnMenuItemClickListener false
                                }

                                else -> {
                                    return@setOnMenuItemClickListener false
                                }
                            }
                        }
                    } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        hasConfigured = false
                        toggleStats.removeAllViews()
                        configureMenuVisibility(toolbar)
                        info.visibility = View.VISIBLE
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    if (slideOffset > 0) {
                        if (!hasConfigured) {
                            hasConfigured = true
                            info.visibility = View.GONE
                            color = configureMenuIcons(toolbar)
                            batteryLevel.setTextColor(color)
                            clock.setTextColor(color)
                            setKeyguardStatus(keyguardManager, buttonAuth)
                        }
                    }
                }
            })
        }

        findViewById<AppCompatImageView>(R.id.button_close).apply {
            setOnClickListener {
                if (prefs.getBoolean(Preferences.prefReacts, true))
                    vibrator.vibrate(effectClick)
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                closeMainDrawer()
            }
            setOnLongClickListener { view ->
                Toast.makeText(
                    view.context, view.contentDescription, Toast.LENGTH_SHORT
                ).show()
                true
            }
        }

        configureMenuVisibility(toolbar)

        if (prefs.getBoolean(getString(R.string.toggle_widgets).toPref, true)) {
            toolbar.findViewById<View>(R.id.toggle_widgets)
                .setOnLongClickListener(View.OnLongClickListener {
                if (viewPager.currentItem > 1) {
                    toolbar.menu.findItem(R.id.toggle_widgets)
                        .setIcon(R.drawable.ic_delete_forever_24dp)
                    if (prefs.getBoolean(Preferences.prefReacts, true))
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        // vibrator.vibrate(effectLongClick)
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    val index = viewPager.currentItem
                    val adapter = pagerAdapter as CoverStateAdapter
                    val fragment = adapter.getFragment(index)
                    val layout = fragment.getLayout()
                    for (child in layout.children) {
                        if (child is AppWidgetHostView) {
                            val widget = child.tag
                            if (widget is PanelWidgetInfo) {
                                model.removeDesktopAppWidget(widget)
                                adapter.removeFragment(index)
                                adapter.notifyItemRemoved(index)
                                appWidgetHost.deleteAppWidgetId(child.appWidgetId)
                                WidgetModel.deleteItemFromDatabase(
                                    applicationContext, widget
                                )
                                viewPager.setCurrentItem(1, true)
                            }
                        }
                    }
                    toolbar.menu.findItem(R.id.toggle_widgets)
                        .setIcon(R.drawable.ic_widgets_24dp)
                    toolbar.menu.findItem(R.id.toggle_widgets).icon?.setTint(color)
                } else {
                    Toast.makeText(this,
                        R.string.incompatible_fragment,
                        Toast.LENGTH_LONG).show()
                }
                return@OnLongClickListener true
            })
        }
        coordinator.visibility = View.GONE
        info.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        launcherManager = LauncherManager(this)

        viewPager = findViewById(R.id.pager)
        viewPager.adapter = pagerAdapter
        viewPager.setPageTransformer(when (prefs.getInt(Preferences.prefPaging, 0)) {
            0 -> CardFlipTransformer().apply { isScalable = false }
            1 -> ClockSpinTransformer()
            2 -> DepthTransformer()
            3 -> FidgetSpinTransformer()
            4 -> PopTransformer()
            5 -> SpinnerTransformer()
            6 -> TossTransformer()
            else -> null
        })
        setViewPagerSensitivity(viewPager, 1)
        viewPager.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                getSearchView()?.isGone = position != 1
                setScreenTimeout(findViewById(R.id.bottom_sheet_main))
                with(prefs.edit()) {
                    putInt(Preferences.prefViewer, position)
                    apply()
                }
            }
        })

        if (prefs.getBoolean(getString(R.string.toggle_widgets).toPref, true)) {
            widgetManager = PanelWidgetManager(this, mAppWidgetManager,
                appWidgetHost, pagerAdapter as CoverStateAdapter
            )
            contentResolver.registerContentObserver(
                WidgetSettings.Favorites.CONTENT_URI, true, mObserver
            )
            model.loadUserItems(true, this)
        }

        val fakeOverlay = findViewById<LinearLayout>(R.id.fake_overlay)
        val menuButton = findViewById<FloatingActionButton>(R.id.menu_fab)
        if (prefs.getBoolean(Preferences.prefTapper, false)) {
            menuButton.size = FloatingActionButton.SIZE_NORMAL
            menuButton.setMaxImageSize(34.toPx)
            (menuButton.layoutParams as? CoordinatorLayout.LayoutParams)?.run {
                val margin = -(8.toPx)
                marginStart = margin
                marginEnd = margin
            }
        }
        val bottomHandle = findViewById<View>(R.id.bottom_handle)
        bottomSheetBehaviorMain = BottomSheetBehavior.from(findViewById(R.id.bottom_sheet_main)).apply {
            isFitToContents = false
            isDraggable = prefs.getBoolean(Preferences.prefSlider, true)
            state = BottomSheetBehavior.STATE_COLLAPSED
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                var hasConfigured = false
                val handler = Handler(Looper.getMainLooper())
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        checkUpdatesWithDelay()
                        isDraggable = false
                        setActionButtonTimeout()
                    } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        coordinator.alpha = 1.0f
                        hasConfigured = false
                        coordinator.visibility = View.GONE
                        setBottomTheme(bottomHandle, menuButton)
                        handler.postDelayed({
                            setActionButtonTimeout()
                        }, 300)
                        isDraggable = prefs.getBoolean(Preferences.prefSlider, true)
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    color = prefs.getInt(
                        Preferences.prefColors, Color.rgb(255, 255, 255)
                    )
                    if (slideOffset > 0) {
                        coordinator.alpha = slideOffset
                        if (!hasConfigured) {
                            hasConfigured = true
                            handler.removeCallbacksAndMessages(null)
                            fakeOverlay.visibility = View.GONE
                            menuButton.hide()
                            coordinator.visibility = View.VISIBLE
                            val index = prefs.getInt(Preferences.prefViewer, viewPager.currentItem)
                            viewPager.setCurrentItem(if (index < 2) 1 else index, false)
                        }
                    }
                }
            })
        }
        setScreenTimeout(findViewById(R.id.bottom_sheet_main))
        showBottomHandle(bottomHandle, menuButton)

        findViewById<AnimatedLinearLayout>(R.id.update_notice).isGone = true

        if (null != intent?.action && SamSprung.launcher == intent.action) {
            Handler(Looper.getMainLooper()).postDelayed({
                bottomSheetBehaviorMain.state = BottomSheetBehavior.STATE_EXPANDED
            }, 150)
        }

        val voice = SpeechRecognizer.createSpeechRecognizer(applicationContext)
        val recognizer = VoiceRecognizer(object : VoiceRecognizer.SpeechResultsListener {
            override fun onSpeechResults(suggested: String) {
                menuButton.keepScreenOn = true
                launchApplication(suggested, menuButton)
            }
        })
        voice?.setRecognitionListener(recognizer)
        if (SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            menuButton.setOnLongClickListener {
                if (prefs.getBoolean(Preferences.prefReacts, true))
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    // vibrator.vibrate(effectLongClick)
                voice?.startListening(recognizer.getSpeechIntent(false))
                return@setOnLongClickListener true
            }
        }
    }

    private val wifiEnabler = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (wifiManager.isWifiEnabled)
            toolbar.menu.findItem(R.id.toggle_wifi).setIcon(R.drawable.ic_wifi_on_24dp)
        else
            toolbar.menu.findItem(R.id.toggle_wifi).setIcon(R.drawable.ic_wifi_off_24dp)
        toolbar.menu.findItem(R.id.toggle_wifi).icon?.setTint(
            prefs.getInt(Preferences.prefColors, Color.rgb(255, 255, 255))
        )
    }

    private val nfcEnabler = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (nfcAdapter.isEnabled)
            toolbar.menu.findItem(R.id.toggle_nfc).setIcon(R.drawable.ic_nfc_on_24dp)
        else
            toolbar.menu.findItem(R.id.toggle_nfc).setIcon(R.drawable.ic_nfc_off_24dp)
        toolbar.menu.findItem(R.id.toggle_nfc).icon?.setTint(
            prefs.getInt(Preferences.prefColors, Color.rgb(255, 255, 255))
        )
    }

    private fun setBottomTheme(bottomHandle: View, menuButton: FloatingActionButton) {
        val color = prefs.getInt(Preferences.prefColors,
            Color.rgb(255, 255, 255))
        bottomHandle.setBackgroundColor(color)
        bottomHandle.alpha = prefs.getFloat(Preferences.prefAlphas, 1f)
        setMenuButtonGravity(menuButton)
        menuButton.drawable.setTint(color)
        menuButton.alpha = prefs.getFloat(Preferences.prefAlphas, 1f)
    }

    private fun setKeyguardStatus(keyguardManager: KeyguardManager, button: AppCompatImageView) {
        button.isInvisible = !keyguardManager.isDeviceLocked
    }

    private fun onKeyguardClicked(keyguardManager: KeyguardManager, button: AppCompatImageView) {
        setKeyguardListener(object: KeyguardListener {
            override fun onKeyguardCheck(unlocked: Boolean) {
                setKeyguardStatus(keyguardManager, button)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBottomHandle(bottomHandle: View, menuButton: FloatingActionButton) {
        setBottomTheme(bottomHandle, menuButton)
        val closeGesture = GestureDetector(applicationContext, object : SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onStopOverlay()
                return super.onDoubleTap(e)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (prefs.getBoolean(Preferences.prefReacts, true))
                    vibrator.vibrate(effectClick)
                bottomSheetBehaviorMain.state = BottomSheetBehavior.STATE_EXPANDED
                return super.onSingleTapConfirmed(e)
            }
        })
        menuButton.setOnTouchListener { _, event -> closeGesture.onTouchEvent(event) }
        setActionButtonTimeout()
    }

    fun closeMainDrawer() {
        bottomSheetBehaviorMain.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun getCoordinator() : CoordinatorLayout {
        return findViewById(R.id.coordinator)
    }

    fun getSearchView() : SearchView? {
        val searchView = findViewById<SearchView>(R.id.package_search)
        searchView.isVisible = prefs.getBoolean(Preferences.prefSearch, true)
        return if (searchView.isVisible) searchView else null
    }

    private fun checkUpdatesWithDelay() {
        if (System.currentTimeMillis() <= prefs.getLong(Preferences.prefUpdate, 0) + 14400000) return
        prefs.edit().putLong(Preferences.prefUpdate, System.currentTimeMillis()).apply()
        updateManager = UpdateManager(this)
        updateManager?.setUpdateListener(object : UpdateManager.UpdateListener {
            override fun onUpdateFound() {
                showUpdateNotice()
            }
        })
    }

    private var keyguardListener: KeyguardListener? = null

    interface KeyguardListener {
        fun onKeyguardCheck(unlocked: Boolean)
    }

    private fun dismissKeyguard(keyguardManager: KeyguardManager, authDialog: AlertDialog?) {
        keyguardManager.requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissCancelled() {
                super.onDismissCancelled()
                authDialog?.dismiss()
                keyguardListener?.onKeyguardCheck(true)
            }

            override fun onDismissError() {
                super.onDismissError()
                if (null != authDialog) {
                    if (prefs.getBoolean(Preferences.prefReacts, true))
                        vibrator.vibrate(effectLongClick)
                    authDialog.dismiss()
                }
                keyguardListener?.onKeyguardCheck(false)
            }

            override fun onDismissSucceeded() {
                super.onDismissSucceeded()
                if (null != authDialog) {
                    if (prefs.getBoolean(Preferences.prefReacts, true))
                        vibrator.vibrate(effectLongClick)
                    authDialog.dismiss()
                }
                keyguardListener?.onKeyguardCheck(true)
            }
        })
        setTurnScreenOn(false)
    }

    @SuppressLint("InflateParams")
    fun setKeyguardListener(listener: KeyguardListener?) {
        this.keyguardListener = listener
        setTurnScreenOn(true)
        with (getSystemService(KEYGUARD_SERVICE) as KeyguardManager) {
            if (isDeviceLocked) {
                val authView = layoutInflater.inflate(R.layout.fingerprint_auth, null)
                val authDialog = AlertDialog.Builder(ContextThemeWrapper(
                    this@SamSprungOverlay, R.style.Theme_Overlay_NoActionBar
                )).setView(authView).create().apply {
                    setCancelable(false)
                    window?.attributes?.windowAnimations = R.style.SlidingDialogAnimation
                    show()
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window?.setLayout(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                background?.let {
                    authView.findViewById<LinearLayout>(R.id.auth_view).background = it
                }
                try {
                    HiddenApiBypass.invoke(
                        Class.forName("android.app.KeyguardManager"),
                        this, "semStartLockscreenFingerprintAuth"
                    )
                    dismissKeyguard(this, authDialog)
                } catch (ite: Exception) {
                    ite.printStackTrace()
                    authDialog.dismiss()
                    if (Debug.isOppoDevice) {
                        IconifiedSnackbar(this@SamSprungOverlay, viewPager).buildTickerBar(
                            getString(R.string.oppo_auth_unavailable), R.drawable.ic_fingerprint_24dp
                        ).show()
                    } else {
                        IconifiedSnackbar(this@SamSprungOverlay, viewPager).buildTickerBar(
                            getString(R.string.auth_unavailable), R.drawable.ic_fingerprint_24dp
                        ).show()
                        keyguardListener?.onKeyguardCheck(false)
                    }
                }
            } else {
                dismissKeyguard(this, null)
                keyguardListener?.onKeyguardCheck(true)
            }
        }
    }

    companion object {
        const val APPWIDGET_HOST_ID = SamSprung.request_code
    }

    private fun launchApplication(launchCommand: String, menuButton: FloatingActionButton) {
        val matchedApps: ArrayList<ApplicationInfo> = arrayListOf()
        val packages: List<ApplicationInfo> =
            if (Version.isTiramisu) {
                packageManager.getInstalledApplications(
                    ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        for (packageInfo in packages) {
            var ai: ApplicationInfo
            try {
                ai =  if (Version.isTiramisu) {
                    packageManager.getApplicationInfo(
                        packageInfo.packageName, ApplicationInfoFlags.of(0)
                    )
                } else {
                    packageManager.getApplicationInfo(packageInfo.packageName, 0)
                }
                if (packageManager.getApplicationLabel(ai).contains(launchCommand, true)) {
                    matchedApps.add(packageInfo)
                }
            } catch (_: PackageManager.NameNotFoundException) { }
        }
        if (matchedApps.isNotEmpty()) {
            if (matchedApps.size == 1) {
                launcherManager?.launchApplicationInfo(matchedApps[0])
            } else {
                bottomSheetBehaviorMain.state = BottomSheetBehavior.STATE_EXPANDED
                getSearchView()?.setQuery(launchCommand, true)
            }
        }
        menuButton.keepScreenOn = false
    }

    @Suppress("SameParameterValue")
    private fun setViewPagerSensitivity(viewPager: ViewPager2, sensitivity: Int) {
        try {
            val ff = ViewPager2::class.java.getDeclaredField("mRecyclerView")
            ff.isAccessible = true
            val recyclerView = ff[viewPager] as RecyclerView
            val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            touchSlopField.isAccessible = true
            val touchSlop = touchSlopField[recyclerView] as Int
            touchSlopField[recyclerView] = touchSlop * sensitivity
        } catch (e: NoSuchFieldException) {
            e.printStackTrace()
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    private fun configureMenuVisibility(toolbar: Toolbar) {
        val toggleStats = findViewById<LinearLayout>(R.id.toggle_status)
        for (i in 0 until toolbar.menu.size()) {
            val enabled = prefs.getBoolean(toolbar.menu.getItem(i).title?.toPref, true)
            if (enabled) {
                toolbar.menu.getItem(i).isVisible = true
                toggleStats.addView(layoutInflater.inflate(
                    R.layout.toggle_state_icon, toggleStats, false
                ).findViewById<AppCompatImageView>(R.id.toggle_icon).apply {
                    setImageDrawable(toolbar.menu.getItem(i).icon)
                })
            } else {
                toolbar.menu.getItem(i).isVisible = false
            }
        }
        toggleStats.invalidate()
    }

    private fun configureMenuIcons(toolbar: Toolbar) : Int {
        val color = prefs.getInt(Preferences.prefColors,
            Color.rgb(255, 255, 255))

        if (wifiManager.isWifiEnabled)
            toolbar.menu.findItem(R.id.toggle_wifi)
                .setIcon(R.drawable.ic_wifi_on_24dp)
        else
            toolbar.menu.findItem(R.id.toggle_wifi)
                .setIcon(R.drawable.ic_wifi_off_24dp)
        if (Version.isTiramisu) {
            toolbar.menu.findItem(R.id.toggle_bluetooth).isVisible = false
        } else if (Version.isSnowCone && hasPermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (bluetoothAdapter.isEnabled)
                toolbar.menu.findItem(R.id.toggle_bluetooth)
                    .setIcon(R.drawable.ic_bluetooth_on_24dp)
            else
                toolbar.menu.findItem(R.id.toggle_bluetooth)
                    .setIcon(R.drawable.ic_bluetooth_off_24dp)
        } else {
            toolbar.menu.findItem(R.id.toggle_bluetooth).isVisible = false
        }

        if (nfcAdapter.isEnabled)
            toolbar.menu.findItem(R.id.toggle_nfc)
                .setIcon(R.drawable.ic_nfc_on_24dp)
        else
            toolbar.menu.findItem(R.id.toggle_nfc)
                .setIcon(R.drawable.ic_nfc_off_24dp)

        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL)
            toolbar.menu.findItem(R.id.toggle_sound)
                .setIcon(R.drawable.ic_sound_on_24dp)
        else
            toolbar.menu.findItem(R.id.toggle_sound)
                .setIcon(R.drawable.ic_sound_off_24dp)

        if (notificationManager.isNotificationPolicyAccessGranted) {
            if (notificationManager.currentInterruptionFilter ==
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
                toolbar.menu.findItem(R.id.toggle_dnd)
                    .setIcon(R.drawable.ic_do_not_disturb_off_24dp)
            else
                toolbar.menu.findItem(R.id.toggle_dnd)
                    .setIcon(R.drawable.ic_do_not_disturb_on_24dp)
        } else {
            toolbar.menu.findItem(R.id.toggle_dnd).isVisible = false
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            toolbar.menu.findItem(R.id.toggle_torch).isVisible = false
        }
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.setTint(color)
        }
        return color
    }

    private fun setMenuButtonGravity(menuButton: FloatingActionButton) {
        var gravity = GravityCompat.END
        when (prefs.getInt(Preferences.prefShifts, 2)) {
            0 -> gravity = GravityCompat.START
            1 -> gravity = Gravity.CENTER_HORIZONTAL
            2 -> gravity = GravityCompat.END
        }
        val menuParams = menuButton.layoutParams as CoordinatorLayout.LayoutParams
        menuParams.anchorGravity = Gravity.TOP or gravity
        menuButton.layoutParams = menuParams
    }

    private fun onFavoritesChanged() {
        model.loadUserItems(false, this)
    }

    @SuppressLint("InflateParams")
    fun bindAppWidgets(
        binder: DesktopBinder,
        appWidgets: LinkedList<PanelWidgetInfo>
    ) {
        widgetManager?.bindAppWidgets(binder, appWidgets)
    }

    fun bindItems(
        binder: DesktopBinder,
        shortcuts: ArrayList<WidgetInfo?>?, start: Int, count: Int
    ) {
        val end = (start + DesktopBinder.ITEMS_COUNT).coerceAtMost(count)
        var i = start
        while (i < end) {
            shortcuts!![i]
            i++
        }
        if (end >= count) {
            binder.startBindingAppWidgetsWhenIdle()
        } else {
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_ITEMS, i, count).sendToTarget()
        }
    }

    fun onDesktopItemsLoaded(
    shortcuts: ArrayList<WidgetInfo?>?,
    appWidgets: ArrayList<PanelWidgetInfo>?
    ) {
        // Flag any old binder to terminate early
        mBinder?.mTerminate = true
        mBinder = DesktopBinder(this, shortcuts, appWidgets)
        mBinder?.startBindingItems()
    }

    @Deprecated("Deprecated in Java")
    override fun onRetainCustomNonConfigurationInstance(): Any? {
        mBinder?.mTerminate = true
        // return lastNonConfigurationInstance
        return null
    }

    fun showUpdateNotice() {
        CoroutineScope(Dispatchers.Main).launch {
            var animate: TranslateAnimation? = null
            val fakeSnackbar = findViewById<AnimatedLinearLayout>(R.id.update_notice)
            if (!fakeSnackbar.isVisible) {
                findViewById<TextView>(R.id.update_text).text =
                    getString(R.string.update_service, organization)
                animate = TranslateAnimation(
                    0f, 0f, 0f, -fakeSnackbar.height.toFloat()
                ).apply {
                    duration = 500
                    fillAfter = true
                }
                fakeSnackbar.setAnimationListener(object : AnimatedLinearLayout.AnimationListener {
                    override fun onAnimationStart(layout: AnimatedLinearLayout) {}
                    override fun onAnimationEnd(layout: AnimatedLinearLayout) {
                        layout.setAnimationListener(null)
                    }
                })
                fakeSnackbar.visibility = View.VISIBLE
                fakeSnackbar.startAnimation(animate)
            }
            fakeSnackbar.setOnClickListener {
                updateManager?.onUpdateRequested()
                Toast.makeText(
                    this@SamSprungOverlay, R.string.main_screen_required, Toast.LENGTH_LONG
                ).show()
                animate?.fillAfter = false
                it.visibility = View.GONE
            }
            fakeSnackbar.postDelayed({
                fakeSnackbar.setOnClickListener(null)
                animate?.fillAfter = false
                fakeSnackbar.visibility = View.GONE
            }, 5500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setScreenTimeout(findViewById(R.id.bottom_sheet_main))
        Handler(Looper.getMainLooper()).postDelayed({
            showBottomHandle(findViewById(R.id.bottom_handle), findViewById(R.id.menu_fab))
            if (null != intent.action && SamSprung.launcher == intent.action) {
                bottomSheetBehaviorMain.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }, 150)
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private fun setScreenTimeout(anchorView: View) {
        timeoutHandler.removeCallbacksAndMessages(null)
        val timeout = prefs.getInt(Preferences.prefDelays, 5)
        when {
            timeout == 4 -> { anchorView.keepScreenOn = true }
            timeout == 5 -> { anchorView.keepScreenOn = false }
            timeout > 5 -> {
                anchorView.keepScreenOn = true
                timeoutHandler.postDelayed({
                    anchorView.keepScreenOn = false
                }, (timeout * 1000).toLong())
            }
        }
    }

    private val fabShowHandler = Handler(Looper.getMainLooper())
    private fun setActionButtonTimeout() {
        fabShowHandler.removeCallbacksAndMessages(null)
        if (bottomSheetBehaviorMain.state == BottomSheetBehavior.STATE_EXPANDED) return
        val fakeOverlay = findViewById<LinearLayout>(R.id.fake_overlay)
        val menuButton = findViewById<FloatingActionButton>(R.id.menu_fab)
        menuButton.show()
        fakeOverlay.visibility = View.VISIBLE
        fabShowHandler.postDelayed({
            fakeOverlay.visibility = View.INVISIBLE
            menuButton.hide()
        }, 3300)
    }

    fun onStopOverlay() {
        try { camManager.unregisterTorchCallback(torchCallback) } catch (_: Exception) { }
        timeoutHandler.removeCallbacksAndMessages(null)
        fabShowHandler.removeCallbacksAndMessages(null)
        findViewById<View>(R.id.bottom_sheet_main).keepScreenOn = false
        try { unregisterReceiver(battReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(offReceiver) } catch (_: Exception) { }
        finish()
    }

    public override fun onDestroy() {
        super.onDestroy()
        onStopOverlay()
        if (prefs.getBoolean(getString(R.string.toggle_widgets).toPref, true)) {
            try {
                appWidgetHost?.stopListening()
            } catch (_: NullPointerException) { }
            model.unbind()
            model.abortLoaders()
            contentResolver.unregisterContentObserver(mObserver)
        }
    }

    private var isUserLeaveHint = false
    public override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        isUserLeaveHint = true
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isUserLeaveHint) {
            isUserLeaveHint = false
            return
        }
        setActionButtonTimeout()
        setScreenTimeout(findViewById(R.id.bottom_sheet_main))
    }

    override fun onStart() {
        super.onStart()
        windowInfoTracker = WindowInfoTrackerCallbackAdapter(
            WindowInfoTracker.getOrCreate(this)
        )
        layoutStateCallback.setListener(object: LayoutStateChangeCallback.FoldingFeatureListener {
            override fun onHalfOpened() { }

            override fun onSeparating() { }

            override fun onFlat() { }

            override fun onNormal() { }
        })
        windowInfoTracker?.addWindowLayoutInfoListener(
            this, Runnable::run, layoutStateCallback
        )
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        super.onStop()
        windowInfoTracker?.removeWindowLayoutInfoListener(layoutStateCallback)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return super.dispatchTouchEvent(ev)
    }
}