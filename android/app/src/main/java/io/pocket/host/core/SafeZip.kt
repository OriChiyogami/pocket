package io.pocket.host.core

import java.io.File
import java.util.zip.ZipFile

/** 解包防护：目录穿越、符号链接、超大包、过多文件。 */
object SafeZip {

    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_SINGLE_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 4000

    fun extract(zipFile: File, destDir: File) {
        destDir.parentFile?.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            var count = 0
            var total = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                count++
                if (count > MAX_ENTRIES) throw PackageException("包内文件过多（上限 $MAX_ENTRIES）")

                val rawName = entry.name.replace('\\', '/')
                if (rawName.startsWith("/") || rawName.contains("..") || rawName.contains("\u0000")) {
                    throw PackageException("包内路径非法：$rawName")
                }
                val outFile = File(destDir, rawName)
                val canonicalDest = destDir.canonicalFile
                val canonicalOut = outFile.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalDest.path + File.separator)) {
                    throw PackageException("包内路径越界：$rawName")
                }

                if (entry.size > MAX_SINGLE_BYTES) throw PackageException("单个文件过大：$rawName")
                total += entry.size
                if (total > MAX_TOTAL_BYTES) throw PackageException("解压后体积超过 64MB")

                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
