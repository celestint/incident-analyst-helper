package com.lyl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.mapper.ToolIdempotencyMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测指标统计服务（只读），口径见 docs/plan/EVALUATION_PLAN.md。
 * - 完成率 / 端到端耗时 P50/P95：incident 表
 * - 工具调用次数分布：tool_idempotency（即执行记录，天然去重）
 * - 失控率硬信号：迭代触顶（error_message）+ 思考循环（重复 agent_thought）
 * - FAILED 归因：error_message 关键词分类；工具成功率：tool_call_result 事件 payload
 * - 采纳率：analysis_report.adopted / adopt_issues 聚合
 * 范围过滤：默认只算正式数据（tag 为空）；tag 参数单看某标签；scope=all 全量。
 * 历史污染数据（限流模型测试期）不删除，打标后从正式口径排除。
 */
@Slf4j
@Service
public class EvaluationStatsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 失控率思考循环判定：同一 incident 内相同思考出现的最小次数 */
    private static final int THOUGHT_LOOP_MIN_REPEATS = 2;

    /** 工具调用次数分布分桶上界（bucket：1-3 / 4-6 / 7-9 / 10+） */
    private static final int[] BUCKET_BOUNDS = {3, 6, 9};

    /** 下钻明细默认条数（slow 指标用） */
    private static final int DEFAULT_SLOW_LIMIT = 5;

    private final IncidentMapper incidentMapper;
    private final IncidentEventMapper incidentEventMapper;
    private final ToolIdempotencyMapper toolIdempotencyMapper;
    private final ReportMapper reportMapper;
    private final AlertMapper alertMapper;
    private final ObjectMapper objectMapper;

    public EvaluationStatsService(IncidentMapper incidentMapper,
                                  IncidentEventMapper incidentEventMapper,
                                  ToolIdempotencyMapper toolIdempotencyMapper,
                                  ReportMapper reportMapper,
                                  AlertMapper alertMapper,
                                  ObjectMapper objectMapper) {
        this.incidentMapper = incidentMapper;
        this.incidentEventMapper = incidentEventMapper;
        this.toolIdempotencyMapper = toolIdempotencyMapper;
        this.reportMapper = reportMapper;
        this.alertMapper = alertMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 汇总评测指标。范围过滤（scope）：默认只统计无标签正式数据；tag 非空单看该标签；scope=all 全量。
     * since 用于按创建时间进一步收窄（yyyy-MM-dd 或完整时间）。
     */
    public Map<String, Object> evaluation(String since, String tag, String scope) {
        ScopeFilter filter = resolveScope(tag, scope);
        String sinceStart = resolveSince(since);
        List<Incident> incidents = filterIncidents(sinceStart, filter, loadAlertsById());
        Set<Long> inRange = new HashSet<>();
        incidents.forEach(i -> inRange.add(i.getId()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scopeDescription(sinceStart, tag, scope, incidents.size()));
        result.put("completion", completion(incidents));
        result.put("durationSeconds", durationSeconds(incidents));
        result.put("toolCalls", toolCalls(incidents.size(), inRange));
        result.put("runaway", runaway(incidents, inRange));
        result.put("failedReasons", failedReasons(incidents));
        result.put("toolSuccess", toolSuccess(inRange));
        result.put("adoption", adoption(inRange));
        return result;
    }

    /**
     * 按指标下钻明细：metric = failed / slow / toolCalls / runaway / dislike。
     * toolCalls 需带 bucket（1-3 / 4-6 / 7-9 / 10+）；slow 可带 limit（默认 5，取耗时最长）。
     */
    public List<Map<String, Object>> detailIncidents(String metric, String bucket, String since,
                                                     String tag, String scope, Integer limit) {
        if (metric == null || metric.isBlank()) {
            throw new ValidationException("metric 不能为空：failed/slow/toolCalls/runaway/dislike");
        }
        ScopeFilter filter = resolveScope(tag, scope);
        String sinceStart = resolveSince(since);
        List<Incident> incidents = filterIncidents(sinceStart, filter, loadAlertsById());
        Set<Long> inRange = new HashSet<>();
        incidents.forEach(i -> inRange.add(i.getId()));
        AdoptionData adoptionData = AdoptionData.load(reportMapper, inRange);

        return switch (metric) {
            case "failed" -> failedRows(incidents, adoptionData);
            case "slow" -> slowRows(incidents, adoptionData, limit);
            case "toolCalls" -> toolCallBucketRows(incidents, adoptionData, bucket);
            case "runaway" -> runawayRows(incidents, inRange, adoptionData);
            case "dislike" -> dislikeRows(incidents, adoptionData);
            default -> throw new ValidationException("未知 metric: " + metric + "（可选 failed/slow/toolCalls/runaway/dislike）");
        };
    }

    // ---- 范围过滤（布尔标只挂 alert，按 incident.alert_id 关联）----

    private enum ScopeMode { ALL, PROD, EVAL, TEST }

    private record ScopeFilter(ScopeMode mode) {
        boolean test(Alert alert) {
            if (alert == null) {
                return false;
            }
            return switch (mode) {
                case ALL -> true;
                case PROD -> Boolean.TRUE.equals(alert.getIsProd());
                case EVAL -> Boolean.TRUE.equals(alert.getIsEval());
                case TEST -> Boolean.TRUE.equals(alert.getIsTest());
            };
        }
    }

    /**
     * scope 解析：默认正式（is_prod=1）；tag=eval/test 单看对应桶；scope=all 全量（与 tag 互斥）
     */
    private ScopeFilter resolveScope(String tag, String scope) {
        boolean all = scope != null && "all".equalsIgnoreCase(scope);
        boolean hasTag = tag != null && !tag.isBlank();
        if (all && hasTag) {
            throw new ValidationException("scope=all 与 tag 参数互斥");
        }
        if (all) {
            return new ScopeFilter(ScopeMode.ALL);
        }
        if (!hasTag) {
            return new ScopeFilter(ScopeMode.PROD);
        }
        return switch (tag.trim().toLowerCase()) {
            case "eval" -> new ScopeFilter(ScopeMode.EVAL);
            case "test" -> new ScopeFilter(ScopeMode.TEST);
            default -> throw new ValidationException("未知 tag: " + tag + "（可选 eval/test）");
        };
    }

    private List<Incident> filterIncidents(String sinceStart, ScopeFilter filter, Map<Long, Alert> alertsById) {
        return incidentMapper.selectAll().stream()
                .filter(i -> filter.test(alertsById.get(i.getAlertId())))
                .filter(i -> sinceStart == null || i.getCreatedAt().compareTo(sinceStart) >= 0)
                .toList();
    }

    private Map<Long, Alert> loadAlertsById() {
        Map<Long, Alert> alertsById = new LinkedHashMap<>();
        for (Alert alert : alertMapper.selectAll()) {
            alertsById.put(alert.getId(), alert);
        }
        return alertsById;
    }

    private Map<String, Object> scopeDescription(String sinceStart, String tag, String scope, int totalIncidents) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("since", sinceStart);
        desc.put("bucket", tag == null || tag.isBlank() ? "prod" : tag.trim().toLowerCase());
        desc.put("all", scope != null && "all".equalsIgnoreCase(scope));
        desc.put("totalIncidents", totalIncidents);
        return desc;
    }

    // ---- 汇总指标 ----

    private Map<String, Object> completion(List<Incident> incidents) {
        long completed = countByStatus(incidents, "COMPLETED");
        long failed = countByStatus(incidents, "FAILED");
        long running = countByStatus(incidents, "RUNNING");
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("total", incidents.size());
        completion.put("completed", completed);
        completion.put("failed", failed);
        completion.put("running", running);
        completion.put("completionRate", rate(completed, incidents.size()));
        return completion;
    }

    private long countByStatus(List<Incident> incidents, String status) {
        return incidents.stream().filter(i -> status.equals(i.getStatus())).count();
    }

    /**
     * 端到端耗时（秒）：COMPLETED 且 completed_at 非空，P50/P95 按最近邻秩法取分位数
     */
    private Map<String, Object> durationSeconds(List<Incident> incidents) {
        List<Long> durations = incidents.stream()
                .filter(i -> "COMPLETED".equals(i.getStatus()) && i.getCompletedAt() != null)
                .map(this::durationOf)
                .filter(d -> d != null)
                .sorted()
                .toList();
        Map<String, Object> duration = new LinkedHashMap<>();
        duration.put("count", durations.size());
        duration.put("p50", percentile(durations, 0.50));
        duration.put("p95", percentile(durations, 0.95));
        return duration;
    }

    /**
     * 工具调用次数分布：只统计有工具调用的 incident（mock 执行器不产生工具调用，混入会失真），
     * 无调用 incident 单独给 incidentsWithoutTools 供核对
     */
    private Map<String, Object> toolCalls(long totalIncidents, Set<Long> inRange) {
        List<Long> counts = new ArrayList<>();
        long withTools = 0;
        long totalCalls = 0;
        int[] bucketCounts = new int[BUCKET_BOUNDS.length + 1];
        for (Map<String, Object> row : toolIdempotencyMapper.countByIncident()) {
            Long incidentId = longValue(row.get("incidentId"));
            if (incidentId == null || !inRange.contains(incidentId)) {
                continue;
            }
            long cnt = longValue(row.get("cnt"));
            counts.add(cnt);
            withTools++;
            totalCalls += cnt;
            bucketCounts[bucketIndex(cnt)]++;
        }

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int i = 0; i <= BUCKET_BOUNDS.length; i++) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("bucket", bucketLabel(i));
            bucket.put("incidents", bucketCounts[i]);
            distribution.add(bucket);
        }

        Map<String, Object> toolCalls = new LinkedHashMap<>();
        toolCalls.put("incidentsWithTools", withTools);
        toolCalls.put("incidentsWithoutTools", totalIncidents - withTools);
        toolCalls.put("avgCalls", withTools == 0 ? 0.0 : Math.round(totalCalls * 100.0 / withTools) / 100.0);
        toolCalls.put("maxCalls", counts.stream().mapToLong(Long::longValue).max().orElse(0));
        toolCalls.put("distribution", distribution);
        return toolCalls;
    }

    private int bucketIndex(long cnt) {
        for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
            if (cnt <= BUCKET_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDS.length;
    }

    private String bucketLabel(int index) {
        if (index == 0) {
            return "1-" + BUCKET_BOUNDS[0];
        }
        if (index < BUCKET_BOUNDS.length) {
            return (BUCKET_BOUNDS[index - 1] + 1) + "-" + BUCKET_BOUNDS[index];
        }
        return (BUCKET_BOUNDS[BUCKET_BOUNDS.length - 1] + 1) + "+";
    }

    /**
     * 失控率：硬信号（迭代触顶 / 思考循环）任一命中即算失控；不含软信号（连续空结果可能是数据缺失下的合法行为）
     */
    private Map<String, Object> runaway(List<Incident> incidents, Set<Long> inRange) {
        Set<Long> runawayIds = new HashSet<>();
        long iterationExhausted = 0;
        for (Incident incident : incidents) {
            if (isIterationExhausted(incident)) {
                iterationExhausted++;
                runawayIds.add(incident.getId());
            }
        }
        Set<Long> thoughtLoopIds = thoughtLoopIds(inRange);
        runawayIds.addAll(thoughtLoopIds);

        Map<String, Object> runaway = new LinkedHashMap<>();
        runaway.put("iterationExhausted", iterationExhausted);
        runaway.put("thoughtLoop", thoughtLoopIds.size());
        runaway.put("incidents", runawayIds.size());
        runaway.put("rate", rate(runawayIds.size(), incidents.size()));
        return runaway;
    }

    /**
     * FAILED 归因分布：按 error_message 关键词分类，兜底为 other
     */
    private Map<String, Object> failedReasons(List<Incident> incidents) {
        long llmCallFailed = 0;
        long reportParseFailed = 0;
        long iterationExhausted = 0;
        long other = 0;
        for (Incident incident : incidents) {
            if (!"FAILED".equals(incident.getStatus())) {
                continue;
            }
            switch (failedReasonKey(incident)) {
                case "iterationExhausted" -> iterationExhausted++;
                case "reportParseFailed" -> reportParseFailed++;
                case "llmCallFailed" -> llmCallFailed++;
                default -> other++;
            }
        }
        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("llmCallFailed", llmCallFailed);
        reasons.put("reportParseFailed", reportParseFailed);
        reasons.put("iterationExhausted", iterationExhausted);
        reasons.put("other", other);
        return reasons;
    }

    /**
     * 工具成功率：tool_call_result 事件 payload 的 success 字段（Java 侧解析，兼容 payload 非法 JSON）
     */
    private Map<String, Object> toolSuccess(Set<Long> inRange) {
        long total = 0;
        long success = 0;
        for (Map<String, Object> row : incidentEventMapper.selectToolResultRows()) {
            Long incidentId = longValue(row.get("incidentId"));
            if (incidentId == null || !inRange.contains(incidentId)) {
                continue;
            }
            total++;
            if (parsePayload((String) row.get("payload")).path("success").asBoolean(false)) {
                success++;
            }
        }
        Map<String, Object> toolSuccess = new LinkedHashMap<>();
        toolSuccess.put("total", total);
        toolSuccess.put("success", success);
        toolSuccess.put("rate", rate(success, total));
        return toolSuccess;
    }

    /**
     * 采纳率聚合：已标注数 / 赞同数 / 采纳率 / 不赞同原因分布（原因取值 noise/evidence/sop/logic/leak/redundant，顺序与前端原因弹窗一致）
     */
    private Map<String, Object> adoption(Set<Long> inRange) {
        long annotated = 0;
        long adoptedCount = 0;
        long dislikeCount = 0;
        long dislikeNoReason = 0;
        Map<String, Long> issueCounts = new LinkedHashMap<>();
        for (String issue : List.of("noise", "evidence", "sop", "logic", "leak", "redundant")) {
            issueCounts.put(issue, 0L);
        }
        for (Map<String, Object> row : reportMapper.selectAdoptionRows()) {
            Long incidentId = longValue(row.get("incidentId"));
            if (incidentId == null || !inRange.contains(incidentId)) {
                continue;
            }
            Boolean adopted = (Boolean) row.get("adopted");
            if (adopted == null) {
                continue;
            }
            annotated++;
            if (adopted) {
                adoptedCount++;
                continue;
            }
            dislikeCount++;
            List<String> issues = parseIssues((String) row.get("adoptIssues"));
            if (issues.isEmpty()) {
                dislikeNoReason++;
            }
            for (String issue : issues) {
                issueCounts.computeIfPresent(issue, (k, v) -> v + 1);
            }
        }
        Map<String, Object> adoption = new LinkedHashMap<>();
        adoption.put("annotated", annotated);
        adoption.put("adopted", adoptedCount);
        adoption.put("dislike", dislikeCount);
        adoption.put("dislikeNoReason", dislikeNoReason);
        adoption.put("rate", rate(adoptedCount, annotated));
        adoption.put("issueCounts", issueCounts);
        return adoption;
    }

    // ---- 下钻明细 ----

    /**
     * 范围内 incident 的采纳标注（一次加载，避免明细行逐条查库）
     */
    private record AdoptionData(Map<Long, Boolean> states, Map<Long, List<String>> issues) {
        static AdoptionData load(ReportMapper reportMapper, Set<Long> inRange) {
            Map<Long, Boolean> states = new LinkedHashMap<>();
            Map<Long, List<String>> issues = new LinkedHashMap<>();
            for (Map<String, Object> row : reportMapper.selectAdoptionRows()) {
                Long incidentId = row.get("incidentId") instanceof Number n ? n.longValue() : null;
                if (incidentId == null || !inRange.contains(incidentId)) {
                    continue;
                }
                states.put(incidentId, (Boolean) row.get("adopted"));
                issues.put(incidentId, parseIssuesJson((String) row.get("adoptIssues")));
            }
            return new AdoptionData(states, issues);
        }

        private static List<String> parseIssuesJson(String json) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new ObjectMapper().readTree(json == null || json.isBlank() ? "[]" : json);
                List<String> list = new ArrayList<>();
                if (node.isArray()) {
                    node.forEach(item -> list.add(item.asText()));
                }
                return list;
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    private List<Map<String, Object>> failedRows(List<Incident> incidents, AdoptionData adoptionData) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Incident incident : incidents) {
            if (!"FAILED".equals(incident.getStatus())) {
                continue;
            }
            Map<String, Object> row = baseRow(incident, adoptionData);
            row.put("failedReason", failedReasonKey(incident));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("incidentId"), (long) a.get("incidentId")));
        return rows;
    }

    private List<Map<String, Object>> slowRows(List<Incident> incidents, AdoptionData adoptionData, Integer limit) {
        int max = limit == null || limit <= 0 ? DEFAULT_SLOW_LIMIT : Math.min(limit, 50);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Incident incident : incidents) {
            Long duration = durationOf(incident);
            if (duration == null) {
                continue;
            }
            Map<String, Object> row = baseRow(incident, adoptionData);
            row.put("durationSeconds", duration);
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("durationSeconds"), (long) a.get("durationSeconds")));
        return rows.subList(0, Math.min(max, rows.size()));
    }

    private List<Map<String, Object>> toolCallBucketRows(List<Incident> incidents, AdoptionData adoptionData, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new ValidationException("toolCalls 指标需带 bucket 参数（1-3/4-6/7-9/10+）");
        }
        int index = -1;
        for (int i = 0; i <= BUCKET_BOUNDS.length; i++) {
            if (bucketLabel(i).equals(bucket.trim())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ValidationException("未知 bucket: " + bucket + "（可选 1-3/4-6/7-9/10+）");
        }
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : toolIdempotencyMapper.countByIncident()) {
            Long incidentId = longValue(row.get("incidentId"));
            if (incidentId != null) {
                counts.put(incidentId, longValue(row.get("cnt")));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Incident incident : incidents) {
            Long cnt = counts.get(incident.getId());
            if (cnt == null || bucketIndex(cnt) != index) {
                continue;
            }
            Map<String, Object> row = baseRow(incident, adoptionData);
            row.put("toolCallCount", cnt);
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("incidentId"), (long) a.get("incidentId")));
        return rows;
    }

    private List<Map<String, Object>> runawayRows(List<Incident> incidents, Set<Long> inRange, AdoptionData adoptionData) {
        Set<Long> thoughtLoopIds = thoughtLoopIds(inRange);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Incident incident : incidents) {
            boolean exhausted = isIterationExhausted(incident);
            boolean loop = thoughtLoopIds.contains(incident.getId());
            if (!exhausted && !loop) {
                continue;
            }
            Map<String, Object> row = baseRow(incident, adoptionData);
            row.put("runawaySignal", exhausted ? "iterationExhausted" : "thoughtLoop");
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("incidentId"), (long) a.get("incidentId")));
        return rows;
    }

    private List<Map<String, Object>> dislikeRows(List<Incident> incidents, AdoptionData adoptionData) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Incident incident : incidents) {
            if (!Boolean.FALSE.equals(adoptionData.states().get(incident.getId()))) {
                continue;
            }
            Map<String, Object> row = baseRow(incident, adoptionData);
            row.put("adoptIssues", adoptionData.issues().getOrDefault(incident.getId(), List.of()));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("incidentId"), (long) a.get("incidentId")));
        return rows;
    }

    /**
     * 明细行公共字段：告警三要素（与前端告警卡同款）+ 状态 + 耗时 + 工具调用数 + 采纳标注
     */
    private Map<String, Object> baseRow(Incident incident, AdoptionData adoptionData) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("incidentId", incident.getId());
        Alert alert = incident.getAlertId() == null ? null : alertMapper.selectById(incident.getAlertId());
        row.put("alertName", alert == null ? null : alert.getAlertName());
        row.put("service", alert == null ? null : alert.getService());
        row.put("startsAt", alert == null ? null : alert.getStartsAt());
        row.put("status", incident.getStatus());
        row.put("durationSeconds", durationOf(incident));
        row.put("adopted", adoptionData.states().get(incident.getId()));
        row.put("adoptIssues", adoptionData.issues().getOrDefault(incident.getId(), List.of()));
        return row;
    }

    // ---- 失控信号与归因 ----

    private boolean isIterationExhausted(Incident incident) {
        return incident.getErrorMessage() != null && incident.getErrorMessage().contains("达到最大迭代次数");
    }

    private Set<Long> thoughtLoopIds(Set<Long> inRange) {
        Set<Long> ids = new HashSet<>(incidentEventMapper.selectThoughtLoopIncidentIds());
        ids.retainAll(inRange);
        return ids;
    }

    private String failedReasonKey(Incident incident) {
        String message = incident.getErrorMessage() == null ? "" : incident.getErrorMessage();
        if (message.contains("达到最大迭代次数")) {
            return "iterationExhausted";
        }
        if (message.contains("未输出可解析的报告 JSON")) {
            return "reportParseFailed";
        }
        if (message.contains("模型调用失败") || message.contains("LLM")) {
            return "llmCallFailed";
        }
        return "other";
    }

    // ---- 工具方法 ----

    private Long durationOf(Incident incident) {
        if (!"COMPLETED".equals(incident.getStatus())
                || incident.getCreatedAt() == null || incident.getCompletedAt() == null) {
            return null;
        }
        LocalDateTime start = parseTime(incident.getCreatedAt());
        LocalDateTime end = parseTime(incident.getCompletedAt());
        return start == null || end == null ? null : Duration.between(start, end).getSeconds();
    }

    private List<String> parseIssues(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> issues = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(item -> issues.add(item.asText()));
            }
            return issues;
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode parsePayload(String payload) {
        try {
            return payload == null || payload.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(payload);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * since 解析：支持 yyyy-MM-dd（补 00:00:00）与完整 yyyy-MM-dd HH:mm:ss；空返回 null（全量）
     */
    private String resolveSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        String trimmed = since.trim();
        if (trimmed.length() == 10) {
            return trimmed + " 00:00:00";
        }
        if (trimmed.length() == 19 && parseTime(trimmed) != null) {
            return trimmed;
        }
        throw new ValidationException("since 格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
    }

    private LocalDateTime parseTime(String text) {
        try {
            return LocalDateTime.parse(text, FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private Long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 10000.0 / total) / 100.0;
    }

    private Long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
