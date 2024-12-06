package com.eightbit.samsprung.drawer

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import java.lang.ref.SoftReference

class OrientationManager(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val orientationManager = LinearLayout(context)

    private val orientationLayout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSPARENT
    )

    companion object {
        private var orientationView: SoftReference<LinearLayout>? = null
    }

    fun addOrientationLayout(orientation: Int) {
        orientationLayout.screenOrientation = orientation
        windowManager.addView(orientationManager, orientationLayout)
        orientationManager.visibility = View.VISIBLE
        orientationView = SoftReference(orientationManager)
    }

    fun removeOrientationLayout() {
        try {
            orientationView?.let {
                orientationLayout.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                windowManager.updateViewLayout(it.get(), orientationLayout)
                try {
                    windowManager.removeViewImmediate(it.get())
                } catch (rvi: Exception) {
                    try { windowManager.removeView(it.get()) } catch (_: Exception) { }
                }
            } ?: windowManager.run {
                orientationLayout.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                updateViewLayout(orientationManager, orientationLayout)
                try {
                    removeViewImmediate(orientationManager)
                } catch (rvi: Exception) {
                    try {
                        removeView(orientationManager)
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }
}