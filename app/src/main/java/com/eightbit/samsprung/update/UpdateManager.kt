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

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.eightbit.net.JSONExecutor
import com.eightbit.os.Version
import com.eightbit.samsprung.BuildConfig
import com.eightbit.samsprung.SamSprung
import com.eightbit.samsprung.organization
import com.eightbit.samsprung.settings.CoverPreferences
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

class UpdateManager(private var activity: AppCompatActivity) {

    private var updateListener: UpdateListener? = null
    private var appUpdateManager: AppUpdateManager? = null
    private var isUpdateAvailable = false

    private var updateUrl: String? = null
    private var appUpdate: AppUpdateInfo? = null

    init {
        if (BuildConfig.GOOGLE_PLAY) configurePlay() else configureGit()
    }

    private fun configurePlay() {
        if (null == appUpdateManager) appUpdateManager = AppUpdateManagerFactory.create(activity)
        val appUpdateInfoTask = appUpdateManager?.appUpdateInfo
        // Checks that the platform will allow the specified type of update.
        appUpdateInfoTask?.addOnSuccessListener { appUpdateInfo ->
            isUpdateAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability
                .UPDATE_AVAILABLE && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            if (isUpdateAvailable) {
                appUpdate = appUpdateInfo
                updateListener?.onUpdateFound()
            }
        }
    }

    private fun configureGit() {
        try {
            (activity.getSystemService(AppCompatActivity.NOTIFICATION_SERVICE)
                    as NotificationManager).cancel(SamSprung.request_code)
        } catch (_: Exception) { }
        if (activity is UpdateShimActivity) {
            with (activity.applicationContext.packageManager.packageInstaller) {
                mySessions.forEach {
                    try { abandonSession(it.sessionId) } catch (_: Exception) { }
                }
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                activity.externalCacheDir?.listFiles { _, name ->
                    name.lowercase(Locale.getDefault()).endsWith(".apk")
                }?.forEach { if (!it.isDirectory) it.delete() }
                requestUpdateJSON()
            }
        }
    }

    private fun installDownload(apkUri: Uri) {
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            activity.run {
                applicationContext.contentResolver.openInputStream(apkUri)?.use { apkStream ->
                    val length = DocumentFile.fromSingleUri(
                        applicationContext, apkUri
                    )?.length() ?: -1
                    val session = with (applicationContext.packageManager.packageInstaller) {
                        val params = PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL
                        )
                        if (!BuildConfig.GOOGLE_PLAY && Version.isSnowCone) {
                            params.setRequireUserAction(
                                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                            )
                        }
                        openSession(createSession(params))
                    }
                    session.openWrite("NAME", 0, length).use { sessionStream ->
                        apkStream.copyTo(sessionStream)
                        session.fsync(sessionStream)
                    }
                    val pi = PendingIntent.getBroadcast(
                        applicationContext, SamSprung.request_code,
                        Intent(applicationContext, UpdateReceiver::class.java)
                            .setAction(SamSprung.updating),
                        if (Version.isSnowCone)
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        else PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    session.commit(pi.intentSender)
                    session.close()
                }
            }
        }
    }

    fun requestDownload(link: String) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            val download: String = link.substringAfterLast(File.separator)
            val apk = File(activity.externalCacheDir, download)
            CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
                URL(link).openStream().use { stream ->
                    FileOutputStream(apk).use {
                        stream.copyTo(it)
                        installDownload(FileProvider.getUriForFile(
                            activity.applicationContext, SamSprung.provider, apk
                        ))
                    }
                }
            }
        } else if (activity is CoverPreferences) {
            (activity as CoverPreferences).updateLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
                    Uri.parse(String.format("package:%s", activity.packageName))))
        }
    }

    private fun startPlayUpdateFlow(appUpdateInfo: AppUpdateInfo) {
        appUpdateManager?.startUpdateFlowForResult(
            // Pass the intent that is returned by 'getAppUpdateInfo()'.
            appUpdateInfo,
            // Or 'AppUpdateType.FLEXIBLE' for flexible updates.
            AppUpdateType.IMMEDIATE,
            // The current activity making the update request.
            activity,
            // Include a request code to later monitor this update request.
            SamSprung.request_code
        )
    }

    fun requestUpdateJSON() {
        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            JSONExecutor(repo).setResultListener(object : JSONExecutor.ResultListener {
                override fun onResults(result: String?) {
                    try {
                        val jsonObject = JSONTokener(result).nextValue() as JSONObject
                        val lastCommit = (jsonObject["name"] as String).substring(
                            organization.length + 1
                        )
                        val assets = jsonObject["assets"] as JSONArray
                        val asset = assets[0] as JSONObject
                        isUpdateAvailable = BuildConfig.COMMIT != lastCommit
                        if (isUpdateAvailable) {
                            updateUrl = asset["browser_download_url"] as String
                            updateListener?.onUpdateFound()
                        }
                    } catch (_: JSONException) { }
                }
                override fun onException(e: Exception) { }
            })
        }
    }

    fun hasPendingUpdate(): Boolean {
        return isUpdateAvailable
    }

    fun onUpdateRequested() {
        if (BuildConfig.GOOGLE_PLAY) {
            appUpdate?.let { startPlayUpdateFlow(it) }
        } else {
            updateUrl?.let { requestDownload(it) }
        }
    }

    fun setUpdateListener(listener: UpdateListener) {
        updateListener = listener
    }

    interface UpdateListener {
        fun onUpdateFound()
    }

    companion object {
        private const val repo = "https://api.github.com/repos/SamSprung/SamSprung-TooUI/releases/tags/sideload"
    }
}
