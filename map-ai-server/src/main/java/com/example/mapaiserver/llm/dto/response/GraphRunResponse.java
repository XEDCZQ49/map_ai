package com.example.mapaiserver.llm.dto.response;

import java.util.List;

/**
 * 图流程聚合响应 DTO：
 * 统一承载判定结果、解析动作列表和闲聊分支结果。
 */
public record GraphRunResponse(
        int code,
        String message,
        String intent,
        String planId,
        JudgeNodeResult judge,
        List<CommandActionDto> commands,
        ChatNodeResult chat
) {
}
