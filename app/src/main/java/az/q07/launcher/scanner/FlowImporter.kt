package az.q07.launcher.scanner

import android.content.Context

/**
 * Skanerin bazasından launcher-ə funksiya/gedişat "çəkib" əlavə edir.
 *  - ad / açar söz ilə axtarış: [search], [importBest]
 *  - siyahıdan seçməklə: [import]
 * Əlavə olunan element [LearnedFunctionsRepository]-yə düşür və 1 toxunuşla işlədilir.
 */
object FlowImporter {

    data class Match(val flow: RecordedFlow, val score: Int, val alreadyAdded: Boolean)

    /** Açar sözlərə görə sıralanmış uyğunluqlar (ad, addım mətnləri, viewId, tətbiq adı daxil) */
    fun search(context: Context, query: String, kind: RecordedFlow.Kind? = null): List<Match> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        val all = RecordedFlowRepository.flows.value.filter { kind == null || it.kind == kind }
        if (words.isEmpty()) return all.sortedByDescending { it.count }
            .map { Match(it, 0, LearnedFunctionsRepository.contains(it.fingerprint)) }
        return all.mapNotNull { f ->
            val name = normalize(f.name)
            val hay = buildString {
                append(name).append(' ')
                f.packages.forEach { append(normalize(RecorderScannerManager.appLabel(context, it))).append(' ').append(it.lowercase()).append(' ') }
                f.steps.forEach {
                    append(normalize(it.text)).append(' ').append(normalize(it.contentDescription)).append(' ')
                    append(it.viewIdResName.substringAfter(":id/").lowercase().replace('_', ' ')).append(' ')
                    append(it.className.substringAfterLast('.').lowercase()).append(' ')
                }
            }
            var score = 0
            for (w in words) {
                score += when {
                    name.split(' ').any { it == w } -> 30
                    name.contains(w) -> 20
                    hay.contains(w) -> 8
                    else -> return@mapNotNull null // bütün sözlər tapılmalıdır
                }
            }
            score += minOf(f.count, 20)                          // tez-tez istifadə olunan önə
            if (f.kind == RecordedFlow.Kind.FUNCTION) score += 2  // eyni bal — tək addım sadədir
            Match(f, score, LearnedFunctionsRepository.contains(f.fingerprint))
        }.sortedByDescending { it.score }
    }

    /** Seçilmiş qeydi launcher-in icra siyahısına əlavə et. Qaytarır: (funksiya, yenidirmi) */
    fun import(flow: RecordedFlow, customName: String? = null): Pair<LearnedFunction, Boolean> =
        LearnedFunctionsRepository.add(customName?.takeIf { it.isNotBlank() } ?: flow.name, flow.steps, flow.id)

    fun import(flowId: String): Pair<LearnedFunction, Boolean>? = RecordedFlowRepository.get(flowId)?.let { import(it) }

    /** Ad/açar söz ilə ən uyğununu tap və birbaşa əlavə et */
    fun importBest(context: Context, query: String, kind: RecordedFlow.Kind? = null): Pair<LearnedFunction, Boolean>? =
        search(context, query, kind).firstOrNull()?.let { import(it.flow, null) }

    private fun normalize(s: String): String = s.lowercase()
        .replace('ə', 'e').replace('ı', 'i').replace('ö', 'o').replace('ü', 'u')
        .replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
}
