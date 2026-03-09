package com.example.ffmpegdemo

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/**
 * Extension functions for handling system bars (status bar and navigation bar)
 * in Android activities and fragments with lifecycle-aware color updates.
 *
 * Usage: Call in Activity.onCreate() or Fragment.onViewCreated().
 * Multiple calls will remove previous observer to avoid conflicts.
 */

private const val TAG_STATUS_BAR = "SystemBarsExt_StatusBar"
private const val TAG_NAVIGATION_BAR = "SystemBarsExt_NavigationBar"
private const val TAG_SYSTEM_BARS = "SystemBarsExt_SystemBars"

/**
 * Tracks active observers per activity to prevent duplicate registration.
 * Key: "${activity.hashCode()}_$tag"
 */
private val activeObservers = mutableMapOf<String, SystemBarsLifecycleObserver>()

/**
 * Adds a lifecycle observer to update the status bar color and appearance.
 *
 * @param statusBarColor The color int for the status bar
 * @param isLightStatusBar Whether to use dark icons (for light backgrounds). Null for auto-detection based on luminance.
 */
fun ComponentActivity.addStatusBarColorUpdate(
    @ColorInt statusBarColor: Int,
    isLightStatusBar: Boolean? = null
) {
    val observer = SystemBarsLifecycleObserver(
        activity = this,
        statusBarColor = statusBarColor,
        navigationBarColor = null,
        isLightStatusBar = isLightStatusBar
    )
    replaceObserver(lifecycle, this, TAG_STATUS_BAR, observer)
}

/**
 * Adds a lifecycle observer to update the navigation bar color and appearance.
 * The icon color will be automatically adjusted based on luminance.
 *
 * @param navigationBarColor The color int for the navigation bar
 */
fun ComponentActivity.addNavigationBarColorUpdate(
    @ColorInt navigationBarColor: Int
) {
    val observer = SystemBarsLifecycleObserver(
        activity = this,
        statusBarColor = null,
        navigationBarColor = navigationBarColor,
        isLightStatusBar = null
    )
    replaceObserver(lifecycle, this, TAG_NAVIGATION_BAR, observer)
}

/**
 * Adds a lifecycle observer to update both status bar and navigation bar colors.
 *
 * @param systemBarsColor The color int for both bars
 * @param isLightStatusBar Whether to use dark icons (for light backgrounds). Null for auto-detection.
 */
fun ComponentActivity.addSystemBarsColorUpdate(
    @ColorInt systemBarsColor: Int,
    isLightStatusBar: Boolean? = null
) {
    val observer = SystemBarsLifecycleObserver(
        activity = this,
        statusBarColor = systemBarsColor,
        navigationBarColor = systemBarsColor,
        isLightStatusBar = isLightStatusBar
    )
    replaceObserver(lifecycle, this, TAG_SYSTEM_BARS, observer)
}

/**
 * Fragment variant: Adds a lifecycle observer to update the status bar color.
 * Must be called in onViewCreated() or later.
 */
fun Fragment.addStatusBarColorUpdate(
    @ColorInt statusBarColor: Int,
    isLightStatusBar: Boolean? = null
) {
    val activity = activity ?: return
    val observer = SystemBarsLifecycleObserver(
        activity = activity,
        statusBarColor = statusBarColor,
        navigationBarColor = null,
        isLightStatusBar = isLightStatusBar
    )
    replaceObserver(viewLifecycleOwner.lifecycle, activity, TAG_STATUS_BAR, observer)
}

/**
 * Fragment variant: Adds a lifecycle observer to update the navigation bar color.
 * Must be called in onViewCreated() or later.
 */
fun Fragment.addNavigationBarColorUpdate(
    @ColorInt navigationBarColor: Int
) {
    val activity = activity ?: return
    val observer = SystemBarsLifecycleObserver(
        activity = activity,
        statusBarColor = null,
        navigationBarColor = navigationBarColor,
        isLightStatusBar = null
    )
    replaceObserver(viewLifecycleOwner.lifecycle, activity, TAG_NAVIGATION_BAR, observer)
}

/**
 * Fragment variant: Adds a lifecycle observer to update both system bars colors.
 * Must be called in onViewCreated() or later.
 */
fun Fragment.addSystemBarsColorUpdate(
    @ColorInt systemBarsColor: Int,
    isLightStatusBar: Boolean? = null
) {
    val activity = activity ?: return
    val observer = SystemBarsLifecycleObserver(
        activity = activity,
        statusBarColor = systemBarsColor,
        navigationBarColor = systemBarsColor,
        isLightStatusBar = isLightStatusBar
    )
    replaceObserver(viewLifecycleOwner.lifecycle, activity, TAG_SYSTEM_BARS, observer)
}

/**
 * Removes any existing observer with the same tag before adding the new one,
 * preventing duplicate observer conflicts.
 */
private fun replaceObserver(
    lifecycle: Lifecycle,
    activity: Activity,
    tag: String,
    observer: SystemBarsLifecycleObserver
) {
    val key = "%d_%s".format(activity.hashCode(), tag)
    activeObservers.remove(key)?.let { old ->
        lifecycle.removeObserver(old)
    }
    activeObservers[key] = observer
    lifecycle.addObserver(observer)
}

/**
 * Unified lifecycle observer for system bars color and appearance changes.
 * Saves original window colors and appearance on creation and restores them on stop.
 */
private class SystemBarsLifecycleObserver(
    activity: Activity,
    @param:ColorInt private val statusBarColor: Int?,
    @param:ColorInt private val navigationBarColor: Int?,
    private val isLightStatusBar: Boolean?
) : DefaultLifecycleObserver {

    @Suppress("DEPRECATION")
    private val originalStatusBarColor = activity.window.statusBarColor

    @Suppress("DEPRECATION")
    private val originalNavigationBarColor = activity.window.navigationBarColor

    // Save original appearance for accurate restoration
    private val originalAppearance: Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.systemBarsAppearance ?: 0
        } else {
            0
        }

    @Suppress("DEPRECATION")
    private val originalSystemUiVisibility: Int = activity.window.decorView.systemUiVisibility

    private val activityRef = WeakReference(activity)

    override fun onStart(owner: LifecycleOwner) {
        activityRef.get()?.window?.let(::applyColors)
    }

    override fun onStop(owner: LifecycleOwner) {
        activityRef.get()?.window?.let(::resetColors)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        activityRef.get()?.let { activity ->
            val keys = activeObservers.entries
                .filter { it.value === this }
                .map { it.key }
            keys.forEach { activeObservers.remove(it) }
        }
        activityRef.clear()
    }

    private fun isLightColor(@ColorInt color: Int): Boolean =
        ColorUtils.calculateLuminance(color) > 0.5

    @SuppressLint("ObsoleteSdkInt")
    @Suppress("DEPRECATION")
    private fun applyColors(window: Window) {
        statusBarColor?.let { window.statusBarColor = it }
        navigationBarColor?.let { window.navigationBarColor = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask =
                (if (statusBarColor != null) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                        (if (navigationBarColor != null) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)

            val appearance =
                (if (statusBarColor != null && (isLightStatusBar ?: isLightColor(statusBarColor)))
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                        (if (navigationBarColor != null && isLightColor(navigationBarColor))
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)

            if (mask != 0) {
                window.insetsController?.setSystemBarsAppearance(appearance, mask)
            }
        } else {
            // Only modify light appearance flags, preserve existing layout flags
            var flags = window.decorView.systemUiVisibility

            if (statusBarColor != null) {
                flags = if (isLightStatusBar ?: isLightColor(statusBarColor)) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }

            if (navigationBarColor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (isLightColor(navigationBarColor)) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }

            window.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun resetColors(window: Window) {
        statusBarColor?.let { window.statusBarColor = originalStatusBarColor }
        navigationBarColor?.let { window.navigationBarColor = originalNavigationBarColor }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Restore original appearance precisely, only for the bits we modified
            val mask =
                (if (statusBarColor != null) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                        (if (navigationBarColor != null) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)

            if (mask != 0) {
                val restoredAppearance = originalAppearance and mask
                window.insetsController?.setSystemBarsAppearance(restoredAppearance, mask)
            }
        } else {
            // Restore original systemUiVisibility precisely
            window.decorView.systemUiVisibility = originalSystemUiVisibility
        }
    }
}
