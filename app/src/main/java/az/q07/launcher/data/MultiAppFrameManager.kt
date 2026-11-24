package az.q07.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

data class AppFrameSlot(
    val slotIndex: Int,
    val packageName: String,
    val appLabel: String
)

enum class FrameGridLayoutMode(val label: String, val slotCount: Int) {
    TWO_SIDE_BY_SIDE("2 Çərçivə (Yan-yana)", 2),
    THREE_COLUMNS("3 Çərçivə (3 Sütun)", 3),
    FOUR_GRID("4 Çərçivə (2x2 Tor)", 4)
}

object MultiAppFrameManager {

    private const val PREFS_NAME = "q07_multi_app_frame_prefs"
    private const val KEY_LAYOUT_MODE = "frame_grid_layout_mode"
    private const val KEY_SLOTS_JSON = "frame_slots_json"

    private val DEFAULT_SLOTS = listOf(
        AppFrameSlot(0, "com.waze", "Waze"),
        AppFrameSlot(1, "com.google.android.youtube", "YouTube"),
        AppFrameSlot(2, "com.android.settings", "Ayarlar"),
        AppFrameSlot(3, "com.zerotier.one", "ZeroTier")
    )

    fun getLayoutMode(context: Context): FrameGridLayoutMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT_MODE, FrameGridLayoutMode.THREE_COLUMNS.name)
        return runCatching { FrameGridLayoutMode.valueOf(name ?: "") }
            .getOrDefault(FrameGridLayoutMode.THREE_COLUMNS)
    }

    fun saveLayoutMode(context: Context, mode: FrameGridLayoutMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAYOUT_MODE, mode.name).apply()
    }

    fun getSlots(context: Context): List<AppFrameSlot> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SLOTS_JSON, null) ?: return DEFAULT_SLOTS
        return try {
            val arr = JSONArray(raw)
            val loaded = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AppFrameSlot(
                    slotIndex = o.optInt("idx", i),
                    packageName = o.optString("pkg", ""),
                    appLabel = o.optString("lbl", "Proqram Seç")
                )
            }
            if (loaded.size < 4) {
                loaded + DEFAULT_SLOTS.drop(loaded.size)
            } else loaded
        } catch (_: Exception) {
            DEFAULT_SLOTS
        }
    }

    fun updateSlot(context: Context, slotIndex: Int, packageName: String, appLabel: String): List<AppFrameSlot> {
        val updated = getSlots(context).map {
            if (it.slotIndex == slotIndex) AppFrameSlot(slotIndex, packageName, appLabel) else it
        }
        val arr = JSONArray()
        updated.forEach { s ->
            arr.put(JSONObject().apply {
                put("idx", s.slotIndex)
                put("pkg", s.packageName)
                put("lbl", s.appLabel)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SLOTS_JSON, arr.toString()).apply()
        return updated
    }

    fun getLaunchableApps(context: Context): List<InstalledAppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        return resolveList
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledAppEntry(
                    packageName = pkg,
                    label = ri.loadLabel(pm)?.toString() ?: pkg,
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchSlotInExactFrame(
        context: Context,
        slot: AppFrameSlot,
        frameBoundsOnScreen: Rect,
        fullscreen: Boolean = false
    ): Boolean {
        if (slot.packageName.isBlank()) return false
        val mode = if (fullscreen) WindowDisplayMode.FULLSCREEN else WindowDisplayMode.MINI_WINDOW
        return FreeformWindowLauncher.launchAppWithMode(
            context = context,
            candidatePackages = listOf(slot.packageName),
            mode = mode,
            appDisplayName = slot.appLabel,
            customBounds = if (fullscreen) null else frameBoundsOnScreen
        )
    }
}
