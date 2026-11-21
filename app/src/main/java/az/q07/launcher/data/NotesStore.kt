package az.q07.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class Note(
    val id: Long,
    val text: String,
    val createdAt: Long,
    val remindAt: Long? = null,
    /** Səs qeydi faylı (Google səs tanıması olmayanda) */
    val audioPath: String? = null
)

/** Qeydləri SharedPreferences-də JSON kimi saxlayır; Compose avtomatik yenilənir. */
object NotesStore {
    private const val KEY = "notes_json"
    private lateinit var prefs: SharedPreferences

    val notes = mutableStateListOf<Note>()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("q07_notes", Context.MODE_PRIVATE)
        load()
    }

    fun add(text: String, audioPath: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty() && audioPath == null) return
        val now = System.currentTimeMillis()
        notes.add(0, Note(id = now, text = clean, createdAt = now, audioPath = audioPath))
        save()
    }

    fun remove(id: Long) {
        notes.filter { it.id == id }.forEach { n -> n.audioPath?.let { java.io.File(it).delete() } }
        notes.removeAll { it.id == id }
        save()
    }

    fun setReminder(id: Long, at: Long?) {
        val idx = notes.indexOfFirst { it.id == id }
        if (idx >= 0) {
            notes[idx] = notes[idx].copy(remindAt = at)
            save()
        }
    }

    private fun load() {
        notes.clear()
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                notes.add(
                    Note(
                        id = o.getLong("id"),
                        text = o.getString("text"),
                        createdAt = o.getLong("createdAt"),
                        remindAt = if (o.has("remindAt") && !o.isNull("remindAt")) o.getLong("remindAt") else null,
                        audioPath = if (o.has("audio") && !o.isNull("audio")) o.getString("audio") else null
                    )
                )
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("text", n.text)
                put("createdAt", n.createdAt)
                put("remindAt", n.remindAt ?: JSONObject.NULL)
                put("audio", n.audioPath ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
