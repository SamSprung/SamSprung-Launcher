/*
 * ====================================================================
 * Copyright (c) 2012-2023 AbandonedCart.  All rights reserved.
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

package com.eightbit.io

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.os.Build
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprung
import com.eightbit.samsprung.organization
import com.eightbit.samsprung.settings.CoverPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class Debug(private var context: Context) {

    var prefs: SharedPreferences? = null

    private val issueUrl = "https://github.com/$organization/$organization-TooUI/issues/" +
            "new?labels=logcat&template=bug_report.yml&title=[Bug]%3A+"

    private fun getDeviceProfile(isSecureDevice: Boolean): StringBuilder {
        val separator = System.lineSeparator() ?: "\n"
        val log = StringBuilder(separator)
        log.append(SamSprung.versionLabel)
        log.append(separator)
        log.append(manufacturer)
        log.append(" ")
        val fields = Build.VERSION_CODES::class.java.fields
        var codeName = "UNKNOWN"
        for (field in fields) {
            try {
                if (field.getInt(Build.VERSION_CODES::class.java) == Build.VERSION.SDK_INT) {
                    codeName = field.name
                }
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }
        }
        log.append(codeName)
        log.append(" (")
        log.append(Build.VERSION.RELEASE)
        log.append(")")
        if (isSecureDevice) log.append(separator).append("Secure Lock Screen")
        return log
    }

    private fun setEmailParams(action: String, subject: String, text: String): Intent {
        return Intent(action).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("samsprungtoo@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private fun submitLogcat(context: Context, logText: String) {
        val subject = context.getString(R.string.git_issue_title, BuildConfig.COMMIT)
        with (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) {
            setPrimaryClip(ClipData.newPlainText(subject, logText))
        }

        try {
            val emailIntent: Intent = setEmailParams(Intent.ACTION_SENDTO, subject, logText)
            context.startActivity(Intent.createChooser(
                emailIntent, context.getString(R.string.logcat_crash)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            try {
                val emailIntent = setEmailParams(Intent.ACTION_SEND, subject, logText)
                context.startActivity(Intent.createChooser(
                    emailIntent, context.getString(R.string.logcat_crash)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl)))
                } catch (_: Exception) { }
            }
        }
    }

    fun processException(isSecureDevice: Boolean, exception: String) {
        val separator = System.lineSeparator() ?: "\n"
        val log = getDeviceProfile(isSecureDevice)
        log.append(separator).append(separator).append(exception)
        submitLogcat(context, log.toString())
        log.setLength(0)
    }

    @Throws(IOException::class)
    fun captureLogcat(isSecureDevice: Boolean) {
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            val separator = System.lineSeparator() ?: "\n"
            val log = getDeviceProfile(isSecureDevice)
            var line: String?
            val mLogcatProc = Runtime.getRuntime().exec(arrayOf(
                "logcat", "-d", "-t", "256", "--pid=${android.os.Process.myPid()}",
                "*:W", "AndroidRuntime", "System.err", "AppIconSolution*:S", "ViewRootImpl*:S"
            ))
            val reader = BufferedReader(InputStreamReader(mLogcatProc.inputStream))
            log.append(separator).append(separator)
            while (reader.readLine().also { line = it } != null) {
                log.append(line)
                log.append(separator)
            }
            reader.close()
            val logText = log.toString()
            withContext(Dispatchers.Main) {
                submitLogcat(context, logText)
                if (!logText.contains("AndroidRuntime") && context is CoverPreferences) {
                    val activity = context as CoverPreferences
                    activity.openWikiDrawer()
                }
            }
        }
    }

    companion object {
        private val manufacturer: String by lazy {
            try {
                @SuppressLint("PrivateApi")
                val c = Class.forName("android.os.SystemProperties")
                val get = c.getMethod("get", String::class.java)
                val name = get.invoke(c, "ro.product.manufacturer") as String
                name.ifEmpty { "Unknown" }
            } catch (_: Exception) {
                Build.MANUFACTURER
            }
        }
        val isOppoDevice: Boolean get() = manufacturer == "OPPO"
    }
}
