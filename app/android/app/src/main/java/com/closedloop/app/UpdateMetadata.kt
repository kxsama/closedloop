package com.closedloop.app

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Checks the published asset, rather than trusting a mirror's own response length. */
object UpdateMetadata {
    fun sha256From(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:", ignoreCase = true)) return null
        return digest.substringAfter(':').takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()
    }

    fun verifyApk(file: File, expectedSize: Long, expectedSha256: String?): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        if (expectedSize > 0 && file.length() != expectedSize) return false
        return try {
            if (expectedSha256 != null) {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        hash.update(buffer, 0, read)
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) return false
            }
            // A 200 response may still be an HTML error page. Older releases can lack a digest.
            ZipFile(file).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")
                manifest != null && !manifest.isDirectory && manifest.size > 0
            }
        } catch (_: Exception) {
            false
        }
    }
}
