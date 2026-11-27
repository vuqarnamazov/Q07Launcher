package az.q07.launcher.data

import android.content.Context
import az.q07.launcher.scanner.FlowImporter
import az.q07.launcher.scanner.FlowStep
import az.q07.launcher.scanner.FullVehicleScannerManager
import az.q07.launcher.scanner.LearnedFunction
import az.q07.launcher.scanner.LearnedFunctionsRepository
import az.q07.launcher.scanner.RecordedFlow
import az.q07.launcher.scanner.SystemKnowledgeReport
import az.q07.launcher.scanner.VehicleStructureRepository

/**
 * «Mühərriki işə sal» / «söndür» düyməsinə skanerdən öyrənilmiş yolu bağlayır.
 *
 * Skaner hər basılan düyməni və keçilən ekranı «Gedişatlar» tabına və loqa yazır.
 * Bu obyekt həmin qeydlərdən mühərrikə aid olanı tapır — Pay Charging / doldurma
 * rejimində başladılan yol (+40 bal) üstün tutulur. Seçilən gedişat
 * [VehicleEngineController] prefs-inə yazılır, START/STOP onu təkrar icra edir.
 */
object EngineFlowBinding {

    private const val ENERGY_PKG = "com.incall.apps.energy"
    private const val DISCHARGE_ID = "btn_discharge"

    data class Candidate(
        val name: String,
        val steps: List<FlowStep>,
        val sourceFlowId: String?,
        val score: Int,
        val charging: Boolean
    ) {
        val fingerprint: String get() = RecordedFlow.fingerprintOf(steps)
    }

    private val START_WORDS = listOf("ise sal", "start", "baslat", "ready", "power on", "engine on", "engine start", "start engine", "muharrik", "启动", "开启")
    private val STOP_WORDS = listOf("sondur", "stop", "dayandir", "power off", "engine off", "关闭")
    /** son addımı «Aç / On / Enable» olan yollar (Pay Charging rejimində enerji verilişi) */
    private val ON_WORDS = listOf("ac", "on", "enable", "aktiv")
    private val OFF_WORDS = listOf("sondur", "off", "deaktiv", "dayandir")
    /** Pay Charging / doldurma rejimi */
    private val CHARGE_WORDS = listOf("doldur", "charge", "charg", "sarj", "pay", "充电")

    private fun hay(steps: List<FlowStep>) = FlowImporter.normalize(
        steps.joinToString(" ") { "${it.text} ${it.contentDescription} ${it.viewIdResName} ${it.className} ${it.packageName}" }
    )

    fun isCharging(steps: List<FlowStep>): Boolean {
        val h = hay(steps)
        return CHARGE_WORDS.any { h.contains(it) }
    }

    /** 0-dan böyük bal = bu gedişat mühərrik üçün namizəddir. */
    fun score(steps: List<FlowStep>, start: Boolean): Int {
        if (steps.isEmpty()) return 0
        val h = hay(steps)
        val startHit = START_WORDS.any { h.contains(it) }
        val stopHit = STOP_WORDS.any { h.contains(it) }
        val chargeHit = CHARGE_WORDS.any { h.contains(it) }
        var s = 0
        if (start) {
            // Mühərrik yolu üçün «işə sal» əlaməti mütləqdir — tək doldurma açarı START sayılmır
            if (!startHit) return 0
            s += 30
            if (stopHit) s -= 25
        } else {
            if (!stopHit) return 0
            s += 30
            if (startHit) s -= 25
        }
        // Pay Charging / doldurma rejimində başladılan yol üstün tutulur
        if (chargeHit) s += 15
        if (steps.size > 6) s -= (steps.size - 6) * 2
        return s
    }

    /**
     * Ekran xəritəsindəki düymədən sadə yol qurur: ekranı aç + düyməni bas.
     * Düymə hələ əl ilə basılmayıbsa belə STOP/START üçün işləyir (məs. «Söndür»).
     */
    fun fallbackFromScreenMap(context: Context, start: Boolean): Candidate? {
        val want = if (start) ON_WORDS else OFF_WORDS
        val avoid = if (start) OFF_WORDS else ON_WORDS
        VehicleStructureRepository.init(context)
        val screens = VehicleStructureRepository.screens.value.values
            .filter { SystemKnowledgeReport.isSystemApp(it.packageName) }
            .sortedByDescending { it.packageName.contains("energy") }
        for (s in screens) {
            val node = s.nodes.filter { it.isClickable }.firstOrNull { n ->
                val words = FlowImporter.normalize(
                    listOf(n.text, n.contentDescription, n.viewIdResName.substringAfter(":id/")).joinToString(" ")
                ).split(' ').filter { it.isNotBlank() }
                want.any { it in words } && avoid.none { it in words }
            } ?: continue
            val steps = listOf(
                FlowStep(FlowStep.Type.WINDOW, s.packageName, s.className),
                node.toStep(s.packageName)
            )
            val charge = isCharging(steps)
            return Candidate(
                "${FullVehicleScannerManager.appLabel(context, s.packageName)}: ${node.label()}",
                steps, null, 25 + (if (charge) 15 else 0), charge
            )
        }
        return null
    }

    fun fromFlow(flow: RecordedFlow, start: Boolean) =
        Candidate(flow.name, flow.steps, flow.id, score(flow.steps, start), isCharging(flow.steps))

    /**
     * Ehtiyat yol: doldurma/enerji ekranında son addımı «Aç» (və ya «Söndür») olan qeyd.
     * Pay Charging rejimində mühərriki işə salan yol bu formada yazılır.
     */
    fun fallbackChargingFlow(start: Boolean): RecordedFlow? {
        val want = if (start) ON_WORDS else OFF_WORDS
        val avoid = if (start) OFF_WORDS else ON_WORDS
        return VehicleStructureRepository.flows.value
            .filter { it.steps.isNotEmpty() && isCharging(it.steps) }
            .filter { f ->
                val words = FlowImporter.normalize(f.steps.last().label()).split(' ').filter { it.isNotBlank() }
                want.any { it in words } && avoid.none { it in words }
            }
            .sortedWith(compareByDescending<RecordedFlow> { it.count }.thenBy { it.steps.size })
            .firstOrNull()
    }

    /** Skaner bazasındakı (və launcher-ə götürülmüş) namizədlər — ən uyğunu birinci. */
    fun candidates(start: Boolean, limit: Int = 6): List<Candidate> {
        val recorded = VehicleStructureRepository.flows.value
            .filter { it.steps.isNotEmpty() && it.steps.all { s -> SystemKnowledgeReport.isSystemApp(s.packageName) } }
            .map { Candidate(it.name, it.steps, it.id, score(it.steps, start), isCharging(it.steps)) }
        val learned = LearnedFunctionsRepository.items.value
            .map { Candidate(it.name, it.steps, it.sourceFlowId, score(it.steps, start), isCharging(it.steps)) }
        return (recorded + learned)
            .filter { it.score > 0 && it.steps.isNotEmpty() }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.charging }.thenBy { it.steps.size })
            .distinctBy { it.fingerprint }
            .take(limit)
    }

    /** Launcher-ə götürür və START/STOP düyməsinə bağlayır. */
    fun assign(context: Context, c: Candidate, start: Boolean): LearnedFunction {
        LearnedFunctionsRepository.init(context)
        val (fn, _) = LearnedFunctionsRepository.add(c.name, c.steps, c.sourceFlowId)
        return bind(context, fn, start, c.charging)
    }

    fun bind(context: Context, fn: LearnedFunction, start: Boolean, charging: Boolean = isCharging(fn.steps)): LearnedFunction {
        VehicleEngineController.setFlow(context, start, fn.id)
        SelfDiag.log(
            "engine",
            if (start) "START gedişatı bağlandı" else "STOP gedişatı bağlandı",
            mapOf("flow" to fn.name, "steps" to fn.steps.size, "pay_charging" to charging)
        )
        SystemKnowledgeReport.refresh(context, force = true)
        return fn
    }

    /**
     * Mühərrik düyməsinin yolu — TƏXMİN DEYİL, skanerin yazdığı ən son əl basışı.
     *
     * İstifadəçi enerji pəncərəsi açıq ikən düyməni əl ilə basır; skaner həmin klik
     * addımını yazır (paket + view-id + mətn + koordinat). Elə həmin addım birbaşa
     * ON/OFF düyməsinə bağlanır — səhifə gəzintisi, `btn_discharge` ehtimalı yoxdur.
     */
    fun engineToggleFlow(context: Context): LearnedFunction? {
        LearnedFunctionsRepository.init(context)
        VehicleStructureRepository.init(context)
        val manual = VehicleStructureRepository.flows.value
            .filter { f ->
                f.steps.size == 1 && f.steps[0].type == FlowStep.Type.CLICK && f.steps[0].packageName == ENERGY_PKG
            }
            .maxByOrNull { it.lastSeen }
        val step = manual?.steps?.firstOrNull()
            ?: VehicleStructureRepository.screens.value.values
                .filter { it.packageName == ENERGY_PKG }
                .flatMap { it.nodes }
                .firstOrNull { it.viewIdResName.endsWith(":id/$DISCHARGE_ID") }
                ?.toStep(ENERGY_PKG)
            ?: return null
        val (fn, _) = LearnedFunctionsRepository.add(
            manual?.name ?: "Enerji mərkəzi: əl ilə basılan düymə", listOf(step), null, pinned = true
        )
        return fn
    }

    /**
     * START/STOP gedişatı hələ bağlanmayıbsa (və ya bağlanmış yol artıq mühərrikə uyğun deyilsə) —
     * skanerdəki ən uyğun yolu (Pay Charging daxil) avtomatik bağlayır.
     */
    fun autoAssign(context: Context, start: Boolean = true): LearnedFunction? {
        LearnedFunctionsRepository.init(context)
        VehicleStructureRepository.init(context)
        // Enerji mərkəzi ekranı öyrənilibsə — hər iki istiqamət üçün eyni «pəncərə + düymə» yolu
        engineToggleFlow(context)?.let { fn ->
            if (VehicleEngineController.getFlowId(context, start) == fn.id) return null
            return bind(context, fn, start, charging = true)
        }
        val currentId = VehicleEngineController.getFlowId(context, start)
        val current = currentId?.let { id -> LearnedFunctionsRepository.items.value.firstOrNull { it.id == id } }
        // Skanerdə real qeyd varsa — ekran xəritəsindən qurulmuş yoldan üstündür: onu bağla
        candidates(start, 1).firstOrNull()?.let { best ->
            if (best.fingerprint != current?.fingerprint) return assign(context, best, start)
        }
        if (current != null) return null
        fallbackChargingFlow(start)?.let { f -> return assign(context, fromFlow(f, start), start) }
        fallbackFromScreenMap(context, start)?.let { c -> return assign(context, c, start) }
        return null
    }
}
