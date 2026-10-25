package org.stypox.dicio.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.stypox.dicio.MainActivity

/**
 * Android 10+ silently blocks starting activities (e.g. the clock app or a phone call) from the
 * background. This returns whether Dicio is currently allowed to start activities: either because
 * its UI is visible, or because the user granted the "Display over other apps" permission.
 */
fun canStartActivities(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return true // no restrictions before Android 10
    }
    return MainActivity.isInForeground > 0 || Settings.canDrawOverlays(context)
}
