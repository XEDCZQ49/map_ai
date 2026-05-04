package com.example.mapaiserver.llm.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PlanIdRequest(
        @NotBlank(message = "plan_id 不能为空")
        String planId
) {
}
