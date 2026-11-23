package az.q07.scanner

import android.app.Application
import az.q07.scanner.data.SelfDiag

class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SelfDiag.init(this)
        SelfDiag.log("start", "Q07 Skaner açıldı", mapOf("version" to BuildConfig.VERSION_NAME))
        // bərpa repozitoriyalar faylları oxumazdan əvvəl
        runCatching { az.q07.scanner.rec.TamQeyd.restoreBase(this) }
        runCatching { FullVehicleScannerManager.init(this) }
        runCatching { FullVehicleScannerManager.restoreIfNeeded(this) }
        // Tam qeyd proses başlayan kimi (xidmət qoşulmasa belə) — ekran hadisələri xidmətdən əlavə olunur
        runCatching { az.q07.scanner.rec.TamQeyd.start(this) }
    }
}
