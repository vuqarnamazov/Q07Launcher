package az.q07.launcher.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Zavod «Ayarlar → Sürüş» səhifəsindəki idarəetmələr.
 * Changan SDK (com.incall.apps.sdkservice / ICaSdkService) yalnız uid=1000 tətbiqlərə yazmağa icazə verir,
 * ona görə dəyişiklik xüsusi imkan xidməti (DriveModeService) ilə zavod ekranında edilir.
 *
 * kind:
 *  TABS   – sekmələr (tv_tab_content), başlığın (anchorId) altındakı sırada, soldan sağa
 *  SLIDER – sürgü; seçim = faiz (percents), sürgü maskasının içində toxunuşla qurulur
 *  MASKS  – hər seçimin öz kliklənən maskası (maskIds); cari dəyər sürgünün «Value, N%»-dən oxunur
 */
enum class VehicleControl(
    val title: String,
    val kind: Kind,
    val anchorId: String,
    val options: List<String>,
    val maskIds: List<String> = emptyList(),
    val sliderMaskId: String? = null,
    val percents: List<Int> = emptyList()
) {
    DRIVE_MODE(
        "Sürüş rejimi (qaz pedalı)", Kind.TABS, "item_drive_mode_often_title",
        listOf("Komfort", "Standart", "Sport")
    ),
    ENERGY_RECOVERY(
        "Enerji bərpası", Kind.SLIDER, "tv_title_energy_recycler",
        listOf("Zəif", "Orta", "Güclü"),
        maskIds = listOf("v_standard_weak_lay_mask", "", "v_strong_lay_mask"),
        sliderMaskId = "v_slider_energy_recycler_mask", percents = listOf(0, 50, 100)
    ),
    SUSPENSION(
        "Asqı komfortu", Kind.TABS, "tv_title_suspension_strength",
        listOf("Comfort", "Standart", "Sport", "Sport+", "Smart")
    ),
    POWER_STEERING(
        "Sükan gücləndiricisi", Kind.MASKS, "tv_title_power_steering",
        listOf("Yüngül", "Standart", "Sabit"),
        maskIds = listOf("v_power_steering_light_mask", "v_power_steering_standard_mask", "v_ppower_steering_stability_mask"),
        sliderMaskId = "v_slider_power_steering_recycler_mask", percents = listOf(0, 50, 100)
    ),
    BRAKE_MODE(
        "Əyləc rejimi", Kind.TABS, "tv_title_break_mode",
        listOf("Komfort", "Standart", "Sport") // real adlar ekrandan oxunur (FactoryBridge.labels)
    );

    enum class Kind { TABS, SLIDER, MASKS }
}

object VehicleQuickControlHelper {

    /** Xüsusi imkan xidməti aktivdirmi */
    fun isServiceEnabled(context: Context): Boolean = FactoryBridge.isEnabled(context)

    /** Xidmət aktiv deyilsə istifadəçini Xüsusi imkanlar səhifəsinə göndər (bu maşında menyu olmaya bilər) */
    fun openAccessibilitySettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** Seçimin göstərilən adı (əyləc rejimində zavod ekranından oxunan ad) */
    fun label(control: VehicleControl, index: Int): String =
        FactoryBridge.labels.value[control]?.getOrNull(index) ?: control.options.getOrNull(index) ?: "#$index"

    /**
     * Funksiyanı icra et:
     *  1) xidmət aktiv deyilsə → false (UI openAccessibilitySettings təklif edir)
     *  2) zavod «Sürüş» səhifəsi artıq ekrandadırsa → dərhal klik (heç nə açılmır)
     *  3) əks halda → launcher görüntüsü örtük kimi qoyulur, vehiclesetting
     *     (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION) qısa açılır, düymə basılır, MainActivity önə qayıdır
     */
    fun execute(activity: Activity, control: VehicleControl, optionIndex: Int): Boolean {
        if (!isServiceEnabled(activity)) return false
        if (FactoryBridge.service?.tryDirect(control, optionIndex) == true) return true
        FactoryBridge.enqueue(activity, FactoryJob.Control(control, optionIndex))
        return true
    }

    /** Cari vəziyyəti səssizcə oxu */
    fun refresh(activity: Activity) {
        if (!isServiceEnabled(activity)) return
        FactoryBridge.enqueue(activity, FactoryJob.Control(VehicleControl.DRIVE_MODE, null))
    }
}
