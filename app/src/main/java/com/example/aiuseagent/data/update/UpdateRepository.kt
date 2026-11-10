package com.example.aiuseagent.data.update

import com.example.aiuseagent.data.provider.update.GitHubReleaseSource
import com.example.aiuseagent.data.provider.update.ReleaseSelector
import com.example.aiuseagent.data.provider.update.ReleaseSource
import com.example.aiuseagent.data.provider.update.SemVer
import com.example.aiuseagent.data.provider.update.UpdateOutcome

/**
 * 更新检查的编排层：数据源、版本与时钟均可注入，便于本地 Fake 测试。
 * 自动检查会先记录“本次尝试时间”，失败时同样计入节流，避免离线反复请求。
 */
class UpdateRepository(
    private val source: ReleaseSource = GitHubReleaseSource(),
    private val versionName: () -> String,
    private val lastAutoCheckAt: () -> Long,
    private val markAutoChecked: (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun check(manual: Boolean, now: Long = clock()): UpdateOutcome {
        if (!manual) markAutoChecked(now)
        val current = SemVer.parse(versionName())
            ?: return UpdateOutcome.Unavailable(UpdateOutcome.Reason.INVALID_RESPONSE)
        return when (val result = source.fetch()) {
            is ReleaseSource.Result.Ok ->
                ReleaseSelector.select(result.json, current)
                    ?: UpdateOutcome.Unavailable(UpdateOutcome.Reason.INVALID_RESPONSE)
            is ReleaseSource.Result.HttpError -> UpdateOutcome.Unavailable(
                when (result.code) {
                    404 -> UpdateOutcome.Reason.PRIVATE_OR_NOT_FOUND
                    403, 429 -> UpdateOutcome.Reason.RATE_LIMITED
                    in 500..599 -> UpdateOutcome.Reason.SERVER_ERROR
                    else -> UpdateOutcome.Reason.INVALID_RESPONSE
                },
            )
            ReleaseSource.Result.Network -> UpdateOutcome.Unavailable(UpdateOutcome.Reason.OFFLINE)
        }
    }

    fun shouldAutoCheck(now: Long = clock()): Boolean =
        now - lastAutoCheckAt() >= AUTO_INTERVAL_MS

    companion object {
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
