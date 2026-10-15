package com.rteats.mpeineo.data

import android.content.Context
import java.io.ByteArrayOutputStream
import android.os.SystemClock
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.util.Base64
import android.text.Html
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * OWA service.svc client for the MPEI Exchange 2013-compatible server.
 *
 * WebView owns interactive authentication. This client borrows only its current
 * cookies and anti-CSRF canary; nothing is stored or printed. Request shapes
 * are based on captured OWA FindConversation / GetConversationItems traffic.
 *
 * Do not use IMAP UIDs to address Exchange conversations or message bodies.
 * Sending follows the separately captured CreateItem / CreateAttachment /
 * UpdateItem exchange; credentials, cookies and message data are never logged.
 */
enum class OwaMailFolder(val distinguishedId: String) {
    INBOX("inbox"), SENT("sentitems"),
}

class OwaMailRepository(
    private val context: Context,
    private val diagnostics: DiagnosticLog,
) {
    class LoginRequired : IllegalStateException("Откройте ОСЭП и войдите в почту.")
    class ServiceFailure(val category: String) :
        IllegalStateException("Не удалось получить почту через ОСЭП (" + category + ").")

    private val mutex = Mutex()
    private val sendMutex = Mutex()
    private val cache = MailCache(context)
    private val accountPrefs = context.getSharedPreferences("owa_mail_account_v1", Context.MODE_PRIVATE)
    @Volatile private var activeAccount: String? = null

    fun cachedInbox(): List<MailSummary> =
        accountPrefs.getString("last_account", null)?.let { cache.inbox(it)?.messages }
            .orEmpty().filter { it.owaConversationId != null }

    fun cachedSent(): List<MailSummary> =
        accountPrefs.getString("last_account", null)?.let { cache.sent(it)?.messages }
            .orEmpty().filter { it.owaConversationId != null }

    fun cachedMessage(summary: MailSummary): MailDetail? =
        accountPrefs.getString("last_account", null)?.let {
            cache.message(it, summary.uid, summary.uidValidity)
        }

    fun currentAccountKey(): String? = activeAccount

    /** Account owning the encrypted inbox snapshot, including after process restart. */
    fun cachedAccountKey(): String? = accountPrefs.getString("last_account", null)

    suspend fun signOut() {
        val previous = accountPrefs.getString("last_account", null)
        activeAccount = null
        accountPrefs.edit().remove("last_account").apply()
        if (previous != null) withContext(Dispatchers.IO) { cache.clear(previous) }
        // The cookie jar is shared with BARS; clear mail.mpei.ru only.
        withContext(Dispatchers.Main) {
            val jar = CookieManager.getInstance()
            val names = jar.getCookie(OWA_URL).orEmpty().split(';')
                .map { it.trim().substringBefore('=').trim() }
                .filter { it.isNotEmpty() }.distinct()
            names.forEach { name ->
                jar.setCookie("https://mail.mpei.ru/", "$name=; Path=/; Max-Age=0; Secure")
                jar.setCookie(OWA_URL, "$name=; Path=/owa; Max-Age=0; Secure")
                jar.setCookie(OWA_URL, "$name=; Path=/owa/; Max-Age=0; Secure")
            }
            jar.flush()
        }
        diagnostics.log("OWA_MAIL", "local_logout cacheCleared=true mailCookiesCleared=true")
    }

    /** Resolve the authenticated mailbox identity, never from an IMAP account. */
    private suspend fun authenticatedAccount(): String {
        val session = withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance().getCookie(OWA_URL).orEmpty()
            if (cookies.isBlank()) throw LoginRequired()
            Session(cookies, "", WebSettings.getDefaultUserAgent(context))
        }
        val response = withContext(Dispatchers.IO) {
            val connection = URL(OWA_URL + "sessiondata.ashx?appcacheclient=0")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.setFixedLengthStreamingMode(0)
                connection.setRequestProperty("Cookie", session.cookieHeader)
                connection.setRequestProperty("User-Agent", session.userAgent)
                connection.setRequestProperty("Origin", "https://mail.mpei.ru")
                connection.setRequestProperty("Referer", OWA_URL)
                connection.outputStream.use { }
                val status = connection.responseCode
                if (status != 200) {
                    if (status in listOf(301, 302, 303, 307, 308, 401, 440))
                        throw LoginRequired()
                    throw ServiceFailure("identity_http_" + status)
                }
                val renewed = connection.headerFields
                    .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                    .values.flatMap { it.orEmpty() }
                connection.inputStream.bufferedReader().use { it.readText() } to renewed
            } finally {
                connection.disconnect()
            }
        }
        if (response.second.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                val jar = CookieManager.getInstance()
                response.second.forEach { jar.setCookie(OWA_URL, it) }
                jar.flush()
            }
        }
        val settings = runCatching {
            JSONObject(response.first).getJSONObject("owaUserConfig").getJSONObject("SessionSettings")
        }.getOrNull() ?: throw LoginRequired()
        val username = settings.optString("UserPrincipalName")
            .ifBlank { settings.optString("UserEmailAddress") }
            .ifBlank { settings.optString("LogonEmailAddress") }
        if (username.isBlank()) throw ServiceFailure("identity_missing")
        return "owa_" + MessageDigest.getInstance("SHA-256")
            .digest(username.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private data class Session(val cookieHeader: String, val canary: String, val userAgent: String)
    private data class Reply(val status: Int, val body: String, val setCookies: List<String>)

    suspend fun inbox(
        maxConversations: Int = 60,
        refreshId: Long = 0L,
        offset: Int = 0,
        folder: OwaMailFolder = OwaMailFolder.INBOX,
    ): List<MailSummary> {
        val started = SystemClock.elapsedRealtime()
        return try {
            val account = authenticatedAccount()
            val body = JSONObject()
                .put("__type", "FindConversationRequest:#Exchange")
                .put("ParentFolderId", JSONObject()
                    .put("__type", "TargetFolderId:#Exchange")
                    .put("BaseFolderId", JSONObject()
                        .put("__type", "DistinguishedFolderId:#Exchange")
                        .put("Id", folder.distinguishedId)))
                .put("ConversationShape", JSONObject()
                    .put("__type", "ConversationResponseShape:#Exchange")
                    .put("BaseShape", "IdOnly"))
                .put("ShapeName", "ConversationListView")
                .put("Paging", JSONObject()
                    .put("__type", "IndexedPageView:#Exchange")
                    .put("BasePoint", "Beginning")
                    .put("Offset", offset.coerceIn(0, 100_000))
                    .put("MaxEntriesReturned", maxConversations.coerceIn(1, 100)))
                .put("ViewFilter", "All")
                .put("SortOrder", JSONArray().put(JSONObject()
                    .put("__type", "SortResults:#Exchange")
                    .put("Order", "Descending")
                    .put("Path", JSONObject()
                        .put("__type", "PropertyUri:#Exchange")
                        .put("FieldURI", "ConversationLastDeliveryTime"))))

            val result = service("FindConversation", "Browse_All", body, refreshId)
            val responseBody = result.optJSONObject("Body") ?: throw ServiceFailure("missing_body")
            verifyResponse(responseBody)
            val conversations = responseBody.optJSONArray("Conversations")
                ?: throw ServiceFailure("missing_conversations")
            val items = (0 until conversations.length()).mapNotNull { index ->
                val item = conversations.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optJSONObject("ConversationId")?.optString("Id").orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                val senders = item.optJSONArray("UniqueSenders")
                val recipients = if (folder == OwaMailFolder.SENT)
                    OwaSentRecipients.names(item) else emptyList()
                MailSummary(
                    uid = stableId(id),
                    uidValidity = OWA_UID_VALIDITY,
                    sender = senders?.optString(0).orEmpty(),
                    subject = item.optString("ConversationTopic"),
                    sentAt = parseDate(item.optString("LastDeliveryTime")),
                    unread = folder == OwaMailFolder.INBOX &&
                        item.optInt("UnreadCount", 0) > 0,
                    hasAttachment = item.optBoolean("HasAttachments"),
                    owaConversationId = id,
                    recipients = recipients,
                )
            }
            activeAccount = account
            accountPrefs.edit().putString("last_account", account).apply()
            // A background poll refreshes the first 60 but never truncates the
            // previously downloaded older pages. Older pages extend the encrypted cache.
            withContext(Dispatchers.IO) {
                if (folder == OwaMailFolder.SENT) {
                    cache.saveOwaSentPage(account, items, offset)
                } else {
                    cache.saveOwaPage(account, items, offset)
                }
            }
            diagnostics.log("OWA_MAIL",
                "folder_success folder=" + folder.name + " refreshId=" + refreshId +
                    " offset=" + offset +
                    " conversations=" + items.size +
                    " durationMs=" + (SystemClock.elapsedRealtime() - started))
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            diagnostics.log("OWA_MAIL",
                "folder_failure folder=" + folder.name + " refreshId=" + refreshId +
                    " offset=" + offset +
                    " category=" + when (e) {
                        is LoginRequired -> "LOGIN_REQUIRED"
                        is ServiceFailure -> e.category
                        else -> e.javaClass.simpleName
                    } +
                    " durationMs=" + (SystemClock.elapsedRealtime() - started))
            throw e
        }
    }


    /** Search Exchange's Global Address List through the existing OWA cookie session. */
    internal suspend fun findRecipients(query: String): List<OwaRecipientSuggestion> {
        if (query.trim().length < 2) return emptyList()
        val response = service(
            "FindPeople", "FindPeople", OwaRecipientDirectory.searchRequest(query),
            actionId = "-11",
        )
        val body = response.optJSONObject("Body") ?: throw ServiceFailure("people_missing_body")
        verifyResponse(body)
        return OwaRecipientDirectory.parse(response)
    }

    /**
     * ConversationListView does not reliably include ToRecipients on Exchange
     * 2013. Enrich sent rows from their actual messages, batched by ten.
     * No email addresses or subjects are written to diagnostics.
     */
    suspend fun sentRecipientNames(summaries: List<MailSummary>): Map<Long, List<String>> {
        val pending = summaries.filter { it.recipients.isNullOrEmpty() &&
            !it.owaConversationId.isNullOrBlank() }.take(10)
        if (pending.isEmpty()) return emptyMap()
        val ids = pending.mapNotNull { it.owaConversationId }
        val response = service("GetConversationItems", "GetConversationItemsAction",
            OwaSentRecipients.request(ids))
        val parsed = OwaSentRecipients.parseBatch(response, ids)
        val found = pending.mapNotNull { summary ->
            val names = parsed[summary.owaConversationId].orEmpty()
            if (names.isEmpty()) null else summary.uid to names
        }.toMap()
        if (found.isNotEmpty()) {
            val account = activeAccount ?: cachedAccountKey()
            if (account != null) withContext(Dispatchers.IO) {
                cache.updateSentRecipients(account, found)
            }
        }
        diagnostics.log("OWA_MAIL", "sent_recipients requested=${pending.size} resolved=${found.size}")
        return found
    }

    /** Mark a conversation read only when the Exchange service confirms NoError.
     *  This action format is based on EWS; the OWA JSON transport still needs device testing. */
    suspend fun markRead(summary: MailSummary) {
        val id = summary.owaConversationId?.takeIf { it.isNotBlank() }
            ?: throw ServiceFailure("missing_conversation_id")
        val body = JSONObject()
            .put("__type", "ApplyConversationActionRequest:#Exchange")
            .put("ConversationActions", JSONArray().put(JSONObject()
                .put("__type", "ConversationAction:#Exchange")
                .put("Action", "SetReadState")
                .put("IsRead", true)
                .put("ProcessRightAway", true)
                .put("ConversationId", JSONObject()
                    .put("__type", "ItemId:#Exchange").put("Id", id))))
        val response = service("ApplyConversationAction", "ApplyConversationAction", body)
            .optJSONObject("Body")?.optJSONObject("ResponseMessages")
            ?.optJSONArray("Items")?.optJSONObject(0)
            ?: throw ServiceFailure("missing_read_confirmation")
        verifyResponse(response)
        // Keep the encrypted offline snapshot in sync only after server acknowledgment.
        activeAccount?.let { account ->
            withContext(Dispatchers.IO) {
                cache.markRead(account, summary.uid, summary.uidValidity)
            }
        }
        diagnostics.log("OWA_MAIL", "read_state_success")
    }

    suspend fun downloadAttachment(attachment: MailAttachment): String {
        val id = attachment.owaAttachmentId?.takeIf { it.isNotBlank() }
            ?: throw ServiceFailure("attachment_id_not_available")
        val body = JSONObject()
            .put("__type", "GetAttachmentRequest:#Exchange")
            .put("AttachmentIds", JSONArray().put(JSONObject()
                .put("__type", "AttachmentId:#Exchange").put("Id", id)))
        val response = service("GetAttachment", "GetAttachment", body)
            .optJSONObject("Body")?.optJSONObject("ResponseMessages")
            ?.optJSONArray("Items")?.optJSONObject(0)
            ?: throw ServiceFailure("missing_attachment_response")
        verifyResponse(response)
        val result = response.optJSONArray("Attachments")?.optJSONObject(0)
            ?: throw ServiceFailure("missing_attachment")
        val encoded = result.optString("Content")
        if (encoded.isBlank() || encoded.length > 29_000_000)
            throw ServiceFailure("invalid_attachment_content")
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            throw ServiceFailure("requires_android_10_downloads")
        val filename = attachment.name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "").take(160)
            .ifBlank { "attachment" }
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE,
                    attachment.mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MPEI Neo")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ServiceFailure("download_insert_failed")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw ServiceFailure("download_stream_failed")
                val complete = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, complete, null, null)
            } catch (failure: Exception) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }
        diagnostics.log("OWA_MAIL", "attachment_saved bytes=" + bytes.size)
        return filename
    }

    suspend fun message(summary: MailSummary): MailDetail {
        val id = summary.owaConversationId?.takeIf { it.isNotBlank() }
            ?: throw ServiceFailure("missing_conversation_id")
        val body = JSONObject()
            .put("__type", "GetConversationItemsRequest:#Exchange")
            .put("Conversations", JSONArray().put(JSONObject()
                .put("__type", "ConversationRequestType:#Exchange")
                .put("ConversationId", JSONObject()
                    .put("__type", "ItemId:#Exchange")
                    .put("Id", id))
                .put("SyncState", "")))
            .put("ItemShape", JSONObject()
                .put("__type", "ItemResponseShape:#Exchange")
                .put("BaseShape", "IdOnly")
                .put("FilterHtmlContent", true)
                .put("BlockExternalImagesIfSenderUntrusted", true)
                .put("AddBlankTargetToLinks", true)
                .put("ClientSupportsIrm", false)
                .put("MaximumBodySize", 2097152)
                .put("MaximumRecipientsToReturn", 10))
            .put("ShapeName", "ItemPartUniqueBody")
            .put("SortOrder", "DateOrderDescending")
            .put("MaxItemsToReturn", 100)
        val result = service("GetConversationItems", "GetConversationItemsAction", body)
        val response = result.optJSONObject("Body")
            ?.optJSONObject("ResponseMessages")
            ?.optJSONArray("Items")
            ?.optJSONObject(0) ?: throw ServiceFailure("missing_message_response")
        verifyResponse(response)
        val nodes = response.optJSONObject("Conversation")?.optJSONArray("ConversationNodes")
            ?: throw ServiceFailure("missing_message_nodes")
        val thread = mutableListOf<MailThreadMessage>()
        var latestRecipients = summary.recipients
        val foundAttachments = mutableListOf<MailAttachment>()
        var anyTruncated = false
        for (i in 0 until nodes.length()) {
            val entries = nodes.optJSONObject(i)?.optJSONArray("Items") ?: continue
            for (j in 0 until entries.length()) {
                val item = entries.optJSONObject(j) ?: continue
                val unique = item.optJSONObject("UniqueBody") ?: item.optJSONObject("Body")
                val raw = unique?.optString("Value").orEmpty()
                val plain = if (unique?.optString("BodyType").equals("HTML", true))
                    Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                else raw
                val mailbox = item.optJSONObject("From")?.optJSONObject("Mailbox")
                    ?: item.optJSONObject("Sender")?.optJSONObject("Mailbox")
                val sender = mailbox?.optString("Name")?.takeIf { it.isNotBlank() }
                    ?: mailbox?.optString("EmailAddress").orEmpty()
                val recipients = OwaSentRecipients.names(item)
                if (recipients.isNotEmpty()) latestRecipients = recipients
                thread.add(MailThreadMessage(
                    sender.ifBlank { summary.sender },
                    item.optString("Subject").ifBlank { summary.subject },
                    parseDate(item.optString("DateTimeReceived")).takeIf { it > 0 } ?: summary.sentAt,
                    plain,
                ))
                val rawAttachments = item.optJSONArray("Attachments")
                for (k in 0 until (rawAttachments?.length() ?: 0)) {
                    val file = rawAttachments?.optJSONObject(k) ?: continue
                    if (file.optBoolean("IsInline", false)) continue
                    val attachmentId = file.optJSONObject("AttachmentId")
                        ?.optString("Id")?.takeIf { it.isNotBlank() } ?: continue
                    foundAttachments.add(MailAttachment(
                        name = file.optString("Name").ifBlank { "attachment" },
                        mimeType = file.optString("ContentType"),
                        size = file.optInt("Size"),
                        partPath = listOf(i, j, k),
                        owaAttachmentId = attachmentId,
                    ))
                }
                anyTruncated = anyTruncated || unique?.optBoolean("IsTruncated", false) == true
            }
        }
        if (thread.isEmpty()) throw ServiceFailure("empty_conversation")
        val ordered = thread.sortedBy { it.sentAt }
        val recent = ordered.last()
        val detail = MailDetail(
            uid = summary.uid, sender = recent.sender, subject = recent.subject,
            sentAt = recent.sentAt, body = recent.body,
            attachments = foundAttachments, threadMessages = ordered,
            recipients = latestRecipients,
        )
        activeAccount?.let { account ->
            withContext(Dispatchers.IO) {
                cache.saveMessage(account, summary.uid, summary.uidValidity, detail)
            }
        }
        diagnostics.log("OWA_MAIL", "message_success bodyPresent=" +
            recent.body.isNotBlank() + " truncated=" + anyTruncated +
            " threadCount=" + ordered.size +
            " attachments=" + foundAttachments.size)
        return detail
    }

    /**
     * Send one new message using the live WebView-authenticated OWA session.
     * A network failure during a send may have an unknown outcome: never retry
     * requests automatically, or an email could be delivered twice.
     */
    internal suspend fun sendMail(
        draft: OutgoingMail,
        files: List<OutgoingFile>,
        onStage: (String) -> Unit = {},
    ) = sendMutex.withLock {
        if (!OwaComposeProtocol.validRecipients(draft))
            throw ServiceFailure("invalid_recipients")
        if (draft.subject.length > 998 || draft.body.length > 1_000_000)
            throw ServiceFailure("message_too_long")
        if (files.size > 8 || files.any { it.size > MAX_OUTGOING_FILE } ||
            files.sumOf { it.size.coerceAtLeast(0L) } > MAX_OUTGOING_TOTAL)
            throw ServiceFailure("attachment_limit")

        val started = SystemClock.elapsedRealtime()
        try {
            onStage(if (files.isEmpty()) "Отправка письма…" else "Создание черновика…")
            val created = service(
                "CreateItem",
                if (files.isEmpty()) "CreateMessageForComposeSend" else "CreateMessageForCompose",
                OwaComposeProtocol.create(draft, files.isNotEmpty()),
                actionId = if (files.isEmpty()) "-37" else "-38",
            )
            val createResponse = OwaComposeProtocol.confirmedResponse(created)
            if (files.isNotEmpty()) {
                val item = createResponse.optJSONArray("Items")?.optJSONObject(0)
                    ?.optJSONObject("ItemId") ?: throw ServiceFailure("missing_draft_id")
                val id = item.optString("Id").takeIf { it.isNotBlank() }
                    ?: throw ServiceFailure("missing_draft_id")
                var changeKey = item.optString("ChangeKey").takeIf { it.isNotBlank() }
                    ?: throw ServiceFailure("missing_draft_change_key")

                var uploadedBytes = 0L
                files.forEachIndexed { index, file ->
                    onStage("Вложение ${index + 1} из ${files.size}…")
                    val bytes = withContext(Dispatchers.IO) { readOutgoingFile(file) }
                    uploadedBytes += bytes.size
                    if (uploadedBytes > MAX_OUTGOING_TOTAL)
                        throw ServiceFailure("attachment_limit")
                    val response = service(
                        "CreateAttachment", "CreateAttachmentAction",
                        OwaComposeProtocol.attach(id, changeKey, file, bytes),
                        actionId = "-39",
                    )
                    val attached = OwaComposeProtocol.confirmedResponse(response)
                    changeKey = attached.optJSONArray("Attachments")?.optJSONObject(0)
                        ?.optJSONObject("AttachmentId")?.optString("RootItemChangeKey")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw ServiceFailure("missing_attachment_change_key")
                }

                onStage("Отправка письма…")
                val sent = service(
                    "UpdateItem", "UpdateMessageForComposeSend",
                    OwaComposeProtocol.sendDraft(id, changeKey, draft),
                    actionId = "-40",
                )
                OwaComposeProtocol.confirmedResponse(sent)
            }
            diagnostics.log(
                "OWA_MAIL",
                "send_success attachments=${files.size} durationMs=" +
                    (SystemClock.elapsedRealtime() - started),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.log("OWA_MAIL", "send_failure type=${failure.javaClass.simpleName} " +
                "category=" + (failure as? ServiceFailure)?.category.orEmpty())
            throw failure
        }
    }

    private fun readOutgoingFile(file: OutgoingFile): ByteArray {
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: throw ServiceFailure("attachment_unavailable")
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_OUTGOING_FILE)
                    throw ServiceFailure("attachment_too_large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private suspend fun service(
        action: String,
        actionName: String,
        body: JSONObject,
        refreshId: Long = 0L,
        actionId: String = if (action == "FindConversation") "-5" else "-8",
    ): JSONObject = mutex.withLock {
        val session = withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance().getCookie(OWA_URL).orEmpty()
            if (cookies.isEmpty()) throw LoginRequired()
            val canary = cookies.split(';').asSequence().map { it.trim() }
                .firstOrNull { it.substringBefore('=').equals("X-OWA-CANARY", true) }
                ?.substringAfter('=', "")?.takeIf { it.isNotBlank() }
                ?: throw ServiceFailure("missing_canary")
            Session(cookies, canary, WebSettings.getDefaultUserAgent(context))
        }
        val payload = JSONObject()
            .put("__type", action + "JsonRequest:#Exchange")
            .put("Header", JSONObject()
                .put("__type", "JsonRequestHeaders:#Exchange")
                .put("RequestServerVersion", "Exchange2013")
                .put("TimeZoneContext", JSONObject()
                    .put("__type", "TimeZoneContext:#Exchange")
                    .put("TimeZoneDefinition", JSONObject()
                        .put("__type", "TimeZoneDefinitionType:#Exchange")
                        .put("Id", "Russian Standard Time"))))
            .put("Body", body)
        val reply = withContext(Dispatchers.IO) {
            request(action, actionName, actionId, session, payload.toString())
        }
        if (reply.setCookies.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                val jar = CookieManager.getInstance()
                reply.setCookies.forEach { jar.setCookie(OWA_URL, it) }
                jar.flush()
            }
        }
        if (reply.status in setOf(301, 302, 303, 307, 308, 401, 440))
            throw LoginRequired()
        if (reply.status != 200) throw ServiceFailure("http_" + reply.status)
        val json = try { JSONObject(reply.body) } catch (_: Exception) {
            throw ServiceFailure("invalid_json")
        }
        if (json.optJSONObject("Body") == null)
            throw ServiceFailure("unexpected_response")
        if (action == "FindConversation") {
            diagnostics.log("OWA_AUTH",
                "service_auth source=inbox_refresh refreshId=" + refreshId +
                    " state=AUTHENTICATED http=200")
        }
        json
    }

    private fun request(
        action: String,
        actionName: String,
        actionId: String,
        session: Session,
        payload: String,
    ): Reply {
        val path = OWA_URL + "service.svc?action=" + action +
            "&ID=" + actionId + "&AC=1"
        val connection = URL(path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout =
                if (action in setOf("CreateItem", "CreateAttachment", "UpdateItem"))
                    90000 else 15000
            connection.doOutput = true
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payloadBytes.size)
            connection.setRequestProperty("Cookie", session.cookieHeader)
            connection.setRequestProperty("X-OWA-CANARY", session.canary)
            connection.setRequestProperty("Action", action)
            connection.setRequestProperty("X-OWA-ActionName", actionName)
            connection.setRequestProperty("X-OWA-ActionId", actionId)
            connection.setRequestProperty("X-OWA-Attempt", "1")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Origin", "https://mail.mpei.ru")
            connection.setRequestProperty("Referer", OWA_URL)
            connection.setRequestProperty("User-Agent", session.userAgent)
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val renewed = connection.headerFields
                .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                .values.flatMap { it.orEmpty() }
            val response = if (status == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else ""
            return Reply(status, response, renewed)
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyResponse(body: JSONObject) {
        if (body.optString("ResponseCode", "NoError") != "NoError" ||
            body.optString("ResponseClass", "Success") != "Success") {
            throw ServiceFailure("exchange_response")
        }
    }

    private fun stableId(id: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
        var number = 0L
        for (i in 0 until 8) number = (number shl 8) or (digest[i].toLong() and 255L)
        return (number and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun parseDate(input: String): Long =
        runCatching { Instant.parse(input).toEpochMilli() }.getOrDefault(0L)

    companion object {
        private const val OWA_URL = "https://mail.mpei.ru/owa/"
        private const val OWA_UID_VALIDITY = 0x4F5741L
        private const val MAX_OUTGOING_FILE = 10L * 1024 * 1024
        private const val MAX_OUTGOING_TOTAL = 18L * 1024 * 1024
    }
}
