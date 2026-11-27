package az.q07.launcher.scanner

import android.content.Context
import az.q07.launcher.data.EngineFlowBinding
import az.q07.launcher.data.FactoryBridge
import az.q07.launcher.data.VehicleEngineController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bütün öyrənilənləri TƏK fayla yazır:  Android/data/az.q07.launcher/files/diag/system_knowledge.md
 *
 * Faylda:
 *  - hər tətbiq, hər ekran, hər düymə və onun NƏ ETDİYİ (→ hansı ekran / nə açılır),
 *  - hələ sınanmayan və ya nəticəsi bilinməyən düymələr,
 *  - yol tapılmayan ekranlar, söndürülmüş icazələr,
 *  - launcher-ə götürülmüş funksiya/gedişatlar.
 *
 * Fayl fon izləməsi işlədikcə avtomatik yenilənir — skaner ekranı bağlı olsa da.
 */
object SystemKnowledgeReport {
    private const val FILE = "system_knowledge.md"
    private const val MIN_GAP_MS = 4000L
    private const val MAX_MISSING = 200

    data class Missing(val kind: String, val where: String, val what: String)

    data class Summary(
        val screens: Int = 0,
        val buttons: Int = 0,
        val knownButtons: Int = 0,
        val flows: Int = 0,
        val missing: Int = 0,
        val path: String = "",
        val updatedAt: Long = 0L
    ) {
        val percent: Int get() = if (buttons == 0) 0 else knownButtons * 100 / buttons
    }

    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary

    private val _missing = MutableStateFlow<List<Missing>>(emptyList())
    val missing: StateFlow<List<Missing>> = _missing

    private var dir: File? = null
    private var lastAt = 0L
    private var lastHash = 0

    fun init(context: Context) {
        if (dir == null) {
            dir = (context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")).apply { mkdirs() }
        }
    }

    /** Hesabatı (tələb olunarsa dərhal) yeniləyir və fayla yazır. */
    @Synchronized
    fun refresh(context: Context, force: Boolean = false): Summary {
        init(context)
        val now = System.currentTimeMillis()
        if (!force && now - lastAt < MIN_GAP_MS) return _summary.value
        lastAt = now
        val text = build(context)
        val d = dir ?: return _summary.value
        val file = File(d, FILE)
        if (text.hashCode() != lastHash) {
            lastHash = text.hashCode()
            runCatching {
                val tmp = File(d, "$FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
            runCatching { File(context.filesDir, FILE).writeText(text) }
        }
        val s = _summary.value.copy(path = file.absolutePath, updatedAt = now)
        _summary.value = s
        return s
    }

    fun read(context: Context): String = runCatching {
        File(dir ?: (context.getExternalFilesDir("diag") ?: context.filesDir), FILE).readText()
    }.getOrDefault("Hesabat hələ yaradılmayıb — skaneri aç və bir az gəz.")

    // ───────────────────────── hesabatın özü ─────────────────────────

    /** Maşının öz sistemi: zavod tətbiqləri, avtomobil ayarları, sistem UI. Media tətbiqləri daxil edilmir. */
    private val SYSTEM_HINTS = listOf(
        "changan", "qiyuan", "incall", "vehicle", "hvac", "cluster", "avm", "adas", "tbox", "evs", "sda", "launcher"
    )

    fun isSystemApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p == "com.android.systemui" || p.startsWith("com.android.car")) return true
        if (p.contains("permissioncontroller") || p.startsWith("az.q07.")) return false
        return SYSTEM_HINTS.any { p.contains(it) }
    }

    private fun build(context: Context): String {
        val allScreens = VehicleStructureRepository.screens.value.values.toList()
        val screens = allScreens.filter { isSystemApp(it.packageName) }
        val skipped = allScreens.size - screens.size
        val edges = VehicleStructureRepository.edges.value
        val flows = VehicleStructureRepository.flows.value.filter { f -> f.steps.all { isSystemApp(it.packageName) } }
        val learned = LearnedFunctionsRepository.items.value.filter { f -> f.steps.all { isSystemApp(it.packageName) } }
        val startFn = LearnedFunctionsRepository.items.value.firstOrNull { it.id == VehicleEngineController.getFlowId(context, true) }
        val stopFn = LearnedFunctionsRepository.items.value.firstOrNull { it.id == VehicleEngineController.getFlowId(context, false) }
        val missing = mutableListOf<Missing>()
        var buttons = 0
        var known = 0

        val body = StringBuilder()
        screens.groupBy { it.packageName }.toSortedMap().forEach { (pkg, list) ->
            body.appendLine()
            body.appendLine("## ${FullVehicleScannerManager.appLabel(context, pkg)} ($pkg)")
            list.sortedBy { it.className }.forEach { s ->
                body.appendLine("### ${s.shortName}${if (s.entry) " · giriş ekranı" else ""} · ${s.seenCount} ziyarət")
                if (s.nodes.isEmpty()) body.appendLine("- (boş ekran oxunub)")
                s.nodes.filter { it.isClickable }.sortedByDescending { it.clickCount }.forEach { n ->
                    buttons++
                    val edge = edges.filter { it.fromScreen == s.id && it.action.nodeKey() == n.key }
                        .maxByOrNull { it.count }
                    val inFlow = flows.any { f ->
                        f.steps.any { it.type == FlowStep.Type.CLICK && it.packageName == s.packageName && it.nodeKey() == n.key }
                    }
                    val effect = when {
                        edge != null && edge.kind == NavigationEdge.Kind.NAVIGATE ->
                            "→ ${screens.firstOrNull { it.id == edge.toScreen }?.shortName ?: edge.toScreen.substringAfterLast('.')}"
                        edge != null ->
                            "→ açır: " + edge.revealed.take(4).joinToString(", ").ifBlank { "vəziyyət dəyişir" }
                        inFlow -> "→ launcher gedişatında işlədilir"
                        n.clickCount > 0 -> "→ nəticə bilinmir"
                        else -> "→ hələ sınanmayıb"
                    }
                    if (edge != null || inFlow) known++
                    val label = n.label().ifBlank { "(adsız düymə)" }
                    val id = n.viewIdResName.substringAfter(":id/").ifBlank { n.className.substringAfterLast('.') }
                    body.appendLine("- $effect | \"$label\" · klik ${n.clickCount} · $id")
                    val where = "${FullVehicleScannerManager.appLabel(context, pkg)} → ${s.shortName}"
                    when {
                        edge == null && n.clickCount > 0 -> missing += Missing("Nəticə bilinmir", where, label)
                        n.clickCount == 0 -> missing += Missing("Sınanmayıb", where, label)
                    }
                }
                val reachable = s.entry || edges.any { it.toScreen == s.id && it.kind == NavigationEdge.Kind.NAVIGATE }
                if (!reachable) missing += Missing("Ekrana yol yoxdur", FullVehicleScannerManager.appLabel(context, pkg), s.shortName)
            }
        }

        if (!FactoryBridge.isEnabled(context)) {
            missing += Missing("İcazə", "Xüsusi imkan xidməti", "Söndürülüb — heç nə öyrənilmir")
        }
        if (FullVehicleScannerManager.mode.value == FullVehicleScannerManager.Mode.OFF) {
            missing += Missing("Skaner", "Rejim", "Söndürülüb — öyrənmir")
        }
        if (flows.isEmpty()) missing += Missing("Gedişat", "Ümumi", "Hələ heç bir funksiya/gedişat qeydə alınmayıb")
        if (startFn == null) missing += Missing("Mühərrik", "START düyməsi", "Gedişat bağlanmayıb — «Gedişatlar» tabı → «Mühərrik düyməsinə qoy»")
        if (stopFn == null) missing += Missing("Mühərrik", "STOP düyməsi", "Söndürmə gedişatı seçilməyib")

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val header = StringBuilder()
        header.appendLine("# Q07 — maşının öyrənilmiş sistemi")
        header.appendLine()
        header.appendLine("Yeniləndi: $fmt · rejim: ${FullVehicleScannerManager.mode.value.label}")
        header.appendLine()
        header.appendLine("## Xülasə")
        header.appendLine("- Ekran: ${screens.size} · Düymə: $buttons · Nəticəsi bilinən: $known" +
            (if (buttons > 0) " (${known * 100 / buttons}%)" else ""))
        header.appendLine("- Gedişat/funksiya: ${flows.size} · Launcher-ə götürülmüş: ${learned.size}")
        header.appendLine("- Mühərrik düyməsi: START «${startFn?.name ?: "seçilməyib"}» · STOP «${stopFn?.name ?: "seçilməyib"}»")
        header.appendLine("- Çatışmayan məlumat: ${missing.size}")
        if (skipped > 0) header.appendLine("- Kənar tətbiqlər (media və s.) hesabata daxil edilmir: $skipped ekran")
        header.appendLine()

        val tail = StringBuilder()
        tail.appendLine()
        tail.appendLine("## Çatışmayan məlumat (növbəti addımlar)")
        if (missing.isEmpty()) tail.appendLine("- Yoxdur ✓ — ekranlar, düymələr və nəticələr öyrənilib.")
        else missing.take(MAX_MISSING).forEachIndexed { i, m ->
            tail.appendLine("${i + 1}. [${m.kind}] ${m.where} — ${m.what}")
        }
        tail.appendLine()
        tail.appendLine("## Mühərrik düyməsi (START / STOP)")
        fun engineLine(prefix: String, fn: LearnedFunction?) {
            if (fn == null) { tail.appendLine("- $prefix: seçilməyib"); return }
            tail.appendLine("- $prefix: ${fn.name} · ${fn.steps.size} addım · ${fn.runs} dəfə")
            fn.steps.forEachIndexed { i, s ->
                tail.appendLine("  ${i + 1}. ${s.label()} · ${FullVehicleScannerManager.appLabel(context, s.packageName)}" +
                    (if (s.viewIdResName.isNotBlank()) " · ${s.viewIdResName.substringAfter(":id/")}" else ""))
            }
        }
        engineLine("START", startFn)
        engineLine("STOP", stopFn)
        if (stopFn == null) EngineFlowBinding.fallbackFromScreenMap(context, false)?.let { c ->
            tail.appendLine("- Söndürmə namizədi (ekran xəritəsi): ${c.name} · ${c.steps.size} addım")
        }
        tail.appendLine()
        tail.appendLine("## Mühərrik namizədləri (skaner qeydlərindən)")
        val candidates = EngineFlowBinding.candidates(true, 5)
        if (candidates.isEmpty()) tail.appendLine("- Yoxdur — maşının ekranında «işə sal» düyməsini bir dəfə bas.")
        else candidates.forEach { c ->
            tail.appendLine("- [${c.score}] ${c.name} · ${c.steps.size} addım" +
                (if (c.charging) " · doldurma / Pay Charging" else ""))
        }
        val chargeFlows = VehicleStructureRepository.flows.value
            .filter { f -> f.steps.isNotEmpty() && EngineFlowBinding.isCharging(f.steps) }
            .sortedByDescending { it.count }
            .take(15)
        tail.appendLine()
        tail.appendLine("## Doldurma / enerji gedişatları (skaner qeydləri)")
        if (chargeFlows.isEmpty()) tail.appendLine("- Yoxdur")
        else chargeFlows.forEach { f ->
            tail.appendLine("- ${f.name} · ×${f.count} · ${f.steps.size} addım")
            f.steps.forEachIndexed { i, s ->
                tail.appendLine("  ${i + 1}. ${s.label()} · ${FullVehicleScannerManager.appLabel(context, s.packageName)}" +
                    (if (s.viewIdResName.isNotBlank()) " · ${s.viewIdResName.substringAfter(":id/")}" else ""))
            }
        }
        tail.appendLine()
        tail.appendLine("## Launcher-ə götürülmüş funksiya / gedişatlar")
        if (learned.isEmpty()) tail.appendLine("- Yoxdur")
        else learned.forEach { tail.appendLine("- ${it.name} · ${it.steps.size} addım · ${it.runs} dəfə") }

        _missing.value = missing.take(MAX_MISSING)
        _summary.value = Summary(
            screens = screens.size, buttons = buttons, knownButtons = known,
            flows = flows.size, missing = missing.size
        )
        return header.toString() + body + tail
    }
}
