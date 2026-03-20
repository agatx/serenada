package app.serenada.callui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.serenada.core.MediaCapability

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

    fun permissionsFor(capabilities: List<MediaCapability>): Array<String> {
        return capabilities
            .mapNotNull { capability ->
                when (capability) {
                    MediaCapability.CAMERA -> Manifest.permission.CAMERA
                    MediaCapability.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                }
            }
            .distinct()
            .toTypedArray()
    }
}
