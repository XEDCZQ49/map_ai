package com.example.mapaiserver.mcp.qweather;

import com.example.mapaiserver.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp/qweather")
/**
 * 和风天气 MCP 控制器：
 * 对外提供城市搜索、天气预报、指数和预警查询。
 */
public class QWeatherMcpController {

    private final QWeatherMcpService service;

    public QWeatherMcpController(QWeatherMcpService service) {
        this.service = service;
    }

    /**
     * GeoAPI 城市搜索
     * 示例：
     * /api/mcp/qweather/geo/city-lookup?location=南京
     */
    @GetMapping("/geo/city-lookup")
    public ApiResponse<JsonNode> cityLookup(
            @RequestParam String location,
            @RequestParam(required = false) String adm,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) String lang
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", location);
        params.put("adm", adm);
        params.put("range", range);
        params.put("number", number);
        params.put("lang", lang);
        return ApiResponse.success(service.geoCityLookup(params));
    }

    /**
     * GeoAPI POI 范围搜索
     * 示例：
     * /api/mcp/qweather/geo/poi-range?location=116.41,39.92&radius=5000
     */
    @GetMapping("/geo/poi-range")
    public ApiResponse<JsonNode> poiRange(
            @RequestParam String location,
            @RequestParam(defaultValue = "5000") String radius,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String number,
            @RequestParam(required = false) String lang
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", location);
        params.put("radius", radius);
        params.put("type", type);
        params.put("number", number);
        params.put("lang", lang);
        return ApiResponse.success(service.geoPoiRange(params));
    }

    /**
     * 每日天气预报
     * 示例：
     * /api/mcp/qweather/weather/daily?location=101010100&days=7
     */
    @GetMapping("/weather/daily")
    public ApiResponse<JsonNode> daily(
            @RequestParam String location,
            @RequestParam(defaultValue = "7") String days,
            @RequestParam(required = false) String lang
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", location);
        params.put("lang", lang);
        return ApiResponse.success(service.weatherDaily(days, params));
    }

    /**
     * 天气指数
     * 示例：
     * /api/mcp/qweather/weather/indices?location=101010100&type=1,2,3&days=1
     */
    @GetMapping("/weather/indices")
    public ApiResponse<JsonNode> indices(
            @RequestParam String location,
            @RequestParam(defaultValue = "1,2,3,5") String type,
            @RequestParam(defaultValue = "1") String days,
            @RequestParam(required = false) String lang
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", location);
        params.put("type", type);
        params.put("lang", lang);
        return ApiResponse.success(service.weatherIndices(days, params));
    }

    /**
     * 天气预警
     * 示例：
     * /api/mcp/qweather/weather/warning?location=101010100
     */
    @GetMapping("/weather/warning")
    public ApiResponse<JsonNode> warning(
            @RequestParam String location,
            @RequestParam(required = false) String lang
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("location", location);
        params.put("lang", lang);
        return ApiResponse.success(service.weatherWarningNow(params));
    }
}
