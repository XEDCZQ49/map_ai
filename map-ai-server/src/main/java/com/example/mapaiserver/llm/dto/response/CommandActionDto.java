package com.example.mapaiserver.llm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 指令识别动作 DTO：
 * 描述单条地图动作执行参数。
 */
public record CommandActionDto(
        String message,
        @JsonProperty("function_name")
        String functionName,
        String color,
        Map<String, Object> arguments,
        List<String> missing
) {
}
