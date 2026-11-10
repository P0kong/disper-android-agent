package com.example.aiuseagent.data.provider.update

/** 可注入的 Release 数据源；生产为 GitHub，测试可用本地 Fake。 */
fun interface ReleaseSource {
    sealed interface Result {
        data class Ok(val json: String) : Result
        data class HttpError(val code: Int) : Result
        data object Network : Result
    }

    suspend fun fetch(): Result
}
