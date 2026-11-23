package az.q07.launcher.scanner

import android.content.Context

/**
 * «Funksiya / Gedişat Götür və Əlavə Et».
 * Skaner bazasından ([VehicleStructureRepository]) sorğu ilə:
 *  - qeydə alınmış funksiya və gedişatları ([RecordedFlow]),
 *  - ekran xəritəsindəki düymələri — öyrənilmiş keçid yolu ilə birlikdə ([ActionPath])
 * tapır və [LearnedFunctionsRepository]-yə (istəsən, kokpitin sürətli idarəetməsinə də) əlavə edir.
 */
object FlowImporter {

    sealed class Candidate {
        abstract val name: String
        abstract val steps: List<FlowStep>
        abstract val score: Int
        abstract val fingerprint: String
        abstract val subtitle: String

        data class Recorded(val flow: RecordedFlow, override val score: Int) : Candidate() {
            override val name get() = flow.name
            override val steps get() = flow.steps
            override val fingerprint get() = flow.fingerprint
            override val subtitle get() = "${flow.kind.label} · ×${flow.count} · ${flow.steps.size} addım"
        }

        data class Button(val screen: ScreenNodeMap, val node: NodeInfo, val path: ActionPath, val appLabel: String, override val score: Int) : Candidate() {
            override val name get() = "$appLabel: ${node.label()}"
            override val steps get() = path.steps
            override val fingerprint get() = RecordedFlow.fingerprintOf(path.steps)
            override val subtitle get() = "Düymə · ${screen.shortName} · yol ${path.steps.size} addım" +
                (if (path.complete) "" else " (yolun bir hissəsi naməlum)") + " · ${node.clickCount} klik / ${node.seenCount} görülüb"
        }
    }

    enum class Source { ALL, FUNCTIONS, FLOWS, BUTTONS;
        val label: String get() = when (this) { ALL -> "Hamısı"; FUNCTIONS -> "Funksiyalar"; FLOWS -> "Gedişatlar"; BUTTONS -> "Düymələr (xəritə)" }
    }

    fun search(context: Context, query: String, source: Source = Source.ALL, limit: Int = 40): List<Candidate> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        val out = mutableListOf<Candidate>()

        if (source != Source.BUTTONS) {
            VehicleStructureRepository.flows.value
                .filter { source == Source.ALL || (source == Source.FUNCTIONS) == (it.kind == RecordedFlow.Kind.FUNCTION) }
                .forEach { f ->
                    val hay = buildString {
                        f.packages.forEach { append(normalize(FullVehicleScannerManager.appLabel(context, it))).append(' ').append(it.lowercase()).append(' ') }
                        f.steps.forEach { append(stepHay(it)) }
                    }
                    score(words, normalize(f.name), hay)?.let { s -> out += Candidate.Recorded(f, s + minOf(f.count, 20)) }
                }
        }
        if (source == Source.ALL || source == Source.BUTTONS) {
            // boş sorğuda yalnız ən çox istifadə olunan düymələr (bütün xəritə çox böyükdür)
            VehicleStructureRepository.screens.value.values.forEach { scr ->
                val app = FullVehicleScannerManager.appLabel(context, scr.packageName)
                scr.nodes.filter { it.isClickable }.forEach { n ->
                    if (words.isEmpty() && n.clickCount == 0) return@forEach
                    val hay = "${normalize(app)} ${scr.packageName.lowercase()} ${scr.shortName.lowercase()} " +
                        n.viewIdResName.substringAfter(":id/").lowercase().replace('_', ' ')
                    score(words, normalize(n.label()), hay)?.let { s ->
                        val path = VehicleStructureRepository.findPath(scr.id, n.key) ?: return@let
                        out += Candidate.Button(scr, n, path, app, s + minOf(n.clickCount * 2, 20) + if (path.complete) 3 else 0)
                    }
                }
            }
        }
        // eyni addımlar (məs. klik funksiyası = düymə yolu) — yalnız ən yüksək bal qalsın
        return out.sortedByDescending { it.score }.distinctBy { it.fingerprint }.take(limit)
    }

    /** Əlavə et: (öyrənilmiş funksiya, yenidirmi) */
    fun import(c: Candidate, customName: String? = null, pinToQuick: Boolean = false): Pair<LearnedFunction, Boolean> =
        LearnedFunctionsRepository.add(
            customName?.takeIf { it.isNotBlank() } ?: c.name, c.steps,
            (c as? Candidate.Recorded)?.flow?.id, pinToQuick
        )

    /** Sorğu ilə ən uyğununu tap və birbaşa əlavə et */
    fun importBest(context: Context, query: String, source: Source = Source.ALL, pinToQuick: Boolean = false): Pair<LearnedFunction, Boolean>? =
        search(context, query, source, 1).firstOrNull()?.let { import(it, null, pinToQuick) }

    fun isAdded(c: Candidate) = LearnedFunctionsRepository.contains(c.fingerprint)

    private fun stepHay(s: FlowStep) = "${normalize(s.text)} ${normalize(s.contentDescription)} " +
        "${s.viewIdResName.substringAfter(":id/").lowercase().replace('_', ' ')} ${s.className.substringAfterLast('.').lowercase()} "

    /** bütün sözlər tapılmalıdır; adda tapılan daha çox bal alır */
    private fun score(words: List<String>, name: String, hay: String): Int? {
        if (words.isEmpty()) return 0
        var s = 0
        val nameWords = name.split(' ')
        for (w in words) s += when {
            nameWords.any { it == w } -> 30
            name.contains(w) -> 20
            hay.contains(w) -> 8
            else -> return null
        }
        return s
    }

    fun normalize(s: String): String = s.lowercase()
        .replace('ə', 'e').replace('ı', 'i').replace('ö', 'o').replace('ü', 'u')
        .replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
}
