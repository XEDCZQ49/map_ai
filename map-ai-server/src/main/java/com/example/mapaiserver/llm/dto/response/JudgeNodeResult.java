package com.example.mapaiserver.llm.dto.response;

/**
 * 指令判定节点 DTO：
 * intent 表示 chat/instruction，confidence 为置信度。
 */
public record JudgeNodeResult(
        String intent,
        double confidence,
        String reason
) {
}
