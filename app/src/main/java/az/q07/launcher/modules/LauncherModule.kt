package az.q07.launcher.modules

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Orta paneldəki hər vidjet kartı bu interfeysi tətbiq edir.
 *
 * YENİ MODUL ƏLAVƏ ETMƏK:
 *  1) `object MyModule : LauncherModule { ... }` yarat
 *  2) Aşağıdakı ModuleRegistry.modules siyahısına əlavə et
 */
interface LauncherModule {
    val id: String
    val title: String
    val icon: ImageVector
    val accent: Color

    /** Tab-bar üçün qısa ad */
    val shortTitle: String get() = title.substringBefore(" ")

    /** Kart bağlı olanda görünən qısa məzmun */
    @Composable
    fun Summary()

    /** Kart açılanda əlavə görünən ətraflı məzmun */
    @Composable
    fun Expanded()
}

object ModuleRegistry {
    val modules: List<LauncherModule> = listOf(
        CarModule,
        MediaModule,
        WeatherModule,
        TripModule,
        WidgetsModule,
        VoiceNoteModule,
        NetworkModule,
        SystemInfoModule,
        // MyModule,  ← yeni modulları bura əlavə et
    )

    /** Ayarlardakı sıraya görə, söndürülənlər çıxılmaqla */
    fun allOrdered(): List<LauncherModule> {
        val order = az.q07.launcher.data.AppSettings.moduleOrder
        return modules.sortedBy { m -> order.indexOf(m.id).let { if (it < 0) 1000 + modules.indexOf(m) else it } }
    }

    fun visible(): List<LauncherModule> =
        allOrdered().filter { it.id !in az.q07.launcher.data.AppSettings.disabledModules }
}
