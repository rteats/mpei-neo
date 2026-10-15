package com.rteats.mpeineo.ui

import com.rteats.mpeineo.data.MailSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwaSentPagingTest {
    private fun sent(id: Long, to: List<String>? = null) = MailSummary(
        uid = id, uidValidity = 7, sender = "Me", subject = "Hello",
        sentAt = id, unread = false, hasAttachment = false,
        owaConversationId = "conversation-$id", recipients = to,
    )

    @Test fun refreshPreservesResolvedRecipientWhenServerOmitsIt() {
        val result = mergeOwaSentPages(
            listOf(sent(3), sent(2)), listOf(sent(2, listOf("Ivan")), sent(1)),
        )
        assertEquals(listOf(3L, 2L, 1L), result.map { it.uid })
        assertEquals(listOf("Ivan"), result[1].recipients)
    }

    @Test fun updatedRecipientListBeatsOlderCache() {
        val result = mergeOwaSentPages(
            listOf(sent(2, listOf("New Name"))),
            listOf(sent(2, listOf("Old Name"))),
        )
        assertEquals(listOf("New Name"), result.single().recipients)
    }

    @Test fun legacyCacheWithNoRecipientFieldDoesNotInventSender() {
        val result = mergeOwaSentPages(listOf(sent(1)), listOf(sent(1)))
        assertNull(result.single().recipients)
        assertEquals("Me", result.single().sender)
    }
}
