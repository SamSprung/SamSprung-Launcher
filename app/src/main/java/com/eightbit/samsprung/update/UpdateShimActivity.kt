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

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.R


class UpdateShimActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setVisible(false)
        ScaledContext(this).internal(2f).setTheme(R.style.Theme_Launcher_NoActionBar)
        super.onCreate(savedInstanceState)
        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.let {
            UpdateManager(this).requestDownload(it)
        }
        finish()
    }
}
