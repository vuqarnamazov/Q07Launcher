package az.q07.launcher.data

import android.content.Context

/**
 * Android Automotive Car API (android.car) — refleksiya ilə, ona görə telefonda da çökmür.
 * Q07-də 25 standart VHAL xassəsi var; üçüncü tərəf tətbiqi yalnız bəzilərini oxuya bilir.
 */
object CarApi {
    // VehiclePropertyIds
    const val PERF_VEHICLE_SPEED = 0x11600207
    const val GEAR_SELECTION = 0x11400400
    const val CURRENT_GEAR = 0x11400401
    const val PARKING_BRAKE_ON = 0x11200402
    const val NIGHT_MODE = 0x11200407
    const val ENV_OUTSIDE_TEMPERATURE = 0x11600703
    const val HVAC_TEMPERATURE_SET = 0x15600503

    private var manager: Any? = null
    private var tried = false
    var error: String? = null
        private set

    @Synchronized
    fun init(context: Context): Boolean {
        if (manager != null) return true
        if (tried) return false
        tried = true
        return try {
            val carCls = Class.forName("android.car.Car")
            val car = carCls.getMethod("createCar", Context::class.java).invoke(null, context.applicationContext)
                ?: throw IllegalStateException("Car xidməti yoxdur")
            manager = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property")
            manager != null
        } catch (t: Throwable) {
            error = (t.cause ?: t).javaClass.simpleName
            false
        }
    }

    /** Xassəni oxu; icazə yoxdursa və ya xassə yoxdursa null */
    fun read(propId: Int, areaId: Int = 0): Any? {
        val m = manager ?: return null
        return try {
            val get = m.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val cpv = get.invoke(m, propId, areaId) ?: return null
            cpv.javaClass.getMethod("getValue").invoke(cpv)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Xassəni oxu, amma icazə xətasını gizlətmə: SecurityException olduğu kimi atılır
     * (refleksiyanın InvocationTargetException qabığı açılır). Xassə yoxdursa null.
     */
    fun readOrThrow(propId: Int, areaId: Int = 0): Any? {
        val m = manager ?: return null
        try {
            val get = m.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val cpv = get.invoke(m, propId, areaId) ?: return null
            return cpv.javaClass.getMethod("getValue").invoke(cpv)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val c = e.cause
            if (c is SecurityException) throw c
            return null
        }
    }

    /** CarPropertyManager.getPropertyList(): (xassə id, zona id-ləri). İcazə yoxdursa boş. */
    fun propertyList(): List<Pair<Int, IntArray>> {
        val m = manager ?: return emptyList()
        return try {
            val list = m.javaClass.getMethod("getPropertyList").invoke(m) as? List<*> ?: return emptyList()
            list.mapNotNull { cfg ->
                if (cfg == null) return@mapNotNull null
                runCatching {
                    val id = cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as Int
                    val areas = cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as? IntArray
                    id to (if (areas == null || areas.isEmpty()) intArrayOf(0) else areas)
                }.getOrNull()
            }
        } catch (e: SecurityException) {
            emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun gearName(v: Any?): String? = when ((v as? Number)?.toInt()) {
        null -> null
        0x0001 -> "N"
        0x0002 -> "R"
        0x0004 -> "P"
        0x0008 -> "D"
        0x0010 -> "1"
        0x0020 -> "2"
        0x0040 -> "3"
        else -> "#$v"
    }
}
