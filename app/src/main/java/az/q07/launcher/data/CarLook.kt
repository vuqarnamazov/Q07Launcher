package az.q07.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/** 3D maşının görünüşü: kuzov rəngi və dövlət nömrəsi (yadda saxlanır) */
object CarLook {
    data class BodyColor(val name: String, val rgb: Int?)   // null = zavod rəngi

    val colors = listOf(
        BodyColor("Zavod", null),
        BodyColor("Ağ", 0xFFF4F5F7.toInt()),
        BodyColor("Gümüşü", 0xFFB8BEC6.toInt()),
        BodyColor("Boz", 0xFF6B7078.toInt()),
        BodyColor("Qara", 0xFF1A1C20.toInt()),
        BodyColor("Göy", 0xFF2F5FA8.toInt()),
        BodyColor("Yaşıl", 0xFF3F6B5A.toInt()),
        BodyColor("Qırmızı", 0xFFB3262E.toInt()),
        BodyColor("Qum", 0xFFC8B58E.toInt()),
    )

    private val _color = MutableStateFlow(0)
    val colorIndex: StateFlow<Int> = _color
    private val _plate = MutableStateFlow("")
    val plate: StateFlow<String> = _plate
    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.getSharedPreferences("q07_carlook", Context.MODE_PRIVATE)
        _color.value = p.getInt("color", 0).coerceIn(0, colors.lastIndex)
        _plate.value = p.getString("plate", "") ?: ""
    }

    fun setColor(c: Context, i: Int) {
        _color.value = i
        c.getSharedPreferences("q07_carlook", Context.MODE_PRIVATE).edit().putInt("color", i).apply()
    }

    fun setPlate(c: Context, text: String) {
        val t = text.trim().uppercase().take(12)
        _plate.value = t
        c.getSharedPreferences("q07_carlook", Context.MODE_PRIVATE).edit().putString("plate", t).apply()
    }

    /**
     * Nömrə toxuması (PNG). Modelin nömrə lövhəsi toxumanın v=0.265..0.547 zolağını istifadə edir.
     * Azərbaycan nömrəsi üslubu: ağ fon, solda bayraq + "AZ", qara yazı.
     */
    fun platePng(text: String): ByteArray {
        val size = 1024
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.rgb(116, 116, 116))
        // Modelin nömrə lövhəsinin UV-si üfüqi tərsdir — toxumanı güzgü kimi çəkirik ki, lövhədə düz oxunsun
        cv.scale(-1f, 1f, size / 2f, size / 2f)
        val top = size * 0.265f; val bottom = size * 0.547f
        val r = RectF(size * 0.006f, top, size * 0.994f, bottom)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; cv.drawRoundRect(r, 24f, 24f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 10f; p.color = Color.BLACK
        cv.drawRoundRect(RectF(r.left + 8, r.top + 8, r.right - 8, r.bottom - 8), 20f, 20f, p)
        p.style = Paint.Style.FILL
        // bayraq (göy-qırmızı-yaşıl) + AZ
        val fx = r.left + 30; val fw = 110f; val fh = 72f; val fy = r.top + 40
        p.color = Color.rgb(0, 181, 226); cv.drawRect(fx, fy, fx + fw, fy + fh / 3, p)
        p.color = Color.rgb(239, 51, 64); cv.drawRect(fx, fy + fh / 3, fx + fw, fy + 2 * fh / 3, p)
        p.color = Color.rgb(80, 158, 47); cv.drawRect(fx, fy + 2 * fh / 3, fx + fw, fy + fh, p)
        p.color = Color.BLACK; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = 78f; p.textAlign = Paint.Align.CENTER
        cv.drawText("AZ", fx + fw / 2, r.bottom - 50, p)
        // nömrə
        val tx = fx + fw + 40; val tw = r.right - 40 - tx
        p.textSize = 210f; p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val w = p.measureText(text)
        if (w > tw) p.textScaleX = tw / w
        val sw = p.measureText(text) * 1f
        val fm = p.fontMetrics
        val baseline = (r.top + r.bottom) / 2 - (fm.ascent + fm.descent) / 2
        cv.drawText(text, tx + (tw - sw) / 2, baseline, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
