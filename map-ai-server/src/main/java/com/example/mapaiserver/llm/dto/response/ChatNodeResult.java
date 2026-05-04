package com.example.mapaiserver.llm.dto.response;

/**
 * chat 节点返回 DTO：
 * message 为状态，reply 为最终回复文本。
 */
public record ChatNodeResult(
        String message,
        String reply
) {
}
