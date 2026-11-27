package az.q07.launcher.macro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Makrolar: filesDir/macros.json */
object MacroStore {

    private const val FILE = "macros.json"

    private val _items = MutableStateFlow<List<Macro>>(emptyList())
    val items: StateFlow<List<Macro>> = _items

    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        file = f
        _items.value = runCatching {
            if (!f.exists()) emptyList()
            else {
                val a = JSONArray(f.readText())
                (0 until a.length()).mapNotNull { runCatching { Macro.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(name: String, steps: List<MacroStep>): Macro {
        val m = Macro(UUID.randomUUID().toString(), name.ifBlank { "Makro" }, steps)
        _items.value = _items.value + m
        save()
        return m
    }

    @Synchronized
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        _items.value = _items.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
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
                tmp.writeText(JSONArray().apply { snapshot.forEach { put(it.toJson()) } }.toString())
                tmp.renameTo(f)
            }
        }
    }
}
