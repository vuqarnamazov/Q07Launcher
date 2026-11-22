package az.q07.launcher.scanner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Skanerin topladığı bütün funksiya və gedişatların bazası.
 * Fayl: filesDir/recorded_flows.json
 * Eyni barmaq izi (fingerprint) olan hərəkət yenidən gələndə — yeni qeyd yox, sayğac artır.
 */
object RecordedFlowRepository {
    private const val FILE = "recorded_flows.json"
    private const val MAX_ITEMS = 500

    private val _flows = MutableStateFlow<List<RecordedFlow>>(emptyList())
    val flows: StateFlow<List<RecordedFlow>> = _flows

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        _flows.value = runCatching {
            if (!f.exists()) emptyList() else {
                val a = JSONArray(f.readText())
                (0 until a.length()).mapNotNull { runCatching { RecordedFlow.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.lastSeen }
    }

    /** Yeni hərəkət/gedişat qeydi — dublikatdırsa sayğac və vaxt yenilənir */
    @Synchronized
    fun record(kind: RecordedFlow.Kind, steps: List<FlowStep>, autoName: String, motion: MotionState): RecordedFlow? {
        if (steps.isEmpty()) return null
        val fp = RecordedFlow.fingerprintOf(steps)
        val now = System.currentTimeMillis()
        val list = _flows.value.toMutableList()
        val i = list.indexOfFirst { it.fingerprint == fp && it.kind == kind }
        val result = if (i >= 0) {
            val old = list.removeAt(i)
            old.copy(
                count = old.count + 1,
                movingCount = old.movingCount + if (motion == MotionState.MOVING) 1 else 0,
                stoppedCount = old.stoppedCount + if (motion == MotionState.STOPPED) 1 else 0,
                lastSeen = now,
                // son qeydin koordinat/vəziyyəti daha dəqiqdir
                steps = steps,
                name = if (old.customName) old.name else autoName
            )
        } else RecordedFlow(
            id = UUID.randomUUID().toString(), name = autoName, kind = kind, steps = steps, fingerprint = fp,
            movingCount = if (motion == MotionState.MOVING) 1 else 0,
            stoppedCount = if (motion == MotionState.STOPPED) 1 else 0,
            firstSeen = now, lastSeen = now
        )
        list.add(0, result)
        // limit: ən az istifadə olunan köhnələr silinir
        if (list.size > MAX_ITEMS) {
            val drop = list.sortedWith(compareBy<RecordedFlow> { it.count }.thenBy { it.lastSeen }).take(list.size - MAX_ITEMS).map { it.id }.toSet()
            list.removeAll { it.id in drop }
        }
        _flows.value = list
        save()
        return result
    }

    fun get(id: String) = _flows.value.firstOrNull { it.id == id }

    @Synchronized
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        _flows.value = _flows.value.map { if (it.id == id) it.copy(name = name.trim(), customName = true) else it }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _flows.value = _flows.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun clear() { _flows.value = emptyList(); save() }

    private fun save() {
        val f = file ?: return
        val snapshot = _flows.value
        io.execute {
            runCatching {
                val a = JSONArray().apply { snapshot.forEach { put(it.toJson()) } }
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(a.toString())
                tmp.renameTo(f)
            }
        }
    }
}
