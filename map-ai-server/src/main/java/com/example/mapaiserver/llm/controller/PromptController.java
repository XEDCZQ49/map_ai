package com.example.mapaiserver.llm.controller;

import com.example.mapaiserver.common.response.ApiResponse;
import com.example.mapaiserver.llm.service.PromptStoreService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prompt/map_annotation")
/**
 * Prompt 配置控制器：
 * 提供节点查询、读取与保存能力。
 */
public class PromptController {

    private final PromptStoreService promptStoreService;

    public PromptController(PromptStoreService promptStoreService) {
        this.promptStoreService = promptStoreService;
    }
    // 节点列表接口，返回所有可用的 prompt 节点名称。
    @GetMapping("/nodes")
    public ApiResponse<Object> nodes() {
        return ApiResponse.success().put("nodes", promptStoreService.nodes());
    }
    // 获取节点接口，支持 type 参数选择获取当前值（now）或默认值（def）。
    @GetMapping("/get")
    public ApiResponse<Object> get(@RequestParam String node, @RequestParam(defaultValue = "now") String type) {
        String value = "def".equalsIgnoreCase(type) ? promptStoreService.getDef(node) : promptStoreService.getNow(node);
        return ApiResponse.success()
                .put("node", node)
                .put("type", type)
                .put("prompt", value);
    }
    // 保存节点接口，前端只能修改当前值（now）。
    @PostMapping("/save")
    public ApiResponse<Object> save(@RequestBody Map<String, String> body) {
        String node = body.getOrDefault("node", "");
        String prompt = body.getOrDefault("prompt", "");
        if (node.isBlank()) {
            return ApiResponse.fail(400, "node 不能为空");
        }
        // 前端只允许修改 now。
        promptStoreService.saveNow(node, prompt);
        return ApiResponse.success();
    }

}
