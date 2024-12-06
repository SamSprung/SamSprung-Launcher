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

package com.eightbit.pm

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
import androidx.appcompat.app.AppCompatActivity
import com.eightbit.os.Version
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.settings.Preferences
import java.util.*

class PackageRetriever(val context: Context) {

    private var prefs: SharedPreferences = context.getSharedPreferences(
        Preferences.prefsValue, AppCompatActivity.MODE_PRIVATE
    )

    fun getPackageList() : MutableList<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages: MutableList<ResolveInfo> = if (Version.isTiramisu)
            context.packageManager.queryIntentActivities(mainIntent, PackageManager
                .ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()))
        else
            context.packageManager.queryIntentActivities(mainIntent, PackageManager.GET_RESOLVED_FILTER)
        packages.removeIf { item ->
            (null != item.filter && item.filter.hasCategory(Intent.CATEGORY_HOME))
                    || item.activityInfo.packageName.startsWith(BuildConfig.APPLICATION_ID)
        }
        Collections.sort(packages, ResolveInfo.DisplayNameComparator(context.packageManager))
        return packages
    }

    fun getHiddenPackages() : HashSet<String> {
        val unlisted: HashSet<String> = hashSetOf()
        unlisted.addAll(prefs.getStringSet(Preferences.prefHidden, setOf<String>()) as Set<String>)
        return unlisted
    }

    private fun getUsagePackageList() : MutableList<ResolveInfo> {
        val packages = getPackageList()
        val recent: HashSet<ResolveInfo> = hashSetOf()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (60 * 60 * 1000)
        with (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager) {
            val usageEvents: UsageEvents = queryEvents(startTime, endTime)
            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)
                if (UsageEvents.Event.ACTIVITY_RESUMED == event.eventType ||
                    UsageEvents.Event.SHORTCUT_INVOCATION == event.eventType
                ) {
                    val iterator: MutableIterator<ResolveInfo> = packages.iterator()
                    while (iterator.hasNext()) {
                        val info = iterator.next()
                        if (context.packageName != event.packageName
                            && event.packageName == info.activityInfo.packageName
                        ) {
                            iterator.remove()
                            recent.add(info)
                        }
                    }
                }
            }
        }
        packages.addAll(0, recent)
        return packages
    }

    fun getFilteredPackageList() : MutableList<ResolveInfo> {
        val packages = if (hasUsageStatistics()) getUsagePackageList() else getPackageList()
        packages.removeIf { getHiddenPackages().contains(it.activityInfo.packageName) }
        return packages
    }

    private fun hasUsageStatistics() : Boolean {
        try {
            with (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager) {
                unsafeCheckOp(
                    "android:get_usage_stats", Process.myUid(), context.packageName
                ).run { if (this == AppOpsManager.MODE_ALLOWED) return true }
            }
        } catch (_: SecurityException) { }
        return false
    }
}
