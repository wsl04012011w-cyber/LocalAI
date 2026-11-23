package com.example.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.example.domain.model.GgufModelSpec

/**
 * Delegates GGUF downloads directly to Android's native [DownloadManager]
 * (saving into the public Downloads directory) with zero third-party network libraries,
 * falling back to [Intent.ACTION_VIEW] if the system download service is unavailable.
 */
class ModelDownloadDispatcher(private val context: Context) {

    sealed interface DispatchResult {
        data class EnqueuedToDownloads(val downloadId: Long, val fileName: String) : DispatchResult
        data class OpenedInBrowser(val url: String) : DispatchResult
        data class Failed(val reason: String) : DispatchResult
    }

    fun enqueueModelDownload(model: GgufModelSpec): DispatchResult {
        val url = model.downloadUrl ?: return DispatchResult.Failed("Modelo sem URL de download.")
        val uri = Uri.parse(url)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) {
            try {
                val request = DownloadManager.Request(uri)
                    .setTitle(model.fileName)
                    .setDescription("Baixando modelo GGUF (${model.quantization.label}) para Downloads")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, model.fileName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)

                val id = dm.enqueue(request)
                return DispatchResult.EnqueuedToDownloads(downloadId = id, fileName = model.fileName)
            } catch (_: Throwable) {
                // Fall back to system browser / handler below
            }
        }

        return openUrlExternally(url)
    }

    fun openUrlExternally(url: String): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DispatchResult.OpenedInBrowser(url)
        } catch (t: Throwable) {
            DispatchResult.Failed(t.message ?: "Não foi possível abrir o link de download.")
        }
    }
}
