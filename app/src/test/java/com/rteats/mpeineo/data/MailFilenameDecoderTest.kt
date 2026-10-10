package com.rteats.mpeineo.data

import javax.mail.internet.MimeUtility
import org.junit.Assert.assertEquals
import org.junit.Test

class MailFilenameDecoderTest {
    @Test fun decodesKoi8rEncodedWord() {
        val readable = "Заявление на стипендию.pdf"
        val encoded = MimeUtility.encodeText(readable, "KOI8-R", "B")
        assertEquals(readable, decodeMailAttachmentName(encoded))
    }

    @Test fun decodesUtf8EncodedWord() {
        val readable = "Документы.docx"
        val encoded = MimeUtility.encodeText(readable, "UTF-8", "B")
        assertEquals(readable, decodeMailAttachmentName(encoded))
    }

    @Test fun preservesPlainAndAlreadyDecodedNames() {
        assertEquals("grades.pdf", decodeMailAttachmentName("grades.pdf"))
        assertEquals("Расписание.xlsx", decodeMailAttachmentName("Расписание.xlsx"))
    }

    @Test fun handlesMissingNames() {
        assertEquals("attachment", decodeMailAttachmentName(null))
        assertEquals("attachment", decodeMailAttachmentName("   "))
    }
}
