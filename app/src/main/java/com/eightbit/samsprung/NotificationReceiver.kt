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

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

@SuppressLint("NotifyDataSetChanged")
class NotificationReceiver : NotificationListenerService() {

    companion object {
        private lateinit var receiverInstance: NotificationReceiver
        private var isConnected: Boolean = false
        fun getReceiver() : NotificationReceiver? {
            return if (isConnected) receiverInstance else null
        }
    }

    init {
        receiverInstance = this
    }

    private var mNotificationsListener: NotificationsListener? = null

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            requestRebind(ComponentName(applicationContext, NotificationReceiver::class.java))
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        getReceiver()?.mNotificationsListener?.onNotificationPosted(sbn)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        super.onNotificationPosted(sbn, rankingMap)
        getReceiver()?.mNotificationsListener?.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        getReceiver()?.mNotificationsListener?.onNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        super.onNotificationRemoved(sbn, rankingMap)
        getReceiver()?.mNotificationsListener?.onNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        getReceiver()?.mNotificationsListener?.onNotificationRemoved(sbn)
    }

    fun setNotificationsListener(listener: NotificationsListener?) {
        getReceiver()?.mNotificationsListener = listener
        val notifications: ArrayList<StatusBarNotification> = arrayListOf()
        if (notifications.addAll(activeNotifications))
            getReceiver()?.mNotificationsListener?.onActiveNotifications(notifications)
    }

    interface NotificationsListener {
        fun onActiveNotifications(activeNotifications: ArrayList<StatusBarNotification>)
        fun onNotificationPosted(sbn: StatusBarNotification)
        fun onNotificationRemoved(sbn: StatusBarNotification)
    }
}
