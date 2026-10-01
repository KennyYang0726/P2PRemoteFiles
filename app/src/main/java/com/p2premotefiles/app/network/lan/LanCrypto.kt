package com.p2premotefiles.app.network.lan

import android.util.Base64
import com.p2premotefiles.app.app.DeviceIdentityRepository
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AuthenticatedPeer(
    val deviceId: String,
    val publicKey: PublicKey,
    val fingerprint: String,
)

internal object LanCrypto {
    private val secureRandom = SecureRandom()

    fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    fun randomNonce(size: Int = 32): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    fun decodeEcPublicKey(value: String): PublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(decode(value)))

    fun clientHelloPayload(
        deviceId: String,
        identityPublicKey: ByteArray,
        ephemeralPublicKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray = canonicalPayload(
        "client-hello",
        deviceId,
        encode(identityPublicKey),
        encode(ephemeralPublicKey),
        encode(nonce),
    )

    fun serverHelloPayload(
        serverDeviceId: String,
        serverIdentityPublicKey: ByteArray,
        serverEphemeralPublicKey: ByteArray,
        serverNonce: ByteArray,
        clientPayload: ByteArray,
    ): ByteArray = canonicalPayload(
        "server-hello",
        serverDeviceId,
        encode(serverIdentityPublicKey),
        encode(serverEphemeralPublicKey),
        encode(serverNonce),
        encode(MessageDigest.getInstance("SHA-256").digest(clientPayload)),
    )

    fun deriveSessionKey(
        localEphemeral: KeyPair,
        remoteEphemeralPublicKey: PublicKey,
        clientPayload: ByteArray,
        serverPayload: ByteArray,
    ): Pair<SecretKey, ByteArray> {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localEphemeral.private)
        agreement.doPhase(remoteEphemeralPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        val transcriptHash = MessageDigest.getInstance("SHA-256").digest(clientPayload + serverPayload)
        val key = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = transcriptHash,
            info = "P2PRemoteFiles-LAN-v${LanProtocol.VERSION}".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32,
        )
        sharedSecret.fill(0)
        return SecretKeySpec(key, "AES") to transcriptHash
    }

    fun fingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalPayload(vararg parts: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            parts.forEach { part ->
                val bytes = part.toByteArray(StandardCharsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
        return output.toByteArray()
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extractMac.doFinal(inputKeyMaterial)

        val output = ByteArray(outputLength)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < outputLength) {
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
            expandMac.update(previous)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            previous = expandMac.doFinal()
            val count = minOf(previous.size, outputLength - written)
            previous.copyInto(output, written, 0, count)
            written += count
            counter++
        }
        pseudoRandomKey.fill(0)
        previous.fill(0)
        return output
    }
}

internal class PlainJsonChannel(
    inputStream: InputStream,
    outputStream: OutputStream,
) {
    constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream())

    private val input = DataInputStream(inputStream)
    private val output = DataOutputStream(BufferedOutputStream(outputStream, 64 * 1024))

    @Synchronized
    fun send(message: JSONObject) {
        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= LanProtocol.MAX_PLAIN_FRAME_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): JSONObject {
        val length = input.readInt()
        if (length <= 0 || length > LanProtocol.MAX_PLAIN_FRAME_BYTES) {
            throw IllegalStateException("Invalid LAN handshake frame length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(String(bytes, StandardCharsets.UTF_8))
    }
}

internal class SecureJsonChannel(
    inputStream: InputStream,
    outputStream: OutputStream,
    private val key: SecretKey,
    private val transcriptHash: ByteArray,
) {
    constructor(socket: Socket, key: SecretKey, transcriptHash: ByteArray) : this(
        socket.getInputStream(),
        socket.getOutputStream(),
        key,
        transcriptHash,
    )

    private val input = DataInputStream(inputStream)
    private val output = DataOutputStream(BufferedOutputStream(outputStream, 64 * 1024))
    private val secureRandom = SecureRandom()
    private var sendSequence = 0L
    private var receiveSequence = 0L

    @Synchronized
    fun send(message: JSONObject) {
        val plain = message.toString().toByteArray(StandardCharsets.UTF_8)
        val sequence = sendSequence++
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(sequence))
        val ciphertext = cipher.doFinal(plain)
        val frameLength = 8 + iv.size + ciphertext.size
        require(frameLength <= LanProtocol.MAX_SECURE_FRAME_BYTES)
        output.writeInt(frameLength)
        output.writeLong(sequence)
        output.write(iv)
        output.write(ciphertext)
        output.flush()
    }

    fun receive(): JSONObject {
        val frameLength = try {
            input.readInt()
        } catch (e: EOFException) {
            throw e
        }
        if (frameLength < 8 + 12 + 16 || frameLength > LanProtocol.MAX_SECURE_FRAME_BYTES) {
            throw IllegalStateException("Invalid encrypted LAN frame length")
        }
        val sequence = input.readLong()
        if (sequence != receiveSequence) {
            throw SecurityException("Unexpected LAN frame sequence")
        }
        receiveSequence++
        val iv = ByteArray(12)
        input.readFully(iv)
        val ciphertext = ByteArray(frameLength - 8 - iv.size)
        input.readFully(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(sequence))
        val plain = cipher.doFinal(ciphertext)
        return JSONObject(String(plain, StandardCharsets.UTF_8))
    }

    private fun aad(sequence: Long): ByteArray {
        val buffer = ByteBuffer.allocate(8 + transcriptHash.size)
        buffer.putLong(sequence)
        buffer.put(transcriptHash)
        return buffer.array()
    }
}

internal fun createClientHello(
    deviceId: String,
    identity: DeviceIdentityRepository,
    ephemeral: KeyPair,
    nonce: ByteArray,
): Pair<JSONObject, ByteArray> {
    val identityPublic = identity.publicKeyEncoded()
    val payload = LanCrypto.clientHelloPayload(
        deviceId = deviceId,
        identityPublicKey = identityPublic,
        ephemeralPublicKey = ephemeral.public.encoded,
        nonce = nonce,
    )
    return JSONObject()
        .put("type", "client_hello")
        .put("protocol", LanProtocol.VERSION)
        .put("deviceId", deviceId)
        .put("identityKey", LanCrypto.encode(identityPublic))
        .put("ephemeralKey", LanCrypto.encode(ephemeral.public.encoded))
        .put("nonce", LanCrypto.encode(nonce))
        .put("signature", LanCrypto.encode(identity.sign(payload))) to payload
}
