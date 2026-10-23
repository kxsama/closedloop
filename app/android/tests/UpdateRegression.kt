package com.closedloop.app

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real file verification without Android or a network connection. */
fun runUpdateRegression(): Int {
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS $name")
    }
    val dir = Files.createTempDirectory("closedloop-update-test-").toFile()
    try {
        fun archive(name: String, entry: String = "AndroidManifest.xml", text: String = "manifest"): File =
            File(dir, name).apply {
                ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        val apk = archive("valid.apk")
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        test("published SHA-256 accepts the matching APK") {
            check(UpdateMetadata.sha256From("sha256:$sha") == sha)
            check(UpdateMetadata.verifyApk(apk, apk.length(), sha))
        }
        test("legacy asset without a digest retains ZIP validation") {
            check(UpdateMetadata.verifyApk(apk, apk.length(), null))
            check(UpdateMetadata.verifyApk(apk, 0L, null))
        }
        test("both short and oversized downloads fail against the published size") {
            check(!UpdateMetadata.verifyApk(apk, apk.length() + 1, null))
            check(!UpdateMetadata.verifyApk(apk, apk.length() - 1, null))
        }
        test("HTTP 200 HTML cannot be passed to the installer") {
            val page = File(dir, "error.apk").apply { writeText("<html>Try again later</html>") }
            check(!UpdateMetadata.verifyApk(page, page.length(), null))
        }
        test("ordinary ZIP is not accepted as an APK") {
            val zip = archive("source.apk", "README.md")
            check(!UpdateMetadata.verifyApk(zip, zip.length(), null))
        }
        test("wrong digest rejects an otherwise valid full-size APK") {
            check(!UpdateMetadata.verifyApk(apk, apk.length(), "0".repeat(64)))
        }
        test("uppercase digest works while malformed digest is identifiable") {
            check(UpdateMetadata.sha256From("SHA256:${sha.uppercase()}") == sha)
            check(UpdateMetadata.sha256From("sha256:broken") == null)
            check(UpdateMetadata.sha256From(null) == null)
            check(UpdateMetadata.verifyApk(apk, apk.length(), sha.uppercase()))
        }
        test("truncated ZIP and missing file fail verification") {
            val truncated = File(dir, "truncated.apk").apply { writeBytes(apk.readBytes().dropLast(8).toByteArray()) }
            check(!UpdateMetadata.verifyApk(truncated, truncated.length(), null))
            check(!UpdateMetadata.verifyApk(File(dir, "missing.apk"), 0, null))
        }
    } finally {
        dir.deleteRecursively()
    }
    return passed
}
