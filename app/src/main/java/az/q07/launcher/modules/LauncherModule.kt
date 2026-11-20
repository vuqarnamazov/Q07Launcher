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

    /** Kart bağlı olanda görünən qısa məzmun */
    @Composable
    fun Summary()

    /** Kart açılanda əlavə görünən ətraflı məzmun */
    @Composable
    fun Expanded()
}

object ModuleRegistry {
    val modules: List<LauncherModule> = listOf(
        VoiceNoteModule,
        NetworkModule,
        // MyModule,  ← yeni modulları bura əlavə et
    )
}
