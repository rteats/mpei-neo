package com.rteats.mpeineo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MailNotificationCheckpointTest {
    private fun item(id: Long, validity: Long = 20L) = MailSummary(
        uid = id, uidValidity = validity, sender = "Sender", subject = "Test",
        sentAt = 1L, unread = true, hasAttachment = false,
    )

    @Test fun newUidComparison() {
        val incoming = listOf(item(4), item(6), item(5))
        assertEquals(listOf(5L, 6L), mailNewUids(incoming, 4L, 20L).map { it.uid })
        assertEquals(emptyList<MailSummary>(), mailNewUids(incoming, 6L, 20L))
        assertEquals(emptyList<MailSummary>(), mailNewUids(incoming, 1L, 21L))
    }
}
