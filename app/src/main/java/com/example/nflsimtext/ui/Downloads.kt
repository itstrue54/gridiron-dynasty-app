package com.example.nflsimtext.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * A text file the player can find. On Android 10 and up it goes to the
 * phone's own Downloads; below that, to the app's folder, which is the only
 * place it may write without asking for permission it does not need.
 */
object Downloads {

    /** Writes [text] as [name] and says where it went. */
    fun write(context: Context, name: String, mime: String, text: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("the phone would not open a file to write")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("the phone would not write the file")
            "Downloads/$name"
        } else {
            val file = File(context.getExternalFilesDir(null), name)
            file.writeText(text)
            file.path
        }
}
