package az.q07.launcher.macro

import org.json.JSONArray
import org.json.JSONObject

/** Bir klik addımı — replay üçün kifayət edən minimal identifikasiya. */
data class MacroStep(
    val packageName: String,
    val className: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val bounds: List<Int>,
    val at: Long = System.currentTimeMillis()
) {
    fun label(): String = text.ifBlank { desc }
        .ifBlank { viewId.substringAfter(":id/") }
        .ifBlank { className.substringAfterLast('.') }
        .ifBlank { "addım" }

    fun key(): String = "$packageName|$viewId|$text|$desc|${bounds.joinToString(",")}"

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", packageName)
        .put("cls", className)
        .put("txt", text)
        .put("desc", desc)
        .put("id", viewId)
        .put("b", JSONArray(bounds))
        .put("at", at)

    companion object {
        fun fromJson(o: JSONObject): MacroStep {
            val a = o.optJSONArray("b")
            val b = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }
            return MacroStep(
                packageName = o.optString("pkg"),
                className = o.optString("cls"),
                text = o.optString("txt"),
                desc = o.optString("desc"),
                viewId = o.optString("id"),
                bounds = b,
                at = o.optLong("at")
            )
        }
    }
}

/** 3-4 addımlı menyu makrosu. */
data class Macro(
    val id: String,
    val name: String,
    val steps: List<MacroStep>,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("created", createdAt)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val a = o.optJSONArray("steps") ?: JSONArray()
            return Macro(
                id = o.getString("id"),
                name = o.optString("name").ifBlank { "Makro" },
                steps = (0 until a.length()).map { MacroStep.fromJson(a.getJSONObject(it)) },
                createdAt = o.optLong("created")
            )
        }
    }
}
