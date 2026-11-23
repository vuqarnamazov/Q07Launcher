package az.q07.launcher.scanner

import org.json.JSONArray
import org.json.JSONObject

/** Düyün açarı — eyni element hər dəfə eyni açarla tanınsın (koordinat yalnız başqa heç nə yoxdursa) */
fun nodeKeyOf(viewId: String, text: String, desc: String, cls: String, b: IntArray): String {
    val ident = "$viewId|$text|$desc"
    val pos = if (ident == "||") "@" + b.joinToString(",") { (it / 20).toString() } else ""
    return "$ident|${cls.substringAfterLast('.')}$pos"
}

/** Klik addımının ekran xəritəsindəki düyün açarı */
fun FlowStep.nodeKey(): String = nodeKeyOf(viewIdResName, text, contentDescription, className, bounds)

private fun IntArray.toJson() = JSONArray().apply { this@toJson.forEach { put(it) } }
private fun JSONArray?.toIntArray() = this?.let { a -> IntArray(a.length()) { a.optInt(it) } } ?: IntArray(0)
private fun JSONArray?.toStrings() = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

/** Ekranda tapılan düymə / element */
data class NodeInfo(
    val key: String,
    val text: String,
    val contentDescription: String,
    val viewIdResName: String,
    val className: String,
    val isClickable: Boolean,
    val bounds: IntArray,
    val seenCount: Int = 1,
    val clickCount: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    fun label(): String = text.ifBlank { contentDescription }.ifBlank { viewIdResName.substringAfter(":id/") }
        .ifBlank { className.substringAfterLast('.') + " " + bounds.joinToString(",") }

    fun toStep(packageName: String): FlowStep = FlowStep(
        FlowStep.Type.CLICK, packageName, className, text, contentDescription, viewIdResName, bounds
    )

    fun toJson(): JSONObject = JSONObject().put("k", key).put("t", text).put("d", contentDescription)
        .put("v", viewIdResName).put("c", className).put("cl", isClickable).put("b", bounds.toJson())
        .put("seen", seenCount).put("clicks", clickCount).put("last", lastSeen)

    override fun equals(other: Any?) = other is NodeInfo && other.key == key && other.seenCount == seenCount &&
        other.clickCount == clickCount && other.isClickable == isClickable && other.bounds.contentEquals(bounds)
    override fun hashCode() = key.hashCode()

    companion object {
        fun fromJson(o: JSONObject) = NodeInfo(
            o.getString("k"), o.optString("t"), o.optString("d"), o.optString("v"), o.optString("c"),
            o.optBoolean("cl"), o.optJSONArray("b").toIntArray(), o.optInt("seen", 1), o.optInt("clicks"), o.optLong("last")
        )
    }
}

/** Bir pəncərə/ekran (packageName + className) üzrə öyrənilmiş bütün elementlər */
data class ScreenNodeMap(
    val id: String,
    val packageName: String,
    val className: String,
    val nodes: List<NodeInfo> = emptyList(),
    val seenCount: Int = 1,
    /** tətbiqə giriş ekranıdır (başqa tətbiqdən/launcher-dən birbaşa açılıb) */
    val entry: Boolean = false,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
) {
    val shortName: String get() = className.substringAfterLast('.').ifBlank { packageName }
    val clickableCount: Int get() = nodes.count { it.isClickable }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("pkg", packageName).put("cls", className)
        .put("seen", seenCount).put("entry", entry).put("first", firstSeen).put("last", lastSeen)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })

    companion object {
        fun idOf(pkg: String, cls: String) = "$pkg/$cls"
        fun fromJson(o: JSONObject): ScreenNodeMap {
            val a = o.optJSONArray("nodes") ?: JSONArray()
            return ScreenNodeMap(
                o.getString("id"), o.optString("pkg"), o.optString("cls"),
                (0 until a.length()).mapNotNull { runCatching { NodeInfo.fromJson(a.getJSONObject(it)) }.getOrNull() },
                o.optInt("seen", 1), o.optBoolean("entry"), o.optLong("first"), o.optLong("last")
            )
        }
    }
}

/**
 * Hansı hərəkətin hara apardığı:
 *  NAVIGATE — başqa ekrana keçid,
 *  STATE    — eyni ekranda yeni elementlər açıldı (sekmə, menyu, alt bölmə); [revealedKeys] — üzə çıxan düyünlər.
 */
data class NavigationEdge(
    val id: String,
    val fromScreen: String,
    val toScreen: String,
    val action: FlowStep,
    val kind: Kind,
    val revealed: List<String> = emptyList(),
    val revealedKeys: List<String> = emptyList(),
    val count: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
) {
    enum class Kind { NAVIGATE, STATE;
        val label: String get() = if (this == NAVIGATE) "Ekrana keçid" else "Vəziyyət dəyişdi"
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", fromScreen).put("to", toScreen)
        .put("a", action.toJson()).put("kind", kind.name).put("rev", JSONArray(revealed))
        .put("revk", JSONArray(revealedKeys)).put("count", count).put("last", lastSeen)

    companion object {
        fun idOf(from: String, action: FlowStep, to: String, kind: Kind) = "$from|${action.nodeKey()}|$to|${kind.name}"
        fun fromJson(o: JSONObject) = NavigationEdge(
            o.getString("id"), o.optString("from"), o.optString("to"), FlowStep.fromJson(o.getJSONObject("a")),
            runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.NAVIGATE),
            o.optJSONArray("rev").toStrings(), o.optJSONArray("revk").toStrings(), o.optInt("count", 1), o.optLong("last")
        )
    }
}

/** Bazadan hesablanmış yol: hədəf düyməyə çatmaq üçün addımlar (giriş ekranı + kliklər) */
data class ActionPath(
    val target: String,
    val screenId: String,
    val steps: List<FlowStep>,
    /** bütün ara addımlar bazada tapıldı */
    val complete: Boolean
)

/** Açıq rejimdə cari ekranın canlı görüntüsü */
data class LiveScreen(
    val screenId: String,
    val packageName: String,
    val className: String,
    val nodes: List<NodeInfo>,
    val at: Long = System.currentTimeMillis()
)
