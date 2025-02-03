package com.eightbit.samsprung.drawer.panels

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.content.res.Resources.NotFoundException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.settings.Preferences
import java.io.File
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

class WidgetPreviews(mLauncher: SamSprungOverlay) {
    private val mContext = mLauncher
    private val mPackageManager: PackageManager

    // Used for drawing widget previews
    private val mCachedAppWidgetPreviewCanvas = CanvasCache()
    private val mCachedAppWidgetPreviewSrcRect = RectCache()
    private val mCachedAppWidgetPreviewDestRect = RectCache()
    private val mCachedAppWidgetPreviewPaint = PaintCache()
    private val mAppIconSize: Int
    private val mProfileBadgeSize: Int
    private val mProfileBadgeMargin: Int
    private var mDb: CacheDb?
    private val mLoadedPreviews: HashMap<String, WeakReference<Bitmap?>>
    private val mUnusedBitmaps: ArrayList<SoftReference<Bitmap?>>

    companion object {
        const val ANDROID_INCREMENTAL_VERSION_NAME_KEY = "android.incremental.version"
        private var sInvalidPackages: HashSet<String> = hashSetOf()
        private const val WIDGET_PREFIX = "Widget:"
        private const val SHORTCUT_PREFIX = "Shortcut:"
        private fun getObjectName(tag: Any?): String? {
            return tag?.let {
                // should cache the string builder
                val sb = StringBuilder()
                if (it is AppWidgetProviderInfo) {
                    sb.append(WIDGET_PREFIX)
                    sb.append(it.profile)
                    sb.append('/')
                    sb.append(it.provider.flattenToString())
                } else {
                    sb.append(SHORTCUT_PREFIX)
                    val info = it as ResolveInfo
                    sb.append(
                        ComponentName(
                            info.activityInfo.packageName,
                            info.activityInfo.name
                        ).flattenToString()
                    )
                }
                val output: String = sb.toString()
                sb.setLength(0)
                output
            }
        }

        private fun renderDrawableToBitmap(
            d: Drawable?, bitmap: Bitmap?, x: Int, y: Int, w: Int, h: Int
        ) {
            bitmap?.let {
                val c = Canvas(it)
                c.scale(1.0f, 1.0f)
                val oldBounds = d!!.copyBounds()
                d.setBounds(x, y, x + w, y + h)
                d.draw(c)
                d.bounds = oldBounds // Restore the bounds
                c.setBitmap(null)
            }
        }

        init {
            sInvalidPackages = HashSet()
        }
    }

    fun recycleBitmap(tag: Any?, bitmapToRecycle: Bitmap) {
        getObjectName(tag)?.let { name ->
            synchronized(mLoadedPreviews) {
                if (mLoadedPreviews.containsKey(name)) {
                    mLoadedPreviews[name]?.get()?.let {
                        if (it == bitmapToRecycle) {
                            mLoadedPreviews.remove(name)
                            if (bitmapToRecycle.isMutable) {
                                synchronized(mUnusedBitmaps) {
                                    mUnusedBitmaps.add(SoftReference(it))
                                }
                            }
                        } else {
                            throw java.lang.RuntimeException("Passed bitmap doesn't match target")
                        }
                    }
                }
            }
        }
    }

    class CacheDb(mContext: Context) : SQLiteOpenHelper(
        mContext, File(mContext.cacheDir, DB_NAME).path, null, DB_VERSION
    ) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                        "$COLUMN_NAME TEXT NOT NULL, " +
                        "$COLUMN_SIZE TEXT NOT NULL, " +
                        "$COLUMN_PREVIEW_BITMAP BLOB NOT NULL, " +
                        "PRIMARY KEY ($COLUMN_NAME, $COLUMN_SIZE) );"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != newVersion) {
                // Delete all the records; they'll be repopulated as this is a cache
                db.execSQL("DELETE FROM $TABLE_NAME")
            }
        }

        companion object {
            const val DB_VERSION = 3
            const val DB_NAME = "widgetpreviews.db"
            const val TABLE_NAME = "previews"
            const val COLUMN_NAME = "name"
            const val COLUMN_SIZE = "size"
            const val COLUMN_PREVIEW_BITMAP = "preview_bitmap"
        }
    }

    private fun clearDb() {
        // Delete everything
        try {
            mDb?.writableDatabase?.delete(CacheDb.TABLE_NAME, null, null)
        } catch (_: SQLiteDiskIOException) { }
    }

    fun generateWidgetPreview(
        info: AppWidgetProviderInfo,
        maxWidth: Int, maxHeight: Int, bitmap: Bitmap?,
        preScaledWidthOut: IntArray?
    ): Bitmap? {
        // Load the preview image if possible
        var maxPreviewWidth = maxWidth
        var maxPreviewHeight = maxHeight
        var preview = bitmap
        if (maxPreviewWidth < 0) maxPreviewWidth = Int.MAX_VALUE
        if (maxPreviewHeight < 0) maxPreviewHeight = Int.MAX_VALUE
        val drawable = info.loadPreviewImage(mContext, 0)
        var previewWidth = 0
        var previewHeight = 0
        var defaultPreview: Bitmap? = null
        val widgetPreviewExists = drawable != null
        if (widgetPreviewExists) {
            previewWidth = drawable.intrinsicWidth
            previewHeight = drawable.intrinsicHeight
        } else {
            // Generate a preview image if we couldn't load one
            (ContextCompat.getDrawable(
                mContext, R.drawable.widget_preview_tile
            ) as? BitmapDrawable)?.let { previewDrawable ->
                val previewDrawableWidth = previewDrawable.intrinsicWidth
                val previewDrawableHeight = previewDrawable.intrinsicHeight
                previewWidth = previewDrawableWidth // subtract 2 dips
                previewHeight = previewDrawableHeight
                defaultPreview = Bitmap.createBitmap(
                    previewWidth, previewHeight,
                    Bitmap.Config.ARGB_8888
                )
                val c = mCachedAppWidgetPreviewCanvas.get()!!
                c.setBitmap(defaultPreview)
                previewDrawable.setBounds(0, 0, previewWidth, previewHeight)
                previewDrawable.setTileModeXY(
                    Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT
                )
                previewDrawable.draw(c)
                c.setBitmap(null)

                // Draw the icon in the top left corner
                val sWidgetPreviewIconPaddingPercentage = 0.25f
                val minOffset = (mAppIconSize * sWidgetPreviewIconPaddingPercentage).toInt()
                val smallestSide = Integer.min(previewWidth, previewHeight)
                val iconScale = (smallestSide.toFloat() / (mAppIconSize + 2
                        * minOffset)).coerceAtMost(1f)
                try {
                    var icon: Drawable? = null
                    val xoffset = ((previewDrawableWidth - mAppIconSize * iconScale) / 2).toInt()
                    val yoffset = ((previewDrawableHeight - mAppIconSize * iconScale) / 2).toInt()
                    if (info.icon > 0)
                        icon = getFullResIcon(info.provider.packageName, info.icon, info.profile)
                    icon?.let {
                        renderDrawableToBitmap(
                            it, defaultPreview, xoffset,
                            yoffset, (mAppIconSize * iconScale).toInt(),
                            (mAppIconSize * iconScale).toInt()
                        )
                    }
                } catch (_: NotFoundException) { }
            }
        }

        // Scale to fit width only - let the widget preview be clipped in the
        // vertical dimension
        var scale = 1f
        preScaledWidthOut?.let { it[0] = previewWidth }
        if (previewWidth > maxPreviewWidth) {
            scale = maxPreviewWidth / previewWidth.toFloat()
        }
        if (scale != 1f) {
            previewWidth = (scale * previewWidth).toInt()
            previewHeight = (scale * previewHeight).toInt()
        }

        // If a bitmap is passed in, we use it; otherwise, we create a bitmap of the right size
        if (preview == null) {
            preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        }

        // Draw the scaled preview into the final bitmap
        val x = (preview.width - previewWidth) / 2
        if (widgetPreviewExists) {
            renderDrawableToBitmap(
                drawable, preview, x, 0, previewWidth,
                previewHeight
            )
        } else {
            val c = mCachedAppWidgetPreviewCanvas.get()!!
            val src = mCachedAppWidgetPreviewSrcRect.get()!!
            val dest = mCachedAppWidgetPreviewDestRect.get()!!
            c.setBitmap(preview)
            defaultPreview?.let { src[0, 0, it.width] = it.height }
            dest[x, 0, x + previewWidth] = previewHeight
            var p = mCachedAppWidgetPreviewPaint.get()
            if (p == null) {
                p = Paint()
                p.isFilterBitmap = true
                mCachedAppWidgetPreviewPaint.set(p)
            }
            defaultPreview?.let { c.drawBitmap(it, src, dest, p) }
            c.setBitmap(null)
        }

        // Finally, if the preview is for a managed profile, badge it.
        if (info.profile != Process.myUserHandle()) {
            val previewBitmapWidth = preview.width
            val previewBitmapHeight = preview.height

            // Figure out the badge location.
            val badgeLocation: Rect
            val configuration = mContext.resources.configuration
            badgeLocation = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                val badgeLeft = previewBitmapWidth - mProfileBadgeSize - mProfileBadgeMargin
                val badgeTop = previewBitmapHeight - mProfileBadgeSize - mProfileBadgeMargin
                val badgeRight = badgeLeft + mProfileBadgeSize
                val badgeBottom = badgeTop + mProfileBadgeSize
                Rect(badgeLeft, badgeTop, badgeRight, badgeBottom)
            } else {
                val badgeLeft = mProfileBadgeMargin
                val badgeTop = previewBitmapHeight - mProfileBadgeSize - mProfileBadgeMargin
                val badgeRight = badgeLeft + mProfileBadgeSize
                val badgeBottom = badgeTop + mProfileBadgeSize
                Rect(badgeLeft, badgeTop, badgeRight, badgeBottom)
            }

            // Badge the preview.
            val previewDrawable = BitmapDrawable(
                mContext.resources, preview
            )
            val badgedPreviewDrawable = mContext.packageManager.getUserBadgedDrawableForDensity(
                previewDrawable, info.profile, badgeLocation, 0
            )

            // Return the badged bitmap.
            if (badgedPreviewDrawable is BitmapDrawable) {
                return badgedPreviewDrawable.bitmap
            }
        }
        return preview
    }

    private val fullResDefaultActivityIcon: Drawable
        get() = getFullResIcon(
            Resources.getSystem(),
            android.R.mipmap.sym_def_app_icon, Process.myUserHandle()
        )

    private fun getFullResIcon(resources: Resources, iconId: Int, user: UserHandle): Drawable {
        var drawable: Drawable? = try {
            ResourcesCompat.getDrawableForDensity(resources, iconId, 160, resources.newTheme())
        } catch (_: NotFoundException) {
            null
        }
        if (drawable == null) {
            drawable = fullResDefaultActivityIcon
        }
        return mPackageManager.getUserBadgedIcon(drawable, user)
    }

    private fun getFullResIcon(packageName: String?, iconId: Int, user: UserHandle): Drawable {
        val resources: Resources? = try {
            // TODO: Check if this needs to use the user param if we support
            // shortcuts/widgets from other profiles. It won't work as is
            // for packages that are only available in a different user profile.
            packageName?.let { mPackageManager.getResourcesForApplication(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        resources?.let { if (iconId != 0) return getFullResIcon(it, iconId, user) }
        return fullResDefaultActivityIcon
    }

    init {
        mPackageManager = mContext.packageManager
        mAppIconSize = mContext.resources.getDimensionPixelSize(R.dimen.app_icon_size)
        mProfileBadgeSize = mContext.resources.getDimensionPixelSize(
            R.dimen.profile_badge_size
        )
        mProfileBadgeMargin = mContext.resources.getDimensionPixelSize(
            R.dimen.profile_badge_margin
        )
        mDb = mLauncher.getWidgetPreviewCacheDb()
        mLoadedPreviews = HashMap()
        mUnusedBitmaps = arrayListOf()
        val sp = mLauncher.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
        val lastVersionName = sp.getString(ANDROID_INCREMENTAL_VERSION_NAME_KEY, null)
        val versionName = Build.VERSION.INCREMENTAL
        if (versionName != lastVersionName) {
            // clear all the previews whenever the system version changes, to ensure that previews
            // are up-to-date for any apps that might have been updated with the system
            clearDb()
            val editor = sp.edit()
            editor.putString(ANDROID_INCREMENTAL_VERSION_NAME_KEY, versionName)
            editor.apply()
        }
    }

    internal abstract class SoftReferenceThreadLocal<T> {
        private val mThreadLocal: ThreadLocal<SoftReference<T?>> = ThreadLocal()
        abstract fun initialValue(): T
        fun set(t: T) {
            mThreadLocal.set(SoftReference(t))
        }

        fun get(): T? {
            val reference = mThreadLocal.get()
            var obj: T?
            if (reference == null) {
                obj = initialValue()
                mThreadLocal.set(SoftReference(obj))
            } else {
                obj = reference.get()
                if (obj == null) {
                    obj = initialValue()
                    mThreadLocal.set(SoftReference(obj))
                }
            }
            return obj
        }

    }

    internal class CanvasCache : SoftReferenceThreadLocal<Canvas>() {
        override fun initialValue(): Canvas {
            return Canvas()
        }
    }

    internal class PaintCache : SoftReferenceThreadLocal<Paint?>() {
        override fun initialValue(): Paint? {
            return null
        }
    }

    internal class RectCache : SoftReferenceThreadLocal<Rect>() {
        override fun initialValue(): Rect {
            return Rect()
        }
    }
}