package com.closedloop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 应用内更新（M6）：读 GitHub Releases latest 与本地 versionName 语义比较（v0.10 > v0.4）；
 *  多源下载兜底（直连 + 国内镜像）；FileProvider 触发安装。
 *  约定：Release tag "v0.4" ↔ versionName "0.4"；Android 层面的覆盖安装仍由 versionCode 递增保证。 */
object Updater {

    private const val REPO = "kxsama/closedloop"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** 依次尝试的源前缀：直连 GitHub + 国内镜像兜底。API 与 APK 下载共用。 */
    private val SOURCES = listOf(
        "",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    data class Release(
        val tag: String,        // "v0.4"
        val title: String,      // release 标题
        val notes: String,      // release 说明
        val apkUrl: String,     // asset 下载地址
        val apkSize: Long,      // 字节
        val versionCode: Int,   // 从 tag 末段数字解析（仅用于展示）
        val versionName: List<Int>, // "v0.10" -> [0, 10]，语义比较用
    )

    /** 检查最新 Release。阻塞调用，调用方自己开线程。所有源都失败返回 null。 */
    fun check(): Release? {
        for (prefix in SOURCES) {
            try {
                val conn = URL(prefix + API).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) {
                    conn.disconnect(); continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                // 解析失败（如镜像返回 200 的错误页）也继续试下一个源
                parse(body)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(body: String): Release? {
        val m = Gson().fromJson(body, Map::class.java) as? Map<String, Any?> ?: return null
        val tag = m["tag_name"]?.toString() ?: return null
        var apkUrl: String? = null
        var apkSize = 0L
        for (a in m["assets"] as? List<*> ?: emptyList<Any>()) {
            val am = a as? Map<String, Any?> ?: continue
            val name = am["name"]?.toString() ?: continue
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = am["browser_download_url"]?.toString()
                apkSize = (am["size"] as? Double)?.toLong() ?: 0L
            }
        }
        if (apkUrl == null) return null
        val code = Regex("""\d+""").findAll(tag).lastOrNull()?.value?.toIntOrNull() ?: return null
        // 版本语义比较用 x.y 逐段数值：v0.10 > v0.4 正确，且不惧 v1.0（纯末段数字会解析成 0）
        val parts = tag.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        return Release(
            tag = tag,
            title = m["name"]?.toString() ?: tag,
            notes = m["body"]?.toString() ?: "",
            apkUrl = apkUrl,
            apkSize = apkSize,
            versionCode = code,
            versionName = parts,
        )
    }

    /** 下载到应用私有目录（无需存储权限）。onProgress 返回 false 时中止（用户取消）。
     *  成功返回文件；所有源都失败返回 null。 */
    fun download(
        ctx: Context,
        rel: Release,
        onProgress: (done: Long, total: Long) -> Boolean,
    ): File? {
        val dir = File(ctx.getExternalFilesDir(null), "update").apply { mkdirs() }
        val dest = File(dir, "closedloop-${rel.tag}.apk")
        for (prefix in SOURCES) {
            try {
                val conn = URL(prefix + rel.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 30000
                if (conn.responseCode != 200) {
                    conn.disconnect(); continue
                }
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else rel.apkSize
                var cancelled = false
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (!onProgress(done, total)) {
                                cancelled = true; break
                            }
                        }
                    }
                }
                conn.disconnect()
                if (cancelled) {
                    dest.delete(); return null
                }
                if (dest.length() > 0) return dest
            } catch (_: Exception) {
                dest.delete()
            }
        }
        return null
    }

    /** 拉起系统安装器。Android 8+ 首次需用户授予"安装未知应用"，交给调用方引导。 */
    fun install(ctx: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 本地 versionName（"0.4"）拆成 [0, 4]，供与 Release 语义比较。 */
    fun localVersionName(ctx: Context): List<Int> = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (pi.versionName ?: "0").removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    } catch (_: Exception) {
        listOf(0)
    }

    /** 语义比较：a > b 返回 true。逐段数值比较，段数不同短的补 0（0.4 == 0.4.0）。 */
    fun newerThan(a: List<Int>, b: List<Int>): Boolean {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(ctx: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        )
}
