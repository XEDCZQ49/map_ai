package com.example.mapaiserver.rag.controller;

import com.example.mapaiserver.common.response.ApiResponse;
import com.example.mapaiserver.rag.service.KnowledgeBaseService;
import com.example.mapaiserver.llm.service.PromptStoreService;
import com.example.mapaiserver.rag.service.RagChatService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/rag")
/**
 * RAG 控制器：
 * 提供知识库管理、文件上传、检索问答等接口。
 */
public class RagController {

    private static final Set<String> ALLOWED_SUFFIX = Set.of(
            ".txt", ".md", ".pdf", ".docx", ".doc", ".csv", ".xlsx", ".json"
    );

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagChatService ragChatService;
    private final PromptStoreService promptStoreService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public RagController(
            KnowledgeBaseService knowledgeBaseService,
            RagChatService ragChatService,
            PromptStoreService promptStoreService,
            ObjectProvider<VectorStore> vectorStoreProvider
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ragChatService = ragChatService;
        this.promptStoreService = promptStoreService;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @GetMapping("/debug/status")
    public ApiResponse<Object> debugStatus() {
        boolean vectorStoreReady = vectorStoreProvider.getIfAvailable() != null;
        return ApiResponse.success()
                .put("vector_store_ready", vectorStoreReady)
                .put("hint", vectorStoreReady ? "ok" : "VectorStore 未初始化，请检查 Redis/Jedis/EmbeddingModel 配置");
    }

    @GetMapping("/kb/list")
    public ApiResponse<Object> listKnowledgeBases() {
        List<Map<String, String>> data = knowledgeBaseService.listKnowledgeBases().stream()
                .map(name -> Map.of("kb_name", name, "file_count", String.valueOf(knowledgeBaseService.countFiles(name))))
                .toList();
        return ApiResponse.success().put("data", data);
    }

    @PostMapping("/kb/create")
    public ApiResponse<Object> createKnowledgeBase(@RequestBody Map<String, Object> body) {
        String kbName = String.valueOf(body.getOrDefault("knowledge_base_name", "")).trim();
        if (kbName.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 不能为空");
        }
        knowledgeBaseService.createKnowledgeBase(kbName);
        return ApiResponse.success();
    }

    @PostMapping("/kb/delete")
    public ApiResponse<Object> deleteKnowledgeBase(@RequestBody Map<String, Object> body) {
        String kbName = String.valueOf(body.getOrDefault("knowledge_base_name", "")).trim();
        if (kbName.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 不能为空");
        }
        int fileCount = knowledgeBaseService.countFiles(kbName);
        if (fileCount > 0) {
            return ApiResponse.fail(409, "知识库中仍有文件，禁止删除").put("file_count", fileCount);
        }
        knowledgeBaseService.deleteKnowledgeBase(kbName);
        // 知识库删除后同步清理专用 RAG prompt（若不存在则忽略）
        promptStoreService.deleteRagChatPromptForKb(kbName);
        return ApiResponse.success();
    }

    @GetMapping("/kb/files")
    public ApiResponse<Object> listKnowledgeBaseFiles(@RequestParam("knowledge_base_name") String kbName) {
        String name = kbName == null ? "" : kbName.trim();
        if (name.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 不能为空");
        }
        return ApiResponse.success()
                .put("knowledge_base_name", name)
                .put("data", knowledgeBaseService.listFiles(name));
    }

    @PostMapping("/file/delete")
    public ApiResponse<Object> deleteFile(@RequestBody Map<String, Object> body) {
        String kbName = String.valueOf(body.getOrDefault("knowledge_base_name", "")).trim();
        String fileName = String.valueOf(body.getOrDefault("file_name", "")).trim();
        if (kbName.isBlank() || fileName.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 和 file_name 不能为空");
        }
        knowledgeBaseService.deleteFile(kbName, fileName);
        return ApiResponse.success();
    }

    @PostMapping("/upload")
    public ApiResponse<Object> upload(
            @RequestParam("knowledge_base_name") String kbName,
            @RequestParam("files") MultipartFile[] files
    ) {
        if (kbName == null || kbName.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 不能为空");
        }
        if (files == null || files.length == 0) {
            return ApiResponse.fail(400, "files 不能为空");
        }

        List<String> invalid = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (ALLOWED_SUFFIX.stream().noneMatch(name::endsWith)) {
                invalid.add(file.getOriginalFilename());
            }
        }

        if (!invalid.isEmpty()) {
            return ApiResponse.fail(400, "文件类型不支持")
                    .put("allowed", ALLOWED_SUFFIX)
                    .put("invalid", invalid);
        }

        int success = 0;
        int skipped = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (MultipartFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file_name", file.getOriginalFilename());
            Map<String, Object> result = knowledgeBaseService.upload(kbName, file);
            item.putAll(result);
            detail.add(item);
            if (Boolean.TRUE.equals(result.get("ok"))) success++;
            if (Boolean.TRUE.equals(result.get("skipped"))) skipped++;
        }

        return ApiResponse.success()
                .put("knowledge_base_name", kbName)
                .put("success", success)
                .put("skipped", skipped)
                .put("detail", detail);
    }

    @PostMapping("/chat")
    public ApiResponse<Object> chat(@RequestBody Map<String, Object> body) {
        String kbName = String.valueOf(body.getOrDefault("knowledge_base_name", "")).trim();
        String question = String.valueOf(body.getOrDefault("question", "")).trim();
        int topK = 5;
        try {
            topK = Integer.parseInt(String.valueOf(body.getOrDefault("top_k", 5)));
        } catch (Exception ignored) {
        }
        if (kbName.isBlank()) {
            return ApiResponse.fail(400, "knowledge_base_name 不能为空");
        }
        if (question.isBlank()) {
            return ApiResponse.fail(400, "question 不能为空");
        }

        Map<String, Object> ragResult = knowledgeBaseService.query(kbName, question, topK);
        List<Map<String, Object>> docs = castDocs(ragResult.get("docs"));
        Map<String, Object> ai = ragChatService.answer(kbName, question, docs);

        return ApiResponse.success()
                .put("knowledge_base_name", kbName)
                .put("question", question)
                .put("reply", ai.get("reply"))
                .put("related", ai.get("related"))
                .put("reason", ai.get("reason"))
                .put("docs", docs);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castDocs(Object docsObj) {
        if (docsObj instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }
}
