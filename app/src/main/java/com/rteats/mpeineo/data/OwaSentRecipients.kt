package com.rteats.mpeineo.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exchange's conversation-list shape may omit recipients or return them as
 * strings, mailbox objects or nested persona objects. Never substitute From
 * for To in Sent Items: it would incorrectly display the current user.
 */
internal object OwaSentRecipients {
    fun names(item: JSONObject): List<String> {
        val arrays = sequenceOf("ToRecipients", "UniqueRecipients", "Recipients", "DisplayTo")
        val values = arrays.mapNotNull { key ->
            val value = item.opt(key)
            when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { at ->
                    mailbox(value.opt(at))
                }
                is JSONObject -> listOfNotNull(mailbox(value))
                is String -> value.split(';', ',').map(String::trim).filter(String::isNotBlank)
                else -> null
            }
        }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return values.distinctBy { it.lowercase() }.take(20)
    }

    private fun mailbox(value: Any?): String? {
        if (value is String) return value.trim().takeIf(String::isNotBlank)
        if (value !is JSONObject) return null
        val nested = value.optJSONObject("Mailbox") ?: value.optJSONObject("EmailAddress")
        if (nested != null) return mailbox(nested)
        return sequenceOf("Name", "DisplayName", "EmailAddress", "Address", "SmtpAddress")
            .map { value.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /** A single request for up to ten conversations avoids one HTTP call per row. */
    fun request(conversationIds: List<String>): JSONObject = JSONObject()
        .put("__type", "GetConversationItemsRequest:#Exchange")
        .put("Conversations", JSONArray().apply {
            conversationIds.take(10).forEach { id ->
                put(JSONObject()
                    .put("__type", "ConversationRequestType:#Exchange")
                    .put("ConversationId", JSONObject()
                        .put("__type", "ItemId:#Exchange").put("Id", id))
                    .put("SyncState", ""))
            }
        })
        .put("ItemShape", JSONObject()
            .put("__type", "ItemResponseShape:#Exchange")
            .put("BaseShape", "IdOnly")
            .put("FilterHtmlContent", true)
            .put("MaximumRecipientsToReturn", 20)
            .put("MaximumBodySize", 0))
        .put("ShapeName", "ItemPartUniqueBody")
        .put("SortOrder", "DateOrderDescending")
        .put("MaxItemsToReturn", 1)

    fun parseBatch(response: JSONObject, requestedIds: List<String>): Map<String, List<String>> {
        val replies = response.optJSONObject("Body")
            ?.optJSONObject("ResponseMessages")
            ?.optJSONArray("Items") ?: return emptyMap()
        return buildMap {
            for (index in 0 until minOf(replies.length(), requestedIds.size)) {
                val reply = replies.optJSONObject(index) ?: continue
                if (reply.optString("ResponseCode") != "NoError") continue
                val nodes = reply.optJSONObject("Conversation")
                    ?.optJSONArray("ConversationNodes") ?: continue
                var found = emptyList<String>()
                for (n in 0 until nodes.length()) {
                    val items = nodes.optJSONObject(n)?.optJSONArray("Items") ?: continue
                    for (j in 0 until items.length()) {
                        val candidate = items.optJSONObject(j)?.let(::names).orEmpty()
                        if (candidate.isNotEmpty()) {
                            found = candidate
                            break
                        }
                    }
                    if (found.isNotEmpty()) break
                }
                if (found.isNotEmpty()) put(requestedIds[index], found)
            }
        }
    }
}
