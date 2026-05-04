package com.example.mapaiserver.redis.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RedisVectorService {

    private final Optional<VectorStore> vectorStore;

    public RedisVectorService(Optional<VectorStore> vectorStore) {
        this.vectorStore = vectorStore;
    }

    public Map<String, Object> upsert(String text, Map<String, Object> metadata) {
        if (vectorStore.isEmpty()) {
            return Map.of("code", 500, "message", "vector store 未初始化");
        }
        String id = UUID.randomUUID().toString();
        Map<String, Object> safeMetadata = metadata == null ? new LinkedHashMap<>() : metadata;
        safeMetadata.putIfAbsent("source", "manual");
        Document doc = new Document(id, text, safeMetadata);
        vectorStore.get().add(List.of(doc));
        return Map.of("code", 0, "message", "success", "id", id);
    }

    public Map<String, Object> search(String query, int topK) {
        if (vectorStore.isEmpty()) {
            return Map.of("code", 500, "message", "vector store 未初始化");
        }
        int k = topK <= 0 ? 5 : Math.min(topK, 20);
        List<Document> docs = vectorStore.get().similaritySearch(
                SearchRequest.builder().query(query).topK(k).build()
        );
        List<Map<String, Object>> items = docs.stream().map(d -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("text", d.getText());
            item.put("metadata", d.getMetadata());
            return item;
        }).collect(Collectors.toList());
        return Map.of("code", 0, "message", "success", "items", items);
    }
}

