package com.example.aiuseagent.domain.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * 把实现中**意外抛出**的异常转换成 [ProviderEvent.Failed] 终态事件，并顺手脱敏。
 *
 * 取消（[CancellationException]）必须继续抛给协程框架：它不是业务错误，也不能被吞掉。
 */
fun Flow<ProviderEvent>.catchAsProviderFailure(
    type: ProviderType,
    vararg secrets: String,
): Flow<ProviderEvent> = catch { throwable ->
    if (throwable is CancellationException) throw throwable
    emit(ProviderEvent.Failed(ProviderErrorMapper.fromThrowable(throwable, type).redact(*secrets)))
}

/** 事件序列契约的校验器：测试与 P2-B 的解析实现都用它兜底。 */
object ProviderEventContract {

    fun validate(events: List<ProviderEvent>): List<String> {
        val problems = mutableListOf<String>()
        if (events.isEmpty()) {
            problems += "事件序列为空：既没有 Started，也没有终态事件"
            return problems
        }
        if (events.first() !is ProviderEvent.Started) problems += "首个事件不是 Started"
        if (events.drop(1).any { it is ProviderEvent.Started }) problems += "Started 出现了多次"
        val terminalIndexes = events.indices.filter { events[it].isTerminal }
        if (terminalIndexes.size > 1) problems += "终态事件多于一个（" + terminalIndexes.size + " 个）"
        if (terminalIndexes.isNotEmpty() && terminalIndexes.first() != events.lastIndex) {
            problems += "终态事件不是最后一个事件"
        }
        if (events.any { it is ProviderEvent.TextDelta && it.text.isEmpty() }) problems += "存在空的 TextDelta"
        if (events.any { it is ProviderEvent.ToolCalls && it.calls.isEmpty() }) problems += "存在空的 ToolCalls"
        return problems
    }

    /** 把增量拼成完整文本（测试断言与 P2-C 落库前的聚合都用它）。 */
    fun textOf(events: List<ProviderEvent>): String = events.mapNotNull { it.textOrNull() }.joinToString("")
}
