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

package com.eightbit.samsprung.drawer.panels

import android.content.ContentValues
import android.content.Context
import android.os.Process
import android.provider.BaseColumns
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.drawer.panels.WidgetSettings.BaseLauncherColumns
import com.eightbit.samsprung.drawer.panels.WidgetSettings.Favorites
import java.lang.ref.WeakReference

/**
 * Maintains in-memory state of the Launcher. It is expected that there should be only one
 * LauncherModel object held in a static. Also provide APIs for updating the database state
 * for the Launcher.
 */
class WidgetModel {
    private var mDesktopItemsLoaded = false
    private var mDesktopItems: ArrayList<WidgetInfo?>? = null
    private var mDesktopAppWidgets: ArrayList<PanelWidgetInfo>? = null
    private var mDesktopItemsLoader: DesktopItemsLoader? = null
    private var mDesktopLoaderThread: Thread? = null
    @Synchronized
    fun abortLoaders() {
        if (mDesktopItemsLoader?.isRunning == true) {
            mDesktopItemsLoader?.stop()
            mDesktopItemsLoaded = false
        }
    }

    private val isDesktopLoaded: Boolean
        get() = mDesktopItems != null && mDesktopAppWidgets != null && mDesktopItemsLoaded

    /**
     * Loads all of the items on the desktop, in folders, or in the dock.
     * These can be apps, shortcuts or widgets
     */
    fun loadUserItems(isLaunching: Boolean, launcher: SamSprungOverlay) {
        if (isLaunching && isDesktopLoaded) {
            // We have already loaded our data from the DB
            launcher.onDesktopItemsLoaded(mDesktopItems, mDesktopAppWidgets)
            return
        }
        if (mDesktopItemsLoader?.isRunning == true) {
            mDesktopItemsLoader?.stop()
            // Wait for the currently running thread to finish, this can take a little
            // time but it should be well below the timeout limit
            try {
                mDesktopLoaderThread?.join(APPLICATION_NOT_RESPONDING_TIMEOUT)
            } catch (e: InterruptedException) {
                // Empty
            }
        }
        mDesktopItemsLoaded = false
        mDesktopItemsLoader = DesktopItemsLoader(launcher)
        mDesktopLoaderThread = Thread(mDesktopItemsLoader, "Desktop Items Loader")
        mDesktopLoaderThread!!.start()
    }

    private inner class DesktopItemsLoader(
        launcher: SamSprungOverlay
    ) : Runnable {
        @Volatile
        private var mStopped = false

        @Volatile
        var isRunning = false
            private set
        private val mLauncher: WeakReference<SamSprungOverlay> = WeakReference(launcher)
        fun stop() {
            mStopped = true
        }

        override fun run() {
            isRunning = true
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            val launcher = mLauncher.get()
            val contentResolver = launcher!!.contentResolver
            mDesktopItems = arrayListOf()
            mDesktopAppWidgets = arrayListOf()
            val desktopItems = mDesktopItems!!
            val desktopAppWidgets = mDesktopAppWidgets!!
            contentResolver.query(
                Favorites.CONTENT_URI,
                null, null, null, null
            ).use { c ->
                val idIndex = c!!.getColumnIndexOrThrow(BaseColumns._ID)
//                val intentIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.INTENT)
//                val titleIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.TITLE)
//                val iconTypeIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON_TYPE)
//                val iconIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.ICON)
                val containerIndex = c.getColumnIndexOrThrow(Favorites.CONTAINER)
                val itemTypeIndex = c.getColumnIndexOrThrow(BaseLauncherColumns.ITEM_TYPE)
                val appWidgetIdIndex = c.getColumnIndexOrThrow(Favorites.APPWIDGET_ID)
                val spanXIndex = c.getColumnIndexOrThrow(Favorites.SPANX)
                val spanYIndex = c.getColumnIndexOrThrow(Favorites.SPANY)
//                val uriIndex = c.getColumnIndexOrThrow(Favorites.URI)
                var appWidgetInfo: PanelWidgetInfo
                var container: Int
                while (!mStopped && c.moveToNext()) {
                    try {
                        val itemType = c.getInt(itemTypeIndex)
                        if (itemType == Favorites.ITEM_TYPE_APPWIDGET) { // Read all Launcher-specific widget details
                            val appWidgetId = c.getInt(appWidgetIdIndex)
                            appWidgetInfo = PanelWidgetInfo(appWidgetId)
                            appWidgetInfo.id = c.getLong(idIndex)
                            appWidgetInfo.spanX = c.getInt(spanXIndex)
                            appWidgetInfo.spanY = c.getInt(spanYIndex)
                            container = c.getInt(containerIndex)
                            if (container != Favorites.CONTAINER_DESKTOP) {
                                continue
                            }
                            appWidgetInfo.container = c.getInt(containerIndex).toLong()
                            desktopAppWidgets.add(appWidgetInfo)
                        }
                    } catch (_: Exception) { }
                }
            }
            if (!mStopped) {
                // Create a copy of the lists in case the workspace loader is restarted
                // and the list are cleared before the UI can go through them
                if (!mStopped) {
                    launcher.runOnUiThread {
                        launcher.onDesktopItemsLoaded(
                            desktopItems,
                            desktopAppWidgets
                        )
                    }
                }
                mDesktopItemsLoaded = true
            }
            isRunning = false
        }

    }

    /**
     * Remove the callback for the cached drawables or we leak the previous
     * Home screen on orientation change.
     */
    fun unbind() {
        // Interrupt the applications loader before setting the adapter to null
        unbindAppWidgetHostViews(mDesktopAppWidgets)
    }

    /**
     * Remove any [WidgetHostView] references in our widgets.
     */
    private fun unbindAppWidgetHostViews(appWidgets: ArrayList<PanelWidgetInfo>?) {
        appWidgets?.let { it ->
            it.forEach { launcherInfo -> launcherInfo.hostView = null }
        }
    }

    /**
     * Add a widget to the desktop
     */
    fun addDesktopAppWidget(info: PanelWidgetInfo) {
        mDesktopAppWidgets!!.add(info)
    }

    /**
     * Remove a widget from the desktop
     */
    fun removeDesktopAppWidget(info: PanelWidgetInfo) {
        mDesktopAppWidgets!!.remove(info)
    }

    companion object {
        private const val APPLICATION_NOT_RESPONDING_TIMEOUT: Long = 5000

        /**
         * Add an item to the database in a specified container. Sets the container, screen, cellX and
         * cellY fields of the item. Also assigns an ID to the item.
         */
        fun addItemToDatabase(
            context: Context, item: WidgetInfo, container: Int,
            spanX: Int, spanY: Int, notify: Boolean
        ) {
            item.container = container.toLong()
            item.spanX = spanX
            item.spanY = spanY
            val values = ContentValues()
            item.onAddToDatabase(values)
            context.contentResolver.insert(
                if (notify) Favorites.CONTENT_URI else Favorites.CONTENT_URI_NO_NOTIFICATION,
                values
            )?.let { item.id = it.pathSegments[1].toInt().toLong() }
        }

        /**
         * Removes the specified item from the database
         * @param context
         * @param item
         */
        fun deleteItemFromDatabase(context: Context, item: WidgetInfo) {
            val cr = context.contentResolver
            cr.delete(Favorites.getContentUri(item.id, false), null, null)
        }
    }
}