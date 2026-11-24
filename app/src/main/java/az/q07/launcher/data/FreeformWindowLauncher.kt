package az.q07.launcher.data

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

enum class WindowDisplayMode { FULLSCREEN, MINI_WINDOW }

object FreeformWindowLauncher {
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    val WAZE_PACKAGES = listOf("com.waze")
    val YOUTUBE_PACKAGES = listOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta"
    )

    var lastLaunchedPackages: List<String> = WAZE_PACKAGES
    var lastLaunchedName: String = "Waze"
    var currentMode: WindowDisplayMode = WindowDisplayMode.FULLSCREEN

    @SuppressLint("PrivateApi")
    fun launchAppWithMode(
        context: Context,
        candidatePackages: List<String>,
        mode: WindowDisplayMode = WindowDisplayMode.FULLSCREEN,
        appDisplayName: String = "Tətbiq",
        customBounds: Rect? = null
    ): Boolean {
        val targetIntent = candidatePackages.firstNotNullOfOrNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)
        }
        if (targetIntent == null) {
            Toast.makeText(context, "$appDisplayName quraşdırılmayıb!", Toast.LENGTH_SHORT).show()
            return false
        }

        lastLaunchedPackages = candidatePackages
        lastLaunchedName = appDisplayName
        currentMode = mode

        if (Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, SmartWindowOverlayService::class.java).apply {
                putExtra("MODE", mode.name)
                putExtra("APP_NAME", appDisplayName)
            })
        }

        if (mode == WindowDisplayMode.FULLSCREEN) {
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            return try {
                val options = ActivityOptions.makeBasic()
                val m = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                m.invoke(options, WINDOWING_MODE_FULLSCREEN)
                context.startActivity(targetIntent, options.toBundle())
                true
            } catch (_: Exception) {
                context.startActivity(targetIntent)
                true
            }
        } else {
            ensureFreeformEnabled(context)
            val metrics = context.resources.displayMetrics
            val winW = (metrics.widthPixels * 0.36f).toInt().coerceAtLeast(480)
            val winH = (metrics.heightPixels * 0.60f).toInt().coerceAtLeast(360)
            val bounds = customBounds ?: Rect(
                metrics.widthPixels - winW - 40,
                (metrics.heightPixels - winH) / 2,
                metrics.widthPixels - 40,
                (metrics.heightPixels + winH) / 2
            )
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            return try {
                val options = ActivityOptions.makeBasic()
                options.setLaunchBounds(bounds)
                val m = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                m.invoke(options, WINDOWING_MODE_FREEFORM)
                context.startActivity(targetIntent, options.toBundle())
                true
            } catch (_: Exception) {
                context.startActivity(targetIntent)
                false
            }
        }
    }

    fun requestOverlayPermissionIfNeeded(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun ensureFreeformEnabled(context: Context): Boolean = try {
        Settings.Global.putInt(context.contentResolver, "enable_freeform_support", 1)
        Settings.Global.putInt(context.contentResolver, "force_resizable_activities", 1)
        true
    } catch (_: Exception) { false }
}
