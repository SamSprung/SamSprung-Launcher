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
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.SamSprung
import java.util.*

class PackageRetriever(val context: Context) {

    private var prefs: SharedPreferences = context.getSharedPreferences(
        SamSprung.prefsValue, AppCompatActivity.MODE_PRIVATE
    )

    fun getPackageList() : MutableList<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val packages: MutableList<ResolveInfo> = context.packageManager.queryIntentActivities(
            mainIntent, PackageManager.GET_RESOLVED_FILTER
        )
        packages.removeIf { item ->
            (null != item.filter && item.filter.hasCategory(Intent.CATEGORY_HOME))
                    || item.activityInfo.packageName.startsWith(BuildConfig.APPLICATION_ID)
        }
        Collections.sort(packages, ResolveInfo.DisplayNameComparator(context.packageManager))
        return packages
    }

    fun getHiddenPackages() : HashSet<String> {
        val unlisted: HashSet<String> = HashSet()
        val hide: Set<String> = prefs.getStringSet(
            SamSprung.prefHidden, setOf<String>()) as Set<String>
        unlisted.addAll(hide)
        return unlisted
    }

    private fun getRecentPackageList(
        statsManager: UsageStatsManager, packages: MutableList<ResolveInfo>
    ) : HashSet<ResolveInfo> {
        val recent: HashSet<ResolveInfo> = HashSet()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (60 * 60 * 1000)
        val usageEvents: UsageEvents = statsManager.queryEvents(startTime, endTime)
        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (UsageEvents.Event.ACTIVITY_RESUMED == event.eventType ||
                UsageEvents.Event.SHORTCUT_INVOCATION == event.eventType) {
                val iterator: MutableIterator<ResolveInfo> = packages.iterator()
                while (iterator.hasNext()) {
                    val info = iterator.next()
                    if (context.packageName != event.packageName
                        && event.packageName == info.activityInfo.packageName) {
                        iterator.remove()
                        recent.add(info)
                    }
                }
            }
        }
        return recent
    }

    fun getFilteredPackageList() : MutableList<ResolveInfo> {
        val packages = getPackageList()
        if (hasUsageStatistics()) {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).run {
                packages.addAll(0, getRecentPackageList(this, packages))
            }
        }
        packages.removeIf {
            prefs.getStringSet(SamSprung.prefHidden, HashSet())
                ?.contains(it.activityInfo.packageName) == true
        }
        return packages
    }

    private fun hasUsageStatistics() : Boolean {
        try {
            (context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).run {
                unsafeCheckOp(
                    "android:get_usage_stats", Process.myUid(), context.packageName
                ).run {
                    if (this == AppOpsManager.MODE_ALLOWED) return true
                }
            }
        } catch (ignored: SecurityException) { }
        return false
    }
}
