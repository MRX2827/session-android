package org.thoughtcrime.securesms.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.thoughtcrime.securesms.util.FileProviderUtil
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class XsessionUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    data class Release(val versionCode: Long, val size: Long, val sha256: String, val url: String)

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val installedVersion = PackageInfoCompat.getLongVersionCode(installedPackage())
        val request = Request.Builder()
            .url("$RELEASES_API?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            val json = response.body?.takeIf { response.isSuccessful }?.string()
                ?: throw IOException("GitHub Releases returned HTTP ${response.code}")
            compatibleRelease(json, installedVersion)
        }
    }

    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "xsession-updates")
        check(updateDir.exists() || updateDir.mkdirs())
        val outdated = System.currentTimeMillis() - 86_400_000L
        updateDir.listFiles()?.filter { it.lastModified() < outdated }?.forEach(File::delete)
        val file = File.createTempFile("xsession-", ".apk", updateDir)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                val body = response.body?.takeIf { response.isSuccessful }
                    ?: throw IOException("APK download returned HTTP ${response.code}")
                if (body.contentLength() >= 0) check(body.contentLength() == release.size)
                body.byteStream().use { source ->
                    file.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var count = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read == -1) break
                            count += read
                            check(count <= release.size) { "APK was larger than its release asset" }
                            digest.update(buffer, 0, read)
                            sink.write(buffer, 0, read)
                        }
                        check(count == release.size) { "Incomplete APK" }
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash.equals(release.sha256, ignoreCase = true)) { "APK digest does not match" }
            verifyPackage(file, release.versionCode)
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun verifyPackage(file: File, expectedVersionCode: Long) {
        val installed = installedPackage()
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signingFlags())
            ?: throw IOException("Invalid APK")
        val installedSigners = signers(installed)
        check(archive.packageName == context.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) == expectedVersionCode &&
            expectedVersionCode > PackageInfoCompat.getLongVersionCode(installed) &&
            installedSigners.isNotEmpty() && installedSigners == signers(archive)
        ) { "APK identity, version or signing certificate does not match" }
    }

    fun installIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        val uri = FileProviderUtil.getUriFor(context, file)
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun installedPackage(): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, signingFlags())

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else info.signatures.orEmpty().toSet()

    companion object {
        private const val REPOSITORY = "MRX2827/session-android"
        private const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY/releases"
        private const val APK = "Xsession.apk"
        private const val MAX_APK_BYTES = 300L * 1024 * 1024
        private val tagFormat = Regex("""xsession-build-([1-9][0-9]*)""")
        private val shaFormat = Regex("""sha256:([a-fA-F0-9]{64})""")

        private val json = Json { ignoreUnknownKeys = true }

        internal fun compatibleRelease(response: String, installedVersion: Long): Release? {
            var best: Release? = null
            for (release in json.decodeFromString<List<GitHubRelease>>(response)) {
                if (release.draft) continue
                val version = tagFormat.matchEntire(release.tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (version <= (best?.versionCode ?: installedVersion)) continue
                val url = "https://github.com/$REPOSITORY/releases/download/${release.tag}/$APK"
                for (asset in release.assets) {
                    if (asset.name != APK || asset.downloadUrl != url || asset.size !in 1..MAX_APK_BYTES) continue
                    val sha = shaFormat.matchEntire(asset.digest.orEmpty())?.groupValues?.get(1) ?: continue
                    best = Release(version, asset.size, sha, url)
                    break
                }
            }
            return best
        }
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long,
        val digest: String? = null,
    )
}
