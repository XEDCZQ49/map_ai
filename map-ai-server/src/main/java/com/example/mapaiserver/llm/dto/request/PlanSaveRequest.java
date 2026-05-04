package com.example.mapaiserver.llm.dto.request;

import com.example.mapaiserver.llm.dto.response.CommandActionDto;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record PlanSaveRequest(
        @NotBlank(message = "plan_id 不能为空")
        String planId,
        String planName,
        List<CommandActionDto> commands
) {
}
