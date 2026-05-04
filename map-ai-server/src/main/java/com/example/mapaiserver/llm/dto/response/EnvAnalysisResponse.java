package com.example.mapaiserver.llm.dto.response;

/**
 * 环境分析结果 DTO：
 * 用于返回环境分析模式、命中状态与文本结果。
 */
public record EnvAnalysisResponse(
        int code,
        String message,
        String mode,
        boolean hasLocation,
        String reply
) {
}
