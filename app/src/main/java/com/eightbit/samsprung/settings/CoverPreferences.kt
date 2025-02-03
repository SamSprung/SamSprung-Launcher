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

package com.eightbit.samsprung.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.icu.text.DecimalFormatSymbols
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.*
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eightbit.content.ScaledContext
import com.eightbit.io.Debug
import com.eightbit.material.IconifiedSnackbar
import com.eightbit.os.Version
import com.eightbit.pm.PackageRetriever
import com.eightbit.samsprung.*
import com.eightbit.samsprung.update.UpdateManager
import com.eightbit.view.AnimatedLinearLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.*

private val Number.toPx get() = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
).toInt()

private val CharSequence.toPref get() = this.toString().lowercase().replace(" ", "_")

@SuppressLint("UseSwitchCompatOrMaterialCode")
class CoverPreferences : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
    }

    private lateinit var coordinator: CoordinatorLayout
    private var updateManager : UpdateManager? = null

    private var mainSwitch: Switch? = null
    private var accessibility: Switch? = null
    private var optimization: Switch? = null
    private var notifications: Switch? = null
    private var statistics: Switch? = null
    private var keyboard: Switch? = null
    private lateinit var wikiDrawer: DrawerLayout

    private lateinit var hiddenList: RecyclerView

    private val donationManager = DonationManager(this)

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setBackgroundDrawable(ColorDrawable(
            MaterialColors.getColor(window.decorView, R.attr.colorSecondaryVariant)
        ))
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu_24)

        setTheme(R.style.Theme_SecondScreen)
        setContentView(R.layout.preferences_layout)

        val componentName = ComponentName(applicationContext, NotificationReceiver::class.java)
        packageManager.setComponentEnabledSetting(componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        packageManager.setComponentEnabledSetting(componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )

        donationManager.retrieveDonationMenu()

        coordinator = findViewById(R.id.coordinator)
        findViewById<BlurView>(R.id.blurContainer).setupWith(coordinator)
            .setFrameClearDrawable(coordinator.background)
            .setBlurRadius(10f).setBlurAutoUpdate(true)

        wikiDrawer = findViewById(R.id.drawer_layout)
        findViewById<TextView>(R.id.build_info).text =
            getString(R.string.build_hash, BuildConfig.COMMIT)
        findViewById<LinearLayout>(R.id.build_layout).setOnClickListener {
            wikiDrawer.openDrawer(GravityCompat.START)
        }

        val googlePlay = findViewById<LinearLayout>(R.id.button_donate)
        googlePlay.setOnClickListener { donationManager.onSendDonationClicked() }

        findViewById<LinearLayout>(R.id.logcat).setOnClickListener {
            if (updateManager?.hasPendingUpdate() == true) {
                IconifiedSnackbar(this).buildTickerBar(
                    getString(R.string.update_service, getString(R.string.app_name))
                ).show()
                return@setOnClickListener
            }
            try {
                Debug(this).captureLogcat(isDeviceSecure())
            } catch (e: IOException) {
                e.message?.let {
                    IconifiedSnackbar(this).buildSnackbar(it, Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        notifications = findViewById(R.id.notifications_switch)
        notifications?.isChecked = hasNotificationListener()
        notifications?.isClickable = false
        findViewById<LinearLayout>(R.id.notifications).setOnClickListener {
            notificationLauncher.launch(Intent(
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            ))
        }

        statistics = findViewById(R.id.usage_switch)
        statistics?.isChecked = hasUsageStatistics()
        statistics?.isClickable = false
        findViewById<LinearLayout>(R.id.usage_layout).setOnClickListener {
            usageLauncher.launch(Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS
            ))
        }

        optimization = findViewById(R.id.optimization_switch)
        optimization?.isChecked = ignoreBatteryOptimization()
        optimization?.isClickable = false
        findViewById<LinearLayout>(R.id.optimization).setOnClickListener {
            try {
                optimizationLauncher.launch(Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: ActivityNotFoundException) {
                optimizationLauncher.launch(Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                ))
            }
        }

        accessibility = findViewById(R.id.accessibility_switch)
        accessibility?.isChecked = hasAccessibility()
        accessibility?.isClickable = false
        findViewById<LinearLayout>(R.id.accessibility).setOnClickListener {
            if (accessibility?.isChecked == true) {
                accessibilityLauncher.launch(
                    Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            } else {
                AlertDialog.Builder(this)
                    .setMessage(R.string.accessibility_directions)
                    .setPositiveButton(R.string.button_confirm) { dialog, _ ->
                        accessibilityLauncher.launch(Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        ))
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.button_cancel) { dialog, _ ->
                        accessibility?.isChecked = false
                        dialog.dismiss()
                    }.show()
            }
        }
        accessibility?.isGone = BuildConfig.GOOGLE_PLAY

        findViewById<LinearLayout>(R.id.voice_layout).setOnClickListener {
            requestVoice.launch(Manifest.permission.RECORD_AUDIO)
        }
        toggleVoiceIcon(hasPermission(Manifest.permission.RECORD_AUDIO))

        keyboard = findViewById(R.id.keyboard_switch)
        keyboard?.isClickable = false
        keyboard?.isChecked = hasKeyboardInstalled()
        findViewById<LinearLayout>(R.id.keyboard_layout).setOnClickListener {
            try {
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(
                    "market://details?id=" + BuildConfig.APPLICATION_ID + ".ime"
                ))
                playIntent.setPackage("com.android.vending")
                keyboardLauncher.launch(playIntent)
            } catch (_: ActivityNotFoundException) {
                keyboardLauncher.launch(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/apps/details?id="
                                + BuildConfig.APPLICATION_ID + ".ime"
                )))
            }
        }

        val nestedOptions = findViewById<ScrollView>(R.id.nested_options)
        val general = findViewById<LinearLayout>(R.id.general)
        val drawer = findViewById<LinearLayout>(R.id.drawer)
        val notices = findViewById<LinearLayout>(R.id.notices)

        findViewById<LinearLayout>(R.id.menu_general).setOnClickListener {
            if (general.isGone && (drawer.isVisible || notices.isVisible)) {
                drawer.isGone = true
                notices.isGone = true
                general.postDelayed({
                    general.isVisible = true
                    nestedOptions.scrollToDescendant(general)
                }, 100)
            } else {
                general.isGone = general.isVisible
                nestedOptions.scrollToDescendant(general)
            }
        }
        general.isGone = true

        val color = prefs.getInt(Preferences.prefColors, Color.rgb(255, 255, 255))

        val textRed = findViewById<TextView>(R.id.color_red_text)
        val colorRedBar = findViewById<SeekBar>(R.id.color_red_bar)
        colorRedBar.progress = color.red

        colorRedBar.progressTintList = ColorStateList
            .valueOf(Color.rgb(colorRedBar.progress, 0,0))

        val textGreen = findViewById<TextView>(R.id.color_green_text)
        val colorGreenBar = findViewById<SeekBar>(R.id.color_green_bar)
        colorGreenBar.progress = color.green

        colorGreenBar.progressTintList = ColorStateList
            .valueOf(Color.rgb(0, colorGreenBar.progress, 0))

        val textBlue = findViewById<TextView>(R.id.color_blue_text)
        val colorBlueBar = findViewById<SeekBar>(R.id.color_blue_bar)
        colorBlueBar.progress = color.blue

        colorBlueBar.progressTintList = ColorStateList
            .valueOf(Color.rgb(0, 0, colorBlueBar.progress))

        val alphaFloat = prefs.getFloat(Preferences.prefAlphas, 1f)
        val alphaPreview = findViewById<View>(R.id.alpha_preview)
        val alphaView = findViewById<LinearLayout>(R.id.color_alpha_view)
        val colorAlphaBar = findViewById<SeekBar>(R.id.color_alpha_bar)
        alphaPreview.setBackgroundColor(color)
        alphaPreview.alpha = alphaFloat
        colorAlphaBar.progress = (alphaFloat * 100).toInt()

        val colorPanel = findViewById<AnimatedLinearLayout>(R.id.color_panel)
        val colorComposite = findViewById<View>(R.id.color_composite)
        colorComposite.setBackgroundColor(color)

        colorComposite.setOnClickListener {
            if (colorPanel.isVisible) {
                val animate = TranslateAnimation(
                    0f, 0f, 0f, -colorPanel.height.toFloat()
                ).apply {
                    duration = 750
                    fillAfter = false
                }
                colorPanel.setAnimationListener(object : AnimatedLinearLayout.AnimationListener {
                    override fun onAnimationStart(layout: AnimatedLinearLayout) {
                        colorPanel.postDelayed({
                            textRed.visibility = View.INVISIBLE
                        }, 125)
                        colorPanel.postDelayed({
                            colorRedBar.visibility = View.INVISIBLE
                        }, 150)
                        colorPanel.postDelayed({
                            textGreen.visibility = View.INVISIBLE
                        }, 250)
                        colorPanel.postDelayed({
                            colorGreenBar.visibility = View.INVISIBLE
                        }, 275)
                        colorPanel.postDelayed({
                            textBlue.visibility = View.INVISIBLE
                        }, 400)
                        colorPanel.postDelayed({
                            colorBlueBar.visibility = View.INVISIBLE
                        }, 425)
                        colorPanel.postDelayed({
                            alphaView.visibility = View.INVISIBLE
                        }, 525)
                        colorPanel.postDelayed({
                            colorAlphaBar.visibility = View.INVISIBLE
                        }, 550)
                    }
                    override fun onAnimationEnd(layout: AnimatedLinearLayout) {
                        colorPanel.clearAnimation()
                        layout.setAnimationListener(null)
                        textRed.visibility = View.GONE
                        colorRedBar.visibility = View.GONE
                        textGreen.visibility = View.GONE
                        colorGreenBar.visibility = View.GONE
                        textBlue.visibility = View.GONE
                        colorBlueBar.visibility = View.GONE
                        alphaView.visibility = View.GONE
                        colorAlphaBar.visibility = View.GONE
                        colorPanel.visibility = View.GONE
                    }
                })
                colorPanel.startAnimation(animate)
            } else {
                colorPanel.visibility = View.VISIBLE
                colorPanel.postDelayed({
                    colorAlphaBar.visibility = View.VISIBLE
                }, 50)
                colorPanel.postDelayed({
                    alphaView.visibility = View.VISIBLE
                }, 75)
                colorPanel.postDelayed({
                    colorBlueBar.visibility = View.VISIBLE
                }, 125)
                colorPanel.postDelayed({
                    textBlue.visibility = View.VISIBLE
                }, 150)
                colorPanel.postDelayed({
                    colorGreenBar.visibility = View.VISIBLE
                }, 200)
                colorPanel.postDelayed({
                    textGreen.visibility = View.VISIBLE
                }, 225)
                colorPanel.postDelayed({
                    colorRedBar.visibility = View.VISIBLE
                }, 275)
                colorPanel.postDelayed({
                    textRed.visibility = View.VISIBLE
                }, 300)
            }
        }

        colorRedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newColor = Color.rgb(
                    progress,
                    colorGreenBar.progress,
                    colorBlueBar.progress
                )
                with(prefs.edit()) {
                    putInt(Preferences.prefColors, newColor)
                    apply()
                }
                colorComposite.setBackgroundColor(newColor)
                alphaPreview.setBackgroundColor(newColor)
                colorRedBar.progressTintList = ColorStateList
                    .valueOf(Color.rgb(progress, 0, 0))
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })

        colorGreenBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newColor = Color.rgb(
                    colorRedBar.progress,
                    progress,
                    colorBlueBar.progress
                )
                with(prefs.edit()) {
                    putInt(Preferences.prefColors, newColor)
                    apply()
                }
                colorComposite.setBackgroundColor(newColor)
                alphaPreview.setBackgroundColor(newColor)
                colorGreenBar.progressTintList = ColorStateList
                    .valueOf(Color.rgb(0, progress, 0))
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })

        colorBlueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newColor = Color.rgb(
                    colorRedBar.progress,
                    colorGreenBar.progress,
                    progress
                )
                with(prefs.edit()) {
                    putInt(Preferences.prefColors, newColor)
                    apply()
                }
                colorComposite.setBackgroundColor(newColor)
                alphaPreview.setBackgroundColor(newColor)
                colorBlueBar.progressTintList = ColorStateList
                    .valueOf(Color.rgb(0, 0, progress))
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })

        colorAlphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val alpha = progress.toFloat() / 100
                with(prefs.edit()) {
                    putFloat(Preferences.prefAlphas, alpha)
                    apply()
                }
                alphaPreview.alpha = alpha
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })

        textRed.visibility = View.GONE
        colorRedBar.visibility = View.GONE
        textGreen.visibility = View.GONE
        colorGreenBar.visibility = View.GONE
        textBlue.visibility = View.GONE
        colorBlueBar.visibility = View.GONE
        alphaView.visibility = View.GONE
        colorAlphaBar.visibility = View.GONE
        colorPanel.visibility = View.GONE

        val placementBar = findViewById<SeekBar>(R.id.placement_bar)
        placementBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                with(prefs.edit()) {
                    putInt(Preferences.prefShifts, progress)
                    apply()
                }
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })
        placementBar.progress = prefs.getInt(Preferences.prefShifts, 2)

        findViewById<Spinner>(R.id.theme_spinner).apply {
            adapter = ArrayAdapter(
                this@CoverPreferences, android.R.layout.simple_spinner_item,
                resources.getStringArray(R.array.theme_options)
            ).apply {
                setDropDownViewResource(R.layout.dropdown_item_1)
            }
            setSelection(prefs.getInt(Preferences.prefThemes, 0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    (getChildAt(0) as? TextView)?.setTextColor(Color.BLACK)
                    with(prefs.edit()) {
                        putInt(Preferences.prefThemes, position)
                        apply()
                    }
                    (application as SamSprung).setThemePreference()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.cover_quick_toggles)

        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            val pref = item.title?.toPref
            with(prefs.edit()) {
                putBoolean(pref, !prefs.getBoolean(pref, true))
                apply()
            }
            when (item.itemId) {
                R.id.toggle_wifi -> {
                    if (prefs.getBoolean(pref, true))
                        item.setIcon(R.drawable.ic_wifi_on_24dp)
                    else
                        item.setIcon(R.drawable.ic_wifi_off_24dp)
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_bluetooth -> {
                    if (Version.isTiramisu) {
                        Toast.makeText(
                            this@CoverPreferences,
                            R.string.tiramisu_bluetooth, Toast.LENGTH_SHORT
                        ).show()
                    } else if (Version.isSnowCone) {
                        requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        toggleBluetoothIcon(toolbar)
                    }
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_nfc -> {
                    if (prefs.getBoolean(pref, true))
                        item.setIcon(R.drawable.ic_nfc_on_24dp)
                    else
                        item.setIcon(R.drawable.ic_nfc_off_24dp)
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_sound -> {
                    if (prefs.getBoolean(pref, true))
                        item.setIcon(R.drawable.ic_sound_on_24dp)
                    else
                        item.setIcon(R.drawable.ic_sound_off_24dp)
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_dnd -> {
                    if (prefs.getBoolean(pref, true))
                        item.setIcon(R.drawable.ic_do_not_disturb_on_24dp)
                    else
                        item.setIcon(R.drawable.ic_do_not_disturb_off_24dp)
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_torch -> {
                    if (prefs.getBoolean(pref, true))
                        item.setIcon(R.drawable.ic_flashlight_on_24dp)
                    else
                        item.setIcon(R.drawable.ic_flashlight_off_24dp)
                    return@setOnMenuItemClickListener true
                }
                R.id.toggle_widgets -> {
                    requestWidgets.launch(Manifest.permission.BIND_APPWIDGET)
                    return@setOnMenuItemClickListener true
                }
                else -> {
                    return@setOnMenuItemClickListener false
                }
            }
        }

        val wifi = toolbar.menu.findItem(R.id.toggle_wifi)
        if (prefs.getBoolean(wifi.title?.toPref, true))
            wifi.setIcon(R.drawable.ic_wifi_on_24dp)
        else
            wifi.setIcon(R.drawable.ic_wifi_off_24dp)

        if (Version.isSnowCone) {
            if (Version.isTiramisu || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                with(prefs.edit()) {
                    putBoolean(toolbar.menu.findItem(R.id.toggle_bluetooth).title?.toPref, false)
                    apply()
                }
            }
        }

        toggleBluetoothIcon(toolbar)

        val nfc = toolbar.menu.findItem(R.id.toggle_nfc)
        if (prefs.getBoolean(nfc.title?.toPref, true))
            nfc.setIcon(R.drawable.ic_nfc_on_24dp)
        else
            nfc.setIcon(R.drawable.ic_nfc_off_24dp)

        val sound = toolbar.menu.findItem(R.id.toggle_sound)
        if (prefs.getBoolean(sound.title?.toPref, true))
            sound.setIcon(R.drawable.ic_sound_on_24dp)
        else
            sound.setIcon(R.drawable.ic_sound_off_24dp)

        val dnd = toolbar.menu.findItem(R.id.toggle_dnd)
        if (prefs.getBoolean(dnd.title?.toPref, true))
            dnd.setIcon(R.drawable.ic_do_not_disturb_on_24dp)
        else
            dnd.setIcon(R.drawable.ic_do_not_disturb_off_24dp)

        val torch = toolbar.menu.findItem(R.id.toggle_torch)
        if (prefs.getBoolean(torch.title?.toPref, true))
            torch.setIcon(R.drawable.ic_flashlight_on_24dp)
        else
            torch.setIcon(R.drawable.ic_flashlight_off_24dp)

        toggleWidgetsIcon(toolbar)

        findViewById<LinearLayout>(R.id.menu_drawer).setOnClickListener {
            if (drawer.isGone && (general.isVisible || notices.isVisible)) {
                general.isGone = true
                notices.isGone = true
                drawer.postDelayed({
                    drawer.isVisible = true
                    nestedOptions.scrollToDescendant(drawer)
                }, 100)
            } else {
                drawer.isGone = drawer.isVisible
                nestedOptions.scrollToDescendant(drawer)
            }
        }
        drawer.isGone = true

        val fabSize = findViewById<AppCompatCheckBox>(R.id.fab_size_switch)
        fabSize.isChecked = prefs.getBoolean(Preferences.prefTapper, false)
        fabSize.setOnCheckedChangeListener { _, isChecked ->
            with(prefs.edit()) {
                putBoolean(Preferences.prefTapper, isChecked)
                apply()
            }
        }
        findViewById<LinearLayout>(R.id.fab_size).setOnClickListener {
            fabSize.isChecked = !fabSize.isChecked
        }

        val vibration = findViewById<AppCompatCheckBox>(R.id.vibration_switch)
        vibration.isChecked = prefs.getBoolean(Preferences.prefReacts, true)
        vibration.setOnCheckedChangeListener { _, isChecked ->
            with(prefs.edit()) {
                putBoolean(Preferences.prefReacts, isChecked)
                apply()
            }
        }
        findViewById<LinearLayout>(R.id.vibration).setOnClickListener {
            vibration.isChecked = !vibration.isChecked
        }

        val gestures = findViewById<AppCompatCheckBox>(R.id.gestures_switch)
        gestures.isChecked = prefs.getBoolean(Preferences.prefSlider, true)
        gestures.setOnCheckedChangeListener { _, isChecked ->
            with(prefs.edit()) {
                putBoolean(Preferences.prefSlider, isChecked)
                apply()
            }
        }
        findViewById<LinearLayout>(R.id.gestures).setOnClickListener {
            gestures.isChecked = !gestures.isChecked
        }

        findViewById<Spinner>(R.id.viewpager_spinner).apply {
            adapter = ArrayAdapter(
                this@CoverPreferences, android.R.layout.simple_spinner_item,
                resources.getStringArray(R.array.pageTransformers)
            ).apply {
                setDropDownViewResource(R.layout.dropdown_item_1)
            }
            setSelection(prefs.getInt(Preferences.prefPaging, 0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    (getChildAt(0) as? TextView)?.setTextColor(Color.BLACK)
                    with(prefs.edit()) {
                        putInt(Preferences.prefPaging, position)
                        apply()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val lengthBar = findViewById<SeekBar>(R.id.length_bar)
        lengthBar.max = if (Debug.isOppoDevice) 3 else 6
        val lengthText = findViewById<TextView>(R.id.length_text)
        lengthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                with(prefs.edit()) {
                    putInt(Preferences.prefLength, progress)
                    apply()
                }
                val textLength = (if (lengthBar.progress < lengthBar.max) lengthBar.progress else
                    DecimalFormatSymbols.getInstance().infinity).toString()
                setSuperscriptText(lengthText, R.string.options_length, textLength)
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })
        lengthBar.progress = prefs.getInt(Preferences.prefLength, 6)
        val textLength = (if (lengthBar.progress < 6) lengthBar.progress else
            DecimalFormatSymbols.getInstance().infinity).toString()
        setSuperscriptText(lengthText, R.string.options_length, textLength)

        val search = findViewById<AppCompatCheckBox>(R.id.search_switch)
        search.setOnCheckedChangeListener { _, isChecked ->
            with(prefs.edit()) {
                putBoolean(Preferences.prefSearch, isChecked && keyboard?.isChecked == true)
                apply()
            }
        }
        search.isChecked = prefs.getBoolean(
            Preferences.prefSearch, keyboard?.isChecked == true
        ) && keyboard?.isChecked == true
        findViewById<LinearLayout>(R.id.search).setOnClickListener {
            search.isChecked = !search.isChecked && keyboard?.isChecked == true
            if (keyboard?.isChecked != true) {
                Toast.makeText(
                    this@CoverPreferences,
                    R.string.keyboard_missing, Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<LinearLayout>(R.id.wallpaper_layout).setOnClickListener {
            onPickImage.launch(arrayOf("image/*"))
        }
        findViewById<LinearLayout>(R.id.wallpaper_layout).setOnLongClickListener {
            val background = File(filesDir, "wallpaper.png")
            if (background.exists()) background.delete()
            val animated = File(filesDir, "wallpaper.gif")
            if (animated.exists()) animated.delete()
            Toast.makeText(this@CoverPreferences,
                R.string.wallpaper_cleared, Toast.LENGTH_SHORT).show()
            return@setOnLongClickListener true
        }

        val radius = findViewById<AppCompatCheckBox>(R.id.radius_switch)
        radius.isChecked = prefs.getBoolean(Preferences.prefRadius, true)
        radius.setOnCheckedChangeListener { _, isChecked ->
            with(prefs.edit()) {
                putBoolean(Preferences.prefRadius, isChecked)
                apply()
            }
        }
        findViewById<LinearLayout>(R.id.radius).setOnClickListener {
            radius.isChecked = !radius.isChecked
        }

        val timeoutBar = findViewById<SeekBar>(R.id.timeout_bar)
        val timeoutText = findViewById<TextView>(R.id.timeout_text)
        timeoutBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                with(prefs.edit()) {
                    putInt(Preferences.prefDelays, progress)
                    apply()
                }
                val textDelay = (if (timeoutBar.progress > 4) timeoutBar.progress else
                    DecimalFormatSymbols.getInstance().infinity).toString()
                setSuperscriptText(timeoutText, R.string.options_timeout, textDelay)
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })
        timeoutBar.progress = prefs.getInt(Preferences.prefDelays, 5)
        val textDelay = (if (timeoutBar.progress > 4) timeoutBar.progress else
            DecimalFormatSymbols.getInstance().infinity).toString()
        setSuperscriptText(timeoutText, R.string.options_timeout, textDelay)

        val isGridView = prefs.getBoolean(Preferences.prefLayout, true)
        findViewById<ToggleButton>(R.id.swapViewType).isChecked = isGridView
        findViewById<ToggleButton>(R.id.swapViewType).setOnCheckedChangeListener { _, isChecked ->
            with (prefs.edit()) {
                putBoolean(Preferences.prefLayout, isChecked)
                apply()
            }
        }

        findViewById<LinearLayout>(R.id.menu_notices).setOnClickListener {
            if (notices.isGone && (general.isVisible || drawer.isVisible)) {
                general.isGone = true
                drawer.isGone = true
                notices.postDelayed({
                    notices.isVisible = true
                    nestedOptions.scrollToDescendant(notices)
                }, 100)
            } else {
                notices.isGone = notices.isVisible
                nestedOptions.scrollToDescendant(notices)
            }
        }
        notices.isGone = true

        val dismissBar = findViewById<SeekBar>(R.id.dismiss_bar)
        val dismissText = findViewById<TextView>(R.id.dismiss_text)
        dismissBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                with(prefs.edit()) {
                    putInt(Preferences.prefSnooze, progress * 10)
                    apply()
                }
                dismissText.text = getString(
                    R.string.options_dismiss, (dismissBar.progress * 10).toString()
                )
            }

            override fun onStartTrackingTouch(seek: SeekBar) { }

            override fun onStopTrackingTouch(seek: SeekBar) { }
        })
        dismissBar.progress = prefs.getInt(Preferences.prefSnooze, 30) / 10
        dismissText.text = getString(
            R.string.options_dismiss, (dismissBar.progress * 10).toString()
        )

        CoroutineScope(Dispatchers.IO).launch {
            val packageRetriever = PackageRetriever(this@CoverPreferences)
            val packages = packageRetriever.getPackageList()
            for (installed in packages) {
                if (installed.resolvePackageName == "apps.ijp.coveros") {
                    withContext(Dispatchers.Main) {
                        val compatDialog = AlertDialog.Builder(this@CoverPreferences)
                            .setMessage(getString(R.string.incompatibility_warning))
                            .setPositiveButton(R.string.button_uninstall) { dialog, _ ->
                                try {
                                    startActivity(Intent(Intent.ACTION_DELETE).setData(
                                        Uri.parse("package:apps.ijp.coveros")
                                    ))
                                    dialog.dismiss()
                                } catch (_: Exception) { }
                            }
                            .setNegativeButton(R.string.button_disable) { dialog, _ ->
                                startActivity(Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:apps.ijp.coveros")
                                ))
                                dialog.dismiss()
                            }.create()
                        compatDialog.setCancelable(false)
                        compatDialog.show()
                    }
                }
            }
            val unlisted = packageRetriever.getHiddenPackages()

            withContext(Dispatchers.Main) {
                hiddenList = findViewById<RecyclerView>(R.id.app_toggle_list).apply {
                    layoutManager = LinearLayoutManager(this@CoverPreferences)
                    addItemDecoration(DividerItemDecoration(
                        this@CoverPreferences, DividerItemDecoration.VERTICAL
                    ))
                    adapter = FilteredAppsAdapter(packageManager, packages, unlisted, prefs)
                    FastScrollerBuilder(this).build().setPadding(0, (-4).toPx,0,0)
                }
            }
        }

        findViewById<View>(R.id.list_divider).setOnTouchListener { v: View, event: MotionEvent ->
            val y = event.y.toInt()
            val srcHeight = nestedOptions.layoutParams.height
            if (nestedOptions.layoutParams.height + y >= -0.5f) {
                if (event.action == MotionEvent.ACTION_MOVE) {
                    nestedOptions.layoutParams.height += y
                    if (srcHeight != nestedOptions.layoutParams.height) nestedOptions.requestLayout()
                } else if (event.action == MotionEvent.ACTION_UP) {
                    if (nestedOptions.layoutParams.height + y < 0f) {
                        nestedOptions.layoutParams.height = 0
                    } else {
                        val minHeight: Float = v.height + resources.getDimension(R.dimen.sliding_bar_margin)
                        if (nestedOptions.layoutParams.height > coordinator.height - minHeight.toInt())
                            nestedOptions.layoutParams.height = coordinator.height - minHeight.toInt()
                    }
                    if (srcHeight != nestedOptions.layoutParams.height) nestedOptions.requestLayout()
                }
            }
            true
        }

        val mWebView = findViewById<WebView>(R.id.webview_wiki)
        val webViewSettings: WebSettings = mWebView.settings
        mWebView.isScrollbarFadingEnabled = true
        webViewSettings.loadWithOverviewMode = true
        webViewSettings.useWideViewPort = true
        @SuppressLint("SetJavaScriptEnabled")
        webViewSettings.javaScriptEnabled = true
        webViewSettings.domStorageEnabled = true
        webViewSettings.cacheMode = WebSettings.LOAD_NO_CACHE
        webViewSettings.setSupportZoom(true)
        webViewSettings.builtInZoomControls = true
        mWebView.loadUrl("https://samsprung.github.io/launcher/")
    }

    private fun setAnimatedUpdateNotice() {
        CoroutineScope(Dispatchers.Main).launch {
            val buildIcon = findViewById<AppCompatImageView>(R.id.build_icon)
            buildIcon.setImageDrawable(ContextCompat.getDrawable(
                this@CoverPreferences, R.drawable.ic_software_update_24))
            val buildInfo = findViewById<TextView>(R.id.build_info)
            val colorStateList = buildInfo.textColors
            buildInfo.setTextColor(Color.RED)
            val anim: Animation = AlphaAnimation(0.2f, 1.0f)
            anim.duration = 500
            anim.repeatMode = Animation.REVERSE
            anim.repeatCount = Animation.INFINITE
            buildInfo.startAnimation(anim)
            findViewById<LinearLayout>(R.id.build_layout).setOnClickListener {
                buildIcon.setImageDrawable(ContextCompat.getDrawable(
                    this@CoverPreferences, R.drawable.ic_github_octocat_24dp))
                anim.cancel()
                buildInfo.setTextColor(colorStateList)
                updateManager?.onUpdateRequested()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val requestWallpaper = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        var isStorageEnabled = false
        permissions.entries.forEach {
            if (!Version.isTiramisu || it.key == Manifest.permission.READ_MEDIA_IMAGES) {
                isStorageEnabled = it.value
            }
        }
        if (isStorageEnabled) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    coordinator.background = try {
                        WallpaperManager.getInstance(this@CoverPreferences).drawable
                    } catch (_: SecurityException) {
                        WallpaperManager.getInstance(this@CoverPreferences).peekDrawable()
                    }
                } catch (_: SecurityException) { }
            }
        }
    }

    private fun saveAnimatedImage(sourceUri: Uri) {
        val background = File(filesDir, "wallpaper.png")
        if (background.exists()) background.delete()
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            val destinationFilename = File(filesDir, "wallpaper.gif")
            try {
                contentResolver.openInputStream(sourceUri).use { stream ->
                    FileOutputStream(destinationFilename, false).use {
                        stream?.copyTo(it)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun saveStaticImage(sourceUri: Uri) {
        val animated = File(filesDir, "wallpaper.gif")
        if (animated.exists()) animated.delete()
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            val source: ImageDecoder.Source = ImageDecoder.createSource(
                this@CoverPreferences.contentResolver, sourceUri
            )
            val bitmap: Bitmap = ImageDecoder.decodeBitmap(source)
            var rotation = -1
            val background = File(filesDir, "wallpaper.png")
            contentResolver.query(
                sourceUri, arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                null, null, null
            ).use {
                if (it?.count == 1) {
                    it.moveToFirst()
                    rotation = it.getInt(0)
                }
                if (rotation > 0) {
                    val matrix = Matrix()
                    matrix.postRotate(rotation.toFloat())
                    background.writeBitmap(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width,
                        bitmap.height, matrix, true), Bitmap.CompressFormat.PNG, 100)
                } else {
                    background.writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 100)
                }
            }
        }
    }

    private val onPickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { document: Uri? ->
        document?.let {
            contentResolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val extension: String? = when {
                document.scheme == ContentResolver.SCHEME_CONTENT -> {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(
                        contentResolver.getType(document)
                    )
                } null != document.path -> {
                    MimeTypeMap.getFileExtensionFromUrl(
                        Uri.fromFile(File(document.path!!)).toString()
                    )
                } else -> {
                    null
                }
            }
            if (extension.equals("gif", true))
                saveAnimatedImage(document)
            else
                saveStaticImage(document)
        }
    }

    private fun toggleVoiceIcon(isEnabled: Boolean) {
        findViewById<AppCompatImageView>(R.id.voice_button).setImageResource(
            if (isEnabled)
                R.drawable.ic_record_voice_over_24dp
            else
                R.drawable.ic_voice_over_off_24dp
        )
    }

    private val requestVoice = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { toggleVoiceIcon(it) }

    private fun toggleBluetoothIcon(toolbar: Toolbar) {
        val bluetooth = toolbar.menu.findItem(R.id.toggle_bluetooth)
        if (prefs.getBoolean(bluetooth.title?.toPref, true))
            bluetooth.setIcon(R.drawable.ic_bluetooth_on_24dp)
        else
            bluetooth.setIcon(R.drawable.ic_bluetooth_off_24dp)
    }

    @SuppressLint("MissingPermission")
    private val requestBluetooth = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        if (!it) {
            with(prefs.edit()) {
                putBoolean(toolbar.menu.findItem(R.id.toggle_bluetooth).title?.toPref, false)
                apply()
            }
        }
        toggleBluetoothIcon(toolbar)
    }

    private fun toggleWidgetsIcon(toolbar: Toolbar) {
        val widgets = toolbar.menu.findItem(R.id.toggle_widgets)
        if (prefs.getBoolean(widgets.title?.toPref, false))
            widgets.setIcon(R.drawable.ic_widgets_24dp)
        else
            widgets.setIcon(R.drawable.ic_insert_page_break_24dp)
    }

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {  }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (null != mainSwitch) {
            mainSwitch?.isChecked = Settings.canDrawOverlays(applicationContext)
            if (Version.isTiramisu && mainSwitch?.isChecked == true) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            try {
                ScaledContext(this).cover().run {
                    startForegroundService(Intent(this, OnBroadcastService::class.java))
                }
            } catch (_: IllegalArgumentException) {
                Toast.makeText(
                    this@CoverPreferences, R.string.display_unavailable, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private val usageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        val packageRetriever = PackageRetriever(this)
        CoroutineScope(Dispatchers.IO).launch {
            val packages = packageRetriever.getPackageList()
            val unlisted = packageRetriever.getHiddenPackages()
            withContext(Dispatchers.Main) {
                val adapter = hiddenList.adapter as FilteredAppsAdapter
                adapter.setPackages(packages, unlisted)
                adapter.notifyDataSetChanged()
            }
        }
        statistics?.isChecked = hasUsageStatistics()
    }

    private val optimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        optimization?.isChecked = ignoreBatteryOptimization()
    }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        accessibility?.isChecked = hasAccessibility()
    }

    private val keyboardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        keyboard?.isChecked = hasKeyboardInstalled()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        notifications?.isChecked = hasNotificationListener()
    }

    private val requestWidgets = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        toggleWidgetsIcon(findViewById(R.id.toolbar))
    }

    fun openWikiDrawer() {
        wikiDrawer.openDrawer(GravityCompat.START)
    }

    private fun setSuperscriptText(view: TextView, resource: Int, value: String) {
        val text = SpannableStringBuilder(getString(resource, value))
        text.setSpan(
            RelativeSizeSpan(0.75f),
            text.length - value.length - 1, text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = text
    }

    private fun isDeviceSecure(): Boolean {
        return with (getSystemService(KEYGUARD_SERVICE) as KeyguardManager) { isDeviceSecure }
    }

    private fun hasPermission(permission: String) : Boolean {
        return ContextCompat.checkSelfPermission(
            this@CoverPreferences, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ignoreBatteryOptimization(): Boolean {
        return with (getSystemService(POWER_SERVICE) as PowerManager) {
            isIgnoringBatteryOptimizations(packageName)
        }
    }

    private fun hasAccessibility(): Boolean {
        if (BuildConfig.GOOGLE_PLAY) return false
        val serviceString = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return serviceString?.contains(packageName
                + File.separator + AccessibilityObserver::class.java.name) == true
    }

    private fun hasNotificationListener(): Boolean {
        val myNotificationListenerComponentName = ComponentName(
            applicationContext, NotificationReceiver::class.java)
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners")
        if (null == enabledListeners || enabledListeners.isEmpty()) return false
        return enabledListeners.split(":").map {
            ComponentName.unflattenFromString(it)
        }.any {componentName->
            myNotificationListenerComponentName == componentName
        }
    }

    private fun hasUsageStatistics() : Boolean {
        try {
            with (getSystemService(APP_OPS_SERVICE) as AppOpsManager) {
                unsafeCheckOp("android:get_usage_stats", Process.myUid(), packageName).run {
                    if (this == AppOpsManager.MODE_ALLOWED) return true
                }
            }
        } catch (_: SecurityException) { }
        return false
    }

    private fun hasKeyboardInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(BuildConfig.APPLICATION_ID + ".ime", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls())
            updateManager?.requestUpdateJSON()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cover_settings_menu, menu)
        val actionSwitch: MenuItem = menu.findItem(R.id.switch_action_bar)
        actionSwitch.setActionView(R.layout.configure_switch)
        mainSwitch = menu.findItem(R.id.switch_action_bar).actionView?.findViewById(R.id.switch2)
        mainSwitch?.isChecked = Settings.canDrawOverlays(applicationContext)
        if (Version.isTiramisu && mainSwitch?.isChecked == true) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        mainSwitch?.setOnClickListener {
            overlayLauncher.launch(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (wikiDrawer.isDrawerOpen(GravityCompat.START)) {
                wikiDrawer.closeDrawer(GravityCompat.START)
            } else {
                wikiDrawer.openDrawer(GravityCompat.START)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private var widgetNotice : Snackbar? = null

    override fun onStart() {
        super.onStart()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    wikiDrawer.isDrawerOpen(GravityCompat.START) ->
                        wikiDrawer.closeDrawer(GravityCompat.START)
                    null == widgetNotice -> {
                        if (mainSwitch?.isChecked == true) {
                            finish()
                        } else {
                            val social = findViewById<LinearLayout>(R.id.social_menu)
                            widgetNotice = IconifiedSnackbar(
                                this@CoverPreferences, social
                            ).buildTickerBar(
                                getString(R.string.cover_switch_warning), Snackbar.LENGTH_INDEFINITE)
                            social.postDelayed({
                                widgetNotice?.show()
                            }, 250)
                        }
                    }
                    else -> {
                        widgetNotice?.dismiss()
                        finish()
                    }
                }
            }
        })

        try {
            ScaledContext(this).cover().run {
                startForegroundService(Intent(this, OnBroadcastService::class.java))
            }
        } catch (_: IllegalArgumentException) {
            Toast.makeText(
                this@CoverPreferences, R.string.display_unavailable, Toast.LENGTH_SHORT
            ).show()
        }
        if (!prefs.getBoolean(Preferences.prefWarned, false)) {
            wikiDrawer.openDrawer(GravityCompat.START)
            with(prefs.edit()) {
                putBoolean(Preferences.prefWarned, true)
                apply()
            }
        }

        requestWallpaper.launch(
            if (Version.isTiramisu) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        )

        updateManager = UpdateManager(this@CoverPreferences)
        updateManager?.setUpdateListener(object: UpdateManager.UpdateListener {
            override fun onUpdateFound() {
                setAnimatedUpdateNotice()
            }
        })
    }

    private fun File.writeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int) {
        outputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.flush()
        }
    }
}
