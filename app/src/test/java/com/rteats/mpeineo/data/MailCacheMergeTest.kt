package com.rteats.mpeineo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MailCacheMergeTest {
    private fun summary(uid: Long, validity: Long = 14, unread: Boolean = true) =
        MailSummary(
            uid = uid, uidValidity = validity,
            sender = "sender", subject = "subject", sentAt = 0L,
            unread = unread, hasAttachment = false,
        )

    @Test fun newUidsComeFirstAndCachedHeadersAreReused() {
        val previous = listOf(summary(20), summary(19), summary(18))
        val actual = mergeMailHeaders(
            cached = previous,
            liveFlags = listOf(20L to false, 19L to true, 18L to true),
            incoming = listOf(summary(22), summary(21)),
        )
        assertEquals(listOf(22L, 21L, 20L, 19L, 18L), actual.map { it.uid })
        assertEquals(false, actual.first { it.uid == 20L }.unread)
    }

    @Test fun deletedCachedHeadersAreRemovedWithoutRedownloadingEnvelope() {
        val actual = mergeMailHeaders(
            cached = listOf(summary(10), summary(9), summary(8)),
            liveFlags = listOf(10L to true, 8L to false),
            incoming = emptyList(),
        )
        assertEquals(listOf(10L, 8L), actual.map { it.uid })
    }

    @Test fun duplicatesAndLargeInboxesRemainBounded() {
        val actual = mergeMailHeaders(
            cached = listOf(summary(42), summary(41)),
            liveFlags = listOf(42L to true, 41L to true),
            incoming = listOf(summary(44), summary(43), summary(42)),
            limit = 3,
        )
        assertEquals(listOf(44L, 43L, 42L), actual.map { it.uid })
    }
}
