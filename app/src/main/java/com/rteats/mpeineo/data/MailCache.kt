package com.rteats.mpeineo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private encrypted IMAP metadata and message-body cache, excluded from backups. */
internal class MailCache(private val context: Context) {
    private val gson = Gson()
    private val alias = context.packageName + ".mail.cache.aes.v1"
    private val folder get() = File(context.noBackupFilesDir, "mail-cache").apply { mkdirs() }
    private fun keyForAccount(username: String) =
        MessageDigest.getInstance("SHA-256")
            .digest(username.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun file(username: String, key: String) =
        File(folder, keyForAccount(username) + "_" + key + ".bin")

    @Synchronized fun inbox(username: String): MailInboxSnapshot? =
        read(file(username, "inbox"), CachedInbox::class.java)
            ?.let { MailInboxSnapshot(it.validity, it.items.take(60)) }

    @Synchronized fun saveInbox(username: String, snapshot: MailInboxSnapshot) {
        write(file(username, "inbox"), CachedInbox(snapshot.uidValidity, snapshot.messages.take(60)))
    }

    @Synchronized fun message(username: String, uid: Long, validity: Long): MailDetail? =
        read(file(username, "body_${validity}_${uid}"), CachedBody::class.java)
            ?.takeIf { it.validity == validity && it.detail.uid == uid }?.detail

    @Synchronized fun saveMessage(username: String, uid: Long, validity: Long, detail: MailDetail) {
        write(file(username, "body_${validity}_${uid}"), CachedBody(validity, detail))
        val prefix = keyForAccount(username) + "_body_"
        val files = folder.listFiles()?.filter { it.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        files.forEachIndexed { index, item ->
            if (index >= 24 || item.lastModified() < cutoff) item.delete()
        }
    }

    @Synchronized fun clear(username: String) {
        val prefix = keyForAccount(username) + "_"
        folder.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun aesKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
        }.generateKey()
    }

    private fun <T> read(file: File, cls: Class<T>): T? = runCatching {
        if (!file.isFile || file.length() > 1_000_000) return@runCatching null
        val bytes = file.readBytes()
        if (bytes.size <= 12) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), cls)
    }.getOrNull()

    private fun write(file: File, value: Any) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey())
            val payload = cipher.iv + cipher.doFinal(gson.toJson(value).toByteArray(Charsets.UTF_8))
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeBytes(payload)
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
        }
    }

    private data class CachedInbox(val validity: Long, val items: List<MailSummary>)
    private data class CachedBody(val validity: Long, val detail: MailDetail)
}

/** Only unseen headers are fetched; existing envelopes reuse cached metadata. */
internal fun mergeMailHeaders(
    cached: List<MailSummary>,
    liveFlags: List<Pair<Long, Boolean>>,
    incoming: List<MailSummary>,
    limit: Int = 60,
): List<MailSummary> {
    val byUid = liveFlags.toMap()
    return (incoming + cached.mapNotNull { mail ->
        byUid[mail.uid]?.let { unread -> mail.copy(unread = unread) }
    }).distinctBy { it.uid }.sortedByDescending { it.uid }.take(limit)
}
