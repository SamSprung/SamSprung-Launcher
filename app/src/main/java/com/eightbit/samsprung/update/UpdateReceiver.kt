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

package com.eightbit.samsprung.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Parcelable
import android.widget.Toast
import com.eightbit.os.Version
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.OnBroadcastService
import com.eightbit.samsprung.SamSprung

class UpdateReceiver : BroadcastReceiver() {

    private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
        Version.isTiramisu ->
            getParcelableExtra(key, T::class.java)
        else -> @Suppress("deprecation") getParcelableExtra(key) as? T
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        intent.setPackage(context.packageName)
        intent.flags = 0
        intent.data = null
        when {
            Intent.ACTION_BOOT_COMPLETED == action
                    || Intent.ACTION_LOCKED_BOOT_COMPLETED == action
                    || Intent.ACTION_REBOOT == action -> {
                startBroadcastService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED == action -> { startBroadcastService(context) }
            !BuildConfig.GOOGLE_PLAY && SamSprung.updating == action -> {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        intent.parcelable<Intent>(Intent.EXTRA_INTENT)?.let {
                            startLauncherActivity(
                                context, Intent.parseUri(
                                    it.toUri(0), Intent.URI_ALLOW_UNSAFE or Intent.URI_INTENT_SCHEME
                                )
                            )
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { }
                    else -> {
                        val error = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        if (error?.contains("Session was abandoned") != true)
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startBroadcastService(context: Context) {
        context.startForegroundService(Intent(context, OnBroadcastService::class.java))
    }

    private fun startLauncherActivity(context: Context, intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
