package com.example.mapaiserver.llm.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.mapaiserver.llm.dto.response.ChatNodeResult;
import com.example.mapaiserver.llm.dto.response.CommandActionDto;
import com.example.mapaiserver.llm.dto.response.EnvAnalysisResponse;
import com.example.mapaiserver.llm.dto.response.GraphRunResponse;
import com.example.mapaiserver.llm.dto.response.JudgeNodeResult;
import com.example.mapaiserver.mcp.qweather.QWeatherMcpService;
import com.example.mapaiserver.redis.service.CommandRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
/**
 * LLM 图流程服务：
 * 负责指令判定、指令解析、切分部署和环境分析主流程编排。
 */
public class LlmGraphService {
    private static final String NODE_DRW_COMMAND_JUDGE = "DRW_COMMAND_JUDGE";
    private static final String NODE_DRW_COMMAND_RECOGNITION = "DRW_COMMAND_RECOGNITION";
    private static final String NODE_DRW_CHAT_GUIDE = "DRW_CHAT_GUIDE";
    private static final String NODE_DRW_SPLIT_COMMAND = "DRW_SPLIT_COMMAND";
    private static final String NODE_ENV_GEO_JUDGE = "ENV_GEO_JUDGE";
    private static final String NODE_ENV_CHAT_GUIDE = "ENV_CHAT_GUIDE";
    private static final String NODE_ENV_BBOX_CITY = "ENV_BBOX_CITY";
    private static final String NODE_ENV_SUMMARY = "ENV_SUMMARY";

    private static final Set<String> ALLOWED_INTENT = Set.of("chat", "instruction");
    private static final Set<String> ALLOWED_FUNCTION = Set.of(
            "add_tank", "add_aircraft", "add_by", "add_car",
            "draw_attack", "draw_attack_route", "draw_encirclement_attack",
            "draw_group", "draw_defense", "draw_boundary"
    );
    private static final Set<String> ALLOWED_ENTITY_NAME = Set.of("tank", "aircraft", "car", "by", "none");
    private static final Pattern MISSING_LOCATION_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,20})的经纬度");
    private static final Pattern TEXT_LOCATION_PATTERN = Pattern.compile("(?:在|从|向|到|途径|夹击)([\\u4e00-\\u9fa5]{2,20})");
    private static final Set<String> LOCATION_STOP_WORDS = Set.of(
            "红方", "蓝方", "黑方", "红军", "蓝军", "黑军",
            "作战", "战斗", "分界线", "防御阵地", "防御", "进攻",
            "部署", "设置", "出发", "分别", "夹击", "部队", "军营", "飞机", "坦克", "战车"
    );

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final QWeatherMcpService qWeatherMcpService;
    private final PromptStoreService promptStoreService;
    private final CommandRegistryService commandRegistryService;
    @Value("${llm.split.model:qwen-turbo}")
    private String splitModel;

    public LlmGraphService(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            QWeatherMcpService qWeatherMcpService,
            PromptStoreService promptStoreService,
            CommandRegistryService commandRegistryService
    ) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.qWeatherMcpService = qWeatherMcpService;
        this.promptStoreService = promptStoreService;
        this.commandRegistryService = commandRegistryService;
    }

    public GraphRunResponse runGraph(String userText) {
        return runGraph(userText, null);
    }

    public GraphRunResponse runGraph(String userText, String planId) {
        String workingPlanId = commandRegistryService.ensureWorkingPlan(planId);
        JudgeNodeResult judge = runJudge(userText);
        if ("instruction".equals(judge.intent())) {
            List<CommandActionDto> commands = runCommandRecognition(userText, workingPlanId);
            return new GraphRunResponse(0, "success", "instruction", workingPlanId, judge, commands, null);
        }
        ChatNodeResult chat = runChat(userText);
        return new GraphRunResponse(0, "success", "chat", workingPlanId, judge, List.of(), chat);
    }

    /**
     * 本地规则切分整段指令：按行拆分，逐条进入后续 graph 流程。
     */
    public List<String> splitCommands(String rawText) {
        return ruleSplitCommands(rawText);
    }

    /**
     * 并行执行多条指令，每条走同一套 runGraph 逻辑，最后聚合结果用于前端统一部署。
     */
    public Map<String, Object> runSplitCommandGraph(String rawText, String planId) {
        String workingPlanId = commandRegistryService.ensureWorkingPlan(planId);
        List<String> splitCommands = splitCommands(rawText);
        if (splitCommands.isEmpty()) {
            return Map.of("code", 400, "message", "未检测到可执行指令", "plan_id", workingPlanId, "split_count", 0, "commands", List.of(), "results", List.of());
        }

        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (int i = 0; i < splitCommands.size(); i++) {
            final int index = i;
            final String cmdText = splitCommands.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                GraphRunResponse resp = runGraph(cmdText, workingPlanId);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", index);
                item.put("input", cmdText);
                item.put("intent", resp.intent());
                item.put("plan_id", resp.planId());
                item.put("judge", resp.judge());
                item.put("commands", resp.commands() == null ? List.of() : resp.commands());
                item.put("chat", resp.chat());
                return item;
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<Map<String, Object>> results = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparingInt(m -> ((Number) m.get("index")).intValue()))
                .toList();

        List<CommandActionDto> mergedCommands = new ArrayList<>();
        for (Map<String, Object> item : results) {
            Object cmds = item.get("commands");
            if (cmds instanceof List<?> list) {
                for (Object c : list) {
                    if (c instanceof CommandActionDto dto) {
                        mergedCommands.add(dto);
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("message", "success");
        out.put("plan_id", workingPlanId);
        out.put("mode", "split_parallel_graph");
        out.put("split_count", splitCommands.size());
        out.put("split_commands", splitCommands);
        out.put("commands", mergedCommands);
        out.put("results", results);
        return out;
    }

    /**
     * SSE 流式执行：每条指令完成后立即发送 item 事件。
     */
    public void runSplitCommandGraphStream(String rawText, String planId, SseEmitter emitter) {
        String workingPlanId = commandRegistryService.ensureWorkingPlan(planId);
        CompletableFuture.runAsync(() -> {
            try {
                List<String> splitCommands = splitCommands(rawText);
                if (splitCommands.isEmpty()) {
                    sendSseThreadSafe(emitter, "error", Map.of("message", "未检测到可执行指令"));
                    emitter.complete();
                    return;
                }

                sendSseThreadSafe(emitter, "start", Map.of(
                        "mode", "split_parallel_graph_stream",
                        "plan_id", workingPlanId,
                        "split_count", splitCommands.size()
                ));

                List<CommandActionDto> mergedCommands = Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (int i = 0; i < splitCommands.size(); i++) {
                    final int index = i;
                    final String cmdText = splitCommands.get(i);
                    CompletableFuture<Void> f = CompletableFuture.supplyAsync(() -> runGraph(cmdText, workingPlanId))
                            .thenAccept(resp -> {
                                List<CommandActionDto> commands = resp.commands() == null ? List.of() : resp.commands();
                                if (!commands.isEmpty()) {
                                    mergedCommands.addAll(commands);
                                }
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("index", index);
                                item.put("input", cmdText);
                                item.put("intent", resp.intent());
                                item.put("plan_id", resp.planId());
                                item.put("judge", resp.judge());
                                item.put("commands", commands);
                                item.put("chat", resp.chat());
                                sendSseThreadSafe(emitter, "item", item);
                            });
                    futures.add(f);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                sendSseThreadSafe(emitter, "done", Map.of(
                        "message", "success",
                        "plan_id", workingPlanId,
                        "split_count", splitCommands.size(),
                        "merged_count", mergedCommands.size(),
                        "commands", mergedCommands
                ));
                emitter.complete();
            } catch (Exception e) {
                sendSseThreadSafe(emitter, "error", Map.of(
                        "message", "split_cmd_stream 处理失败",
                        "detail", e.getMessage() == null ? "unknown" : e.getMessage()
                ));
                emitter.completeWithError(e);
            }
        });
    }

    private List<String> ruleSplitCommands(String rawText) {
        if (rawText == null || rawText.isBlank()) return List.of();
        List<String> byLine = Arrays.stream(rawText.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        return byLine.isEmpty() ? List.of(rawText.trim()) : byLine;
    }

    public JudgeNodeResult runJudge(String userText) {
        String raw;
        try {
            raw = ask(promptNow(NODE_DRW_COMMAND_JUDGE), userText);
        } catch (ContentPolicyBlockedException e) {
            return new JudgeNodeResult("chat", 1.0, "触发内容安全策略，转为引导回复");
        }
        try {
            JsonNode node = extractJsonNode(raw);
            String intent = normalizeIntent(node.path("intent").asText("chat"));
            double confidence = normalizeConfidence(node.path("confidence").asDouble(0.7));
            String reason = node.path("reason").asText("按规则判定");
            return new JudgeNodeResult(intent, confidence, reason);
        } catch (Exception ignored) {
            return new JudgeNodeResult("chat", 0.5, "解析失败，按 chat 兜底");
        }
    }

    public List<CommandActionDto> runCommandRecognition(String userText, String planId) {
        String raw;
        try {
            raw = ask(promptNow(NODE_DRW_COMMAND_RECOGNITION), userText);
        } catch (ContentPolicyBlockedException e) {
            return List.of(needClarification(e.getMessage()));
        }
        try {
            JsonNode node = extractJsonNode(raw);
            JsonNode actionArray = node;
            // 兼容 { "commands": [...] } 结构，避免模型输出包裹对象时解析失败。
            if (!actionArray.isArray() && actionArray.has("commands")) {
                actionArray = actionArray.path("commands");
            }
            if (!actionArray.isArray()) {
                return List.of(needClarification("输出不是数组"));
            }
            List<CommandActionDto> out = new ArrayList<>();
            for (JsonNode item : actionArray) {
                out.add(sanitizeAction(item, userText));
            }
            if (out.isEmpty()) {
                return List.of(needClarification("未识别到可执行动作"));
            }
            return commandRegistryService.assignIds(planId, out);
        } catch (Exception ignored) {
            return List.of(needClarification("解析失败，请补充更明确的指令"));
        }
    }

    public ChatNodeResult runChat(String userText) {
        String raw;
        try {
            raw = ask(promptNow(NODE_DRW_CHAT_GUIDE), userText);
        } catch (ContentPolicyBlockedException e) {
            return new ChatNodeResult("success", e.getMessage());
        }
        try {
            JsonNode node = extractJsonNode(raw);
            String message = node.path("message").asText("success");
            String reply = node.path("reply").asText("收到。请继续输入。");
            return new ChatNodeResult(message, reply);
        } catch (Exception ignored) {
            return new ChatNodeResult("success", "收到。请继续输入。");
        }
    }

    /**
     * Env Graph:
     * 1) GeoJudge -> 判断是否有地理信息
     * 2) 无地理信息: ChatGuide
     * 3) 有地理信息: MCP查询（大bbox先识别城市）
     * 4) MilitarySummary -> 输出专业作战环境分析
     */
    public EnvAnalysisResponse runEnvAnalysis(String userText) {
        JsonNode geo = runEnvGeoJudgeNode(userText);
        boolean hasLocation = geo.path("hasLocation").asBoolean(false);
        if (!hasLocation) {
            String guide = runEnvChatGuideNode(userText);
            return new EnvAnalysisResponse(0, "success", "chat_guidance", false, guide);
        }

        Map<String, Object> mcpData = runEnvMcpQueryNode(userText, geo, null);
        String summary = runEnvSummaryNode(userText, geo, mcpData);
        return new EnvAnalysisResponse(0, "success", "env_analysis", true, summary);
    }

    public void runEnvAnalysisStream(String userText, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                sendSse(emitter, "stage", Map.of(
                        "stage", "geo_judge",
                        "status", "start",
                        "message", "步骤1/3：正在识别地理信息"
                ));
                JsonNode geo = runEnvGeoJudgeNode(userText);
                boolean hasLocation = geo.path("hasLocation").asBoolean(false);
                sendSse(emitter, "stage", Map.of(
                        "stage", "geo_judge",
                        "status", "done",
                        "message", hasLocation ? "已识别到地理信息，进入环境查询" : "未识别到地理信息，进入引导回复",
                        "hasLocation", hasLocation,
                        "locationType", geo.path("locationType").asText("unknown")
                ));

                if (!hasLocation) {
                    String guide = runEnvChatGuideNode(userText);
                    sendSse(emitter, "final", Map.of(
                            "mode", "chat_guidance",
                            "hasLocation", false,
                            "reply", "### 模块说明与引导\n\n" + guide
                    ));
                    emitter.complete();
                    return;
                }

                sendSse(emitter, "stage", Map.of(
                        "stage", "mcp_query",
                        "status", "start",
                        "message", "步骤2/3：正在调用MCP查询天气预报、指数与预警"
                ));

                Map<String, Object> mcpData = runEnvMcpQueryNode(userText, geo, msg ->
                        safeSendSse(emitter, "stage", Map.of("stage", "mcp_query", "status", "running", "message", msg))
                );

                sendSse(emitter, "stage", Map.of(
                        "stage", "mcp_query",
                        "status", "done",
                        "message", "MCP查询完成，开始生成专业分析"
                ));

                sendSse(emitter, "stage", Map.of(
                        "stage", "summary",
                        "status", "start",
                        "message", "步骤3/3：正在汇总作战环境分析"
                ));
                String summary = runEnvSummaryNode(userText, geo, mcpData);
                sendSse(emitter, "stage", Map.of(
                        "stage", "summary",
                        "status", "done",
                        "message", "分析完成"
                ));

                sendSse(emitter, "final", Map.of(
                        "mode", "env_analysis",
                        "hasLocation", true,
                        "reply", "### 作战环境分析结果\n\n" + summary
                ));
                emitter.complete();
            } catch (Exception e) {
                safeSendSse(emitter, "error", Map.of(
                        "message", "流式分析失败",
                        "detail", e.getMessage() == null ? "unknown" : e.getMessage()
                ));
                emitter.completeWithError(e);
            }
        });
    }

    private JsonNode runEnvGeoJudgeNode(String userText) {
        String raw = ask(promptNow(NODE_ENV_GEO_JUDGE), userText);
        try {
            JsonNode node = extractJsonNode(raw);
            boolean has = node.path("hasLocation").asBoolean(false);
            if (!has) {
                return objectMapper.readTree("{" +
                        "\"hasLocation\":false,\"locationType\":\"unknown\",\"reason\":\"未检测到地理信息\"}");
            }
            return node;
        } catch (Exception ignored) {
            try {
                return objectMapper.readTree("{" +
                        "\"hasLocation\":false,\"locationType\":\"unknown\",\"reason\":\"GeoJudge解析失败\"}");
            } catch (Exception e) {
                return objectMapper.createObjectNode();
            }
        }
    }

    private String runEnvChatGuideNode(String userText) {
        String raw = ask(promptNow(NODE_ENV_CHAT_GUIDE), userText);
        try {
            JsonNode node = extractJsonNode(raw);
            String reply = node.path("reply").asText("");
            if (reply == null || reply.isBlank()) {
                return "当前模块用于作战环境分析，请补充城市名或经纬度点/范围，例如：\"分析南京\"或\"分析 118.6,31.9 到 119.2,32.4\"。";
            }
            return reply;
        } catch (Exception ignored) {
            return "当前模块用于作战环境分析，请补充城市名或经纬度点/范围，例如：\"分析南京\"或\"分析 118.6,31.9 到 119.2,32.4\"。";
        }
    }

    private Map<String, Object> runEnvMcpQueryNode(String userText, JsonNode geo, Consumer<String> progressLogger) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("geoJudge", geo);

        String locationType = geo.path("locationType").asText("unknown");
        out.put("locationType", locationType);

        try {
            if ("bbox".equals(locationType) && isLargeBbox(geo.path("bbox"))) {
                logProgress(progressLogger, "检测到大范围经纬度，先识别范围内城市");
                List<String> cities = runBboxCityDetectNode(userText, geo.path("bbox"));
                out.put("cityCandidates", cities);

                List<Map<String, Object>> cityReports = new ArrayList<>();
                for (String city : cities) {
                    logProgress(progressLogger, "正在查询城市：" + city);
                    cityReports.add(queryWeatherBundleByCity(city));
                }
                out.put("cityReports", cityReports);
                out.put("queryStrategy", "large_bbox_city_batch");
                return out;
            }

            // 非大范围：按 city / point / 小bbox（中心点）查询
            if ("city".equals(locationType)) {
                String city = geo.path("city").asText("");
                if (city != null && !city.isBlank()) {
                    logProgress(progressLogger, "按城市查询：" + city);
                    out.put("singleReport", queryWeatherBundleByCity(city));
                    out.put("queryStrategy", "single_city");
                    return out;
                }
            }

            if ("point".equals(locationType)) {
                String location = geo.path("point").path("lon").asText("") + "," + geo.path("point").path("lat").asText("");
                logProgress(progressLogger, "按经纬度点查询：" + location);
                out.put("singleReport", queryWeatherBundleByLocation(location));
                out.put("queryStrategy", "single_point");
                return out;
            }

            if ("bbox".equals(locationType)) {
                JsonNode bbox = geo.path("bbox");
                double minLon = bbox.path("minLon").asDouble();
                double maxLon = bbox.path("maxLon").asDouble();
                double minLat = bbox.path("minLat").asDouble();
                double maxLat = bbox.path("maxLat").asDouble();
                double centerLon = (minLon + maxLon) / 2.0;
                double centerLat = (minLat + maxLat) / 2.0;
                String location = centerLon + "," + centerLat;
                logProgress(progressLogger, "按范围中心点查询：" + location);
                out.put("singleReport", queryWeatherBundleByLocation(location));
                out.put("queryStrategy", "small_bbox_center");
                return out;
            }

            // unknown 兜底
            logProgress(progressLogger, "位置类型不明确，按原始文本兜底查询");
            out.put("singleReport", queryWeatherBundleByLocation(userText));
            out.put("queryStrategy", "fallback_raw_text");
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private void logProgress(Consumer<String> progressLogger, String msg) {
        if (progressLogger != null) progressLogger.accept(msg);
    }

    private void sendSse(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private void safeSendSse(SseEmitter emitter, String event, Object data) {
        try {
            sendSse(emitter, event, data);
        } catch (Exception ignored) {
        }
    }

    private void sendSseThreadSafe(SseEmitter emitter, String event, Object data) {
        synchronized (emitter) {
            safeSendSse(emitter, event, data);
        }
    }

    private boolean isLargeBbox(JsonNode bbox) {
        try {
            double minLon = bbox.path("minLon").asDouble();
            double maxLon = bbox.path("maxLon").asDouble();
            double minLat = bbox.path("minLat").asDouble();
            double maxLat = bbox.path("maxLat").asDouble();
            double lonSpan = Math.abs(maxLon - minLon);
            double latSpan = Math.abs(maxLat - minLat);
            // 经验阈值：任一方向超过2度视为大范围
            return lonSpan >= 2.0 || latSpan >= 2.0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> runBboxCityDetectNode(String userText, JsonNode bbox) {
        try {
            String bboxText = objectMapper.writeValueAsString(bbox);
            String raw = chatClient.prompt()
                    .system(promptNow(NODE_ENV_BBOX_CITY))
                    .user("用户输入：" + userText + "\n经纬度范围：" + bboxText)
                    .call()
                    .content();
            JsonNode node = extractJsonNode(raw);
            List<String> cities = objectMapper.convertValue(node.path("cities"), new TypeReference<>() {
            });
            if (cities == null) return List.of();
            List<String> cleaned = new ArrayList<>();
            for (String c : cities) {
                if (c != null && !c.isBlank() && !cleaned.contains(c)) {
                    cleaned.add(c.trim());
                }
                if (cleaned.size() >= 5) break;
            }
            return cleaned;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> queryWeatherBundleByCity(String city) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", city);
        try {
            Map<String, String> cityLookupParams = new LinkedHashMap<>();
            cityLookupParams.put("location", city);
            JsonNode cityLookup = qWeatherMcpService.geoCityLookup(cityLookupParams);
            out.put("cityLookup", cityLookup);

            String location = extractBestLocation(cityLookup, city);
            out.put("resolvedLocation", location);

            out.putAll(queryWeatherBundleByLocation(location));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> queryWeatherBundleByLocation(String location) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", location);
        try {
            Map<String, String> base = new LinkedHashMap<>();
            base.put("location", location);

            Map<String, String> indexParams = new LinkedHashMap<>(base);
            indexParams.put("type", "1,2,3,5,9");

            JsonNode daily = qWeatherMcpService.weatherDaily("7", base);
            JsonNode warning = qWeatherMcpService.weatherWarningNow(base);
            JsonNode indices = qWeatherMcpService.weatherIndices("1", indexParams);

            out.put("daily", daily);
            out.put("warning", warning);
            out.put("indices", indices);
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private String extractBestLocation(JsonNode cityLookup, String fallback) {
        try {
            JsonNode upstream = cityLookup.path("upstream");
            JsonNode locations = upstream.path("location");
            if (locations.isArray() && locations.size() > 0) {
                JsonNode first = locations.get(0);
                String id = first.path("id").asText("");
                if (!id.isBlank()) return id;
                String lon = first.path("lon").asText("");
                String lat = first.path("lat").asText("");
                if (!lon.isBlank() && !lat.isBlank()) return lon + "," + lat;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String runEnvSummaryNode(String userText, JsonNode geo, Map<String, Object> mcpData) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("userText", userText);
            context.put("geo", geo);
            context.put("mcpData", mcpData);
            String ctx = objectMapper.writeValueAsString(context);

            String raw = chatClient.prompt()
                    .system(promptNow(NODE_ENV_SUMMARY))
                    .user("分析上下文：" + ctx)
                    .call()
                    .content();
            JsonNode node = extractJsonNode(raw);
            String reply = node.path("reply").asText("");
            if (reply == null || reply.isBlank()) {
                return "已完成环境数据查询，但总结节点未返回有效文本，请稍后重试。";
            }
            return reply;
        } catch (Exception ignored) {
            return "环境查询已完成，但总结阶段失败。请补充更明确的地区范围后重试。";
        }
    }

    private String ask(String systemPrompt, String userText) {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user("用户输入：" + userText)
                    .call()
                    .content();
            return content == null ? "" : content;
        } catch (Exception e) {
            if (isContentPolicyBlocked(e)) {
                throw new ContentPolicyBlockedException("当前指令触发内容安全策略，请改用“演训/推演”表述或拆分后重试。");
            }
            throw e;
        }
    }

    private boolean isContentPolicyBlocked(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String msg = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("datainspectionfailed") || msg.contains("inappropriate content")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class ContentPolicyBlockedException extends RuntimeException {
        ContentPolicyBlockedException(String message) {
            super(message);
        }
    }

    private String promptNow(String node) {
        String text = promptStoreService.getNow(node);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("缺少 Redis 提示词: " + node);
        }
        return text;
    }

    private String normalizeIntent(String intent) {
        String i = intent == null ? "chat" : intent.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_INTENT.contains(i) ? i : "chat";
    }

    private double normalizeConfidence(double confidence) {
        if (Double.isNaN(confidence)) return 0.7;
        if (confidence < 0) return 0.0;
        return Math.min(confidence, 1.0);
    }

    private String normalizeColor(String color) {
        String c = color == null ? "" : color.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return "red";
        return c.matches("[a-z]+") ? c : "red";
    }

    private CommandActionDto sanitizeAction(JsonNode item, String userText) {
        String modelMessage = item.path("message").asText("success");
        if (!"success".equals(modelMessage) && !"need_clarification".equals(modelMessage)) {
            modelMessage = "success";
        }

        String functionName = item.has("function_name")
                ? item.path("function_name").asText("")
                : item.path("functionName").asText("");
        functionName = normalizeFunctionName(functionName);

        String color = normalizeColor(item.path("color").asText(""));

        Map<String, Object> arguments = objectMapper.convertValue(
                item.path("arguments"),
                new TypeReference<>() {
                }
        );
        if (arguments == null) arguments = new LinkedHashMap<>();
        if (!ALLOWED_FUNCTION.contains(functionName)) {
            return needClarification("未识别的 function_name");
        }

        List<String> missing = objectMapper.convertValue(
                item.path("missing"),
                new TypeReference<>() {
                }
        );
        if (missing == null) missing = new ArrayList<>();
        Map<String, Object> normalized = normalizeArguments(functionName, arguments, missing, userText);
        String finalMessage = missing.isEmpty() && "success".equals(modelMessage) ? "success" : "need_clarification";
        return new CommandActionDto(finalMessage, functionName, color, normalized, missing);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeArguments(String functionName, Map<String, Object> args, List<String> missing, String userText) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (functionName) {
            case "add_tank", "add_aircraft", "add_by", "add_car" -> {
                List<Double> point = asPoint(args.get("lonlat"));
                if (point == null) {
                    List<List<Double>> resolved = resolvePointsFromContext(missing, userText, 1, 1);
                    if (!resolved.isEmpty()) {
                        point = resolved.get(0);
                    }
                }
                if (point == null) {
                    missing.add("lonlat 必须是经纬度数组 [lon,lat]，不支持地名");
                } else {
                    out.put("lonlat", point);
                }
                String entityName = entityNameOf(functionName);
                if (entityName.isBlank()) {
                    missing.add("entity_name 不能为空");
                } else if (!ALLOWED_ENTITY_NAME.contains(entityName)) {
                    missing.add("entity_name 必须是 tank|aircraft|car|by|none");
                } else {
                    out.put("entity_name", entityName);
                }
            }
            case "draw_group", "draw_defense" -> {
                List<List<Double>> points = asPoints(args.get("lonlat"));
                if (points.size() < 3) {
                    points = resolvePointsFromContext(missing, userText, 3, 3);
                }
                if (points.size() >= 3) {
                    out.put("lonlat", points.subList(0, 3));
                } else {
                    missing.add("draw_group/draw_defense 需要3个经纬度点");
                }
            }
            case "draw_boundary" -> {
                List<List<Double>> points = asPoints(args.get("lonlat"));
                if (points.size() < 2) {
                    points = resolvePointsFromContext(missing, userText, 2, 10);
                }
                if (points.size() >= 2) {
                    out.put("lonlat", points);
                } else {
                    missing.add("draw_boundary 至少需要2个经纬度点");
                }
                out.put("include", normalizeInclude(args.get("include"), points.size()));
            }
            case "draw_attack" -> {
                List<List<Double>> points = asPoints(args.get("lonlat"));
                if (points.size() < 2) {
                    points = resolvePointsFromContext(missing, userText, 2, 2);
                }
                if (points.size() >= 2) {
                    out.put("lonlat", points);
                } else {
                    missing.add("draw_attack 至少需要2个经纬度点");
                }
            }
            case "draw_attack_route" -> {
                List<List<Double>> points = asPoints(args.get("lonlat"));
                if (points.size() < 3 || points.size() > 4) {
                    points = resolvePointsFromContext(missing, userText, 3, 4);
                }
                if (points.size() >= 3 && points.size() <= 4) {
                    out.put("lonlat", points);
                } else {
                    missing.add("draw_attack_route 需要3或4个经纬度点");
                }
            }
            case "draw_encirclement_attack" -> {
                List<List<Double>> points = asPoints(args.get("lonlat"));
                if (points.size() < 3 || points.size() > 4) {
                    points = resolvePointsFromContext(missing, userText, 3, 4);
                }
                if (points.size() >= 3 && points.size() <= 4) {
                    out.put("lonlat", points);
                } else {
                    missing.add("draw_encirclement_attack 需要3或4个经纬度点");
                }
            }
            default -> out.putAll(args);
        }
        return out;
    }

    private Integer asInt(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private List<Integer> asAngles(Object value, int size) {
        List<Integer> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) out.add(asInt(o, 0));
        } else {
            out.add(asInt(value, 0));
        }
        if (size <= 0) return out;
        while (out.size() < size) out.add(0);
        if (out.size() > size) return out.subList(0, size);
        return out;
    }

    private List<String> normalizeInclude(Object value, int size) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                String v = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
                out.add("in".equals(v) ? "in" : "out");
            }
        }
        while (out.size() < size) out.add("out");
        if (out.size() > size) return out.subList(0, size);
        return out;
    }

    private List<Double> asPoint(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 2) return null;
        Double lon = parseNum(list.get(0));
        Double lat = parseNum(list.get(1));
        if (lon == null || lat == null) return null;
        if (!isLonLatValid(lon, lat)) return null;
        return List.of(lon, lat);
    }

    private List<List<Double>> asPoints(Object value) {
        List<List<Double>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            if (list.size() >= 2 && !(list.get(0) instanceof List<?>)) {
                List<Double> single = asPoint(list);
                if (single != null) out.add(single);
                return out;
            }
            for (Object item : list) {
                List<Double> p = asPoint(item);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private List<List<Double>> resolvePointsFromContext(List<String> missing, String userText, int minCount, int maxCount) {
        List<String> hints = collectLocationHints(missing, userText);
        if (hints.isEmpty()) return List.of();

        List<List<Double>> points = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        for (String name : hints) {
            List<Double> point = resolvePointByName(name);
            if (point == null) continue;
            String key = point.get(0) + "," + point.get(1);
            if (dedup.add(key)) {
                points.add(point);
            }
            if (maxCount > 0 && points.size() >= maxCount) break;
        }

        if (points.size() >= minCount) {
            clearLocationMissing(missing);
            return points;
        }
        return List.of();
    }

    private List<String> collectLocationHints(List<String> missing, String userText) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();

        if (missing != null) {
            for (String miss : missing) {
                if (miss == null || miss.isBlank()) continue;
                Matcher matcher = MISSING_LOCATION_PATTERN.matcher(miss);
                while (matcher.find()) {
                    String name = cleanLocationName(matcher.group(1));
                    if (!name.isBlank()) hints.add(name);
                }
            }
        }

        String text = userText == null ? "" : userText;
        Matcher textMatcher = TEXT_LOCATION_PATTERN.matcher(text);
        while (textMatcher.find()) {
            String name = cleanLocationName(textMatcher.group(1));
            if (!name.isBlank()) hints.add(name);
        }

        for (String token : text.split("[，。；、\\s]+")) {
            String name = cleanLocationName(token);
            if (!name.isBlank()) hints.add(name);
        }
        return new ArrayList<>(hints);
    }

    private String cleanLocationName(String raw) {
        if (raw == null) return "";
        String name = raw.trim()
                .replaceAll("^(红方|蓝方|黑方|红军|蓝军|黑军|在|从|向|到|途径|夹击)+", "")
                .replaceAll("(部署|设置|进攻|防御|出发|夹击|作战群|防御阵地|战斗分界线).*$", "")
                .trim();
        if (name.isBlank()) return "";
        if (name.length() > 20 || name.length() < 2) return "";
        if (!name.matches("[\\u4e00-\\u9fa5]{2,20}")) return "";
        if (LOCATION_STOP_WORDS.contains(name)) return "";
        return name;
    }

    private List<Double> resolvePointByName(String locationName) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("location", locationName);
            JsonNode result = qWeatherMcpService.geoCityLookup(params);
            JsonNode locations = result.path("upstream").path("location");
            if (!locations.isArray() || locations.isEmpty()) return null;
            JsonNode first = locations.get(0);
            Double lon = parseNum(first.path("lon").asText(""));
            Double lat = parseNum(first.path("lat").asText(""));
            if (lon == null || lat == null || !isLonLatValid(lon, lat)) return null;
            return List.of(lon, lat);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearLocationMissing(List<String> missing) {
        if (missing == null || missing.isEmpty()) return;
        missing.removeIf(msg -> {
            if (msg == null || msg.isBlank()) return true;
            return msg.contains("经纬度") || msg.toLowerCase(Locale.ROOT).contains("lonlat");
        });
    }

    private Double parseNum(Object value) {
        if (value == null) return null;
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(d) ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isLonLatValid(double lon, double lat) {
        return lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
    }

    private String normalizeFunctionName(String rawName) {
        if (rawName == null) return "";
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        return name.isBlank() ? "" : name;
    }

    private String entityNameOf(String functionName) {
        return switch (functionName) {
            case "add_tank" -> "tank";
            case "add_aircraft" -> "aircraft";
            case "add_by" -> "by";
            case "add_car" -> "car";
            default -> "";
        };
    }

    private CommandActionDto needClarification(String missingMessage) {
        return new CommandActionDto(
                "need_clarification",
                "add_tank",
                "red",
                new LinkedHashMap<>(),
                List.of(missingMessage)
        );
    }

    private JsonNode extractJsonNode(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('{');
            int firstArr = text.indexOf('[');
            int start = first < 0 ? firstArr : (firstArr < 0 ? first : Math.min(first, firstArr));
            int endObj = text.lastIndexOf('}');
            int endArr = text.lastIndexOf(']');
            int end = Math.max(endObj, endArr);
            if (start >= 0 && end > start) {
                text = text.substring(start, end + 1);
            }
        }
        return objectMapper.readTree(text);
    }
}
