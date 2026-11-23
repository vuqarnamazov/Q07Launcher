package az.q07.launcher.scanner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Maşının sxemi və yolları.
 *  - vehicle_structure_map.json : ekranlar ([ScreenNodeMap]) + keçidlər ([NavigationEdge])
 *  - recorded_flows.json        : tək addımlı funksiyalar və çoxaddımlı gedişatlar ([RecordedFlow])
 * Dublikatlar açarla birləşdirilir, görülmə / istifadə sayları saxlanılır.
 */
object VehicleStructureRepository {
    private const val MAP_FILE = "vehicle_structure_map.json"
    private const val FLOWS_FILE = "recorded_flows.json"
    private const val MAX_SCREENS = 250
    private const val MAX_NODES = 400
    private const val MAX_EDGES = 2500
    private const val MAX_FLOWS = 600

    private val _screens = MutableStateFlow<Map<String, ScreenNodeMap>>(emptyMap())
    val screens: StateFlow<Map<String, ScreenNodeMap>> = _screens

    private val _edges = MutableStateFlow<List<NavigationEdge>>(emptyList())
    val edges: StateFlow<List<NavigationEdge>> = _edges

    private val _flows = MutableStateFlow<List<RecordedFlow>>(emptyList())
    val flows: StateFlow<List<RecordedFlow>> = _flows

    private val io = Executors.newSingleThreadScheduledExecutor()
    private val mapPending = AtomicBoolean(false)
    private val flowsPending = AtomicBoolean(false)
    private var dir: File? = null

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = context.applicationContext.filesDir
        dir = d
        runCatching {
            val f = File(d, MAP_FILE)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val s = o.optJSONArray("screens") ?: JSONArray()
                _screens.value = (0 until s.length()).mapNotNull { runCatching { ScreenNodeMap.fromJson(s.getJSONObject(it)) }.getOrNull() }
                    .associateBy { it.id }
                val e = o.optJSONArray("edges") ?: JSONArray()
                _edges.value = (0 until e.length()).mapNotNull { runCatching { NavigationEdge.fromJson(e.getJSONObject(it)) }.getOrNull() }
            }
        }
        runCatching {
            val f = File(d, FLOWS_FILE)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                _flows.value = (0 until a.length()).mapNotNull { runCatching { RecordedFlow.fromJson(a.getJSONObject(it)) }.getOrNull() }
                    .sortedByDescending { it.lastSeen }
            }
        }
    }

    // ───────────────────────── ekran xəritəsi ─────────────────────────

    /**
     * Snapshot-u ekran xəritəsinə birləşdir.
     * [countKeys] — bu ziyarətdə ilk dəfə görünən açarlar (görülmə sayı ziyarət başına 1 dəfə artır).
     */
    @Synchronized
    fun mergeSnapshot(pkg: String, cls: String, nodes: List<NodeInfo>, newVisit: Boolean, entry: Boolean, countKeys: Set<String>) {
        val id = ScreenNodeMap.idOf(pkg, cls)
        val now = System.currentTimeMillis()
        val old = _screens.value[id]
        val byKey = LinkedHashMap<String, NodeInfo>()
        old?.nodes?.forEach { byKey[it.key] = it }
        for (n in nodes) {
            val prev = byKey[n.key]
            byKey[n.key] = if (prev == null) n.copy(seenCount = 1, lastSeen = now)
            else prev.copy(
                text = n.text, contentDescription = n.contentDescription, isClickable = prev.isClickable || n.isClickable,
                bounds = n.bounds, lastSeen = now, seenCount = prev.seenCount + if (n.key in countKeys) 1 else 0
            )
        }
        var list = byKey.values.toList()
        if (list.size > MAX_NODES) list = list.sortedWith(compareByDescending<NodeInfo> { it.clickCount }.thenByDescending { it.seenCount }
            .thenByDescending { it.lastSeen }).take(MAX_NODES)
        val screen = (old ?: ScreenNodeMap(id, pkg, cls, firstSeen = now, seenCount = 0)).copy(
            nodes = list, lastSeen = now,
            seenCount = (old?.seenCount ?: 0) + if (newVisit || old == null) 1 else 0,
            entry = (old?.entry ?: false) || entry
        )
        var map = _screens.value + (id to screen)
        if (map.size > MAX_SCREENS) {
            val drop = map.values.sortedWith(compareBy<ScreenNodeMap> { it.seenCount }.thenBy { it.lastSeen })
                .take(map.size - MAX_SCREENS).map { it.id }.toSet()
            map = map - drop
        }
        _screens.value = map
        scheduleMapSave()
    }

    @Synchronized
    fun recordClick(pkg: String, cls: String, step: FlowStep) {
        val id = ScreenNodeMap.idOf(pkg, cls)
        val s = _screens.value[id] ?: return
        val key = step.nodeKey()
        if (s.nodes.none { it.key == key }) {
            // klik edilən düyün hələ snapshot-da yox idi — əlavə et
            val n = NodeInfo(key, step.text, step.contentDescription, step.viewIdResName, step.className, true, step.bounds, 1, 1)
            _screens.value = _screens.value + (id to s.copy(nodes = s.nodes + n))
        } else {
            _screens.value = _screens.value + (id to s.copy(nodes = s.nodes.map {
                if (it.key == key) it.copy(clickCount = it.clickCount + 1, isClickable = true) else it
            }))
        }
        scheduleMapSave()
    }

    @Synchronized
    fun addEdge(from: String, to: String, action: FlowStep, kind: NavigationEdge.Kind, revealed: List<String> = emptyList(), revealedKeys: List<String> = emptyList()) {
        val id = NavigationEdge.idOf(from, action, to, kind)
        val list = _edges.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            val o = list[i]
            list[i] = o.copy(
                count = o.count + 1, lastSeen = System.currentTimeMillis(), action = action,
                revealed = (o.revealed + revealed).distinct().take(12),
                revealedKeys = (o.revealedKeys + revealedKeys).distinct().take(60)
            )
        } else {
            list += NavigationEdge(id, from, to, action, kind, revealed.take(12), revealedKeys.take(60))
        }
        if (list.size > MAX_EDGES) list.sortWith(compareByDescending<NavigationEdge> { it.count }.thenByDescending { it.lastSeen })
        _edges.value = if (list.size > MAX_EDGES) list.take(MAX_EDGES) else list
        scheduleMapSave()
    }

    @Synchronized
    fun clearStructure() { _screens.value = emptyMap(); _edges.value = emptyList(); scheduleMapSave() }

    /**
     * Hədəf düyməyə yol hesabla:
     *  1) eyni ekranda onu üzə çıxaran klikləri (STATE keçidləri) geriyə doğru tap,
     *  2) ekrana gətirən NAVIGATE keçidlərini tətbiqin giriş ekranına qədər geriyə izlə,
     *  3) giriş ekranı (WINDOW addımı) + kliklər.
     */
    fun findPath(screenId: String, nodeKey: String): ActionPath? {
        val screens = _screens.value
        val edges = _edges.value
        val screen = screens[screenId] ?: return null
        val node = screen.nodes.firstOrNull { it.key == nodeKey } ?: return null
        val clicks = ArrayDeque<FlowStep>()
        clicks.addFirst(node.toStep(screen.packageName))
        var complete = true

        fun revealChain(sid: String, startKey: String) {
            var cur = startKey
            val used = mutableSetOf(cur)
            for (d in 0 until 4) {
                val e = edges.filter { it.kind == NavigationEdge.Kind.STATE && it.fromScreen == sid && cur in it.revealedKeys }
                    .maxByOrNull { it.count } ?: break
                val k = e.action.nodeKey()
                if (!used.add(k)) break
                clicks.addFirst(e.action); cur = k
            }
        }
        revealChain(screenId, nodeKey)

        var cur = screen
        val visited = mutableSetOf(cur.id)
        while (!cur.entry && visited.size < 8) {
            val e = edges.filter {
                it.kind == NavigationEdge.Kind.NAVIGATE && it.toScreen == cur.id && it.fromScreen !in visited &&
                    screens[it.fromScreen]?.packageName == cur.packageName
            }.maxByOrNull { it.count }
            if (e == null) { complete = false; break }
            clicks.addFirst(e.action)
            val prev = screens[e.fromScreen] ?: break
            revealChain(prev.id, e.action.nodeKey())
            cur = prev; visited += cur.id
        }
        val open = FlowStep(FlowStep.Type.WINDOW, cur.packageName, cur.className)
        return ActionPath(node.label(), screenId, listOf(open) + clicks, complete)
    }

    // ───────────────────────── funksiya / gedişat ─────────────────────────

    @Synchronized
    fun recordFlow(kind: RecordedFlow.Kind, steps: List<FlowStep>, autoName: String, motion: MotionState): RecordedFlow? {
        if (steps.isEmpty()) return null
        val fp = RecordedFlow.fingerprintOf(steps)
        val now = System.currentTimeMillis()
        val list = _flows.value.toMutableList()
        val i = list.indexOfFirst { it.fingerprint == fp && it.kind == kind }
        val r = if (i >= 0) {
            val o = list.removeAt(i)
            o.copy(
                count = o.count + 1, lastSeen = now, steps = steps,
                movingCount = o.movingCount + if (motion == MotionState.MOVING) 1 else 0,
                stoppedCount = o.stoppedCount + if (motion == MotionState.STOPPED) 1 else 0,
                name = if (o.customName) o.name else autoName
            )
        } else RecordedFlow(
            UUID.randomUUID().toString(), autoName, kind, steps, fp,
            movingCount = if (motion == MotionState.MOVING) 1 else 0,
            stoppedCount = if (motion == MotionState.STOPPED) 1 else 0, firstSeen = now, lastSeen = now
        )
        list.add(0, r)
        if (list.size > MAX_FLOWS) {
            val drop = list.sortedWith(compareBy<RecordedFlow> { it.count }.thenBy { it.lastSeen }).take(list.size - MAX_FLOWS).map { it.id }.toSet()
            list.removeAll { it.id in drop }
        }
        _flows.value = list
        scheduleFlowsSave()
        return r
    }

    fun flow(id: String) = _flows.value.firstOrNull { it.id == id }

    @Synchronized
    fun renameFlow(id: String, name: String) {
        if (name.isBlank()) return
        _flows.value = _flows.value.map { if (it.id == id) it.copy(name = name.trim(), customName = true) else it }
        scheduleFlowsSave()
    }

    @Synchronized
    fun deleteFlow(id: String) { _flows.value = _flows.value.filterNot { it.id == id }; scheduleFlowsSave() }

    @Synchronized
    fun clearFlows() { _flows.value = emptyList(); scheduleFlowsSave() }

    // ───────────────────────── yaddaş ─────────────────────────

    private fun scheduleMapSave() {
        if (dir == null || !mapPending.compareAndSet(false, true)) return
        io.schedule({
            mapPending.set(false)
            val o = JSONObject()
                .put("version", 1)
                .put("screens", JSONArray().apply { _screens.value.values.forEach { put(it.toJson()) } })
                .put("edges", JSONArray().apply { _edges.value.forEach { put(it.toJson()) } })
            write(MAP_FILE, o.toString())
        }, 3, TimeUnit.SECONDS)
    }

    private fun scheduleFlowsSave() {
        if (dir == null || !flowsPending.compareAndSet(false, true)) return
        io.schedule({
            flowsPending.set(false)
            write(FLOWS_FILE, JSONArray().apply { _flows.value.forEach { put(it.toJson()) } }.toString())
        }, 1, TimeUnit.SECONDS)
    }

    /** Fon xidməti dayananda gözləyən yazıları dərhal bitir */
    fun flushNow() {
        if (dir == null) return
        io.execute {
            runCatching {
                write(MAP_FILE, JSONObject().put("version", 1)
                    .put("screens", JSONArray().apply { _screens.value.values.forEach { put(it.toJson()) } })
                    .put("edges", JSONArray().apply { _edges.value.forEach { put(it.toJson()) } }).toString())
                write(FLOWS_FILE, JSONArray().apply { _flows.value.forEach { put(it.toJson()) } }.toString())
            }
        }
    }

    private fun write(name: String, text: String) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(d, name))
        }
    }
}
