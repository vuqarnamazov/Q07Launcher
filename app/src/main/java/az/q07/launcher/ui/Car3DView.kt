package az.q07.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import az.q07.launcher.data.SelfDiag
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Maşının ÖZ zavod 3D modeli (360° kamera sistemindən: CaCar.gltf) — launcher-ə kopyalanmır,
 * birbaşa baş qurğunun sistem qovluğundan oxunur. Model tapılmasa false qaytarır (köhnə rəsm qalır).
 */
object Car3DModel {
    private const val RES = "/system/etc/automotive/caevs/lib/avm/res/"
    private const val GLTF = RES + "model/car/CaCar.gltf"

    private const val PREFS = "q07_car3d"

    /** 3D yüklənərkən proses çöksə (native), növbəti açılışda 3D söndürülür — köhnə rəsm göstərilir */
    fun available(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("loading", false)) {
            p.edit().putBoolean("loading", false).putBoolean("disabled", true).apply()
            SelfDiag.log("car3d", "əvvəlki yükləmə zamanı çökmə — 3D söndürüldü, köhnə rəsm")
        }
        if (p.getBoolean("disabled", false)) return false
        return runCatching { File(GLTF).canRead() }.getOrDefault(false)
    }

    fun markLoading(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("loading", on).commit()

    /** Ayarlardan yenidən yoxlamaq üçün */
    fun reset(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    fun gltf(): ByteBuffer = direct(File(GLTF).readBytes())

    /** glTF daxilindəki fayl adları: .bin modelin yanında, şəkillər res/ altında (.pkm əvəzinə .png) */
    fun resource(uri: String, plate: String = ""): ByteBuffer? {
        val u = uri.substringBefore('?')
        // Öz nömrə lövhəmiz
        if (plate.isNotBlank() && u.contains("LicencePlate", ignoreCase = true)) return direct(az.q07.launcher.data.CarLook.platePng(plate))
        // .pkm (ETC sıxılmış) Filament tərəfindən oxunmur — həmişə yanındakı .png götürülür
        val png = u.replace(Regex("\\.pkm$"), ".png")
        val candidates = if (png != u) listOf(RES + png, RES + "model/car/" + png)
        else listOf(RES + "model/car/" + u, RES + u)
        val f = candidates.map(::File).firstOrNull { it.canRead() } ?: return null
        return direct(f.readBytes())
    }

    private fun direct(b: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.flip() }
}

@Composable
fun Car3DView(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { az.q07.launcher.data.CarLook.load(ctx) }
    val plate by az.q07.launcher.data.CarLook.plate.collectAsState()
    val color by az.q07.launcher.data.CarLook.colorIndex.collectAsState()
    // Nömrə dəyişəndə model yenidən qurulur; rəng isə canlı dəyişir
    androidx.compose.runtime.key(plate) {
        AndroidView(
            factory = { c -> Car3DTextureView(c, plate) },
            modifier = modifier,
            update = { it.setBodyColor(az.q07.launcher.data.CarLook.colors.getOrNull(color)?.rgb) },
            onRelease = { it.release() }
        )
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Car3DTextureView(context: Context, private val plate: String) : TextureView(context), Choreographer.FrameCallback {

    companion object { init { Utils.init() } }

    private val engine = Engine.create()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
    private val viewer: ModelViewer
    private val choreographer = Choreographer.getInstance()
    private var released = false

    // Toxunulmayanda maşın yavaş-yavaş özü fırlanır; barmaqla istənilən tərəfə çevrilir
    private var spinDeg = 215f
    private var lastTouch = 0L
    private var lastFrame = 0L
    private var base: FloatArray? = null
    private var framesOk = 0

    init {
        val manipulator = Manipulator.Builder()
            .targetPosition(0f, -0.15f, -4f)
            .orbitHomePosition(0f, 0.75f, -1.75f)
            .zoomSpeed(0.02f)
            .orbitSpeed(0.006f, 0.006f)
            .build(Manipulator.Mode.ORBIT)
        viewer = ModelViewer(this, engine, uiHelper, manipulator)
        viewer.cameraFocalLength = 34f   // maşın kadrı daha çox doldursun
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }
        viewer.scene.skybox = null
        // KTX faylı olmadan sadə ətraf işığı
        viewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1.05f))
            .intensity(32_000f)
            .build(engine)
        load()
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            viewer.onTouchEvent(e)
            lastTouch = SystemClock.uptimeMillis()
            true
        }
        choreographer.postFrameCallback(this)
    }

    private fun load() {
        // Yükləmə + ilk kadrlar bitənə qədər "loading" bayrağı qalır (native çökmədən qorunma)
        Car3DModel.markLoading(context, true)
        try {
            viewer.loadModelGltf(Car3DModel.gltf()) { uri -> Car3DModel.resource(uri, plate) }
            val asset = viewer.asset ?: return
            // Model Z-yuxarıdır (AVM) → Y-yuxarı; mərkəzə gətir, ölçünü normallaşdır
            val bb = asset.boundingBox
            val c = bb.center; val h = bb.halfExtent
            val s = 1f / maxOf(h[0], h[1], h[2])
            val m = FloatArray(16)
            android.opengl.Matrix.setIdentityM(m, 0)
            android.opengl.Matrix.scaleM(m, 0, s, s, s)
            android.opengl.Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f)
            android.opengl.Matrix.translateM(m, 0, -c[0], -c[1], -c[2])
            base = m
            applyTransform()
            setBodyColor(bodyColor)
            SelfDiag.log("car3d", "zavod 3D modeli yükləndi (${asset.entities.size} hissə)")
        } catch (t: Throwable) {
            SelfDiag.log("car3d", "3D model yüklənmədi: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var bodyColor: Int? = null

    /** Kuzov rəngi: "Paint" materialının baseColorFactor-u (toxuma ilə vurulur); null = zavod */
    fun setBodyColor(rgb: Int?) {
        bodyColor = rgb
        val asset = viewer.asset ?: return
        val r: Float; val g: Float; val b: Float
        if (rgb == null) { r = 1f; g = 1f; b = 1f } else {
            // toxuma açıq-boz olduğu üçün rəngi bir az gücləndiririk
            r = ((rgb shr 16) and 0xFF) / 255f * 1.25f; g = ((rgb shr 8) and 0xFF) / 255f * 1.25f; b = (rgb and 0xFF) / 255f * 1.25f
        }
        runCatching {
            asset.instance.materialInstances.filter { it.name.equals("Paint", true) }.forEach { mi ->
                mi.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.SRGB, r, g, b, 1f)
            }
        }
    }

    private fun applyTransform() {
        val asset = viewer.asset ?: return
        val b = base ?: return
        val out = FloatArray(16); val rot = FloatArray(16); val tmp = FloatArray(16)
        android.opengl.Matrix.setIdentityM(rot, 0)
        android.opengl.Matrix.translateM(rot, 0, 0f, 0f, -4f)
        android.opengl.Matrix.rotateM(rot, 0, spinDeg, 0f, 1f, 0f)
        android.opengl.Matrix.multiplyMM(tmp, 0, rot, 0, b, 0)
        System.arraycopy(tmp, 0, out, 0, 16)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), out)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (released) return
        choreographer.postFrameCallback(this)
        if (!isShown) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        if (now - lastTouch > 4000) { spinDeg = (spinDeg + dt * 12f) % 360f; applyTransform() }
        runCatching { viewer.render(frameTimeNanos) }
        if (framesOk < 120 && ++framesOk == 120) Car3DModel.markLoading(context, false) // ~2 san problemsiz
    }

    fun release() {
        if (released) return
        released = true
        choreographer.removeFrameCallback(this)
        runCatching { viewer.destroyModel() }
        runCatching { uiHelper.detach() }
        runCatching { engine.destroy() }
    }
}
