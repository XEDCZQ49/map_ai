package com.example.mapaiserver.common.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 API 返回体。
 * - 固定字段：code/message
 * - 兼容字段：data（可选）
 * - 扩展字段：通过 extra 平铺输出，兼容历史前端读取方式
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 追加兼容字段（平铺输出到 JSON 顶层）。
     */
    public ApiResponse<T> put(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> any() {
        return extra;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
