package com.example.mapaiserver.llm.controller;

import com.example.mapaiserver.common.response.ApiResponse;
import com.example.mapaiserver.llm.dto.request.PlanIdRequest;
import com.example.mapaiserver.llm.dto.request.PlanSaveRequest;
import com.example.mapaiserver.llm.dto.response.EnvAnalysisResponse;
import com.example.mapaiserver.llm.dto.request.GraphRunRequest;
import com.example.mapaiserver.llm.dto.response.GraphRunResponse;
import com.example.mapaiserver.llm.service.LlmGraphService;
import com.example.mapaiserver.redis.service.CommandRegistryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
/**
 * LLM 业务主控制器：
 * 提供图上指令解析、切分部署和环境分析接口。
 */
public class LlmController {

    private final LlmGraphService llmGraphService;
    private final CommandRegistryService commandRegistryService;

    public LlmController(LlmGraphService llmGraphService, CommandRegistryService commandRegistryService) {
        this.llmGraphService = llmGraphService;
        this.commandRegistryService = commandRegistryService;
    }
    // 要图标绘流程接口：输入用户消息，输出图上各节点的执行结果（包含但不限于 DRW_COMMAND_JUDGE -> DRW_CHAT_GUIDE / DRW_COMMAND_RECOGNITION）。
    @PostMapping("/graph/run")
    public GraphRunResponse runGraph(@RequestBody @Valid GraphRunRequest request) {
        // Graph （DRW_COMMAND_JUDGE -> DRW_CHAT_GUIDE / DRW_COMMAND_RECOGNITION）
        return llmGraphService.runGraph(request.message(), request.planId());
    }
    // 一键部署 命令切分接口：输入一段指令，输出切分后的部署命令列表。
    @PostMapping("/split_cmd")
    public ApiResponse<Object> splitCmd(@RequestBody @Valid GraphRunRequest request) {
        // 先切分整段指令，再并行跑 graph，最后统一返回可部署 commands。
        Map<String, Object> result = llmGraphService.runSplitCommandGraph(request.message(), request.planId());
        int code = ((Number) result.getOrDefault("code", 0)).intValue();
        String message = String.valueOf(result.getOrDefault("message", "success"));
        ApiResponse<Object> response = code == 0 ? ApiResponse.success() : ApiResponse.fail(code, message);
        // 兼容历史前端：除 code/message 外继续平铺输出原字段。
        result.forEach((k, v) -> {
            if (!"code".equals(k) && !"message".equals(k)) {
                response.put(k, v);
            }
        });
        return response;
    }

    @PostMapping(value = "/split_cmd_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter splitCmdStream(@RequestBody @Valid GraphRunRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        llmGraphService.runSplitCommandGraphStream(request.message(), request.planId(), emitter);
        return emitter;
    }

    @PostMapping("/plan/create")
    public ApiResponse<Object> createPlan() {
        String planId = commandRegistryService.allocatePlanId();
        return ApiResponse.success().put("plan_id", planId);
    }

    @PostMapping("/plan/save")
    public ApiResponse<Object> savePlan(@RequestBody @Valid PlanSaveRequest request) {
        Map<String, Object> result = commandRegistryService.savePlan(request.planId(), request.planName(), request.commands());
        ApiResponse<Object> response = ApiResponse.success();
        result.forEach(response::put);
        return response;
    }

    @PostMapping("/plan/release")
    public ApiResponse<Object> releasePlan(@RequestBody @Valid PlanIdRequest request) {
        return ApiResponse.success().put("released", commandRegistryService.releasePlan(request.planId()));
    }

    @PostMapping("/plan/delete")
    public ApiResponse<Object> deletePlan(@RequestBody @Valid PlanIdRequest request) {
        return ApiResponse.success().put("deleted", commandRegistryService.deletePlan(request.planId()));
    }

    @GetMapping("/plan/load")
    public ApiResponse<Object> loadPlan(@RequestParam("plan_id") String planId) {
        ApiResponse<Object> response = ApiResponse.success();
        commandRegistryService.loadPlanDetail(planId).forEach(response::put);
        return response;
    }

    @GetMapping("/plan/list")
    public ApiResponse<Object> listPlans() {
        return ApiResponse.success().put("plans", commandRegistryService.listPlans());
    }

    @PostMapping("/submit_msg")
    public ApiResponse<Object> submitMsg(@RequestBody @Valid GraphRunRequest request) {
        // submit_msg 当前用于链路打通，保持轻量回显。
        return ApiResponse.success().put("reply", "收到消息：" + request.message());
    }

    @PostMapping("/envanalysis")
    public EnvAnalysisResponse envAnalysis(@RequestBody @Valid GraphRunRequest request) {
        return llmGraphService.runEnvAnalysis(request.message());
    }

    @PostMapping(value = "/envanalysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter envAnalysisStream(@RequestBody @Valid GraphRunRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        llmGraphService.runEnvAnalysisStream(request.message(), emitter);
        return emitter;
    }
}
