package com.example.mapaiserver.llm.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * LLM 图流程请求 DTO：
 * message 为用户输入原文。
 */
public record GraphRunRequest(
        @NotBlank(message = "message 不能为空")
        String message,
        String planId
) {
}
