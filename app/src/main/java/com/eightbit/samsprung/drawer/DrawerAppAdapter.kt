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

package com.eightbit.samsprung.drawer

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import com.eightbit.samsprung.R
import com.eightbit.samsprung.settings.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DrawerAppAdapter(
    private var packages: MutableList<ResolveInfo>,
    private val listener: OnAppClickListener,
    private val packageManager: PackageManager,
    private val prefs: SharedPreferences
) : RecyclerView.Adapter<DrawerAppAdapter.AppViewHolder>() {
    private var filter: PackageFilter? = null
    private var filteredData: MutableList<ResolveInfo>

    fun setPackages(packages: MutableList<ResolveInfo>) {
        this.packages = packages
        filteredData = packages
    }

    override fun getItemCount(): Int {
        return filteredData.size
    }

    override fun getItemId(i: Int): Long {
        return filteredData[i].labelRes.toLong()
    }

    private fun getItem(i: Int): ResolveInfo {
        return filteredData[i]
    }

    init {
        filteredData = packages
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        return if (prefs.getBoolean(Preferences.prefLayout, true))
            SimpleGridHolder(parent, listener, packageManager, prefs).apply {
                itemView.setOnClickListener {
                    listener?.onAppClicked(resolveInfo, bindingAdapterPosition)
                }
                iconView.setOnClickListener {
                    listener?.onAppClicked(resolveInfo, bindingAdapterPosition)
                }
            }
        else return SimpleViewHolder(parent, listener, packageManager, prefs).apply {
            itemView.setOnClickListener {
                listener?.onAppClicked(resolveInfo, bindingAdapterPosition)
            }
            iconView.setOnClickListener {
                listener?.onAppClicked(resolveInfo, bindingAdapterPosition)
            }
        }
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(holder.bindingAdapterPosition))
    }

    fun setQuery(query: String) {
        getFilter()?.filter(query)
    }

    private fun getFilter(): PackageFilter? {
        filter = filter ?: PackageFilter()
        return filter
    }

    inner class PackageFilter : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString() ?: ""
            val filterResults = FilterResults()
            val queryText = query.trim { it <= ' ' }.lowercase()
            if (queryText.isBlank()) {
                filterResults.count = packages.size
                filterResults.values = packages
                return filterResults
            }
            val tempList: MutableList<ResolveInfo> = mutableListOf()
            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                for (app in packages) {
                    if (app.loadLabel(packageManager).contains(queryText, ignoreCase = true))
                        tempList.add(app)
                }
            }
            filterResults.count = tempList.size
            filterResults.values = tempList
            return filterResults
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun publishResults(charSequence: CharSequence, filterResults: FilterResults) {
            if (filteredData === filterResults.values) return
            val results: MutableList<ResolveInfo> = mutableListOf()
            filterResults.values?.let { results.addAll(it as Collection<ResolveInfo>) }
            filteredData = results
            notifyDataSetChanged()
        }
    }

    abstract class AppViewHolder(
        itemView: View, val listener: OnAppClickListener?,
        private val packageManager: PackageManager,
        private val prefs: SharedPreferences
    ) : RecyclerView.ViewHolder(itemView) {
        lateinit var resolveInfo: ResolveInfo
        val iconView: AppCompatImageView = itemView.findViewById(R.id.widgetItemImage)
        private var textView: TextView? = null
        fun bind(resolveInfo: ResolveInfo) {
            this.resolveInfo = resolveInfo
            CoroutineScope(Dispatchers.IO).launch {
                val icon = resolveInfo.loadIcon(packageManager)
                withContext(Dispatchers.Main) {
                    icon?.let { iconView.setImageDrawable(it) }
                }
                if (!prefs.getBoolean(Preferences.prefLayout, true)) {
                    textView = itemView.findViewById(R.id.widgetItemText)
                    val label: CharSequence? = try {
                        resolveInfo.loadLabel(packageManager)
                    } catch (ex: Exception) {
                        try {
                            resolveInfo.nonLocalizedLabel
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val appName = if (label.isNullOrEmpty()) "?" else label
                    withContext(Dispatchers.Main) {
                        textView?.text = appName
                    }
                }
            }
        }
    }

    internal class SimpleViewHolder(
        parent: ViewGroup,
        listener: OnAppClickListener?,
        packageManager: PackageManager,
        prefs: SharedPreferences
    ) : AppViewHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.drawer_apps_card, parent, false
        ), listener, packageManager, prefs
    )

    internal class SimpleGridHolder(
        parent: ViewGroup,
        listener: OnAppClickListener?,
        packageManager: PackageManager,
        prefs: SharedPreferences
    ) : AppViewHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.drawer_apps_icon, parent, false
        ), listener, packageManager, prefs
    )

    interface OnAppClickListener {
        fun onAppClicked(resolveInfo: ResolveInfo, position: Int)
    }
}
