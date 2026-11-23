package az.q07.scanner.rec

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy

/**
 * Avtomobil xassələri (VHAL): icazəmiz olan BÜTÜN xassələrin siyahısı (vendor/zavod kodları daxil)
 * və hər dəyişikliyi. Car API refleksiya ilə — kitabxana yoxdursa sadəcə işləmir.
 */
object CarRec {
    @Volatile var count = 0; private set
    private var manager: Any? = null
    private var callback: Any? = null
    private val last = HashMap<String, String>()
    private val lastAt = HashMap<String, Long>()

    fun start(context: Context) {
        val carCls = Class.forName("android.car.Car")
        val car = carCls.getMethod("createCar", Context::class.java).invoke(null, context.applicationContext)
            ?: throw IllegalStateException("Car xidməti yoxdur")
        val m = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property") ?: throw IllegalStateException("property manager yoxdur")
        manager = m
        @Suppress("UNCHECKED_CAST")
        val configs = m.javaClass.getMethod("getPropertyList").invoke(m) as List<Any>
        count = configs.size

        // 1) inventar: hər xassənin kodu, adı, sahələri, giriş növü
        val idsCls = runCatching { Class.forName("android.car.VehiclePropertyIds") }.getOrNull()
        val arr = JSONArray()
        val ids = ArrayList<Pair<Int, Float>>()
        for (c in configs) {
            val cc = c.javaClass
            val id = cc.getMethod("getPropertyId").invoke(c) as Int
            val change = runCatching { cc.getMethod("getChangeMode").invoke(c) as Int }.getOrDefault(-1)
            val maxRate = runCatching { cc.getMethod("getMaxSampleRate").invoke(c) as Float }.getOrDefault(0f)
            val o = JSONObject().put("kod", "0x" + Integer.toHexString(id)).put("id", id)
                .put("ad", runCatching { idsCls?.getMethod("toString", Int::class.javaPrimitiveType)?.invoke(null, id) }.getOrNull() ?: "?")
                .put("vendor", (id and 0xF0000000.toInt()) == 0x20000000)
                .put("giris", runCatching { cc.getMethod("getAccess").invoke(c) }.getOrNull() ?: "?")
                .put("deyisme", change)
                .put("tip", runCatching { (cc.getMethod("getPropertyType").invoke(c) as Class<*>).simpleName }.getOrNull() ?: "?")
                .put("sahe", runCatching { JSONArray((cc.getMethod("getAreaIds").invoke(c) as IntArray).toList()) }.getOrNull() ?: JSONArray())
                .put("max_hz", maxRate)
            arr.put(o)
            // 0=STATIC, 1=ON_CHANGE, 2=CONTINUOUS — davamlılar 1 Hz ilə (dəyişiklik olanda yazılır)
            ids += id to (if (change == 2) 1f.coerceAtMost(maxRate.takeIf { it > 0f } ?: 1f) else 0f)
        }
        TamQeyd.write("car", JSONObject().put("hadise", "xassə siyahısı").put("say", configs.size).put("xasseler", arr))

        // 2) hər xassənin indiki dəyəri
        val get = m.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val areas = o.getJSONArray("sahe").let { a -> (0 until a.length()).map { a.getInt(it) } }.ifEmpty { listOf(0) }
            for (area in areas) runCatching {
                val v = get.invoke(m, o.getInt("id"), area)
                if (v != null) logValue(v, "ilkin")
            }
        }

        // 3) dəyişikliklərə abunə (CarPropertyEventCallback — Proxy ilə)
        val cbCls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        val cb = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> runCatching { logValue(args!![0], "deyisdi") }
                "onErrorEvent" -> TamQeyd.write("car", JSONObject().put("hadise", "xeta").put("kod", "0x" + Integer.toHexString(args!![0] as Int)).put("sahe", args[1]))
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "toString" -> return@newProxyInstance "TamQeydCarCallback"
            }
            null
        }
        callback = cb
        val reg = m.javaClass.getMethod("registerCallback", cbCls, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        var ok = 0
        for ((id, rate) in ids) if (runCatching { reg.invoke(m, cb, id, rate) as Boolean }.getOrDefault(false)) ok++
        TamQeyd.write("car", JSONObject().put("hadise", "abunə").put("ugurlu", ok).put("cemi", ids.size))
    }

    @Synchronized
    private fun logValue(v: Any, why: String) {
        val c = v.javaClass
        val id = c.getMethod("getPropertyId").invoke(v) as Int
        val area = c.getMethod("getAreaId").invoke(v) as Int
        val value = c.getMethod("getValue").invoke(v)
        val str = when (value) {
            is Array<*> -> value.joinToString(",", "[", "]")
            is ByteArray -> value.joinToString("", "hex:") { "%02x".format(it) }
            is IntArray -> value.joinToString(",", "[", "]")
            is FloatArray -> value.joinToString(",", "[", "]")
            is LongArray -> value.joinToString(",", "[", "]")
            else -> value.toString()
        }
        val key = "$id/$area"
        val now = System.currentTimeMillis()
        if (why == "deyisdi" && last[key] == str) return
        // çox tez dəyişən (sürət kimi) — ən çoxu saniyədə 2 dəfə
        if (why == "deyisdi" && now - (lastAt[key] ?: 0) < 500) { last[key] = str; return }
        last[key] = str; lastAt[key] = now
        val status = runCatching { c.getMethod("getStatus").invoke(v) as Int }.getOrDefault(0)
        TamQeyd.write("car", JSONObject().put("hadise", why).put("kod", "0x" + Integer.toHexString(id)).put("sahe", area).put("deyer", str)
            .apply { if (status != 0) put("status", status) })
    }

    fun stop() {
        val m = manager ?: return
        val cb = callback ?: return
        runCatching {
            val cbCls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            m.javaClass.getMethod("unregisterCallback", cbCls).invoke(m, cb)
        }
    }
}
