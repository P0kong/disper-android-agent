package com.example.aiuseagent.data.provider.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 仅供更新检查使用的只读网络源；只访问公开 GitHub API，不携带任何凭据。 */
class GitHubReleaseSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val url: String = "https://api.github.com/repos/POkong/disper-android-agent/releases?per_page=30",
) : ReleaseSource {

    override suspend fun fetch(): ReleaseSource.Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Disper-Android-UpdateCheck")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ReleaseSource.Result.HttpError(response.code)
                val body = response.body ?: return@use ReleaseSource.Result.Network
                val input = body.byteStream()
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) return@use ReleaseSource.Result.HttpError(413)
                    buffer.write(chunk, 0, read)
                }
                ReleaseSource.Result.Ok(buffer.toString(Charsets.UTF_8.name()))
            }
        }.getOrElse { ReleaseSource.Result.Network }
    }

    private companion object {
        const val MAX_BYTES = 512 * 1024
    }
}
