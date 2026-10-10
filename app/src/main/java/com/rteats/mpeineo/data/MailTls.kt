package com.rteats.mpeineo.data

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MPEI_MAIL_HOST = "mail.mpei.ru"
internal const val MPEI_MAIL_PORT = 993

internal data class MailServerCertificate(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val validFrom: Long,
    val validUntil: Long,
    val dnsNames: List<String>,
    val chainIssuerSha256: String?,
) {
    val currentlyValid: Boolean
        get() = System.currentTimeMillis() in validFrom..validUntil

    val matchesMailHostname: Boolean
        get() {
            val names = dnsNames.ifEmpty {
                listOfNotNull(
                    Regex("(?:^|,)CN=([^,]+)", RegexOption.IGNORE_CASE)
                        .find(subject)?.groupValues?.getOrNull(1),
                )
            }
            return names.any { name ->
                name.equals(MPEI_MAIL_HOST, ignoreCase = true) ||
                    (name.startsWith("*.") &&
                        MPEI_MAIL_HOST.substringAfter('.', "").equals(
                            name.drop(2), ignoreCase = true,
                        ))
            }
        }
}

/**
 * Cert exception applies ONLY to the exact server leaf certificate, and only
 * after the user explicitly reviews and pins its SHA-256 fingerprint.
 * Other connections continue to use the default Android trust configuration.
 * A new server certificate never gets trusted automatically.
 */
internal class MailTlsTrust(context: Context) {
    private val preferences = context.getSharedPreferences("mail_tls_pin_v1", Context.MODE_PRIVATE)
    private val trustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    fun savedFingerprint(): String? =
        preferences.getString("leaf_sha256", null)?.takeIf { it.matches(Regex("[0-9A-F]{64}")) }

    fun forget() {
        preferences.edit().remove("leaf_sha256").apply()
    }

    fun trust(certificate: MailServerCertificate) {
        require(certificate.currentlyValid) { "Срок действия сертификата истёк." }
        require(certificate.matchesMailHostname) {
            "Сертификат не предназначен для mail.mpei.ru."
        }
        require(certificate.sha256.matches(Regex("[0-9A-F]{64}")))
        check(preferences.edit().putString("leaf_sha256", certificate.sha256).commit())
    }

    fun socketFactory(): SSLSocketFactory {
        val pinned = savedFingerprint()
        val validator = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                trustManager.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                trustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (pinned == null) {
                    trustManager.checkServerTrusted(chain, authType)
                    return
                }
                // Once approved, fail closed on any certificate rotation,
                // including if the replacement chains to a public CA.
                val leaf = chain.firstOrNull()
                    ?: throw CertificateException("Сервер не представил сертификат")
                if (certificateFingerprint(leaf) != pinned) {
                    throw CertificateException("Сертификат почтового сервера изменился (SHA-256 pin mismatch)")
                }
                // TLS also verifies server possession of the leaf private key.
                // JavaMail checks the hostname separately.
                leaf.checkValidity(Date())
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(validator), SecureRandom())
        }.socketFactory
    }

    /**
     * A separate unauthenticated TLS probe. Intentionally rejects the chain
     * after observing it, so no mail credentials are sent and no exception is
     * granted before explicit verification and approval.
     */
    suspend fun inspectServer(): MailServerCertificate = withContext(Dispatchers.IO) {
        var offeredChain: Array<X509Certificate>? = null
        val recorder = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Mail TLS certificate inspection only")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                offeredChain = chain.clone()
                throw CertificateException("Mail TLS certificate inspection only")
            }
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(recorder), SecureRandom())
        }
        val tcp = Socket()
        tcp.connect(InetSocketAddress(MPEI_MAIL_HOST, MPEI_MAIL_PORT), 12_000)
        try {
            val ssl = context.socketFactory.createSocket(
                tcp, MPEI_MAIL_HOST, MPEI_MAIL_PORT, true,
            ) as SSLSocket
            ssl.use {
                it.soTimeout = 12_000
                try {
                    it.startHandshake()
                } catch (_: javax.net.ssl.SSLHandshakeException) {
                    // The recorder deliberately rejects the chain.
                }
            }
        } finally {
            if (!tcp.isClosed) tcp.close()
        }
        val chain = offeredChain ?: throw IllegalStateException(
            "Не удалось получить сертификат сервера. Проверьте подключение к сети.",
        )
        val leaf = chain.first()
        val alternativeNames = runCatching {
            leaf.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                if (entry.firstOrNull() == 2) entry.getOrNull(1)?.toString() else null
            }
        }.getOrDefault(emptyList())
        MailServerCertificate(
            sha256 = certificateFingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            validFrom = leaf.notBefore.time,
            validUntil = leaf.notAfter.time,
            dnsNames = alternativeNames,
            chainIssuerSha256 = chain.lastOrNull()
                ?.takeIf { it !== leaf }?.let(::certificateFingerprint),
        )
    }

    companion object {
        fun certificateFingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

        fun fingerprintForDisplay(hex: String): String = hex.chunked(2).joinToString(":")
    }
}
