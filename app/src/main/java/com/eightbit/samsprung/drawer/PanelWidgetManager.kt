package com.eightbit.samsprung.drawer

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.*
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import androidx.viewpager2.widget.ViewPager2
import com.eightbit.content.ScaledContext
import com.eightbit.os.Version
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.drawer.panels.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class PanelWidgetManager(
    private var overlay: SamSprungOverlay,
    private var mAppWidgetManager: AppWidgetManager,
    private var appWidgetHost: AppWidgetHost,
    private var pagerAdapter: CoverStateAdapter,
) {
    private var displayMetrics: IntArray = ScaledContext(overlay).getDisplayParams()

    @SuppressLint("InflateParams")
    fun completeAddAppWidget(appWidgetId: Int, viewPager: ViewPager2) {
        val appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId)

        // Build Launcher-specific widget info and save to database
        val launcherInfo = PanelWidgetInfo(appWidgetId)

        var spanX = appWidgetInfo.minWidth
        var spanY = appWidgetInfo.minHeight
        if (Version.isSnowCone) {
            spanX = Integer.max(appWidgetInfo.minWidth, appWidgetInfo.maxResizeWidth)
            spanY = Integer.max(appWidgetInfo.minHeight, appWidgetInfo.maxResizeHeight)
        }
        if (spanX > displayMetrics[0]) spanX = displayMetrics[0]
        if (spanY > displayMetrics[1]) spanY = displayMetrics[1]
        val spans = intArrayOf(spanX, spanY)

        launcherInfo.spanX = spans[0]
        launcherInfo.spanY = spans[1]

        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            WidgetModel.addItemToDatabase(
                overlay.applicationContext, launcherInfo,
                WidgetSettings.Favorites.CONTAINER_DESKTOP,
                spans[0], spans[1], false
            )
        }
        overlay.model.addDesktopAppWidget(launcherInfo)

        launcherInfo.hostView = appWidgetHost.createView(ScaledContext(
            ScaledContext(overlay.applicationContext).internal(1.5f)
        ).cover(), appWidgetId, appWidgetInfo)
        launcherInfo.hostView!!.setAppWidget(appWidgetId, appWidgetInfo)
        launcherInfo.hostView!!.tag = launcherInfo

        val id = pagerAdapter.addFragment()
        val fragment = pagerAdapter.getFragment(id)
        val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            displayMetrics[0], displayMetrics[1]
        )
        params.gravity = Gravity.CENTER
        fragment.setListener(object: PanelViewFragment.ViewCreatedListener {
            override fun onViewCreated(view: View) {
                val layout = view as LinearLayout
                try {
                    layout.children.forEach { child ->
                        if (child is AppWidgetHostView) layout.removeView(child)
                    }
                } catch (_: Exception) { }
                layout.addView(launcherInfo.hostView, params)
            }
        })
        viewPager.setCurrentItem(id, true)
    }

    private fun addAppWidget(appWidgetId: Int, viewPager: ViewPager2) {
        val appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
        if (null != appWidget.configure) {
            overlay.setKeyguardListener(object: SamSprungOverlay.KeyguardListener {
                override fun onKeyguardCheck(unlocked: Boolean) {
                    if (!unlocked) return
                    try {
                        overlay.requestCreateAppWidget.launch(
                            Intent(ACTION_APPWIDGET_CONFIGURE).apply {
                                component = appWidget.configure
                                putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                        )
                    } catch (ex: Exception) {
                        Toast.makeText(overlay, R.string.widget_error, Toast.LENGTH_LONG).show()
                        ex.printStackTrace()
                    }
                }
            })
        } else {
            completeAddAppWidget(appWidgetId, viewPager)
        }
    }

    @SuppressLint("InflateParams")
    fun showAddDialog(viewPager: ViewPager2) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val mWidgetPreviewLoader = WidgetPreviews(overlay)

        val view: View = overlay.layoutInflater.inflate(R.layout.panel_picker_view, null)
        val previews = view.findViewById<LinearLayout>(R.id.previews_layout)
        val widgetDialog = AlertDialog.Builder(
            ContextThemeWrapper(overlay, R.style.Theme_Overlay_NoActionBar)
        ).apply {
            setOnCancelListener {
                previews.children.forEach {
                    val imageView = it as AppCompatImageView
                    mWidgetPreviewLoader.recycleBitmap(it.tag, imageView.drawable.toBitmap())
                }
                previews.removeAllViewsInLayout()
            }
            setOnDismissListener {
                previews.children.forEach {
                    val imageView = it as AppCompatImageView
                    mWidgetPreviewLoader.recycleBitmap(it.tag, imageView.drawable.toBitmap())
                }
                previews.removeAllViewsInLayout()
            }
        }.setView(view).create()
        view.findViewById<LinearLayout>(R.id.widget_cancel).setOnClickListener {
            widgetDialog.dismiss()
        }
        mAppWidgetManager.installedProviders.forEach { info ->
            val previewSizeBeforeScale = IntArray(1)
            val preview = mWidgetPreviewLoader.generateWidgetPreview(
                info, overlay.window.decorView.width, overlay.window.decorView.height,
                null, previewSizeBeforeScale
            )
            val imageView = overlay.layoutInflater.inflate(R.layout.widget_preview, null) as AppCompatImageView
            previews.addView(imageView.apply {
                adjustViewBounds = true
                setImageBitmap(preview)
                setOnClickListener {
                    if (mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
                        addAppWidget(appWidgetId, viewPager)
                    } else {
                        this@PanelWidgetManager.overlay.requestCreateAppWidget.launch(
                            Intent(ACTION_APPWIDGET_BIND).apply {
                                putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                                putExtra(EXTRA_APPWIDGET_PROVIDER, info.provider)
                                putExtra(EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                            }
                        )
                    }
                    widgetDialog.dismiss()
                }
                tag = info
            })
        }
        widgetDialog.show()
        widgetDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    @SuppressLint("InflateParams")
    fun bindAppWidgets(
        binder: DesktopBinder,
        appWidgets: LinkedList<PanelWidgetInfo>
    ) {
        if (appWidgets.isNotEmpty()) {
            val item = appWidgets.removeFirst()
            val appWidgetId = item.appWidgetId
            val appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId)
            item.hostView = appWidgetHost.createView(ScaledContext(
                ScaledContext(overlay.applicationContext).internal(1.5f)
            ).cover(), appWidgetId, appWidgetInfo).apply {
                setAppWidget(appWidgetId, appWidgetInfo)
                tag = item
            }

            val id = pagerAdapter.addFragment()
            val fragment = pagerAdapter.getFragment(id)
            val params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(
                displayMetrics[0], displayMetrics[1]
            )
            params.gravity = Gravity.CENTER
            fragment.setListener(object: PanelViewFragment.ViewCreatedListener {
                override fun onViewCreated(view: View) {
                    try {
                        val layout = (view as LinearLayout)
                        layout.children.forEach {
                            if (it is AppWidgetHostView) layout.removeView(it)
                        }
                    } catch (_: Exception) { }
                    val layout = view as LinearLayout
                    layout.addView(item.hostView, params)
                }
            })
        }
        if (appWidgets.isNotEmpty())
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_APPWIDGETS).sendToTarget()
    }
}