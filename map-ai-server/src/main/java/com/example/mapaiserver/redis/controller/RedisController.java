package com.example.mapaiserver.redis.controller;

import com.example.mapaiserver.common.response.ApiResponse;
import com.example.mapaiserver.redis.service.RedisDataService;
import com.example.mapaiserver.redis.service.RedisVectorService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/redis")
/**
 * Redis 调试控制器：
 * 包含 KV 与向量检索的基础调试接口。
 */
public class RedisController {

    private final RedisDataService redisDataService;
    private final RedisVectorService redisVectorService;

    public RedisController(RedisDataService redisDataService, RedisVectorService redisVectorService) {
        this.redisDataService = redisDataService;
        this.redisVectorService = redisVectorService;
    }

    @PostMapping("/kv/set")
    public ApiResponse<Object> kvSet(
            @RequestParam String key,
            @RequestParam String value,
            @RequestParam(required = false) Long ttlSeconds
    ) {
        redisDataService.set(key, value, ttlSeconds);
        return ApiResponse.success();
    }

    @GetMapping("/kv/get")
    public ApiResponse<Object> kvGet(@RequestParam String key) {
        return ApiResponse.success().put("value", redisDataService.get(key));
    }

    @DeleteMapping("/kv/delete")
    public ApiResponse<Object> kvDelete(@RequestParam String key) {
        return ApiResponse.success().put("deleted", Boolean.TRUE.equals(redisDataService.delete(key)));
    }

    @PostMapping("/vector/upsert")
    public ApiResponse<Object> vectorUpsert(
            @RequestParam String text,
            @RequestParam(required = false) String tag
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (tag != null && !tag.isBlank()) metadata.put("tag", tag);
        Map<String, Object> result = redisVectorService.upsert(text, metadata);
        ApiResponse<Object> response = ApiResponse.success();
        // 向后兼容：平铺 vector upsert 的历史字段。
        result.forEach(response::put);
        return response;
    }

    @GetMapping("/vector/search")
    public ApiResponse<Object> vectorSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK
    ) {
        Map<String, Object> result = redisVectorService.search(query, topK);
        ApiResponse<Object> response = ApiResponse.success();
        // 向后兼容：平铺 vector search 的历史字段。
        result.forEach(response::put);
        return response;
    }
}
