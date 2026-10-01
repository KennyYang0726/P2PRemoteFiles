package com.p2premotefiles.app.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class DeviceIdentityRepository {
    fun ensureKey(): PublicKey {
        val keyStore = keyStore()
        val existing = keyStore.getCertificate(KEY_ALIAS)?.publicKey
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    fun publicKeyEncoded(): ByteArray = ensureKey().encoded

    fun publicKeyEncodedBase64(): String = Base64.encodeToString(publicKeyEncoded(), Base64.NO_WRAP)

    fun publicKeyFingerprint(): String = fingerprint(ensureKey())

    fun sign(payload: ByteArray): ByteArray {
        ensureKey()
        val privateKey = keyStore().getKey(KEY_ALIAS, null)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(payload)
        return signature.sign()
    }

    fun verify(publicKey: PublicKey, payload: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(payload)
        signature.verify(signatureBytes)
    }.getOrDefault(false)

    fun decodePublicKey(base64: String): PublicKey {
        val encoded = Base64.decode(base64, Base64.DEFAULT)
        return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC)
            .generatePublic(X509EncodedKeySpec(encoded))
    }

    fun fingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "p2p_remote_files_device_identity_v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
