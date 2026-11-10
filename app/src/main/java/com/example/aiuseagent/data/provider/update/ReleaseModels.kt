package com.example.aiuseagent.data.provider.update

import kotlinx.serialization.json.*

/** 解析后的应用版本：支持 vMAJOR.MINOR.PATCH[-alpha.N|-beta.N|-rc.N]。 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stage: Int,
    val stageNumber: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        stage.compareTo(other.stage).let { if (it != 0) return it }
        if (stage == STABLE) return 0
        return stageNumber.compareTo(other.stageNumber)
    }

    fun display(): String =
        "$major.$minor.$patch" + when (stage) {
            ALPHA -> "-alpha.$stageNumber"
            BETA -> "-beta.$stageNumber"
            RC -> "-rc.$stageNumber"
            else -> ""
        }

    companion object {
        const val ALPHA = 0
        const val BETA = 1
        const val RC = 2
        const val STABLE = 3

        private val PATTERN = Regex(
            "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.?(\\d+))?$",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String?): SemVer? {
            val text = raw?.trim().orEmpty()
            val match = PATTERN.matchEntire(text) ?: return null
            val stage = when (match.groupValues[4].lowercase()) {
                "alpha" -> ALPHA
                "beta" -> BETA
                "rc" -> RC
                else -> STABLE
            }
            val number = match.groupValues[5].toIntOrNull() ?: if (stage == STABLE) 0 else return null
            return SemVer(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                stage = stage,
                stageNumber = number,
            )
        }
    }
}

/** GitHub Release 的最小公开元数据；body 已截断，绝不包含 token 或私有数据。 */
data class RemoteRelease(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val version: SemVer,
)

/** 单次检查的结构化结果；UI 负责本地化文案。 */
sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Available(val release: RemoteRelease) : UpdateOutcome
    data class Unavailable(val reason: Reason) : UpdateOutcome

    enum class Reason { PRIVATE_OR_NOT_FOUND, RATE_LIMITED, SERVER_ERROR, OFFLINE, INVALID_RESPONSE }
}

/** 只接受本项目仓库的 Releases 页面，拒绝外部跳转。 */
const val TRUSTED_RELEASE_PREFIX = "https://github.com/POkong/disper-android-agent/releases/"

object ReleaseSelector {
    const val MAX_BODY_CHARS = 2000

    /** 从 JSON 文本解析并选择“严格高于当前版本”的最高 Release；无效项跳过。 */
    fun select(json: String, current: SemVer): UpdateOutcome? {
        val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray }.getOrNull()
            ?: return null
        val releases = array.mapNotNull { element -> runCatching {
            val obj = element.jsonObject
            val draft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false
            if (draft) return@runCatching null
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val version = SemVer.parse(tag) ?: return@runCatching null
            val url = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            if (!isTrustedUrl(url)) return@runCatching null
            val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_BODY_CHARS)
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { tag }
            val prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            RemoteRelease(tag, name, body, url, prerelease, version)
        }.getOrNull() }
        val best = releases.filter { it.version > current }.maxByOrNull { it.version }
        return if (best == null) UpdateOutcome.UpToDate else UpdateOutcome.Available(best)
    }

    fun isTrustedUrl(url: String): Boolean =
        url.startsWith(TRUSTED_RELEASE_PREFIX) && !url.contains(' ') && !url.contains('\n')
}
