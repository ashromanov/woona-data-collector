package com.example.myapplication.storage

import android.content.Context
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.myapplication.R
import com.example.myapplication.localization.AppTextResolver
import java.io.File

interface FileShareIntentFactory {
    fun createChooserIntent(context: Context, file: File): Intent
}

class BleFileShareIntentFactory(
    private val authority: String,
    private val appTextResolver: AppTextResolver,
) : FileShareIntentFactory {
    override fun createChooserIntent(context: Context, file: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            authority,
            file.canonicalFile,
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(file)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri(file.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(
            shareIntent,
            appTextResolver.getString(R.string.share_chooser_title),
        )
    }

    private fun mimeTypeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "csv" -> "text/csv"
            "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
