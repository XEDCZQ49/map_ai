package com.example.mapaiserver.llm.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
/**
 * Prompt 存储服务：
 * 管理 Redis 中 now/def 提示词，支持初始化与迁移。
 */
public class PromptStoreService {

    // now 表示运行时当前生效版本，def 表示默认基线版本。
    private static final String NOW_PREFIX = "prompt:map_annotation:now:";
    private static final String DEF_PREFIX = "prompt:map_annotation:def:";

    // 环境分析链路节点。
    private static final String NODE_ENV_GEO_JUDGE = "ENV_GEO_JUDGE";
    private static final String NODE_ENV_CHAT_GUIDE = "ENV_CHAT_GUIDE";
    private static final String NODE_ENV_BBOX_CITY = "ENV_BBOX_CITY";
    private static final String NODE_ENV_SUMMARY = "ENV_SUMMARY";
    // 指令识别链路节点。
    private static final String NODE_DRW_COMMAND_JUDGE = "DRW_COMMAND_JUDGE";
    private static final String NODE_DRW_COMMAND_RECOGNITION = "DRW_COMMAND_RECOGNITION";
    private static final String NODE_DRW_CHAT_GUIDE = "DRW_CHAT_GUIDE";
    private static final String NODE_DRW_SPLIT_COMMAND = "DRW_SPLIT_COMMAND";
    // RAG 问答基础节点；具体知识库会扩展成 RAG_CHAT_<KB_NAME>。
    private static final String NODE_RAG_CHAT = "RAG_CHAT";
    private static final String BUILTIN_PROMPTS_RESOURCE = "prompts/builtin-defaults.yml";

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, String> builtinDefaults;

    public PromptStoreService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.builtinDefaults = loadBuiltinDefaults();
    }

    @PostConstruct
    public void initDefaultsIfAbsent() {
        // 首次启动时补齐 Redis 中缺失的默认提示词，
        // 并保证 now 至少能从 def 或内置模板中回填出来。
        for (Map.Entry<String, String> e : builtinDefaults.entrySet()) {
            String node = e.getKey();
            String value = e.getValue();

            if (isBlank(stringRedisTemplate.opsForValue().get(defKey(node)))) {
                stringRedisTemplate.opsForValue().set(defKey(node), value);
            }
            if (isBlank(stringRedisTemplate.opsForValue().get(nowKey(node)))) {
                String fromDef = stringRedisTemplate.opsForValue().get(defKey(node));
                stringRedisTemplate.opsForValue().set(nowKey(node), isBlank(fromDef) ? value : fromDef);
            }
        }
    }

    public List<String> nodes() {
        Set<String> all = new HashSet<>();
        Set<String> nowKeys = stringRedisTemplate.keys(NOW_PREFIX + "*");
        Set<String> defKeys = stringRedisTemplate.keys(DEF_PREFIX + "*");
        if (nowKeys != null) {
            for (String key : nowKeys) {
                all.add(key.substring(NOW_PREFIX.length()));
            }
        }
        if (defKeys != null) {
            for (String key : defKeys) {
                all.add(key.substring(DEF_PREFIX.length()));
            }
        }
        List<String> out = new ArrayList<>(all);
        out.sort(String::compareTo);
        return out;
    }

    public String getNow(String node) {
        // 优先读取运行时版本；缺失时按 def -> builtin 逐级回退，并把结果写回 now。
        String val = stringRedisTemplate.opsForValue().get(nowKey(node));
        if (!isBlank(val)) return val;

        String fallback = stringRedisTemplate.opsForValue().get(defKey(node));
        if (!isBlank(fallback)) {
            stringRedisTemplate.opsForValue().set(nowKey(node), fallback);
            return fallback;
        }

        String builtin = builtinDefaults.getOrDefault(node, "");
        if (!isBlank(builtin)) {
            stringRedisTemplate.opsForValue().set(defKey(node), builtin);
            stringRedisTemplate.opsForValue().set(nowKey(node), builtin);
        }
        return builtin;
    }

    public String getDef(String node) {
        // def 是“可恢复的默认版本”，缺失时仅从内置模板补 def，不覆盖 now。
        String val = stringRedisTemplate.opsForValue().get(defKey(node));
        if (!isBlank(val)) return val;

        String builtin = builtinDefaults.getOrDefault(node, "");
        if (!isBlank(builtin)) {
            stringRedisTemplate.opsForValue().set(defKey(node), builtin);
        }
        return builtin;
    }

    public void saveNow(String node, String prompt) {
        stringRedisTemplate.opsForValue().set(nowKey(node), prompt == null ? "" : prompt);
    }

    public void saveDef(String node, String prompt) {
        stringRedisTemplate.opsForValue().set(defKey(node), prompt == null ? "" : prompt);
    }

    public String ensureRagChatPromptForKb(String kbName) {
        // 每个知识库有自己的 prompt 节点，首次访问时从通用 RAG_CHAT 复制一份。
        String node = ragChatNodeOfKb(kbName);
        String now = stringRedisTemplate.opsForValue().get(nowKey(node));
        if (!isBlank(now)) {
            return node;
        }
        String base = getNow(NODE_RAG_CHAT);
        stringRedisTemplate.opsForValue().set(nowKey(node), base);
        if (isBlank(stringRedisTemplate.opsForValue().get(defKey(node)))) {
            stringRedisTemplate.opsForValue().set(defKey(node), base);
        }
        return node;
    }

    public void deleteRagChatPromptForKb(String kbName) {
        String node = ragChatNodeOfKb(kbName);
        stringRedisTemplate.delete(nowKey(node));
        stringRedisTemplate.delete(defKey(node));
    }

    private String nowKey(String node) {
        return NOW_PREFIX + node;
    }

    private String defKey(String node) {
        return DEF_PREFIX + node;
    }

    private String ragChatNodeOfKb(String kbName) {
        String name = kbName == null ? "" : kbName.trim();
        if (name.isBlank()) {
            name = "DEFAULT";
        }
        // Redis 节点名统一转成稳定的大写下划线格式，减少知识库名差异带来的重复 key。
        name = name.replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
        return NODE_RAG_CHAT + "_" + name;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadBuiltinDefaults() {
        // 内置 prompt 从 resources 加载，便于后续单独维护和热替换 Redis 基线。
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource(BUILTIN_PROMPTS_RESOURCE));
        Map<String, Object> loaded = factory.getObject();
        if (loaded == null || loaded.isEmpty()) {
            throw new IllegalStateException("加载内置提示词资源失败: " + BUILTIN_PROMPTS_RESOURCE);
        }
        Map<String, String> prompts = new LinkedHashMap<>();
        loaded.forEach((key, value) -> prompts.put(key, value == null ? "" : String.valueOf(value)));
        return prompts;
    }
}
