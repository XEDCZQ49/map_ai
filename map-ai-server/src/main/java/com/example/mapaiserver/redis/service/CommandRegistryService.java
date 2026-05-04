package com.example.mapaiserver.redis.service;

import com.example.mapaiserver.llm.dto.response.CommandActionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
/**
 * 地图方案仓库：
 * 1) 为每次新会话分配临时 plan_id
 * 2) 在临时 plan 下为实体/绘制命令分配不重复 id
 * 3) 用户确认保存后再把命令按 plan_id 分组写入 Redis
 * 4) 未保存直接退出时释放临时 plan
 */
public class CommandRegistryService {

    private static final String PLAN_PREFIX = "plan";
    private static final String ENTITY_PREFIX = "entity";
    private static final String DRAW_PREFIX = "draw";

    private static final String PLAN_SEQ_KEY = "map:plan:seq";
    private static final String PLAN_ACTIVE_IDS_KEY = "map:plan:active:ids";
    private static final String PLAN_SAVED_IDS_KEY = "map:plan:saved:ids";

    private static final DefaultRedisScript<Long> NEXT_PLAN_ID_SCRIPT = new DefaultRedisScript<>(
            """
                    local current = 0
                    local function scan_ids(key, prefix)
                        local ids = redis.call('SMEMBERS', key)
                        for _, id in ipairs(ids) do
                            local num = string.match(id, '^' .. prefix .. '%-(%d+)$')
                            if num then
                                local value = tonumber(num)
                                if value and value > current then
                                    current = value
                                end
                            end
                        end
                    end
                    scan_ids(KEYS[2], ARGV[1])
                    scan_ids(KEYS[3], ARGV[1])
                    local next = current + 1
                    redis.call('SET', KEYS[1], next)
                    return next
                    """,
            Long.class
    );

    private static final DefaultRedisScript<Long> NEXT_OBJECT_ID_SCRIPT = new DefaultRedisScript<>(
            """
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    current = current + 1
                    redis.call('SET', KEYS[1], current)
                    return current
                    """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public CommandRegistryService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void cleanupUnsavedPlansOnStartup() {
        Set<String> activeIds = stringRedisTemplate.opsForSet().members(PLAN_ACTIVE_IDS_KEY);
        if (activeIds == null || activeIds.isEmpty()) {
            return;
        }
        for (String planId : activeIds) {
            if (planId == null || planId.isBlank()) continue;
            clearPlanKeysByPattern(planId.trim());
        }
        stringRedisTemplate.delete(PLAN_ACTIVE_IDS_KEY);
    }

    public String allocatePlanId() {
        Long next = stringRedisTemplate.execute(
                NEXT_PLAN_ID_SCRIPT,
                List.of(PLAN_SEQ_KEY, PLAN_SAVED_IDS_KEY, PLAN_ACTIVE_IDS_KEY),
                PLAN_PREFIX
        );
        if (next == null || next <= 0) {
            throw new IllegalStateException("无法分配新的 plan_id");
        }
        String planId = PLAN_PREFIX + "-" + next;
        markPlanActive(planId);
        return planId;
    }

    public String ensureWorkingPlan(String planId) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank()) {
            return allocatePlanId();
        }
        if (isPlanSaved(normalized) || isPlanActive(normalized)) {
            return normalized;
        }
        markPlanActive(normalized);
        return normalized;
    }

    public List<CommandActionDto> assignIds(String planId, List<CommandActionDto> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        String workingPlanId = ensureWorkingPlan(planId);
        List<CommandActionDto> out = new ArrayList<>(commands.size());
        for (CommandActionDto command : commands) {
            CommandBucket bucket = CommandBucket.of(command == null ? null : command.functionName());
            if (!shouldAssignId(command, bucket)) {
                out.add(command);
                continue;
            }
            String id = nextObjectId(workingPlanId, bucket);
            out.add(withId(command, id));
        }
        return out;
    }

    public Map<String, Object> savePlan(String planId, String planName, List<CommandActionDto> commands) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("plan_id 不能为空");
        }

        List<CommandActionDto> safeCommands = commands == null ? List.of() : commands;
        clearSavedPlanCommands(normalized);

        List<CommandActionDto> entityCommands = new ArrayList<>();
        List<CommandActionDto> drawCommands = new ArrayList<>();
        for (CommandActionDto command : safeCommands) {
            CommandBucket bucket = CommandBucket.of(command == null ? null : command.functionName());
            if (bucket == CommandBucket.ENTITY) {
                entityCommands.add(command);
            } else if (bucket == CommandBucket.DRAW) {
                drawCommands.add(command);
            }
        }

        try {
            if (!entityCommands.isEmpty()) {
                persistBucket(normalized, CommandBucket.ENTITY, entityCommands);
            }
            if (!drawCommands.isEmpty()) {
                persistBucket(normalized, CommandBucket.DRAW, drawCommands);
            }
            stringRedisTemplate.opsForValue().set(planCommandsKey(normalized), objectMapper.writeValueAsString(safeCommands));
            stringRedisTemplate.opsForValue().set(planMetaKey(normalized), objectMapper.writeValueAsString(Map.of(
                    "plan_id", normalized,
                    "plan_name", normalizePlanName(planName, normalized),
                    "status", "saved",
                    "saved_at", Instant.now().toString(),
                    "command_count", safeCommands.size()
            )));
        } catch (Exception e) {
            throw new IllegalStateException("保存地图方案失败", e);
        }

        stringRedisTemplate.opsForSet().remove(PLAN_ACTIVE_IDS_KEY, normalized);
        stringRedisTemplate.opsForSet().add(PLAN_SAVED_IDS_KEY, normalized);
        return Map.of(
                "plan_id", normalized,
                "plan_name", normalizePlanName(planName, normalized),
                "saved", true,
                "command_count", safeCommands.size()
        );
    }

    public boolean releasePlan(String planId) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank() || isPlanSaved(normalized)) {
            return false;
        }
        Long removed = stringRedisTemplate.opsForSet().remove(PLAN_ACTIVE_IDS_KEY, normalized);
        stringRedisTemplate.delete(planMetaKey(normalized));
        stringRedisTemplate.delete(planObjectSeqKey(normalized, CommandBucket.ENTITY));
        stringRedisTemplate.delete(planObjectSeqKey(normalized, CommandBucket.DRAW));
        clearSavedPlanCommands(normalized);
        return removed != null && removed > 0;
    }

    public boolean deletePlan(String planId) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank()) {
            return false;
        }
        Long removedSaved = stringRedisTemplate.opsForSet().remove(PLAN_SAVED_IDS_KEY, normalized);
        Long removedActive = stringRedisTemplate.opsForSet().remove(PLAN_ACTIVE_IDS_KEY, normalized);
        // 硬删除：兼容历史/未来 key 变化，统一清理该 plan 下所有缓存。
        Long removedPlanKeys = clearPlanKeysByPattern(normalized);

        return (removedSaved != null && removedSaved > 0)
                || (removedActive != null && removedActive > 0)
                || (removedPlanKeys != null && removedPlanKeys > 0);
    }

    public List<CommandActionDto> loadPlanCommands(String planId) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank()) {
            return List.of();
        }
        String json = stringRedisTemplate.opsForValue().get(planCommandsKey(normalized));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CommandActionDto>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取地图方案失败", e);
        }
    }

    public boolean isPlanSaved(String planId) {
        String normalized = normalizePlanId(planId);
        return !normalized.isBlank() && Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(PLAN_SAVED_IDS_KEY, normalized));
    }

    public Map<String, Object> loadPlanDetail(String planId) {
        String normalized = normalizePlanId(planId);
        if (normalized.isBlank()) {
            return Map.of(
                    "plan_id", "",
                    "plan_name", "",
                    "status", "unknown",
                    "saved", false,
                    "commands", List.of()
            );
        }
        Map<String, Object> meta = readPlanMeta(normalized);
        String status = String.valueOf(meta.getOrDefault("status", isPlanSaved(normalized) ? "saved" : "active"));
        return Map.of(
                "plan_id", normalized,
                "plan_name", String.valueOf(meta.getOrDefault("plan_name", normalized)),
                "status", status,
                "saved", "saved".equals(status),
                "commands", loadPlanCommands(normalized)
        );
    }

    public List<Map<String, Object>> listPlans() {
        Set<String> savedIds = stringRedisTemplate.opsForSet().members(PLAN_SAVED_IDS_KEY);
        Set<String> activeIds = stringRedisTemplate.opsForSet().members(PLAN_ACTIVE_IDS_KEY);
        List<String> ids = new ArrayList<>();
        if (savedIds != null) ids.addAll(savedIds);
        if (activeIds != null) {
            for (String id : activeIds) {
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }

        ids.sort(Comparator.comparingInt(this::planOrder));
        List<Map<String, Object>> plans = new ArrayList<>();
        for (String planId : ids) {
            Map<String, Object> meta = readPlanMeta(planId);
            String status = String.valueOf(meta.getOrDefault("status", isPlanSaved(planId) ? "saved" : "active"));
            plans.add(Map.of(
                    "plan_id", planId,
                    "plan_name", String.valueOf(meta.getOrDefault("plan_name", planId)),
                    "status", status,
                    "saved", "saved".equals(status)
            ));
        }
        return plans;
    }

    private boolean isPlanActive(String planId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(PLAN_ACTIVE_IDS_KEY, planId));
    }

    private void markPlanActive(String planId) {
        stringRedisTemplate.opsForSet().add(PLAN_ACTIVE_IDS_KEY, planId);
        try {
            stringRedisTemplate.opsForValue().set(planMetaKey(planId), objectMapper.writeValueAsString(Map.of(
                    "plan_id", planId,
                    "status", "active",
                    "created_at", Instant.now().toString()
            )));
        } catch (Exception e) {
            throw new IllegalStateException("初始化临时 plan 失败", e);
        }
    }

    private Map<String, Object> readPlanMeta(String planId) {
        String json = stringRedisTemplate.opsForValue().get(planMetaKey(planId));
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取方案元信息失败", e);
        }
    }

    private boolean shouldAssignId(CommandActionDto command, CommandBucket bucket) {
        if (command == null || bucket == CommandBucket.OTHER) {
            return false;
        }
        boolean success = "success".equals(command.message());
        boolean noMissing = command.missing() == null || command.missing().isEmpty();
        return success && noMissing;
    }

    private String nextObjectId(String planId, CommandBucket bucket) {
        Long next = stringRedisTemplate.execute(
                NEXT_OBJECT_ID_SCRIPT,
                List.of(planObjectSeqKey(planId, bucket))
        );
        if (next == null || next <= 0) {
            throw new IllegalStateException("无法生成新的对象 id");
        }
        return bucket.prefix + "-" + next;
    }

    private CommandActionDto withId(CommandActionDto command, String id) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (command.arguments() != null) {
            arguments.putAll(command.arguments());
        }
        arguments.put("id", id);
        return new CommandActionDto(
                command.message(),
                command.functionName(),
                command.color(),
                arguments,
                command.missing()
        );
    }

    private void persistBucket(String planId, CommandBucket bucket, List<CommandActionDto> commands) throws Exception {
        List<String> ids = new ArrayList<>();
        for (CommandActionDto command : commands) {
            String id = String.valueOf((command.arguments() == null ? "" : command.arguments().getOrDefault("id", ""))).trim();
            if (id.isBlank()) continue;
            ids.add(id);
            stringRedisTemplate.opsForHash().put(planStoreKey(planId, bucket), id, objectMapper.writeValueAsString(command));
        }
        if (!ids.isEmpty()) {
            stringRedisTemplate.opsForSet().add(planIdsKey(planId, bucket), ids.toArray(new String[0]));
        }
    }

    private void clearSavedPlanCommands(String planId) {
        stringRedisTemplate.delete(planCommandsKey(planId));
        stringRedisTemplate.delete(planIdsKey(planId, CommandBucket.ENTITY));
        stringRedisTemplate.delete(planStoreKey(planId, CommandBucket.ENTITY));
        stringRedisTemplate.delete(planIdsKey(planId, CommandBucket.DRAW));
        stringRedisTemplate.delete(planStoreKey(planId, CommandBucket.DRAW));
    }

    private String normalizePlanId(String planId) {
        return planId == null ? "" : planId.trim();
    }

    private String normalizePlanName(String planName, String planId) {
        String normalized = planName == null ? "" : planName.trim();
        return normalized.isBlank() ? planId : normalized;
    }

    private int planOrder(String planId) {
        if (planId == null) {
            return Integer.MAX_VALUE;
        }
        String value = planId.trim();
        if (!value.startsWith(PLAN_PREFIX + "-")) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value.substring((PLAN_PREFIX + "-").length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String planMetaKey(String planId) {
        return "map:plan:" + planId + ":meta";
    }

    private String planCommandsKey(String planId) {
        return "map:plan:" + planId + ":commands";
    }

    private String planObjectSeqKey(String planId, CommandBucket bucket) {
        return "map:plan:" + planId + ":" + bucket.prefix + ":seq";
    }

    private String planIdsKey(String planId, CommandBucket bucket) {
        return "map:plan:" + planId + ":" + bucket.prefix + ":ids";
    }

    private String planStoreKey(String planId, CommandBucket bucket) {
        return "map:plan:" + planId + ":" + bucket.prefix + ":store";
    }

    private Long clearPlanKeysByPattern(String planId) {
        Set<String> keys = stringRedisTemplate.keys("map:plan:" + planId + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        return stringRedisTemplate.delete(keys);
    }

    private enum CommandBucket {
        ENTITY(ENTITY_PREFIX),
        DRAW(DRAW_PREFIX),
        OTHER("object");

        private final String prefix;

        CommandBucket(String prefix) {
            this.prefix = prefix;
        }

        private static CommandBucket of(String functionName) {
            if (functionName == null) {
                return OTHER;
            }
            return switch (functionName) {
                case "add_tank", "add_aircraft", "add_by", "add_car" -> ENTITY;
                case "draw_attack", "draw_attack_route", "draw_encirclement_attack",
                        "draw_group", "draw_defense", "draw_boundary",
                        "delete_object", "delete_entity", "delete_draw", "delete_graphic" -> DRAW;
                default -> OTHER;
            };
        }
    }
}
