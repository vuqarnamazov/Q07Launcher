package az.q07.launcher.scanner

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM şifrələmə — öyrənilmiş yolların (gedişatların) diskdə saxlanması üçün.
 *
 * Açar Android Keystore-da saxlanılır (`q07_flows_aes256_v1`), heç vaxt fayla düşmür.
 * Fayl formatı: `Q07ENC1:<base64(iv + ciphertext + tag)>`
 * Köhnə (şifrələnməmiş) JSON faylları oxunur və ilk yazılışda avtomatik şifrələnir.
 */
object FlowCrypto {

    private const val ALIAS = "q07_flows_aes256_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Şifrələnmiş mətnin prefiksi. */
    const val MAGIC = "Q07ENC1:"

    fun isEncrypted(text: String): Boolean = text.startsWith(MAGIC)

    @Synchronized
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** Açıq mətni şifrələyir; alınmasa `null`. */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val out = ByteArray(IV_BYTES + cipher.getOutputSize(plain.toByteArray().size))
        System.arraycopy(iv, 0, out, 0, IV_BYTES)
        val n = cipher.doFinal(plain.toByteArray(), 0, plain.toByteArray().size, out, IV_BYTES)
        MAGIC + Base64.encodeToString(out.copyOf(IV_BYTES + n), Base64.NO_WRAP)
    }.getOrNull()

    /** Şifrələnmiş mətni açır; açar/format uyğun deyilsə `null`. */
    fun decrypt(payload: String): String? = runCatching {
        val raw = Base64.decode(payload.removePrefix(MAGIC), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }.getOrNull()
}
