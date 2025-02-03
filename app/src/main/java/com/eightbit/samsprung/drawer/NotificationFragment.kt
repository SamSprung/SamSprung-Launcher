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
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eightbit.app.CoverOptions
import com.eightbit.content.ScaledContext
import com.eightbit.os.Version
import com.eightbit.samsprung.NotificationReceiver
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprung
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.settings.Preferences
import com.eightbit.view.OnSwipeTouchListener
import com.eightbit.widget.RecyclerViewTouch
import java.util.*

class NotificationFragment : Fragment(), NotificationAdapter.OnNoticeClickListener {

    private val Number.toPx get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(),
        ScaledContext(requireActivity()).cover().resources.displayMetrics
    ).toInt()

    private inline val @receiver:ColorInt Int.blended
        @ColorInt
        get() = ColorUtils.blendARGB(this,
            if ((requireActivity().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE, 0.6f)

    private var launcherManager: LauncherManager? = null
    private var textSpeech: TextToSpeech? = null
    private var layoutManager: LinearLayoutManager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_notices, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        launcherManager = LauncherManager(requireActivity() as SamSprungOverlay)

        textSpeech = TextToSpeech(requireContext().applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result: Int? = textSpeech?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    textSpeech = null
                }
            }
        }

        val noticesView = view.findViewById<RecyclerView>(R.id.notificationList)
        // noticesView.setHasFixedSize(true)

        layoutManager = LinearLayoutManager(requireContext())
        noticesView.layoutManager = layoutManager
        noticesView.adapter = NotificationAdapter(requireActivity(), this)
        if (hasNotificationListener(requireContext())) {
            NotificationReceiver.getReceiver()?.setNotificationsListener(
                noticesView.adapter as NotificationAdapter
            )
        }

        RecyclerViewTouch(noticesView).setSwipeCallback(
            ItemTouchHelper.END or ItemTouchHelper.DOWN,
            object: RecyclerViewTouch.SwipeCallback {
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (direction == ItemTouchHelper.END) {
                    val holder = viewHolder as NotificationAdapter.NoticeViewHolder
                    holder.dismissNotification()
                }
                if (direction == ItemTouchHelper.DOWN) {
                    onSwipeClosed(noticesView)
                }
            }
        })

        noticesView.setOnTouchListener(object : OnSwipeTouchListener(requireActivity()) {
            override fun onSwipeBottom() : Boolean {
                onSwipeClosed(noticesView)
                return false
            }
        })
    }

    private fun onSwipeClosed(recyclerView: RecyclerView) {
        val manager = recyclerView.layoutManager as LinearLayoutManager
        if (manager.itemCount == 0 || manager.findFirstCompletelyVisibleItemPosition() == 0) {
            val activity = requireActivity() as SamSprungOverlay
            activity.closeMainDrawer()
        }
    }

    private fun promptNotificationReply(action: Notification.Action) {
        for (remoteInput in action.remoteInputs) {
            if (remoteInput.allowFreeFormInput) {
                var replyDialog: Dialog? = null
                val view: View = layoutInflater.inflate(R.layout.notification_reply, null)
                val dialog = AlertDialog.Builder(
                    ContextThemeWrapper(requireActivity(), R.style.Theme_SecondScreen_NoActionBar)
                )
                view.findViewById<LinearLayout>(R.id.entries).run {
                    findViewById<AppCompatImageView>(R.id.send).setOnClickListener {
                        val reply = findViewById<EditText>(R.id.reply)
                        val replyIntent = Intent()
                        val replyBundle = Bundle()
                        replyBundle.putCharSequence(remoteInput.resultKey, reply.text.toString())
                        RemoteInput.addResultsToIntent(action.remoteInputs, replyIntent, replyBundle)
                        requireActivity().startIntentSender(action.actionIntent.intentSender,
                            replyIntent, 0, 0, 0,
                            CoverOptions(null).getActivityOptions(1).toBundle())
                        replyDialog?.dismiss()
                    }
                    findViewById<AppCompatImageView>(R.id.cancel).setOnClickListener {
                        replyDialog?.dismiss()
                    }
                }
                replyDialog = dialog.setView(view).show()
                replyDialog.window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }
    }

    private fun setNotificationAction(
        position: Int, actionsPanel: LinearLayout, action: Notification.Action, color: Int) {
        val actionButtons = actionsPanel.findViewById<LinearLayout>(R.id.actions)
        val button = AppCompatButton(
            ContextThemeWrapper(requireActivity(), R.style.Theme_SecondScreen_NoActionBar)
        )
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        button.setSingleLine()
        button.text = action.title
        button.backgroundTintBlendMode = BlendMode.MODULATE
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.setOnClickListener {
            if (!action.remoteInputs.isNullOrEmpty()) {
                promptNotificationReply(action)
            } else {
                try {
                    var onFinished: PendingIntent.OnFinished? = null
                    if (action.actionIntent.creatorPackage.toString() == "com.android.systemui") {
                        onFinished = PendingIntent.OnFinished { pendingIntent, _, _, _, _ ->
                            val foregroundService = Intent(
                                requireContext().applicationContext, AppDisplayListener::class.java
                            ).putExtras(Bundle().apply {
                                putString("launchPackage", pendingIntent.creatorPackage)
                            })
                            requireContext().startForegroundService(foregroundService)
                        }
                    }
                    action.actionIntent.send(requireContext(), SamSprung.request_code,
                        Intent().setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_FORWARD_RESULT
                        ), onFinished, null, null,
                        CoverOptions(null).getActivityOptions(1).toBundle())
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
                actionsPanel.visibility = View.GONE
            }
            layoutManager?.scrollToPosition(position)
        }
        actionButtons.addView(button, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT,
            when {
                action.title.length > 14 -> 1.0f
                action.title.length > 9 -> 0.8f
                action.title.length > 4 -> 0.6f
                else -> 0.5f
            }
        ))
    }

    fun onNoticeExpanded(itemView: View, position: Int, notice: StatusBarNotification) {
        val actionsPanel = itemView.findViewById<LinearLayout>(R.id.action_panel)
        val linesText = itemView.findViewById<TextView>(R.id.lines)
        if (actionsPanel.isVisible) {
            linesText.maxLines = 3
            linesText.ellipsize = TextUtils.TruncateAt.END
            actionsPanel.visibility = View.GONE
        } else {
            linesText.maxLines = Integer.MAX_VALUE
            linesText.ellipsize = null
            val actionButtons = actionsPanel.findViewById<LinearLayout>(R.id.actions)
            if (actionButtons.childCount > 0) {
                actionsPanel.visibility = View.VISIBLE
            } else {
                notice.notification.actions?.let {
                    actionsPanel.visibility = View.VISIBLE
                    val prefs = requireActivity().getSharedPreferences(
                        Preferences.prefsValue, AppCompatActivity.MODE_PRIVATE)
                    it.forEach { action ->
                        setNotificationAction(position, actionsPanel, action, prefs.getInt(
                            Preferences.prefColors, Color.rgb(255, 255, 255)
                        ).blended)
                    }
                }
            }
            if (actionButtons.childCount > 0) {
                layoutManager?.scrollToPositionWithOffset(
                    position, -(actionButtons.height) - 20.toPx
                )
            } else {
                layoutManager?.scrollToPosition(position)
            }
        }
    }

    override fun onNoticeClicked(itemView: View, position: Int, notice: StatusBarNotification) {
        val activity = requireActivity() as SamSprungOverlay
        activity.setKeyguardListener(object: SamSprungOverlay.KeyguardListener {
            override fun onKeyguardCheck(unlocked: Boolean) {
                if (!unlocked) return
                onNoticeExpanded(itemView, position, notice)
            }
        })
    }

    override fun onNoticeLongClicked(
        itemView: View, position: Int, notice: StatusBarNotification
    ) : Boolean {
        tactileFeedback()
        val content = StringBuilder(itemView.findViewById<TextView>(R.id.title).text)
            .append(System.lineSeparator() ?: "\n")
            .append(itemView.findViewById<TextView>(R.id.lines).text)
        textSpeech?.speak(content, TextToSpeech.QUEUE_ADD, null, SamSprung.notification)
        return true
    }

    override fun onLaunchClicked(pendingIntent: PendingIntent) {
        launcherManager?.launchPendingIntent(pendingIntent)
    }

    private val vibrator by lazy {
        if (Version.isSnowCone)
            with (requireActivity().getSystemService(
                Context.VIBRATOR_MANAGER_SERVICE
            ) as VibratorManager) { defaultVibrator }
        else
            @Suppress("DEPRECATION")
            requireActivity().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val vibrationEffect = VibrationEffect
        .createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
    private fun tactileFeedback() {
        vibrator.vibrate(vibrationEffect)
    }

    private fun hasNotificationListener(context: Context): Boolean {
        val myNotificationListenerComponentName = ComponentName(
            context.applicationContext, NotificationReceiver::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners")
        if (enabledListeners.isNullOrEmpty()) return false
        return enabledListeners.split(":").map {
            ComponentName.unflattenFromString(it)
        }.any {componentName->
            myNotificationListenerComponentName == componentName
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (null != textSpeech) {
            textSpeech?.stop()
            textSpeech?.shutdown()
        }
    }
}
