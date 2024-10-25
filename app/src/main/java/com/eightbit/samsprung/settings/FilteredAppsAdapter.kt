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

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import com.eightbit.samsprung.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.PopupTextProvider

class FilteredAppsAdapter(
    private val pacMan: PackageManager,
    private var packages: MutableList<ResolveInfo>,
    private var hide: HashSet<String>,
    private val prefs: SharedPreferences
) : RecyclerView.Adapter<FilteredAppsAdapter.HideViewHolder>(), PopupTextProvider {

    fun setPackages(packages: MutableList<ResolveInfo>, hide: HashSet<String>) {
        this.packages = packages
        this.hide = hide
    }

    override fun getItemCount(): Int {
        return packages.size
    }

    override fun getItemId(i: Int): Long {
        return packages[i].labelRes.toLong()
    }

    private fun getItem(i: Int): ResolveInfo {
        return packages[i]
    }

    override fun getItemViewType(position: Int): Int {
        return position
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HideViewHolder {
        return SimpleViewHolder(parent, pacMan, prefs, hide)
    }

    override fun getPopupText(view: View, position: Int) : CharSequence {
        if (position >= packages.size) return "?"
        val item = packages[position]
        val label: CharSequence? = try {
            item.loadLabel(pacMan)
        } catch (ex: Exception) {
            try {
                item.nonLocalizedLabel
            } catch (ignored: Exception) {
                null
            }
        }
        return if (label.isNullOrEmpty()) "?" else label[0].uppercase()
    }

    override fun onBindViewHolder(holder: HideViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    abstract class HideViewHolder(
        itemView: View,
        private val packageManager: PackageManager,
        private val prefs: SharedPreferences,
        private val hide: HashSet<String>
    ) : RecyclerView.ViewHolder(itemView) {
        private lateinit var application: ResolveInfo

        fun bind(appInfo: ResolveInfo) {
            this.application = appInfo
            val detailView = itemView.findViewById<LinearLayout>(R.id.hiddenItemContainer)

            CoroutineScope(Dispatchers.IO).launch {
                val label: CharSequence? = try {
                    application.loadLabel(packageManager)
                } catch (ex: Exception) {
                    try {
                        application.nonLocalizedLabel
                    } catch (ignored: Exception) {
                        null
                    }
                }
                val appName = if (label.isNullOrEmpty()) "?" else label
                val icon = application.loadIcon(packageManager)

                withContext(Dispatchers.Main) {
                    detailView.findViewById<AppCompatImageView>(
                        R.id.hiddenItemImage).setImageDrawable(icon)
                    detailView.findViewById<TextView>(R.id.hiddenItemText).text = appName
                }
            }

            @SuppressLint("UseSwitchCompatOrMaterialCode")
            val hideSwitch = detailView.findViewById<Switch>(R.id.hiddenItemSwitch)
            hideSwitch.isChecked = !hide.contains(application.activityInfo.packageName)

            hideSwitch.setOnClickListener {
                val packageName = application.activityInfo.packageName
                if (hide.contains(packageName)) {
                    hide.remove(packageName)
                    with(prefs.edit()) {
                        putStringSet(Preferences.prefHidden, hide)
                        apply()
                    }
                } else {
                    hide.add(packageName)
                    with(prefs.edit()) {
                        putStringSet(Preferences.prefHidden, hide)
                        apply()
                    }
                }
            }
        }
    }

    internal class SimpleViewHolder(
        parent: ViewGroup,
        packageManager: PackageManager,
        prefs: SharedPreferences,
        hide: HashSet<String>
    ) : HideViewHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.filtered_apps_item, parent, false
        ), packageManager, prefs, hide
    )
}
