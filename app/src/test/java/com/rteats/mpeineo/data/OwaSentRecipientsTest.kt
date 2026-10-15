package com.rteats.mpeineo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwaSentRecipientsTest {
    @Test fun sentConversationCannotMistakeSenderForRecipient() {
        val response = JSONObject()
            .put("UniqueSenders", JSONArray().put("My Name"))
            .put("ConversationTopic", "Hello")
        assertTrue(OwaSentRecipients.names(response).isEmpty())
    }

    @Test fun recipientsCanBeMailboxObjectsAndDeduplicate() {
        val item = JSONObject().put("ToRecipients", JSONArray()
            .put(JSONObject().put("Mailbox", JSONObject()
                .put("Name", "Ivan Ivanov").put("EmailAddress", "ivan@mpei.ru")))
            .put(JSONObject().put("Mailbox", JSONObject()
                .put("Name", "Ivan Ivanov").put("EmailAddress", "ivan@mpei.ru")))
            .put(JSONObject().put("EmailAddress", "petr@mpei.ru")))
        assertEquals(listOf("Ivan Ivanov", "petr@mpei.ru"), OwaSentRecipients.names(item))
    }

    @Test fun uniqueRecipientsCanBePlainStrings() {
        val response = JSONObject().put("UniqueRecipients", JSONArray()
            .put("student@mpei.ru").put("teacher@mpei.ru"))
        assertEquals(listOf("student@mpei.ru", "teacher@mpei.ru"),
            OwaSentRecipients.names(response))
    }

    @Test fun previewsAreRequestedInBatchesOfTen() {
        val ids = (1..13).map(Int::toString)
        val payload = OwaSentRecipients.request(ids)
        assertEquals(10, payload.getJSONArray("Conversations").length())
        assertEquals("1", payload.getJSONArray("Conversations").getJSONObject(0)
            .getJSONObject("ConversationId").getString("Id"))
    }

    @Test fun batchResponseMapsToRequestsWithoutSwappingRecipients() {
        fun item(person: String) = JSONObject()
            .put("ResponseCode", "NoError")
            .put("Conversation", JSONObject().put("ConversationNodes", JSONArray()
                .put(JSONObject().put("Items", JSONArray()
                    .put(JSONObject().put("ToRecipients", JSONArray().put(person)))))))
        val result = JSONObject().put("Body", JSONObject()
            .put("ResponseMessages", JSONObject().put("Items", JSONArray()
                .put(item("first@mpei.ru")).put(item("second@mpei.ru")))))
        assertEquals(mapOf(
            "c1" to listOf("first@mpei.ru"), "c2" to listOf("second@mpei.ru"),
        ), OwaSentRecipients.parseBatch(result, listOf("c1", "c2")))
        assertFalse(OwaSentRecipients.parseBatch(JSONObject(), listOf("c1")).isNotEmpty())
    }
}
