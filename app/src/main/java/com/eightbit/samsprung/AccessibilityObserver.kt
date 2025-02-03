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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.File

class AccessibilityObserver : AccessibilityService() {

    companion object {
        private lateinit var observerInstance: AccessibilityObserver
        private var isConnected: Boolean = false
        fun getInstance() : AccessibilityObserver? {
            return if (isConnected) observerInstance else null
        }
        fun hasEnabledService(context: Context): Boolean {
            if (BuildConfig.GOOGLE_PLAY) return false
            val serviceString = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return serviceString?.contains(context.packageName
                    + File.separator + AccessibilityObserver::class.java.name) == true
        }
        fun performBackAction() {
            getInstance()?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun getWindowManager() : WindowManager {
            return getInstance()?.getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    init {
        observerInstance = this
    }

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo()
        info.packageNames = arrayOf(packageName)
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        serviceInfo = info
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { }

    override fun onInterrupt() {
        if (null == getInstance()) observerInstance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        return super.onUnbind(intent)
    }
}
