package az.q07.launcher.data

import android.content.Context
import az.q07.launcher.scanner.FlowStep
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository

/** «Öyrənilmiş Yollar» panelindəki idarə qrupu. */
enum class PathControlGroup(val titleAz: String) {
    ENGINE("Mühərrik"),
    LIGHTS("İşıqlar"),
    DOORS("Qapılar"),
    CLIMATE("Kondisioner"),
    WINDOWS("Pəncərələr")
}

/** Bir idarə: sabit id, qrupu və istifadəçiyə görünən adı. */
data class PathControl(
    val id: String,
    val group: PathControlGroup,
    val title: String
)

/**
 * «Öyrənilmiş Yollar» — hər idarəyə skanerdən öyrənilmiş funksiya/gedişat bağlayır.
 *
 * Bağlama yalnız funksiyanın id-sini saxlayır; addımlar [LearnedFunctionsRepository]-də qalır.
 * Panel: Öyrən / Test / Sil / Əvəz et və maskalanmış yol görünüşü.
 */
object VehicleControlBindings {

    private const val PREFS = "q07_control_bindings"
    private const val PREFIX = "ctl_"

    /** Panelin göstərdiyi bütün idarələr. */
    val controls: List<PathControl> = listOf(
        PathControl("engine_start", PathControlGroup.ENGINE, "Mühərrik START"),
        PathControl("engine_stop", PathControlGroup.ENGINE, "Mühərrik STOP"),

        PathControl("lights_on", PathControlGroup.LIGHTS, "İşıqlar ON"),
        PathControl("lights_off", PathControlGroup.LIGHTS, "İşıqlar OFF"),
        PathControl("high_beam", PathControlGroup.LIGHTS, "Uzaq işıq"),
        PathControl("fog_lights", PathControlGroup.LIGHTS, "Duman işıqları"),
        PathControl("hazard_lights", PathControlGroup.LIGHTS, "Qəza işıqları"),

        PathControl("doors_lock", PathControlGroup.DOORS, "Qapıları bağla"),
        PathControl("doors_unlock", PathControlGroup.DOORS, "Qapıları aç"),
        PathControl("trunk", PathControlGroup.DOORS, "Baqaj qapağı"),

        PathControl("ac_on", PathControlGroup.CLIMATE, "Kondisioner ON"),
        PathControl("ac_off", PathControlGroup.CLIMATE, "Kondisioner OFF"),
        PathControl("ac_temp_up", PathControlGroup.CLIMATE, "Temperatur +"),
        PathControl("ac_temp_down", PathControlGroup.CLIMATE, "Temperatur −"),
        PathControl("defrost", PathControlGroup.CLIMATE, "Şüşə buxarı"),

        PathControl("windows_up", PathControlGroup.WINDOWS, "Pəncərələri bağla"),
        PathControl("windows_down", PathControlGroup.WINDOWS, "Pəncərələri aç"),
        PathControl("sunroof", PathControlGroup.WINDOWS, "Panoram dam")
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun boundFunctionId(context: Context, controlId: String): String? =
        prefs(context).getString(PREFIX + controlId, "")?.ifBlank { null }

    /** Funksiyanı idarəyə bağlayır (əvvəlki bağlama əvəz olunur). */
    fun bind(context: Context, controlId: String, functionId: String) {
        prefs(context).edit().putString(PREFIX + controlId, functionId).apply()
    }

    /** İdarənin yolunu silir (öyrənilmiş funksiyanın özü skanerdə qalır). */
    fun unbind(context: Context, controlId: String) {
        prefs(context).edit().remove(PREFIX + controlId).apply()
    }

    fun boundFunction(context: Context, controlId: String): LearnedFunction? {
        val id = boundFunctionId(context, controlId) ?: return null
        LearnedFunctionsRepository.init(context)
        return LearnedFunctionsRepository.items.value.firstOrNull { it.id == id }
    }

    fun boundCount(context: Context): Int =
        controls.count { boundFunctionId(context, it.id) != null }

    /**
     * Yolu ekranda göstərmək üçün maskalayır: prefiks qalır,
     * paket/açar adının yalnız ilk 4 və son 3 simvolu görünür.
     */
    fun maskPath(rawPath: String): String {
        if (rawPath.isBlank()) return ""
        val separator = rawPath.indexOf(':')
        val prefix = if (separator >= 0) rawPath.substring(0, separator + 1) else ""
        val body = if (separator >= 0) rawPath.substring(separator + 1) else rawPath
        if (body.length <= 7) return prefix + "•".repeat(body.length.coerceAtLeast(4))
        return prefix + body.take(4) + "••••••" + body.takeLast(3)
    }

    /** Funksiyanın son klik addımından maskalanmış yol görünüşü. */
    fun maskedPath(function: LearnedFunction?): String {
        if (function == null) return ""
        val step: FlowStep = function.steps.lastOrNull { it.type == FlowStep.Type.CLICK }
            ?: function.steps.lastOrNull()
            ?: return ""
        val node = step.viewIdResName.substringAfter(":id/")
            .ifBlank { step.text }
            .ifBlank { step.contentDescription }
            .ifBlank { step.className.substringAfterLast('.') }
        return maskPath("UI_NODE:${step.packageName}|$node")
    }
}
