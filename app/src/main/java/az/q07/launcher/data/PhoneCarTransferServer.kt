package az.q07.launcher.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

data class TransferItem(
    val id: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val direction: String, // "FROM_PHONE" və ya "FROM_CAR"
    val timestamp: Long,
    val isImage: Boolean
)

/**
 * Telefon ↔ maşın fayl kanalı.
 * Təhlükəsizlik: hər sessiya üçün 6 rəqəmli PIN yaranır və yalnız maşının ekranında göstərilir.
 * PIN olmadan API və fayl yükləmə/endirmə işləmir.
 */
object PhoneCarTransferServer {

    const val PORT = 8766
    private const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    /** Cari sessiyanın PIN-i (maşın ekranında göstərilir) */
    @Volatile
    var currentPin: String = ""
        private set

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items = _items.asStateFlow()

    private val _latestIncomingAlert = MutableStateFlow<TransferItem?>(null)
    val latestIncomingAlert = _latestIncomingAlert.asStateFlow()

    fun dismissAlert() {
        _latestIncomingAlert.value = null
    }

    fun getTransferFolder(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(base, "Q07_Transfers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        refreshFileList(appCtx)
        if (isRunning) return
        isRunning = true
        currentPin = (100000 + SecureRandom().nextInt(900000)).toString()

        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(appCtx, client) }
                }
            } catch (_: Exception) {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        currentPin = ""
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    fun getAccessUrls(): List<String> {
        val urls = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addrs = interfaces.nextElement().inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        urls.add("http://${addr.hostAddress}:$PORT")
                    }
                }
            }
        } catch (_: Exception) {}
        return urls
    }

    fun refreshFileList(context: Context) {
        val dir = getTransferFolder(context)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        _items.value = files.map { f ->
            val lower = f.name.lowercase()
            val isImg = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
            val dirTag = if (f.name.startsWith("CAR_")) "FROM_CAR" else "FROM_PHONE"
            TransferItem(
                id = f.name,
                fileName = f.name.removePrefix("CAR_").removePrefix("PHONE_"),
                filePath = f.absolutePath,
                sizeBytes = f.length(),
                direction = dirTag,
                timestamp = f.lastModified(),
                isImage = isImg
            )
        }
    }

    fun shareFromCarToPhone(context: Context, inputStream: InputStream, originalName: String) {
        val dir = getTransferFolder(context)
        val safeName = "CAR_${System.currentTimeMillis()}_${originalName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val outFile = File(dir, safeName)
        FileOutputStream(outFile).use { out -> inputStream.copyTo(out) }
        refreshFileList(context)
    }

    fun deleteItem(context: Context, item: TransferItem) {
        File(item.filePath).delete()
        refreshFileList(context)
    }

    private fun queryParam(query: String, key: String): String =
        query.split("&").firstOrNull { it.startsWith("$key=") }?.substringAfter("=") ?: ""

    private fun isPinValid(given: String): Boolean {
        val expected = currentPin
        if (expected.isEmpty() || given.isEmpty()) return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var prev4 = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                prev4 = ((prev4 shl 8) or (b and 0xFF))
                if (prev4 == 0x0D0A0D0A) break
            }

            val rawHeaders = headerBytes.toString("UTF-8")
            val lines = rawHeaders.split("\r\n")
            if (lines.isEmpty()) return

            val reqParts = lines[0].split(" ")
            if (reqParts.size < 2) return
            val method = reqParts[0]
            val fullPath = reqParts[1]
            val path = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")

            val headersMap = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(":")
                if (idx > 0) {
                    headersMap[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                }
            }

            val out = socket.getOutputStream()
            val isPage = method == "GET" && path == "/"

            // PIN yoxlanışı: səhifə (giriş forması) istisnadır, qalan hər şey PIN tələb edir
            if (!isPage) {
                val givenPin = headersMap["x-pin"]
                    ?: URLDecoder.decode(queryParam(query, "pin"), "UTF-8")
                if (!isPinValid(givenPin)) {
                    sendText(out, "403 Forbidden", "PIN yanlisdir")
                    return
                }
            }

            when {
                isPage -> sendHtml(out, buildMobileWebPage())

                method == "GET" && path == "/api/list" -> {
                    val arr = JSONArray()
                    _items.value.forEach { it ->
                        arr.put(JSONObject().apply {
                            put("id", it.id)
                            put("name", it.fileName)
                            put("size", it.sizeBytes)
                            put("direction", it.direction)
                            put("isImage", it.isImage)
                            put("downloadUrl", "/download?file=${URLEncoder.encode(it.id, "UTF-8")}")
                        })
                    }
                    sendJson(out, arr.toString())
                }

                method == "GET" && path == "/download" -> {
                    val fileId = URLDecoder.decode(queryParam(query, "file"), "UTF-8")
                    val target = File(getTransferFolder(context), File(fileId).name)
                    if (target.exists()) {
                        sendFile(out, target)
                    } else {
                        sendText(out, "404 Not Found", "Fayl tapilmadi")
                    }
                }

                method == "POST" && path == "/upload" -> {
                    val contentLength = headersMap["content-length"]?.toLongOrNull() ?: 0L
                    if (contentLength <= 0L || contentLength > MAX_UPLOAD_BYTES) {
                        sendText(out, "413 Payload Too Large", "Fayl həddindən böyükdür")
                        return
                    }
                    val rawName = URLDecoder.decode(headersMap["x-file-name"] ?: "telefon_sekil_${System.currentTimeMillis()}.jpg", "UTF-8")
                    val safeName = "PHONE_${System.currentTimeMillis()}_${rawName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
                    val outFile = File(getTransferFolder(context), safeName)

                    var remaining = contentLength
                    val buf = ByteArray(8192)
                    FileOutputStream(outFile).use { fos ->
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read <= 0) break
                            fos.write(buf, 0, read)
                            remaining -= read
                        }
                    }

                    MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
                    refreshFileList(context)
                    _latestIncomingAlert.value = _items.value.firstOrNull { it.id == safeName }
                    sendJson(out, JSONObject().put("status", "ok").put("file", safeName).toString())
                }

                else -> sendText(out, "404 Not Found", "Tapilmadi")
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendJson(out: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendText(out: OutputStream, status: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendFile(out: OutputStream, file: File) {
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
        val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Disposition: attachment; filename=\"${file.name.removePrefix("CAR_").removePrefix("PHONE_")}\"\r\n" +
                "Content-Length: ${file.length()}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    /**
     * Telefon səhifəsi. Qeyd: bu Kotlin raw string-dir, ona görə JS-də ${...} istifadə edilmir.
     * PIN istifadəçi tərəfindən daxil edilir və hər sorğuda X-Pin başlığı ilə göndərilir.
     */
    private fun buildMobileWebPage(): String {
        return """
<!DOCTYPE html>
<html lang="az">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Q07 Telefon - Maşın Transfer</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 18px; }
  .card { background: #1e293b; border-radius: 18px; padding: 18px; margin-bottom: 16px; border: 1px solid #334155; }
  h2, h3 { margin-top: 0; color: #38bdf8; }
  input[type=text], input[type=file] { width: 100%; padding: 14px; background: #0f172a; border: 1px dashed #38bdf8; border-radius: 12px; color: #cbd5e1; box-sizing: border-box; margin-bottom: 12px; }
  input[type=text] { border-style: solid; font-size: 20px; letter-spacing: 4px; text-align: center; }
  button { width: 100%; padding: 15px; background: #0284c7; color: #fff; border: none; border-radius: 12px; font-size: 16px; font-weight: bold; cursor: pointer; }
  .item { display: flex; justify-content: space-between; align-items: center; padding: 12px; background: #0f172a; border-radius: 12px; margin-top: 10px; }
  .item a { background: #10b981; color: #fff; padding: 8px 14px; border-radius: 8px; text-decoration: none; font-weight: bold; font-size: 14px; }
  .badge { font-size: 11px; padding: 2px 6px; border-radius: 6px; background: #334155; color: #38bdf8; }
</style>
</head>
<body>
  <div class="card">
    <h2>Changan Qiyuan Q07</h2>
    <p style="color:#94a3b8;font-size:14px;">Maşının ekranında göstərilən 6 rəqəmli PIN-i daxil edin:</p>
    <input type="text" id="pinInput" inputmode="numeric" maxlength="6" placeholder="------">
    <button onclick="setPin()">Daxil ol</button>
    <p id="pinStatus" style="color:#f87171;font-weight:bold;margin-top:10px;"></p>
  </div>

  <div class="card">
    <h2>Telefondan Maşına</h2>
    <input type="file" id="fileInput" multiple>
    <button onclick="uploadFiles()">Maşına Göndər</button>
    <p id="status" style="color:#10b981;font-weight:bold;margin-top:10px;"></p>
  </div>

  <div class="card">
    <h3>Maşından Telefona</h3>
    <div id="fileList">PIN daxil edin...</div>
  </div>

<script>
var pin = '';

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function setPin() {
  pin = document.getElementById('pinInput').value.trim();
  loadList();
}

async function uploadFiles() {
  var input = document.getElementById('fileInput');
  var status = document.getElementById('status');
  if (!pin) { alert('Əvvəlcə PIN daxil edin!'); return; }
  if (!input.files.length) { alert('Əvvəlcə şəkil və ya fayl seçin!'); return; }
  for (var i = 0; i < input.files.length; i++) {
    var file = input.files[i];
    status.innerText = 'Göndərilir: ' + file.name + '...';
    var res = await fetch('/upload', {
      method: 'POST',
      headers: { 'X-File-Name': encodeURIComponent(file.name), 'X-Pin': pin },
      body: file
    });
    if (!res.ok) { status.innerText = 'Xəta: ' + (await res.text()); return; }
  }
  status.innerText = 'Uğurla maşına göndərildi!';
  input.value = '';
  loadList();
}

async function loadList() {
  if (!pin) return;
  var container = document.getElementById('fileList');
  var res = await fetch('/api/list', { headers: { 'X-Pin': pin } });
  if (!res.ok) {
    document.getElementById('pinStatus').innerText = 'PIN yanlışdır';
    container.innerHTML = '';
    return;
  }
  document.getElementById('pinStatus').innerText = '';
  var list = await res.json();
  if (!list.length) { container.innerHTML = '<p style="color:#64748b;">Hələ fayl yoxdur.</p>'; return; }
  container.innerHTML = list.map(function (f) {
    return '<div class="item"><div><span class="badge">' +
      (f.direction === 'FROM_CAR' ? 'Maşından' : 'Telefondan') +
      '</span><div style="margin-top:4px;font-size:14px;">' + esc(f.name) + '</div></div>' +
      '<a href="' + esc(f.downloadUrl) + '&pin=' + encodeURIComponent(pin) + '">Yüklə</a></div>';
  }).join('');
}

setInterval(loadList, 4000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
