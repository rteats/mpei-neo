package com.rteats.mpeineo.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sun.mail.imap.IMAPFolder
import java.security.KeyStore
import java.util.Date
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.mail.BodyPart
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MailCredentials(val username: String, val password: String)
data class MailSummary(
    val uid: Long,
    val uidValidity: Long,
    val sender: String,
    val subject: String,
    val sentAt: Long,
    val unread: Boolean,
    val hasAttachment: Boolean,
)
data class MailAttachment(
    val name: String,
    val mimeType: String,
    val size: Int,
    val partPath: List<Int>,
)
data class MailDetail(
    val uid: Long,
    val sender: String,
    val subject: String,
    val sentAt: Long,
    val body: String,
    val attachments: List<MailAttachment>,
)

/** The password is never written in plaintext, the logs, or app backups. */
class MailCredentialsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mail_imap_auth", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.mail.aes.gcm.v1"

    fun load(): MailCredentials? = runCatching {
        val username = prefs.getString("username", null).orEmpty()
        val encrypted = prefs.getString("password", null) ?: return@runCatching null
        val data = Base64.decode(encrypted, Base64.NO_WRAP)
        if (data.size <= 12) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        MailCredentials(username, String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }.getOrNull()

    fun save(credentials: MailCredentials) {
        require(credentials.username.isNotBlank() && credentials.password.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(credentials.password.toByteArray(Charsets.UTF_8))
        check(prefs.edit()
            .putString("username", credentials.username.trim())
            .putString("password", Base64.encodeToString(data, Base64.NO_WRAP))
            .commit())
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}

/**
 * IMAPS only; opens INBOX READ_ONLY and enables PEEK so fetching full bodies
 * does not mark messages read. Each operation uses a fresh, short-lived session.
 */
class MailRepository(private val context: Context) {
    val credentials = MailCredentialsStore(context)
    private val tls = MailTlsTrust(context)

    internal suspend fun inspectServerCertificate(): MailServerCertificate = tls.inspectServer()
    internal fun trustCertificate(certificate: MailServerCertificate) = tls.trust(certificate)
    fun forgetTrustedCertificate() = tls.forget()

    private fun <T> withInbox(auth: MailCredentials, action: (IMAPFolder) -> T): T {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "mail.mpei.ru")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            // Android system trust plus an optional user-approved SHA-256 leaf pin.
            // Do not use mail.imaps.ssl.trust or bypass hostname validation.
            put("mail.imaps.ssl.socketFactory", tls.socketFactory())
            put("mail.imaps.ssl.socketFactory.fallback", "false")
            put("mail.imaps.starttls.enable", "false")
            put("mail.imaps.peek", "true")
            put("mail.imaps.connectiontimeout", "12000")
            put("mail.imaps.timeout", "15000")
            put("mail.imaps.writetimeout", "15000")
            put("mail.imaps.partialfetch", "true")
            put("mail.imaps.fetchsize", "16384")
            put("mail.debug", "false")
        }
        val store: Store = Session.getInstance(properties).getStore("imaps")
        try {
            store.connect("mail.mpei.ru", 993, auth.username, auth.password)
            val folder = store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try { return action(folder) } finally { folder.close(false) }
        } finally { store.close() }
    }

    suspend fun listInbox(auth: MailCredentials, maxMessages: Int = 60): List<MailSummary> =
        withContext(Dispatchers.IO) {
            withInbox(auth) { folder ->
                val total = folder.messageCount
                if (total <= 0) return@withInbox emptyList()
                val messages = folder.getMessages((total - maxMessages + 1).coerceAtLeast(1), total)
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add("Content-Type")
                    add("Content-Disposition")
                }
                folder.fetch(messages, fp)
                messages.reversed().map { message ->
                    MailSummary(
                        uid = folder.getUID(message),
                        uidValidity = folder.uidValidity,
                        sender = sender(message),
                        subject = message.subject.orEmpty(),
                        sentAt = (message.receivedDate ?: message.sentDate ?: Date()).time,
                        unread = !message.isSet(Flags.Flag.SEEN),
                        hasAttachment = message.contentType?.contains("multipart", true) == true,
                    )
                }
            }
        }

    suspend fun readMessage(auth: MailCredentials, uid: Long, validity: Long): MailDetail =
        withContext(Dispatchers.IO) {
            withInbox(auth) { folder ->
                require(folder.uidValidity == validity) { "Папка почты изменилась. Обновите входящие." }
                val message = folder.getMessageByUID(uid)
                    ?: throw IllegalStateException("Письмо больше не найдено. Обновите входящие.")
                val collector = BodyCollector()
                collectParts(message, emptyList(), collector)
                MailDetail(
                    uid = uid,
                    sender = sender(message),
                    subject = message.subject.orEmpty(),
                    sentAt = (message.receivedDate ?: message.sentDate ?: Date()).time,
                    body = collector.plain ?: collector.html.orEmpty(),
                    attachments = collector.attachments,
                )
            }
        }

    suspend fun saveAttachment(
        auth: MailCredentials,
        uid: Long,
        validity: Long,
        attachment: MailAttachment,
    ): String = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Автосохранение в Загрузки поддерживается на Android 10 и новее."
        }
        withInbox(auth) { folder ->
            require(folder.uidValidity == validity) { "Папка почты изменилась. Обновите входящие." }
            val message = folder.getMessageByUID(uid)
                ?: throw IllegalStateException("Письмо не найдено")
            var part: Part = message
            attachment.partPath.forEach { index ->
                val multipart = part.content as? Multipart
                    ?: throw IllegalStateException("Формат вложения изменился")
                part = multipart.getBodyPart(index)
            }
            val safeName = attachment.name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
                .take(160).ifBlank { "attachment" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, attachment.mimeType.substringBefore(';'))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MPEI Neo")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Не удалось создать файл в Загрузках")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    part.inputStream.use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Не удалось записать вложение")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                "Загрузки/MPEI Neo/$safeName"
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    private fun sender(message: Message): String {
        val from = message.from?.firstOrNull()
        return if (from is InternetAddress) {
            from.personal?.takeIf { it.isNotBlank() } ?: from.address.orEmpty()
        } else from?.toString().orEmpty()
    }

    private class BodyCollector {
        var plain: String? = null
        var html: String? = null
        val attachments = ArrayList<MailAttachment>()
    }

    private fun collectParts(part: Part, path: List<Int>, result: BodyCollector) {
        val filename = runCatching { part.fileName }.getOrNull()
        val attachment = filename != null ||
            part.disposition?.equals(Part.ATTACHMENT, ignoreCase = true) == true
        if (attachment) {
            result.attachments += MailAttachment(
                name = decodeMailAttachmentName(filename),
                mimeType = part.contentType.substringBefore(';').ifBlank { "application/octet-stream" },
                size = part.size,
                partPath = path,
            )
            return
        }
        when {
            part.isMimeType("text/plain") -> {
                if (result.plain == null) result.plain = part.content as? String
            }
            part.isMimeType("text/html") -> {
                if (result.html == null) result.html =
                    android.text.Html.fromHtml(part.content as? String ?: "", android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            }
            part.isMimeType("multipart/*") -> {
                val parts = part.content as? Multipart ?: return
                for (i in 0 until parts.count) {
                    collectParts(parts.getBodyPart(i), path + i, result)
                }
            }
            part.isMimeType("message/rfc822") -> {
                val nested = part.content as? Part ?: return
                collectParts(nested, path, result)
            }
        }
    }
}
