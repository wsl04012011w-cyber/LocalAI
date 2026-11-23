package com.example.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.domain.model.ChatTemplateFormat
import com.example.domain.model.DeviceMemoryInfo
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.GgufQuantization
import com.example.domain.model.ModelOrigin

class DeviceHardwareInspector(private val context: Context) {

    fun inspectMemoryAndCpu(): DeviceMemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1024)
        val availMb = (memInfo.availMem / (1024L * 1024L)).toInt().coerceAtLeast(256)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

        return DeviceMemoryInfo(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            cpuCores = cores,
            primaryAbi = primaryAbi,
            isArm64 = isArm64
        )
    }

    fun inspectPickedGgufUri(uri: Uri): GgufModelSpec? {
        var displayName = "modelo_local.gguf"
        var sizeBytes = 0L

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) {
                    displayName = cursor.getString(nameIdx) ?: displayName
                }
                if (sizeIdx != -1) {
                    sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        }

        val sizeMb = if (sizeBytes > 0L) {
            (sizeBytes / (1024L * 1024L)).toInt().coerceAtLeast(1)
        } else {
            1500
        }
        val detectedQuant = detectQuantFromFilename(displayName)
        val detectedTemplate = detectTemplateFromFilename(displayName)
        val cleanName = displayName.removeSuffix(".gguf").replace('-', ' ').replace('_', ' ')

        return GgufModelSpec(
            id = "uri_${uri.toString().hashCode()}",
            name = cleanName.take(36),
            architecture = inferArchitecture(displayName),
            parameterLabel = inferParamSize(sizeMb),
            quantization = detectedQuant,
            fileName = displayName,
            fileSizeMb = sizeMb,
            estimatedRamMb = (sizeMb * 1.18f).toInt(),
            maxContextLength = 8192,
            defaultTemplate = detectedTemplate,
            uriString = uri.toString(),
            origin = ModelOrigin.DEVICE_STORAGE,
            description = "Arquivo GGUF vinculado via Storage Access Framework (SAF) pronto para fd/mmap."
        )
    }

    private fun detectQuantFromFilename(name: String): GgufQuantization {
        val upper = name.uppercase()
        return GgufQuantization.entries.firstOrNull { upper.contains(it.name) }
            ?: GgufQuantization.Q4_K_M
    }

    private fun detectTemplateFromFilename(name: String): ChatTemplateFormat {
        val lower = name.lowercase()
        return when {
            lower.contains("qwen") -> ChatTemplateFormat.CHATML
            lower.contains("gemma") -> ChatTemplateFormat.GEMMA
            lower.contains("phi") -> ChatTemplateFormat.PHI3
            else -> ChatTemplateFormat.LLAMA3
        }
    }

    private fun inferArchitecture(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("qwen") -> "Qwen2.5"
            lower.contains("gemma") -> "Gemma2"
            lower.contains("phi") -> "Phi-3.5"
            lower.contains("mistral") -> "Mistral"
            else -> "Llama"
        }
    }

    private fun inferParamSize(sizeMb: Int): String = when {
        sizeMb < 1100 -> "~1.5B"
        sizeMb < 2200 -> "~3B"
        sizeMb < 4200 -> "~4B–7B"
        else -> "~8B+"
    }
}
