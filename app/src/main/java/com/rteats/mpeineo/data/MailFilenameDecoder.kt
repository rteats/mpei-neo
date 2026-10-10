package com.rteats.mpeineo.data

import javax.mail.internet.MimeUtility

/**
 * JavaMail does not necessarily decode RFC 2047 encoded-words in MIME filename
 * parameters (for example =?koi8-r?B?...?=). Decode for both display and the
 * saved MediaStore filename, preserving ordinary and already-decoded names.
 */
internal fun decodeMailAttachmentName(raw: String?): String {
    val filename = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "attachment"
    return runCatching { MimeUtility.decodeText(filename) }
        .getOrDefault(filename)
        .trim()
        .ifEmpty { filename }
}
