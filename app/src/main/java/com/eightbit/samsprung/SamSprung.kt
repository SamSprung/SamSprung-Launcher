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

import android.app.Application
import android.app.KeyguardManager
import androidx.appcompat.app.AppCompatDelegate
import com.eightbit.io.Debug
import com.eightbit.samsprung.drawer.OrientationManager
import com.eightbit.samsprung.settings.Preferences
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

const val organization = "SamSprung"

class SamSprung : Application() {

    fun setThemePreference() {
        when (getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE).getInt(Preferences.prefThemes, 0)) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    companion object {
        const val provider: String = "${BuildConfig.APPLICATION_ID}.provider"
        const val updating: String = "${BuildConfig.APPLICATION_ID}.UPDATING"
        const val services: String = "${BuildConfig.APPLICATION_ID}.SERVICES"
        const val launcher: String = "${BuildConfig.APPLICATION_ID}.LAUNCHER"

        const val request_code = 8675309
        const val notification = request_code.toString()

        var hasSubscription = false

        private const val commitHash = "#${BuildConfig.COMMIT}"
        val versionLabel = "$organization ${BuildConfig.VERSION_NAME} (${
            if (BuildConfig.GOOGLE_PLAY) "Play" else "GitHub"
        } ${
            if (BuildConfig.BUILD_TYPE == "release") "Release" else "Debug"
        }) $commitHash"
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        Thread.setDefaultUncaughtExceptionHandler { _: Thread?, error: Throwable ->
            val exception = StringWriter()
            error.printStackTrace(PrintWriter(exception))
            try {
                OrientationManager(this).removeOrientationLayout()
            } catch (_: Exception) { }
            try {
                Debug(this).processException(
                    with (getSystemService(KEYGUARD_SERVICE) as KeyguardManager) { isDeviceSecure },
                    exception.toString()
                )
            } catch (_: Exception) { }
            // Unrecoverable error encountered
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(-1)
        }

        setThemePreference()
    }
}
