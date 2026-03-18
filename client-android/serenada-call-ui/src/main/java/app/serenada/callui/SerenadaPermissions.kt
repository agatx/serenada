package app.serenada.callui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object SerenadaPermissions {
    val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    fun areGranted(activity: Activity): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
