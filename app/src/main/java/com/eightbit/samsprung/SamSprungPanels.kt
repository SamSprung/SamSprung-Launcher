/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.eightbit.samsprung

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Dialog
import android.app.WallpaperManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.os.MessageQueue.IdleHandler
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.get
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.panels.*
import com.eightbit.samsprung.panels.WidgetSettings.Favorites
import com.eightbitlab.blurview.BlurView
import com.eightbitlab.blurview.RenderScriptBlur
import java.lang.Integer.max
import java.lang.ref.SoftReference
import java.util.*
import java.util.concurrent.Executors


/**
 * Default launcher application.
 */
@SuppressLint("ClickableViewAccessibility")
class SamSprungPanels : AppCompatActivity() {
    private val mObserver: ContentObserver = FavoritesChangeObserver()
    private var mAppWidgetManager: AppWidgetManager? = null
    var appWidgetHost: CoverWidgetHost? = null
        private set
    private lateinit var workspace: LinearLayout

    private var mDestroyed = false
    private var mIsNewIntent = false
    private var mBinder: DesktopBinder? = null

    private var widgetContext: Context? = null
    private lateinit var offReceiver: BroadcastReceiver

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)

        widgetContext = ScaledContext.widget(applicationContext)

        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ScaledContext.widget(this).setTheme(R.style.Theme_AppCompat)

        mAppWidgetManager = AppWidgetManager.getInstance(widgetContext)
        appWidgetHost = CoverWidgetHost(
            widgetContext,
            APPWIDGET_HOST_ID
        )
        appWidgetHost!!.startListening()

        setContentView(R.layout.panel_main_layout)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            findViewById<View>(R.id.coordinator).background =
                WallpaperManager.getInstance(this).drawable
        }

        (findViewById<View>(R.id.blurContainer) as BlurView).setupWith(
            window.decorView.findViewById(R.id.coordinator)
        )
            .setFrameClearDrawable(window.decorView.background)
            .setBlurRadius(1f)
            .setBlurAutoUpdate(true)
            .setHasFixedTransformationMatrix(true)
            .setBlurAlgorithm(RenderScriptBlur(this))

        workspace = findViewById<LinearLayout>(R.id.workspace)
        val menuHandle = findViewById<AppCompatImageView>(R.id.menu_zone)
        menuHandle.setOnClickListener {
            tactileFeedback()
            showAddDialog()
        }
        menuHandle.setOnLongClickListener {
            finish()
            startForegroundService(
                Intent(
                    applicationContext,
                    OnBroadcastService::class.java
                ).setAction(SamSprung.launcher)
            )
            return@setOnLongClickListener true
        }

        val deleteHandle = findViewById<AppCompatImageView>(R.id.delete_zone)
        deleteHandle.setOnClickListener {
            tactileFeedback()
            val layout = getVisibleItem(workspace)
            if (null != layout) {
                val widget = (layout as LinearLayout)[0]
                WidgetModel.deleteItemFromDatabase(
                    widgetContext, widget.tag as CoverWidgetInfo
                )
                workspace.removeView(layout)
            }
        }
        deleteHandle.setOnLongClickListener {
            finish()
            startForegroundService(
                Intent(
                    applicationContext,
                    OnBroadcastService::class.java
                ).setAction(SamSprung.services)
            )
            return@setOnLongClickListener true
        }

        contentResolver.registerContentObserver(Favorites.CONTENT_URI, true, mObserver)
        startLoaders()

        offReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    finish()
                    startForegroundService(
                        Intent(
                            applicationContext,
                            OnBroadcastService::class.java
                        ).setAction(SamSprung.services)
                    )
                }
            }
        }
        IntentFilter(Intent.ACTION_SCREEN_OFF).also {
            registerReceiver(offReceiver, it)
        }
    }

    private fun startLoaders() {
        model.loadUserItems(true, this)
    }

    override fun onResume() {
        super.onResume()
        startLoaders()
        mIsNewIntent = false
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? {
        if (mBinder != null) {
            mBinder!!.mTerminate = true
        }
        // return lastNonConfigurationInstance
        return null
    }

    private fun completeAddAppWidget(appWidgetId: Int) {
        val appWidgetInfo = mAppWidgetManager!!.getAppWidgetInfo(appWidgetId)

        // Build Launcher-specific widget info and save to database
        val launcherInfo = CoverWidgetInfo(appWidgetId)

        var spanX = appWidgetInfo.minWidth
        var spanY = appWidgetInfo.minHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            spanX = max(appWidgetInfo.minWidth, appWidgetInfo.maxResizeWidth)
            spanY = max(appWidgetInfo.minHeight, appWidgetInfo.maxResizeHeight)
        }
        if (spanX > window.decorView.width)
            spanX = window.decorView.width
        if (spanY > window.decorView.height)
            spanY = window.decorView.height
        val spans = intArrayOf(spanX, spanY)

        launcherInfo.spanX = spans[0]
        launcherInfo.spanY = spans[1]

        Executors.newSingleThreadExecutor().execute {
            WidgetModel.addItemToDatabase(
                widgetContext, launcherInfo,
                Favorites.CONTAINER_DESKTOP,
                0, 0, 0, false
            )
        }
        model.addDesktopAppWidget(launcherInfo)

        launcherInfo.hostView = appWidgetHost!!.createView(
            widgetContext, appWidgetId, appWidgetInfo)
        launcherInfo.hostView!!.setAppWidget(appWidgetId, appWidgetInfo)
        launcherInfo.hostView!!.tag = launcherInfo

        val wrapper: LinearLayout = layoutInflater.inflate(
            R.layout.workspace_screen, null) as LinearLayout
        val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            window.decorView.width, window.decorView.height
        )
        wrapper.addView(launcherInfo.hostView)
        workspace.addView(wrapper, params)
    }

    private fun tactileFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator.vibrate(VibrationEffect.createOneShot(
                    30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                .vibrate(VibrationEffect.createOneShot(
                    30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun getVisibleItem(listView: LinearLayout): LinearLayout? {
        val buffer = 30
        for(item in listView.children) {
            if (!item.isShown) {
                continue
            }
            val actualPosition = Rect()
            item.getGlobalVisibleRect(actualPosition)
            val screen = Rect(
                buffer.toPx.toInt(), buffer.toPx.toInt(),
                window.decorView.width - buffer.toPx.toInt(),
                window.decorView.height - buffer.toPx.toInt()
            )
            if (actualPosition.intersect(screen)) {
               return item as LinearLayout
            }
        }
        return null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    public override fun onDestroy() {
        mDestroyed = true
        super.onDestroy()
        try {
            appWidgetHost!!.stopListening()
        } catch (ex: NullPointerException) {
            Log.w(LogTag, "problem while stopping AppWidgetHost during Launcher destruction", ex)
        }
        model.unbind()
        model.abortLoaders()
        contentResolver.unregisterContentObserver(mObserver)
        try {
            if (this::offReceiver.isInitialized)
                unregisterReceiver(offReceiver)
        } catch (ignored: Exception) { }
    }

    private val requestCreateAppWidget = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        val appWidgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == RESULT_CANCELED) {
            if (appWidgetId != -1) {
                appWidgetHost!!.deleteAppWidgetId(appWidgetId)
            }
        } else {
            completeAddAppWidget(appWidgetId)
        }
    }

    private fun addAppWidget(appWidgetId: Int) {
        val appWidget = mAppWidgetManager!!.getAppWidgetInfo(appWidgetId) ?: return
        if (null != appWidget.configure) {
            try {
                appWidgetHost?.startAppWidgetConfigureActivityForResult(
                    this, appWidgetId, 0, requestCreateAppWidgetHost,
                    ActivityOptions.makeBasic().setLaunchDisplayId(1).toBundle()
                )
            } catch (ignored: ActivityNotFoundException) {
                // Launch over to configure widget, if needed
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                intent.component = appWidget.configure
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                requestCreateAppWidget.launch(intent)
            }
        } else {
            completeAddAppWidget(appWidgetId)
        }
    }

    /**
     * When the notification that favorites have changed is received, requests
     * a favorites list refresh.
     */
    private fun onFavoritesChanged() {
        model.loadUserItems(false, this)
    }

    fun onDesktopItemsLoaded(
        shortcuts: ArrayList<WidgetInfo?>?,
        appWidgets: ArrayList<CoverWidgetInfo>?
    ) {
        if (mDestroyed) {
            if (WidgetModel.DEBUG_LOADERS) {
                Log.d(
                    WidgetModel.LOG_TAG,
                    "  ------> destroyed, ignoring desktop items"
                )
            }
            return
        }
        // Flag any old binder to terminate early
        if (mBinder != null) {
            mBinder!!.mTerminate = true
        }
        mBinder = DesktopBinder(this, shortcuts, appWidgets)
        mBinder!!.startBindingItems()
    }

    private fun bindItems(
        binder: DesktopBinder,
        shortcuts: ArrayList<WidgetInfo?>?, start: Int, count: Int
    ) {
        val end = (start + DesktopBinder.ITEMS_COUNT).coerceAtMost(count)
        var i = start
        while (i < end) {
            shortcuts!![i]
            i++
        }
        workspace.requestLayout()
        if (end >= count) {
            binder.startBindingAppWidgetsWhenIdle()
        } else {
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_ITEMS, i, count).sendToTarget()
        }
    }

    private fun bindAppWidgets(
        binder: DesktopBinder,
        appWidgets: LinkedList<CoverWidgetInfo>
    ) {
        if (!appWidgets.isEmpty()) {
            val item = appWidgets.removeFirst()
            val appWidgetId = item.appWidgetId
            val appWidgetInfo = mAppWidgetManager!!.getAppWidgetInfo(appWidgetId)
            item.hostView = appWidgetHost!!.createView(
                widgetContext, appWidgetId, appWidgetInfo)
            if (LOGD) {
                Log.d(
                    LogTag, String.format(
                        "about to setAppWidget for id=%d, info=%s",
                        appWidgetId, appWidgetInfo
                    )
                )
            }
            item.hostView!!.setAppWidget(appWidgetId, appWidgetInfo)
            item.hostView!!.tag = item

            val wrapper: LinearLayout = layoutInflater.inflate(
                R.layout.workspace_screen, null) as LinearLayout
            val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
                window.decorView.width, window.decorView.height
            )
            wrapper.addView(item.hostView)
            workspace.addView(wrapper, params)
        }
        if (!appWidgets.isEmpty()) {
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_APPWIDGETS).sendToTarget()
        }
    }

    private fun showAddDialog() {
        val appWidgetId = appWidgetHost!!.allocateAppWidgetId()

        val view: View = layoutInflater.inflate(R.layout.panel_picker_view, null)
        val dialog = AlertDialog.Builder(
            ContextThemeWrapper(this, R.style.DialogTheme_NoActionBar)
        )
        val previews = view.findViewById<LinearLayout>(R.id.previews_layout)
        dialog.setOnCancelListener {
            previews.removeAllViews()
        }
        val widgetDialog: Dialog = dialog.setView(view).show()
        val mWidgetPreviewLoader = WidgetPreviewLoader(this)
        val infoList: List<AppWidgetProviderInfo> = mAppWidgetManager!!.installedProviders
        for (info: AppWidgetProviderInfo in infoList) {
            var spanX: Int = info.minWidth
            var spanY: Int = info.minHeight
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                spanX = max(info.minWidth, info.maxResizeWidth)
                spanY = max(info.minHeight, info.maxResizeHeight)
            }
            val maxWidth: Int = window.decorView.width
            val maxHeight: Int = window.decorView.height
            val previewSizeBeforeScale = IntArray(1)
            val preview = mWidgetPreviewLoader.generateWidgetPreview(
                info, spanX, spanY, maxWidth, maxHeight, null, previewSizeBeforeScale
            )
            val previewImage = layoutInflater.inflate(
                R.layout.widget_preview, null) as AppCompatImageView
            previewImage.adjustViewBounds = true
            previewImage.setImageBitmap(preview)
            previewImage.setOnClickListener {
                val success = mAppWidgetManager!!.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)
                if (success) {
                    addAppWidget(appWidgetId)
                } else {
                    val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    intent.putExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile
                    )
                    requestCreateAppWidget.launch(intent)
                }
                widgetDialog.dismiss()
            }
            previews.addView(previewImage)
        }
        widgetDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * Receives notifications whenever the user favorites have changed.
     */
    private inner class FavoritesChangeObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onFavoritesChanged()
        }
    }

    private class DesktopBinder(
        launcher: SamSprungPanels, shortcuts: ArrayList<WidgetInfo?>?,
        appWidgets: ArrayList<CoverWidgetInfo>?
    ) : Handler(Looper.getMainLooper()), IdleHandler {
        private val mShortcuts: ArrayList<WidgetInfo?>? = shortcuts
        private val mAppWidgets: LinkedList<CoverWidgetInfo>
        private val mLauncher: SoftReference<SamSprungPanels> = SoftReference(launcher)
        var mTerminate = false
        fun startBindingItems() {
            if (WidgetModel.DEBUG_LOADERS) Log.d(LogTag, "------> start binding items")
            obtainMessage(MESSAGE_BIND_ITEMS, 0, mShortcuts!!.size).sendToTarget()
        }

        fun startBindingAppWidgetsWhenIdle() {
            // Ask for notification when message queue becomes idle
            val messageQueue = Looper.myQueue()
            messageQueue.addIdleHandler(this)
        }

        override fun queueIdle(): Boolean {
            // Queue is idle, so start binding items
            startBindingAppWidgets()
            return false
        }

        fun startBindingAppWidgets() {
            obtainMessage(MESSAGE_BIND_APPWIDGETS).sendToTarget()
        }

        override fun handleMessage(msg: Message) {
            val launcher = mLauncher.get()
            if (launcher == null || mTerminate) {
                return
            }
            when (msg.what) {
                MESSAGE_BIND_ITEMS -> {
                    launcher.bindItems(this, mShortcuts, msg.arg1, msg.arg2)
                }
                MESSAGE_BIND_APPWIDGETS -> {
                    launcher.bindAppWidgets(this, mAppWidgets)
                }
            }
        }

        companion object {
            const val MESSAGE_BIND_ITEMS = 0x1
            const val MESSAGE_BIND_APPWIDGETS = 0x2

            // Number of items to bind in every pass
            const val ITEMS_COUNT = 6
        }

        init {
            // Sort widgets so active workspace is bound first
            val size = appWidgets!!.size
            mAppWidgets = LinkedList()
            for (i in 0 until size) {
                val appWidgetInfo = appWidgets[i]
                if (appWidgetInfo.screen == 0) {
                    mAppWidgets.addFirst(appWidgetInfo)
                } else {
                    mAppWidgets.addLast(appWidgetInfo)
                }
            }
            if (WidgetModel.DEBUG_LOADERS) {
                Log.d(LogTag, "------> binding " + shortcuts!!.size + " items")
                Log.d(LogTag, "------> binding " + appWidgets.size + " widgets")
            }
        }
    }

    companion object {
        val LogTag: String = SamSprungPanels::class.java.name
        const val LOGD = false

        val model = WidgetModel()

        const val APPWIDGET_HOST_ID = SamSprung.request_code
    }

    private fun getSpanForWidget(
        context: Context, component: ComponentName?, minWidth: Int,
        minHeight: Int
    ): IntArray {
        val padding: Rect = AppWidgetHostView.getDefaultPaddingForWidget(context, component, null)
        // We want to account for the extra amount of padding that we are adding to the widget
        // to ensure that it gets the full amount of space that it has requested
        val requiredWidth: Int = minWidth + padding.left + padding.right
        val requiredHeight: Int = minHeight + padding.top + padding.bottom
        return intArrayOf(requiredWidth, requiredHeight)
    }

    fun getSpanForWidget(context: Context, info: AppWidgetProviderInfo): IntArray {
        return getSpanForWidget(context, info.provider, info.minWidth, info.minHeight)
    }

    @Suppress("UNUSED")
    fun getMinSpanForWidget(context: Context, info: AppWidgetProviderInfo): IntArray {
        return getSpanForWidget(context, info.provider, info.minResizeWidth, info.minResizeHeight)
    }

    private val requestCreateAppWidgetHost = 9001

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // We have special handling for widgets
        if (requestCode == requestCreateAppWidgetHost) {
            val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (resultCode == RESULT_CANCELED) {
                if (appWidgetId != -1) {
                    appWidgetHost!!.deleteAppWidgetId(appWidgetId)
                }
            } else {
                completeAddAppWidget(appWidgetId)
            }
            return
        }
    }

    val Number.toPx get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics)
}