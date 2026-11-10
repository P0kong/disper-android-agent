package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*

interface AttachmentParser {
    suspend fun parse(handle:AttachmentHandle,limits:AttachmentLimits):AttachmentParseResult
}
