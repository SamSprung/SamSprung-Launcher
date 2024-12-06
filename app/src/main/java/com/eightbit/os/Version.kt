/*
 * ====================================================================
 * Copyright (c) 2023 AbandonedCart.  All rights reserved.
 *
 * https://github.com/AbandonedCart/AbandonedCart/blob/main/LICENSE#L4
 * ====================================================================
 *
 * The license and distribution terms for any publicly available version or
 * derivative of this code cannot be changed.  i.e. this code cannot simply be
 * copied and put under another distribution license
 * [including the GNU Public License.] Content not subject to these terms is
 * subject to to the terms and conditions of the Apache License, Version 2.0.
 */

package com.eightbit.os

import android.os.Build

object Version {

    /**
     * Android 12, API 31
     */
    @JvmStatic
    val isSnowCone: Boolean get() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Android 13, API 33
     */
    @JvmStatic
    val isTiramisu: Boolean get() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    /**
     * Android 14, API 34
     */
    @JvmStatic
    val isUpsideDownCake: Boolean get() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}