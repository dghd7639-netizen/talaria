package app.hermes.mobile.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** Called on Dispatchers.IO. OpenDocument grants read access without storage permission. */
fun readAttachment(resolver: ContentResolver, uri: Uri): AttachmentSource {
    var name = "附件"
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
        }
    }
    return AttachmentSource(name, size, resolver.getType(uri) ?: "application/octet-stream") {
        resolver.openInputStream(uri) ?: throw AttachmentValidationException("无法读取附件，请重新选择文件。")
    }
}
