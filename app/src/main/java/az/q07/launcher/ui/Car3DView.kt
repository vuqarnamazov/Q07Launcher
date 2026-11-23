package az.q07.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.runtime.Composable
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

    val available: Boolean by lazy { runCatching { File(GLTF).canRead() }.getOrDefault(false) }

    fun gltf(): ByteBuffer = direct(File(GLTF).readBytes())

    /** glTF daxilindəki fayl adları: .bin modelin yanında, şəkillər res/ altında (.pkm əvəzinə .png) */
    fun resource(uri: String): ByteBuffer? {
        val u = uri.substringBefore('?')
        val candidates = buildList {
            val png = u.replace(Regex("\\.pkm$"), ".png")
            add(RES + "model/car/" + u); add(RES + u)
            add(RES + "model/car/" + png); add(RES + png)
        }
        val f = candidates.map(::File).firstOrNull { it.canRead() } ?: return null
        return direct(f.readBytes())
    }

    private fun direct(b: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.flip() }
}

@Composable
fun Car3DView(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> Car3DTextureView(ctx) },
        modifier = modifier,
        onRelease = { it.release() }
    )
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Car3DTextureView(context: Context) : TextureView(context), Choreographer.FrameCallback {

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

    init {
        val manipulator = Manipulator.Builder()
            .targetPosition(0f, 0f, -4f)
            .orbitHomePosition(0f, 1.25f, -0.55f)
            .zoomSpeed(0.02f)
            .orbitSpeed(0.006f, 0.006f)
            .build(Manipulator.Mode.ORBIT)
        viewer = ModelViewer(this, engine, uiHelper, manipulator)
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
        try {
            viewer.loadModelGltf(Car3DModel.gltf()) { uri -> Car3DModel.resource(uri) }
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
            SelfDiag.log("car3d", "zavod 3D modeli yükləndi (${asset.entities.size} hissə)")
        } catch (t: Throwable) {
            SelfDiag.log("car3d", "3D model yüklənmədi: ${t.javaClass.simpleName}: ${t.message}")
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
