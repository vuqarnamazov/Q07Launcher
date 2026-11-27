package az.q07.launcher.scanner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Launcher-in 1 toxunuşla işlədə bildiyi öyrənilmiş funksiya / gedişat */
data class LearnedFunction(
    val id: String,
    val name: String,
    val steps: List<FlowStep>,
    val sourceFlowId: String? = null,
    val fingerprint: String = RecordedFlow.fingerprintOf(steps),
    val stepDelayMs: Long = 800,
    val runs: Int = 0,
    /** kokpitin sürətli idarəetmə zolağında göstərilsin */
    val pinned: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    val isFlow: Boolean get() = steps.size > 1

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("src", sourceFlowId ?: "").put("fp", fingerprint)
        .put("delay", stepDelayMs).put("runs", runs).put("pin", pinned).put("added", addedAt)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): LearnedFunction {
            val a = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until a.length()).map { FlowStep.fromJson(a.getJSONObject(it)) }
            return LearnedFunction(
                id = o.getString("id"), name = o.optString("name"), steps = steps,
                sourceFlowId = o.optString("src").ifBlank { null },
                fingerprint = o.optString("fp").ifBlank { RecordedFlow.fingerprintOf(steps) },
                stepDelayMs = o.optLong("delay", 800), runs = o.optInt("runs"), pinned = o.optBoolean("pin"), addedAt = o.optLong("added")
            )
        }
    }
}

/** Fayl: filesDir/learned_functions.json (AES-256-GCM ilə şifrələnmiş) */
object LearnedFunctionsRepository {
    private const val FILE = "learned_functions.json"

    private val _items = MutableStateFlow<List<LearnedFunction>>(emptyList())
    val items: StateFlow<List<LearnedFunction>> = _items

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        _items.value = runCatching {
            if (!f.exists()) emptyList() else {
                val raw = f.readText()
                // AES-256 şifrələnmiş fayl; köhnə açıq JSON da oxunur (ilk yazılışda şifrələnir)
                val json = if (FlowCrypto.isEncrypted(raw)) FlowCrypto.decrypt(raw) ?: "[]" else raw
                val a = JSONArray(json)
                (0 until a.length()).mapNotNull { runCatching { LearnedFunction.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /** Eyni addımlar artıq varsa — mövcud olanı qaytarır (dublikat yoxdur) */
    @Synchronized
    fun add(name: String, steps: List<FlowStep>, sourceFlowId: String?, pinned: Boolean = false): Pair<LearnedFunction, Boolean> {
        val fp = RecordedFlow.fingerprintOf(steps)
        _items.value.firstOrNull { it.fingerprint == fp }?.let { old ->
            if (pinned && !old.pinned) setPinned(old.id, true)
            return old to false
        }
        val f = LearnedFunction(UUID.randomUUID().toString(), name, steps, sourceFlowId, fp, pinned = pinned)
        _items.value = _items.value + f
        save()
        return f to true
    }

    fun contains(fingerprint: String) = _items.value.any { it.fingerprint == fingerprint }

    @Synchronized
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        _items.value = _items.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        save()
    }

    @Synchronized
    fun setDelay(id: String, ms: Long) {
        _items.value = _items.value.map { if (it.id == id) it.copy(stepDelayMs = ms.coerceIn(200, 5000)) else it }
        save()
    }

    @Synchronized
    fun setPinned(id: String, on: Boolean) {
        _items.value = _items.value.map { if (it.id == id) it.copy(pinned = on) else it }
        save()
    }

    @Synchronized
    fun markRun(id: String) {
        _items.value = _items.value.map { if (it.id == id) it.copy(runs = it.runs + 1) else it }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val f = file ?: return
        val snapshot = _items.value
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, "$FILE.tmp")
                val json = JSONArray().apply { snapshot.forEach { put(it.toJson()) } }.toString()
                // AES-256-GCM: açar Android Keystore-da, faylda yalnız şifrəli mətn
                val payload = FlowCrypto.encrypt(json) ?: json
                tmp.writeText(payload)
                tmp.renameTo(f)
            }
        }
    }
}
