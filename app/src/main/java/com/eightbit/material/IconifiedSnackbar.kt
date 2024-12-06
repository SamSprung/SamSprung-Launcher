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

package com.eightbit.material

import android.app.Activity
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.transition.TransitionManager
import com.eightbit.samsprung.R
import com.google.android.material.snackbar.Snackbar

class IconifiedSnackbar @JvmOverloads constructor(activity: Activity, layout: ViewGroup? = null) {
    private val mActivity: Activity
    private val layout: ViewGroup?

    init {
        mActivity = activity
        this.layout = layout
    }

    fun buildSnackbar(msg: String?, drawable: Int, length: Int, anchor: View?): Snackbar {
        val message = msg ?: ""
        val snackbar = Snackbar.make(
            mActivity.findViewById(R.id.coordinator), message, length
        )
        val snackbarLayout = snackbar.view
        val textView = snackbarLayout.findViewById<TextView>(
            com.google.android.material.R.id.snackbar_text
        ).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            compoundDrawablePadding = resources.getDimensionPixelOffset(R.dimen.snackbar_icon_padding)
        }
        when (mActivity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> {
                snackbar.setBackgroundTint(
                    ContextCompat.getColor(mActivity, R.color.snackbar_dark)
                )
                textView.setTextColor(
                    ContextCompat.getColor(mActivity, R.color.primary_text_dark)
                )
            }
            Configuration.UI_MODE_NIGHT_NO -> {
                snackbar.setBackgroundTint(
                    ContextCompat.getColor(mActivity, android.R.color.darker_gray)
                )
                textView.setTextColor(
                    ContextCompat.getColor(mActivity, R.color.primary_text_light)
                )
            }
        }
        val params = snackbarLayout.layoutParams as CoordinatorLayout.LayoutParams
        snackbar.view.layoutParams = params.apply {
            width = CoordinatorLayout.LayoutParams.MATCH_PARENT
        }
        snackbar.anchorView = anchor
        return snackbar
    }

    fun buildSnackbar(msg: String, length: Int): Snackbar {
        return buildSnackbar(msg, R.drawable.ic_samsprung_24dp, length, null)
    }

    fun buildTickerBar(msg: String, drawable: Int, length: Int): Snackbar {
        val snackbar = buildSnackbar(
            msg, drawable, length, null
        ).addCallback(object : Snackbar.Callback() {
            val top = layout?.paddingTop ?: 0
            val bottom = layout?.paddingBottom ?: 0
            override fun onDismissed(snackbar: Snackbar, event: Int) {
                if (null == layout) {
                    super.onDismissed(snackbar, event)
                    return
                }
                TransitionManager.beginDelayedTransition(layout)
                layout.updatePadding(top = top, bottom = bottom)
                super.onDismissed(snackbar, event)
            }

            override fun onShown(snackbar: Snackbar) {
                if (null == layout) {
                    super.onShown(snackbar)
                    return
                }
                val adjusted = top + snackbar.view.measuredHeight
                layout.updatePadding(top = adjusted, bottom = bottom)
                super.onShown(snackbar)
            }
        })
        val params = snackbar.view.layoutParams as CoordinatorLayout.LayoutParams
        params.gravity = Gravity.TOP
        snackbar.view.layoutParams = params
        return snackbar
    }

    fun buildTickerBar(msg: String, value: Int): Snackbar {
        return try {
            mActivity.resources.getResourceTypeName(value)
            buildTickerBar(msg, value, Snackbar.LENGTH_LONG)
        } catch (_: Exception) {
            buildTickerBar(msg, R.drawable.ic_samsprung_24dp, value)
        }
    }

    fun buildTickerBar(msg: String): Snackbar {
        return buildTickerBar(msg, R.drawable.ic_samsprung_24dp, Snackbar.LENGTH_LONG)
    }
}
