package com.example.mapaiserver.rag.service;

import com.example.mapaiserver.llm.service.PromptStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * RAG 问答服务：
 * 将检索片段交给 LLM，输出相关性判断与最终回复。
 */
public class RagChatService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PromptStoreService promptStoreService;

    public RagChatService(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            PromptStoreService promptStoreService
    ) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.promptStoreService = promptStoreService;
    }

    public Map<String, Object> answer(String kbName, String question, List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return Map.of(
                    "related", false,
                    "reason", "未检索到有效资料",
                    "reply", "当前未检索到与问题相关的资料。请在该知识库中更换关键词后重试，例如：地点名、单位名、时间范围。"
            );
        }

        try {
            String ragNode = promptStoreService.ensureRagChatPromptForKb(kbName);
            String context = buildContext(docs);
            String raw = chatClient.prompt()
                    .system(promptNow(ragNode))
                    .user("知识库：" + kbName + "\n用户问题：" + question + "\n检索资料：\n" + context)
                    .call()
                    .content();

            JsonNode node = extractJsonNode(raw);
            boolean related = node.path("related").asBoolean(false);
            String reply = node.path("reply").asText("").trim();
            String reason = node.path("reason").asText("").trim();

            if (reply.isBlank()) {
                reply = related
                        ? "已检索到资料，但模型未返回可展示答案，请重试。"
                        : "当前检索结果与问题相关性较低。请更换关键词，并尽量包含地点、对象或时间。";
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("related", related);
            out.put("reason", reason.isBlank() ? (related ? "相关" : "相关性较低") : reason);
            out.put("reply", reply);
            return out;
        } catch (Exception e) {
            return Map.of(
                    "related", false,
                    "reason", "RAG_CHAT调用失败",
                    "reply", "情报问答暂时不可用，请稍后重试。"
            );
        }
    }

    private String buildContext(List<Map<String, Object>> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Object> item = docs.get(i);
            String text = String.valueOf(item.getOrDefault("text", ""));
            Map<String, Object> meta = castMap(item.get("metadata"));
            String source = String.valueOf(meta.getOrDefault("file_name", "unknown"));

            sb.append(i + 1)
                    .append(". 来源=")
                    .append(source)
                    .append("\n内容=")
                    .append(shorten(text, 900))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private String shorten(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "...";
    }

    private String promptNow(String node) {
        String text = promptStoreService.getNow(node);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("缺少 Redis 提示词: " + node);
        }
        return text;
    }

    private JsonNode extractJsonNode(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (first >= 0 && end > first) {
                text = text.substring(first, end + 1);
            }
        }
        return objectMapper.readTree(text);
    }
}
