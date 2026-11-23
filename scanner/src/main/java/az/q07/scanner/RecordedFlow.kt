package az.q07.scanner

import org.json.JSONArray
import org.json.JSONObject

/** Maşının vəziyyəti — addım qeydə alınanda */
enum class MotionState { MOVING, STOPPED, UNKNOWN;
    val label: String get() = when (this) { MOVING -> "Hərəkətdə"; STOPPED -> "Dayanıb"; UNKNOWN -> "Naməlum" }
}

/** Bir addım: ya klik, ya pəncərə (ekran) keçidi */
data class FlowStep(
    val type: Type,
    val packageName: String,
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val viewIdResName: String = "",
    /** left,top,right,bottom — ekran koordinatları */
    val bounds: IntArray = IntArray(0),
    val motion: MotionState = MotionState.UNKNOWN,
    val at: Long = System.currentTimeMillis()
) {
    enum class Type { CLICK, WINDOW }

    /** Dublikat yoxlaması üçün açar (vaxt / sürət / koordinat daxil deyil — koordinat yalnız başqa heç nə yoxdursa) */
    fun key(): String {
        val ident = listOf(viewIdResName, text, contentDescription).joinToString("|")
        val pos = if (ident.isBlank() || ident == "||") bounds.joinToString(",") { (it / 20).toString() } else ""
        return "${type.name}|$packageName|$className|$ident|$pos"
    }

    /** İstifadəçiyə göstəriləcək qısa ad */
    fun label(): String = when (type) {
        Type.WINDOW -> "Ekran: " + className.substringAfterLast('.').ifBlank { packageName }
        Type.CLICK -> text.ifBlank { contentDescription }.ifBlank { viewIdResName.substringAfter(":id/") }
            .ifBlank { className.substringAfterLast('.') + " " + bounds.joinToString(",") }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("pkg", packageName).put("cls", className)
        .put("text", text).put("desc", contentDescription).put("vid", viewIdResName)
        .put("b", JSONArray().apply { bounds.forEach { put(it) } })
        .put("motion", motion.name).put("at", at)

    override fun equals(other: Any?) = other is FlowStep && other.key() == key()
    override fun hashCode() = key().hashCode()

    companion object {
        fun fromJson(o: JSONObject) = FlowStep(
            type = runCatching { Type.valueOf(o.getString("type")) }.getOrDefault(Type.CLICK),
            packageName = o.optString("pkg"), className = o.optString("cls"),
            text = o.optString("text"), contentDescription = o.optString("desc"),
            viewIdResName = o.optString("vid"),
            bounds = o.optJSONArray("b")?.let { a -> IntArray(a.length()) { a.optInt(it) } } ?: IntArray(0),
            motion = runCatching { MotionState.valueOf(o.optString("motion")) }.getOrDefault(MotionState.UNKNOWN),
            at = o.optLong("at")
        )
    }
}

/**
 * Qeydə alınmış funksiya (tək addım) və ya gedişat (çox addım).
 * [fingerprint] eyni hərəkət təkrarlananda dublikat yaratmamaq üçündür.
 */
data class RecordedFlow(
    val id: String,
    val name: String,
    val kind: Kind,
    val steps: List<FlowStep>,
    val fingerprint: String,
    val count: Int = 1,
    val movingCount: Int = 0,
    val stoppedCount: Int = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    /** istifadəçi adı dəyişibsə — avtomatik ad üstünə yazılmasın */
    val customName: Boolean = false
) {
    enum class Kind { FUNCTION, FLOW;
        val label: String get() = if (this == FUNCTION) "Funksiya" else "Gedişat"
    }

    val packages: List<String> get() = steps.map { it.packageName }.distinct()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("fp", fingerprint)
        .put("count", count).put("moving", movingCount).put("stopped", stoppedCount)
        .put("first", firstSeen).put("last", lastSeen).put("custom", customName)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    companion object {
        fun fingerprintOf(steps: List<FlowStep>) = steps.joinToString(" > ") { it.key() }

        fun fromJson(o: JSONObject): RecordedFlow {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until arr.length()).map { FlowStep.fromJson(arr.getJSONObject(it)) }
            return RecordedFlow(
                id = o.getString("id"), name = o.optString("name"),
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.FLOW),
                steps = steps, fingerprint = o.optString("fp").ifBlank { fingerprintOf(steps) },
                count = o.optInt("count", 1), movingCount = o.optInt("moving"), stoppedCount = o.optInt("stopped"),
                firstSeen = o.optLong("first"), lastSeen = o.optLong("last"), customName = o.optBoolean("custom")
            )
        }
    }
}
