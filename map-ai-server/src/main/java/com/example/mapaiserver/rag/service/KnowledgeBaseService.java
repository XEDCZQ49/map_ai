package com.example.mapaiserver.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class KnowledgeBaseService {

    private static final String KB_NAMES_KEY = "rag:kb:names";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringRedisTemplate stringRedisTemplate;
    private final Optional<VectorStore> vectorStore;
    private final ObjectMapper objectMapper;
    private final Tika tika = new Tika();

    public KnowledgeBaseService(
            StringRedisTemplate stringRedisTemplate,
            Optional<VectorStore> vectorStore,
            ObjectMapper objectMapper
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    public void createKnowledgeBase(String kbName) {
        stringRedisTemplate.opsForSet().add(KB_NAMES_KEY, kbName);
    }

    public void deleteKnowledgeBase(String kbName) {
        List<String> files = listFileNames(kbName);
        for (String fileName : files) {
            deleteFile(kbName, fileName);
        }
        stringRedisTemplate.delete(filesHashKey(kbName));
        stringRedisTemplate.opsForSet().remove(KB_NAMES_KEY, kbName);
    }

    public List<String> listKnowledgeBases() {
        Set<String> set = stringRedisTemplate.opsForSet().members(KB_NAMES_KEY);
        if (set == null) return List.of();
        List<String> list = new ArrayList<>(set);
        list.sort(String::compareTo);
        return list;
    }

    public List<Map<String, Object>> listFiles(String kbName) {
        HashOperations<String, Object, Object> hash = stringRedisTemplate.opsForHash();
        Map<Object, Object> raw = hash.entries(filesHashKey(kbName));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            try {
                Map<String, Object> item = objectMapper.readValue(String.valueOf(e.getValue()), new TypeReference<>() {
                });
                out.add(item);
            } catch (Exception ignored) {
            }
        }
        out.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("file_name", ""))));
        return out;
    }

    public int countFiles(String kbName) {
        return listFiles(kbName).size();
    }

    public Map<String, Object> upload(String kbName, MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
            createKnowledgeBase(kbName);
            byte[] rawBytes = file.getBytes();
            String fileHash = sha256(rawBytes);

            // 内容去重：同知识库中任意同 hash 文件直接跳过（不重复写向量）
            Map<String, Object> existingByHash = findFileMetaByHash(kbName, fileHash);
            if (!existingByHash.isEmpty()) {
                return Map.of(
                        "ok", true,
                        "skipped", true,
                        "message", "文件重复，已跳过",
                        "status", "duplicate_skip",
                        "chunks", existingByHash.getOrDefault("docs_count", 0)
                );
            }

            // 同名但内容不同：先删旧版，再写新版
            Map<String, Object> existingSameName = getFileMeta(kbName, fileName);
            if (!existingSameName.isEmpty()) {
                deleteFile(kbName, fileName);
            }

            String content = extractText(rawBytes);
            List<String> chunkIds = List.of();
            String status;

            if (content == null || content.isBlank()) {
                status = "parse_empty";
            } else {
                chunkIds = upsertToVector(kbName, fileName, content);
                status = chunkIds.isEmpty() ? "vector_skip" : "parsed";
            }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("file_name", fileName);
            meta.put("create_time", LocalDateTime.now().format(TIME_FMT));
            meta.put("file_size", formatSize(file.getSize()));
            meta.put("file_version", "v1");
            meta.put("docs_count", chunkIds.size());
            meta.put("status", status);
            meta.put("in_db", !chunkIds.isEmpty());
            meta.put("file_hash", fileHash);

            stringRedisTemplate.opsForHash().put(filesHashKey(kbName), fileName, objectMapper.writeValueAsString(meta));
            if (content != null && !content.isBlank()) {
                stringRedisTemplate.opsForValue().set(fileTextKey(kbName, fileName), content);
            }
            if (!chunkIds.isEmpty()) {
                stringRedisTemplate.opsForSet().add(fileChunkKey(kbName, fileName), chunkIds.toArray(new String[0]));
            }

            return Map.of(
                    "ok", true,
                    "message", "上传成功",
                    "status", status,
                    "chunks", chunkIds.size()
            );
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public Map<String, Object> getFileMeta(String kbName, String fileName) {
        try {
            Object json = stringRedisTemplate.opsForHash().get(filesHashKey(kbName), fileName);
            if (json == null) return Map.of();
            return objectMapper.readValue(String.valueOf(json), new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public Map<String, Object> findFileMetaByHash(String kbName, String fileHash) {
        if (fileHash == null || fileHash.isBlank()) return Map.of();
        for (Map<String, Object> meta : listFiles(kbName)) {
            String hash = String.valueOf(meta.getOrDefault("file_hash", ""));
            if (fileHash.equals(hash)) return meta;
        }
        return Map.of();
    }

    public void deleteFile(String kbName, String fileName) {
        // 删除向量 chunk
        Set<String> chunkIds = stringRedisTemplate.opsForSet().members(fileChunkKey(kbName, fileName));
        if (chunkIds != null && !chunkIds.isEmpty() && vectorStore.isPresent()) {
            vectorStore.get().delete(new ArrayList<>(chunkIds));
        }
        stringRedisTemplate.delete(fileChunkKey(kbName, fileName));
        stringRedisTemplate.delete(fileTextKey(kbName, fileName));
        stringRedisTemplate.opsForHash().delete(filesHashKey(kbName), fileName);
    }

    public Map<String, Object> query(String kbName, String query, int topK) {
        if (vectorStore.isEmpty()) {
            return Map.of("reply", "向量库未初始化", "docs", List.of());
        }

        int safeK = Math.max(1, Math.min(topK, 20));
        int fetchK = Math.min(200, Math.max(60, safeK * 30));
        Set<String> allowedIds = listChunkIdsForKb(kbName);
        List<Document> docs = vectorStore.get().similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build()
        );
        docs = docs.stream()
                .filter(d -> allowedIds.contains(d.getId())
                        || kbName.equals(String.valueOf(d.getMetadata().getOrDefault("kb_name", ""))))
                .limit(safeK)
                .toList();

        if (docs.isEmpty()) {
            docs = keywordFallbackDocs(kbName, query, safeK);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", doc.getText());
            item.put("metadata", doc.getMetadata());
            items.add(item);
        }

        String reply;
        if (items.isEmpty()) {
            reply = "未检索到相关资料。";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("检索到 ").append(items.size()).append(" 条资料：\n");
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                String text = String.valueOf(item.getOrDefault("text", ""));
                Map<String, Object> meta = (Map<String, Object>) item.getOrDefault("metadata", new LinkedHashMap<>());
                String source = String.valueOf(meta.getOrDefault("file_name", "unknown"));
                sb.append(i + 1).append(". 来源 ").append(source).append("\n");
                sb.append(shorten(text, 140)).append("\n");
            }
            reply = sb.toString().trim();
        }

        return Map.of("reply", reply, "docs", items);
    }

    private Set<String> listChunkIdsForKb(String kbName) {
        Set<String> out = new LinkedHashSet<>();
        for (String fileName : listFileNames(kbName)) {
            Set<String> ids = stringRedisTemplate.opsForSet().members(fileChunkKey(kbName, fileName));
            if (ids != null) out.addAll(ids);
        }
        return out;
    }

    private List<Document> keywordFallbackDocs(String kbName, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Document> out = new ArrayList<>();
        for (Map<String, Object> fileMeta : listFiles(kbName)) {
            try {
                String text = stringRedisTemplate.opsForValue().get(fileTextKey(
                        kbName,
                        String.valueOf(fileMeta.getOrDefault("file_name", ""))
                ));
                if (text == null || text.isBlank()) continue;
                if (!text.toLowerCase(Locale.ROOT).contains(q)) continue;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("kb_name", kbName);
                meta.put("file_name", String.valueOf(fileMeta.getOrDefault("file_name", "unknown")));
                String id = UUID.randomUUID().toString();
                out.add(new Document(id, shorten(text, 1800), meta));
                if (out.size() >= limit) break;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private List<String> upsertToVector(String kbName, String fileName, String content) {
        if (vectorStore.isEmpty()) return List.of();

        List<String> chunks = semanticSplit(content);
        List<Document> docs = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kb_name", kbName);
            meta.put("file_name", fileName);
            meta.put("chunk_index", i);
            docs.add(new Document(id, chunks.get(i), meta));
        }

        if (!docs.isEmpty()) {
            vectorStore.get().add(docs);
        }
        return ids;
    }

    private String extractText(byte[] rawBytes) {
        try {
            return tika.parseToString(new ByteArrayInputStream(rawBytes));
        } catch (Exception ignored) {
            // 兜底：按 UTF-8 文本读取
            try {
                return new String(rawBytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
    }

    private List<String> listFileNames(String kbName) {
        Set<Object> keys = stringRedisTemplate.opsForHash().keys(filesHashKey(kbName));
        if (keys == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object k : keys) {
            out.add(String.valueOf(k));
        }
        return out;
    }

    // 语义分片：优先按段落与句子切分，再按目标长度组装，避免生硬固定字数截断。
    private List<String> semanticSplit(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;

        final int targetSize = 700;
        final int maxSize = 1000;
        final int minSize = 180;

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\\n\\s*\\n+");

        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            String p = para == null ? "" : para.trim();
            if (p.isBlank()) continue;

            if (p.length() > maxSize) {
                List<String> sentenceBlocks = splitLargeParagraphBySentence(p, maxSize);
                for (String block : sentenceBlocks) {
                    appendChunk(out, current, block, targetSize, maxSize);
                }
                continue;
            }
            appendChunk(out, current, p, targetSize, maxSize);
        }

        flushChunk(out, current);

        List<String> merged = new ArrayList<>();
        for (String item : out) {
            String t = item.trim();
            if (t.isBlank()) continue;
            if (!merged.isEmpty() && t.length() < minSize) {
                int lastIdx = merged.size() - 1;
                merged.set(lastIdx, merged.get(lastIdx) + "\n" + t);
            } else {
                merged.add(t);
            }
        }
        return merged;
    }

    private void appendChunk(List<String> out, StringBuilder current, String block, int targetSize, int maxSize) {
        if (current.length() == 0) {
            current.append(block);
            return;
        }
        if (current.length() + 1 + block.length() <= targetSize) {
            current.append('\n').append(block);
            return;
        }
        flushChunk(out, current);
        if (block.length() <= maxSize) {
            current.append(block);
            return;
        }

        List<String> subBlocks = splitLargeParagraphBySentence(block, maxSize);
        for (String sub : subBlocks) {
            if (current.length() == 0) current.append(sub);
            else if (current.length() + 1 + sub.length() <= targetSize) current.append('\n').append(sub);
            else {
                flushChunk(out, current);
                current.append(sub);
            }
        }
    }

    private void flushChunk(List<String> out, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isBlank()) out.add(text);
        current.setLength(0);
    }

    private List<String> splitLargeParagraphBySentence(String text, int maxSize) {
        List<String> sentenceList = new ArrayList<>();
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String l = line.trim();
            if (l.isBlank()) continue;
            String[] segments = l.split("(?<=[。！？；;.!?])");
            for (String seg : segments) {
                String s = seg.trim();
                if (!s.isBlank()) sentenceList.add(s);
            }
        }

        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String s : sentenceList) {
            if (s.length() > maxSize) {
                if (buf.length() > 0) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
                int start = 0;
                while (start < s.length()) {
                    int end = Math.min(start + maxSize, s.length());
                    out.add(s.substring(start, end));
                    start = end;
                }
                continue;
            }
            if (buf.length() == 0) {
                buf.append(s);
            } else if (buf.length() + 1 + s.length() <= maxSize) {
                buf.append(' ').append(s);
            } else {
                out.add(buf.toString().trim());
                buf.setLength(0);
                buf.append(s);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        return out;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String filesHashKey(String kbName) {
        return "rag:kb:" + kbName + ":files";
    }

    private String fileChunkKey(String kbName, String fileName) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
        return "rag:kb:" + kbName + ":file:" + encoded + ":chunks";
    }

    private String fileTextKey(String kbName, String fileName) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
        return "rag:kb:" + kbName + ":file:" + encoded + ":text";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private String shorten(String text, int maxLen) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        if (t.length() <= maxLen) return t;
        return t.substring(0, maxLen) + "...";
    }
}
