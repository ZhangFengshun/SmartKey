package dev.lovekey.open.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encryption keys never leave AndroidKeyStore. Ciphertexts are bound to their storage purpose. */
internal class SecurePreferences {
    fun encrypt(plaintext: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .toString()
    }

    fun decrypt(ciphertext: String, purpose: String): String {
        if (ciphertext.length > 2_097_152) throw GeneralSecurityException("Invalid encrypted data")
        val objectValue = JSONObject(ciphertext)
        if (objectValue.getInt("version") != 1) throw GeneralSecurityException("Unsupported encrypted data")
        val iv = Base64.decode(objectValue.getString("iv"), Base64.NO_WRAP)
        if (iv.size != 12) throw GeneralSecurityException("Invalid IV")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(objectValue.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw GeneralSecurityException("Device encryption key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "dev.lovekey.open.secure-storage.v1"
    }
}
