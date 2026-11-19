package dev.chungjungsoo.gptmobile.data.pairing

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class OwnerInvitation(val origin: String, val pin: String, val code: String) {
    companion object {
        fun parse(raw: String): OwnerInvitation {
            require(raw.length <= 2048)
            val uri = Uri.parse(raw)
            require(uri.scheme == "gptmobile" && uri.encodedAuthority == "gateway-owner" && uri.path.isNullOrEmpty() && uri.fragment == null)
            require(uri.queryParameterNames == setOf("host", "port", "pin", "code") && uri.queryParameterNames.all { uri.getQueryParameters(it).size == 1 })
            val host = uri.getQueryParameter("host").orEmpty()
            val octets = host.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 && n.toString() == it } ?: error("Invalid address") }
            require(octets.size == 4 && (octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31))
            val port = uri.getQueryParameter("port")!!.toInt()
            require(port in 1024..65535)
            val pin = uri.getQueryParameter("pin").orEmpty()
            val code = uri.getQueryParameter("code").orEmpty()
            require(pin.matches(Regex("[0-9a-f]{64}")) && code.matches(Regex("[A-Za-z0-9_-]{43}")))
            return OwnerInvitation("https://$host:$port", pin, code)
        }
    }
}

internal data class OwnerCredential(val origin: String, val pin: String, val alias: String, val cert: String, val dataCert: String, val device: String, val role: String)

/** Only public material is stored here. The private key remains inside AndroidKeyStore. */
internal object OwnerGateway {
    private val credentials = java.util.concurrent.ConcurrentHashMap<String, OwnerCredential>()
    private var directory: java.io.File? = null
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun encoded(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)

    @Synchronized
    fun initialize(context: Context) {
        directory = java.io.File(context.noBackupFilesDir, "gateway-owners").also { it.mkdirs() }
        directory?.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            runCatching {
                val v = JSONObject(file.readText())
                val entry = OwnerCredential(v.getString("origin"), v.getString("pin"), v.getString("alias"), v.getString("cert"), v.getString("dataCert"), v.getString("device"), v.getString("role"))
                credentials[entry.origin] = entry
            }
        }
    }

    fun pairedDevices() = credentials.values.sortedBy { it.origin }

    fun find(origin: String) = credentials[origin]
    fun forUrl(url: String) = runCatching {
        val uri = URI(url)
        if (uri.scheme == "https" && uri.userInfo == null) find("https://${uri.host}:${uri.port}") else null
    }.getOrNull()

    private fun certificate(text: String) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(text, Base64.DEFAULT))) as X509Certificate

    fun configure(builder: OkHttpClient.Builder, pin: String, entry: OwnerCredential? = null, management: Boolean = false) {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Unsupported client trust")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Missing Gateway certificate")
                leaf.checkValidity()
                val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (digest != pin) throw CertificateException("Gateway certificate changed")
            }
        }
        val manager = entry?.let { grant ->
            val leaf = certificate(if (management) grant.cert else grant.dataCert)
            val privateKey = keyStore().getKey(grant.alias, null) as? PrivateKey ?: error("Pair this device again.")
            object : X509KeyManager {
                override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = grant.alias.takeIf { types?.contains("EC") == true }
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (keyType == "EC") arrayOf(grant.alias) else null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
                override fun getCertificateChain(alias: String?) = if (alias == grant.alias) arrayOf(leaf) else null
                override fun getPrivateKey(alias: String?) = privateKey.takeIf { alias == grant.alias }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(manager?.let { arrayOf(it) }, arrayOf(trust), null) }
        // The default hostname verifier also checks the exact IP SAN. Never share a pool across grants.
        builder.sslSocketFactory(ssl.socketFactory, trust).connectionPool(ConnectionPool()).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    }

    private class RejectedChange(message: String) : IllegalStateException(message)

    private fun exchange(origin: String, pin: String, entry: OwnerCredential?, path: String, body: JSONObject?): JSONObject {
        val builder = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
        configure(builder, pin, entry, management = true)
        val client = builder.build()
        try {
            val req = Request.Builder().url(origin + path)
            body?.let { req.post(it.toString().toRequestBody("application/json".toMediaType())) }
            return client.newCall(req.build()).execute().use { res ->
                if (res.code == 409 || res.code == 400) throw RejectedChange("Gateway rejected this change. Refresh its current settings.")
                check(res.isSuccessful) {
                    when (res.code) {
                        428 -> "Confirm this device on Windows · code ${entry?.device?.removePrefix("phone:")?.take(12)?.uppercase()}."
                        403 -> "The device grant or invitation is unavailable."
                        else -> "Gateway returned HTTP ${res.code}."
                    }
                }
                val input = requireNotNull(res.body).source()
                check(!input.request(32769)) { "Gateway response exceeds limit." }
                JSONObject(input.readUtf8())
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }

    suspend fun pair(invitation: OwnerInvitation): OwnerCredential = withContext(Dispatchers.IO) {
        check(find(invitation.origin) == null) { "Remove the existing phone pairing first." }
        val alias = "gptmobile-gateway-" + UUID.randomUUID()
        val pair = KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build()
            )
        }.generateKeyPair()
        try {
            val body = JSONObject().put("code", invitation.code).put("name", "Android").put("public_key", encoded(pair.public.encoded))
            val res = exchange(invitation.origin, invitation.pin, null, "/gateway/owner/pair", body)
            val cert = res.getString("certificate")
            require(certificate(cert).publicKey.encoded.contentEquals(pair.public.encoded))
            val dataCert = res.getString("data_certificate")
            require(certificate(dataCert).publicKey.encoded.contentEquals(pair.public.encoded))
            val entry = OwnerCredential(invitation.origin, invitation.pin, alias, cert, dataCert, res.getString("device"), res.getString("role"))
            val json = JSONObject().put("origin", entry.origin).put("pin", entry.pin).put("alias", alias).put("cert", cert).put("dataCert", dataCert).put("device", entry.device).put("role", entry.role)
            synchronized(this@OwnerGateway) {
                check(find(entry.origin) == null)
                val file = android.util.AtomicFile(java.io.File(requireNotNull(directory), "$alias.json"))
                val stream = file.startWrite()
                try {
                    stream.write(json.toString().toByteArray())
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
                credentials[entry.origin] = entry
            }
            entry
        } catch (failure: Exception) {
            keyStore().deleteEntry(alias)
            throw failure
        }
    }

    private fun pendingFile(entry: OwnerCredential) =
        android.util.AtomicFile(java.io.File(requireNotNull(directory), entry.alias + ".pending"))

    private fun observedFile(entry: OwnerCredential) =
        android.util.AtomicFile(java.io.File(requireNotNull(directory), entry.alias + ".observed"))

    suspend fun lastConfirmed(entry: OwnerCredential): JSONObject? = withContext(Dispatchers.IO) {
        if (find(entry.origin) != entry) return@withContext null
        runCatching { JSONObject(observedFile(entry).readFully().decodeToString()) }.getOrNull()
            ?.takeIf { it.optString("device") == entry.device && it.optString("protocol") == "controlSettings.v1" }
    }

    private fun rememberConfirmed(entry: OwnerCredential, state: JSONObject) {
        val file = observedFile(entry)
        val stream = file.startWrite()
        try {
            state.put("observed_at", System.currentTimeMillis())
            stream.write(state.toString().toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private val commandLock = kotlinx.coroutines.sync.Mutex()

    suspend fun settings(entry: OwnerCredential, body: JSONObject? = null, retry: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        commandLock.withLock {
            check(find(entry.origin) == entry)
            val file = pendingFile(entry)
            val previous = try {
                JSONObject(file.readFully().decodeToString())
            } catch (_: java.io.FileNotFoundException) {
                null
            }
            check(body == null || previous == null) { "Resolve the previous change first." }
            val command = body ?: previous
            if (body != null) {
                val stream = file.startWrite()
                try {
                    stream.write(body.toString().toByteArray())
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
            }
            val id = command?.getString("command_id")
            val sending = body != null || retry && previous != null
            val path = "/gateway/owner/settings" + if (!sending && id != null) "?command_id=$id" else ""
            try {
                val state = exchange(entry.origin, entry.pin, entry, path, if (sending) command else null)
                check(state.getString("device") == entry.device && state.getString("protocol") == "controlSettings.v1")
                rememberConfirmed(entry, state)
                if (id != null && state.getString("command_id") == id && state.getString("command_state") !in setOf("unconfirmed", "queued", "saved")) file.delete()
                state
            } catch (failure: RejectedChange) {
                file.delete()
                throw failure
            }
        }
    }

    suspend fun remove(entry: OwnerCredential, revoke: Boolean) = withContext(Dispatchers.IO) {
        if (revoke) exchange(entry.origin, entry.pin, entry, "/gateway/owner/revoke", JSONObject())
        synchronized(this@OwnerGateway) {
            if (credentials.remove(entry.origin, entry)) {
                observedFile(entry).delete()
                pendingFile(entry).delete()
                keyStore().deleteEntry(entry.alias)
                java.io.File(requireNotNull(directory), entry.alias + ".json").delete()
            }
        }
    }
}
