package io.pocket.host.core

import android.content.Context
import java.io.File

fun iconPathOf(context: Context, app: InstalledApp): String? {
    val name = app.iconFile ?: return null
    val file = File(context.filesDir, "apps/${app.id}/www/$name")
    return if (file.isFile) file.absolutePath else null
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0 分钟"
    val minutes = millis / 60000
    return when {
        minutes < 1 -> "不到 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
        }
    }
}

fun formatLastUsed(timestamp: Long): String {
    if (timestamp <= 0) return "从未打开"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = timestamp
            String.format(
                "%d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
