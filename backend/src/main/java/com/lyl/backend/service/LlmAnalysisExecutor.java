package com.lyl.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lyl.backend.config.AnalysisProperties;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.ReportMapper;
import com.lyl.backend.mapper.ToolIdempotencyMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentEvent;
import com.lyl.backend.model.IncidentStatus;
import com.lyl.backend.model.ToolIdempotency;
import com.lyl.backend.tool.AnalysisToolRegistry;
import com.lyl.backend.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * LLM 分析执行器：手动驱动"调模型 → 工具调用 → 回填结果"循环。
 * - 每轮叙述/思考落库 agent_thought，工具起止落库 tool_call_start/result，证据落库 evidence_collected
 * - 最终回答逐字推送 text_delta（不落库），结尾 ```json 块解析为报告落库
 * - 工具执行基于 tool_idempotency 幂等，崩溃重试复用结果
 * - run 为从头分析；resume 从最近一次失败的点续跑（从事件重建上下文）
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.analysis.executor", havingValue = "llm")
public class LlmAnalysisExecutor implements AnalysisExecutor {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 模型重复发起相同参数调用时附加在工具结果后的提示。
     * 必须写在工具结果消息内：工具结果必须紧跟 assistant 消息，中间不能插入 UserMessage。
     */
    private static final String DUPLICATE_CALL_HINT =
            "\n\n[系统提示] 相同参数的调用已执行过，请基于以上结果继续推理，不要重复调用相同参数的工具。";

    /**
     * 全空结果累计止损阈值：大于兜底手册正常路径预算（3 类数据 × 2 窗口 ≈ 6 次空调用），
     * 守手册的模型不受干扰，只拦失控扩窗循环。只统计成功执行且结果全空的调用，失败不计。
     */
    private static final int EMPTY_RESULT_STOP_LOSS = 8;

    /**
     * 累计全空结果达到止损阈值时附加在工具结果后的一次性提示（写在工具结果消息内，理由同上）。
     */
    private static final String STOP_LOSS_HINT =
            "\n\n[系统提示] 可观测数据持续为空，禁止再调用工具，直接输出最终 JSON 报告。";

    private final LlmChatClient llmChatClient;
    private final AnalysisToolRegistry registry;
    private final IncidentEventService eventService;
    private final IncidentEventMapper incidentEventMapper;
    private final SsePushService ssePushService;
    private final ToolIdempotencyMapper toolIdempotencyMapper;
    private final ReportMapper reportMapper;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties properties;
    private final ToolCallSummarizer summarizer;

    public LlmAnalysisExecutor(LlmChatClient llmChatClient,
                               AnalysisToolRegistry registry,
                               IncidentEventService eventService,
                               IncidentEventMapper incidentEventMapper,
                               SsePushService ssePushService,
                               ToolIdempotencyMapper toolIdempotencyMapper,
                               ReportMapper reportMapper,
                               ObjectMapper objectMapper,
                               AnalysisProperties properties,
                               ToolCallSummarizer summarizer) {
        this.llmChatClient = llmChatClient;
        this.registry = registry;
        this.eventService = eventService;
        this.incidentEventMapper = incidentEventMapper;
        this.ssePushService = ssePushService;
        this.toolIdempotencyMapper = toolIdempotencyMapper;
        this.reportMapper = reportMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.summarizer = summarizer;
    }

    @Override
    public void run(Incident incident, Alert alert) {
        ToolContext ctx = buildContext(alert);

        // 1. 开始分析事件
        ObjectNode received = objectMapper.createObjectNode()
                .put("alertName", alert.getAlertName())
                .put("service", alert.getService())
                .put("severity", alert.getSeverity())
                .put("startsAt", alert.getStartsAt());
        eventService.appendAndPush(ssePushService, incident, "received", "incident_received", received);

        // 2. 消息列表：system + 告警信息
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AnalysisPrompts.SYSTEM_PROMPT));
        messages.add(UserMessage.from(alertInfoText(alert)));

        runLoop(incident, ctx, messages, 0, List.of());
    }

    /**
     * 断点续跑：从 incident_events 最近一次尝试段重建消息上下文，从失败点继续。
     * 工具完整结果从 tool_idempotency 取回；执行中断的工具先补执行（幂等命中复用）。
     * 重建后补写带 resumed 标记的 incident_received 边界事件，让"续跑前失败"与"续跑段"在前端正确分段。
     */
    @Override
    public void resume(Incident incident, Alert alert) {
        ToolContext ctx = buildContext(alert);
        RebuiltContext rebuilt = rebuildContext(incident, alert);
        log.info("Resuming incident {} from step {}, {} pending tool executions",
                incident.getId(), rebuilt.nextStepSeq(), rebuilt.pendingExecutions().size());

        // 续跑边界事件：前端据此把"续跑前失败"折叠隐藏
        ObjectNode received = objectMapper.createObjectNode()
                .put("alertName", alert.getAlertName())
                .put("service", alert.getService())
                .put("severity", alert.getSeverity())
                .put("startsAt", alert.getStartsAt())
                .put("resumed", true);
        eventService.appendAndPush(ssePushService, incident, "received", "incident_received", received);

        runLoop(incident, ctx, rebuilt.messages(), rebuilt.nextStepSeq(), rebuilt.pendingExecutions());
    }

    /**
     * 工具循环主体：run 与 resume 共用。pending 为中断时未拿到结果的工具，先补执行再进循环。
     */
    private void runLoop(Incident incident, ToolContext ctx, List<ChatMessage> messages,
                         int stepSeq, List<PendingTool> pending) {
        // 已成功执行过的调用（tool|args → 结果文本）：模型（尤其是小参数模型）在结果不符合预期时
        // 容易连续输出相同思考+相同工具调用，重复调用直接复用结果，不再执行、不落库、不消耗 stepSeq
        Map<String, String> executedResults = new HashMap<>();
        StopLossState stopLoss = new StopLossState();
        for (PendingTool tool : pending) {
            String cached = executedResults.get(dedupKey(tool.request()));
            if (cached != null) {
                messages.add(ToolExecutionResultMessage.from(
                        tool.request().id(), tool.request().name(), cached + DUPLICATE_CALL_HINT));
                continue;
            }
            String result = executeTool(incident, tool.request(), tool.stepSeq(), ctx, messages, stopLoss);
            if (result != null) {
                executedResults.put(dedupKey(tool.request()), result);
            }
        }

        int jsonRetries = 0;
        for (int round = 0; round < properties.getMaxIterations(); round++) {
            ChatResponse response = streamTurn(incident, messages);
            AiMessage aiMessage = response.aiMessage();
            String text = aiMessage.text() == null ? "" : aiMessage.text();

            // 本轮叙述落库（agent_thought）
            if (!text.isBlank()) {
                ObjectNode thought = objectMapper.createObjectNode().put("thought", text);
                eventService.appendAndPush(ssePushService, incident, "thinking", "agent_thought", thought);
            }

            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            if (toolRequests == null || toolRequests.isEmpty()) {
                // 无工具调用 → 最终回答，尝试解析报告
                AnalysisReport report = parseReport(text);
                if (report == null && jsonRetries == 0) {
                    jsonRetries++;
                    log.warn("Report JSON parse failed for incident {}, retrying", incident.getId());
                    messages.add(aiMessage);
                    messages.add(UserMessage.from(AnalysisPrompts.JSON_RETRY_PROMPT));
                    continue;
                }
                if (report == null) {
                    throw new IllegalStateException("LLM 未输出可解析的报告 JSON");
                }
                finish(incident, report);
                return;
            }

            // 执行本轮全部工具调用并回填
            messages.add(aiMessage);
            for (ToolExecutionRequest request : toolRequests) {
                String cached = executedResults.get(dedupKey(request));
                if (cached != null) {
                    messages.add(ToolExecutionResultMessage.from(
                            request.id(), request.name(), cached + DUPLICATE_CALL_HINT));
                    continue;
                }
                stepSeq++;
                String result = executeTool(incident, request, stepSeq, ctx, messages, stopLoss);
                if (result != null) {
                    executedResults.put(dedupKey(request), result);
                }
            }
        }
        throw new IllegalStateException("达到最大迭代次数 " + properties.getMaxIterations() + "，仍未生成最终报告");
    }

    /**
     * 从事件流重建消息上下文。重放全部事件（跨多次续跑的完整成功链路），
     * 跳过 error 事件（历史失败点）；未拿到结果的工具作为 pending 返回。
     * 旧实现只回放最近一次尝试段，导致续跑后模型丢失此前所有工具结果，
     * 重新调用 getRunbook/重查已查过的数据（用户验收发现的"重复已执行步骤"问题）。
     */
    private RebuiltContext rebuildContext(Incident incident, Alert alert) {
        List<IncidentEvent> events = incidentEventMapper.selectAfter(incident.getId(), 0);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AnalysisPrompts.SYSTEM_PROMPT));
        messages.add(UserMessage.from(alertInfoText(alert)));

        String currentThought = null;
        List<RebuiltTool> roundTools = new ArrayList<>();
        List<RebuiltTool> allTools = new ArrayList<>();
        int maxStepSeq = 0;

        for (IncidentEvent event : events) {
            JsonNode payload = parsePayload(event.getPayload());
            switch (event.getEventType()) {
                case "agent_thought" -> {
                    flushRound(messages, currentThought, roundTools);
                    currentThought = payload.path("thought").asText(null);
                }
                case "tool_call_start" -> {
                    int stepSeq = payload.path("stepSeq").asInt(0);
                    ToolExecutionRequest request = ToolExecutionRequest.builder()
                            .id("resume-" + event.getSeq())
                            .name(payload.path("tool").asText())
                            .arguments(payload.path("args").asText("{}"))
                            .build();
                    RebuiltTool tool = new RebuiltTool(request, stepSeq);
                    roundTools.add(tool);
                    allTools.add(tool);
                    maxStepSeq = Math.max(maxStepSeq, stepSeq);
                }
                case "tool_call_result" -> {
                    boolean success = payload.path("success").asBoolean(false);
                    RebuiltTool match = findUnresolved(allTools, payload);
                    if (match != null) {
                        if (success) {
                            String full = fullResultFromIdempotency(incident.getId(), match.stepSeq(), match.request());
                            match.result = full != null ? full : payload.path("summary").asText("");
                        } else {
                            match.result = "工具执行失败: " + payload.path("error").asText("未知错误");
                        }
                    }
                }
                case "error" -> log.info("Resume: skipping past failure point: {}", payload.path("message").asText(""));
                default -> {
                    // incident_received / evidence_collected / report_finalized 不进模型上下文
                }
            }
        }
        flushRound(messages, currentThought, roundTools);

        // 未拿到结果（有 start 无 result）的工具 → 补执行
        List<PendingTool> pending = new ArrayList<>();
        for (RebuiltTool tool : allTools) {
            if (tool.result == null) {
                pending.add(new PendingTool(tool.request(), tool.stepSeq()));
            }
        }
        return new RebuiltContext(messages, maxStepSeq, pending);
    }

    /**
     * 把当前轮的"思考 + 工具请求 + 结果"按模型对话格式写入消息列表。
     * 没有拿到结果的步骤跳过结果消息，由 runLoop 的补执行路径回填。
     */
    private void flushRound(List<ChatMessage> messages, String thought, List<RebuiltTool> roundTools) {
        if (roundTools.isEmpty()) {
            return;
        }
        messages.add(AiMessage.from(thought == null ? "" : thought,
                roundTools.stream().map(RebuiltTool::request).toList()));
        for (RebuiltTool tool : roundTools) {
            if (tool.result != null) {
                messages.add(ToolExecutionResultMessage.from(tool.request.id(), tool.request.name(), tool.result));
            }
        }
        roundTools.clear();
    }

    /**
     * 为 result 事件匹配未回填结果的 start：优先 stepSeq + 工具名都一致（取最近一条，
     * 兼容多次续跑导致的 stepSeq 跨段重复）；旧数据不带 stepSeq 时退回按工具名匹配
     */
    private RebuiltTool findUnresolved(List<RebuiltTool> allTools, JsonNode payload) {
        int stepSeq = payload.path("stepSeq").asInt(0);
        String tool = payload.path("tool").asText();
        for (int i = allTools.size() - 1; i >= 0; i--) {
            RebuiltTool tool1 = allTools.get(i);
            if (tool1.result != null) {
                continue;
            }
            boolean stepSeqMatched = stepSeq <= 0 || tool1.stepSeq == stepSeq;
            if (stepSeqMatched && tool1.request.name().equals(tool)) {
                return tool1;
            }
        }
        return null;
    }

    /**
     * 重建的单个工具调用：start 事件 + 回填的结果文本（null = 未拿到，需补执行）
     */
    private static class RebuiltTool {
        final ToolExecutionRequest request;
        final int stepSeq;
        String result;

        RebuiltTool(ToolExecutionRequest request, int stepSeq) {
            this.request = request;
            this.stepSeq = stepSeq;
        }

        ToolExecutionRequest request() {
            return request;
        }

        int stepSeq() {
            return stepSeq;
        }
    }

    /**
     * 幂等表中取完整工具结果；未命中返回 null（调用方退回事件摘要）
     */
    private String fullResultFromIdempotency(Long incidentId, int stepSeq, ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        String key = sha256(incidentId + "|" + stepSeq + "|" + request.name() + "|" + request.arguments());
        ToolIdempotency cached = toolIdempotencyMapper.selectByKey(key);
        return cached != null ? cached.getResult() : null;
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
     * 断点续跑的重建结果
     */
    private record RebuiltContext(List<ChatMessage> messages, int nextStepSeq, List<PendingTool> pendingExecutions) {
    }

    /**
     * 执行中断的工具（有 start 无 result）
     */
    private record PendingTool(ToolExecutionRequest request, int stepSeq) {
    }

    /**
     * 流式调用一轮模型：文本增量实时推 text_delta（不落库），结束后返回完整响应。
     * 多模型故障转移由 LlmChatClient 处理（某模型限流/异常自动切下一个）。
     */
    private ChatResponse streamTurn(Incident incident, List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(registry.specifications())
                        .build())
                .build();

        try {
            return llmChatClient.chat(request, token -> {
                ObjectNode delta = objectMapper.createObjectNode();
                delta.put("type", "text_delta");
                delta.putObject("data").put("delta", token);
                try {
                    ssePushService.push(incident.getId(), delta.toString());
                } catch (Exception e) {
                    log.debug("text_delta push failed for incident {}: {}", incident.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("LLM 调用失败, incident {}: {}", incident.getId(), e.getMessage(), e);
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 执行单个工具调用：tool_call_start 落库 → 幂等查询/执行 → tool_call_result 落库 →
     * 证据事件落库 → 结果回填消息列表。成功时返回结果文本（供重复调用检测复用），失败返回 null。
     */
    private String executeTool(Incident incident, ToolExecutionRequest request, int stepSeq,
                               ToolContext ctx, List<ChatMessage> messages, StopLossState stopLoss) {
        String toolName = request.name();
        String argsJson = request.arguments();

        ObjectNode startPayload = objectMapper.createObjectNode()
                .put("tool", toolName)
                .put("args", argsJson == null ? "{}" : argsJson)
                .put("stepSeq", stepSeq);
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_start", startPayload);

        String resultText;
        boolean success = true;
        String error = null;
        try {
            if (!registry.contains(toolName)) {
                throw new IllegalArgumentException("未注册的工具: " + toolName);
            }
            String idempotencyKey = sha256(incident.getId() + "|" + stepSeq + "|" + toolName + "|" + argsJson);
            ToolIdempotency cached = toolIdempotencyMapper.selectByKey(idempotencyKey);
            if (cached != null && cached.getResult() != null) {
                log.info("Tool idempotency hit: incident={}, step={}, tool={}", incident.getId(), stepSeq, toolName);
                resultText = cached.getResult();
            } else {
                Map<String, Object> args = argsJson == null || argsJson.isBlank()
                        ? Map.of()
                        : objectMapper.readValue(argsJson, new TypeReference<>() {});
                resultText = registry.execute(toolName, args, ctx);
                ToolIdempotency record = new ToolIdempotency();
                record.setIdempotencyKey(idempotencyKey);
                record.setIncidentId(incident.getId());
                record.setStepSeq(stepSeq);
                record.setToolName(toolName);
                record.setResult(resultText);
                record.setCreatedAt(LocalDateTime.now().format(FMT));
                try {
                    toolIdempotencyMapper.insert(record);
                } catch (Exception duplicate) {
                    // 并发下同键冲突，忽略：结果仍有效
                    log.warn("Idempotency insert conflict for key {}, keeping result", idempotencyKey);
                }
            }
        } catch (Exception e) {
            success = false;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            resultText = null;
        }

        // 语义化文案：折叠头 summary + 展开 detail + 证据结论；解析失败（null）降级为旧行为
        ToolCallSummarizer.Display display = success
                ? summarizer.summarize(toolName, argsJson, resultText, ctx)
                : null;

        ObjectNode resultPayload = objectMapper.createObjectNode()
                .put("tool", toolName)
                .put("stepSeq", stepSeq)
                .put("success", success);
        if (success) {
            resultPayload.put("summary", display != null ? display.collapsed() : abbreviate(resultText, 500));
            if (display != null) {
                resultPayload.put("detail", display.detail());
            }
        } else {
            resultPayload.put("summary", abbreviate(error, 500));
            resultPayload.put("error", abbreviate(error, 1000));
        }
        eventService.appendAndPush(ssePushService, incident, "tool_running", "tool_call_result", resultPayload);

        // 证据事件：数据类工具成功时采集，内容为数据结论而非原始 JSON
        String source = evidenceSource(toolName);
        if (success && source != null) {
            String evidenceContent = display != null && display.evidence() != null && !display.evidence().isBlank()
                    ? display.evidence()
                    : abbreviate(resultText, 300);
            ObjectNode evidence = objectMapper.createObjectNode()
                    .put("source", source)
                    .put("content", evidenceContent)
                    .put("timestamp", LocalDateTime.now().format(FMT));
            eventService.appendAndPush(ssePushService, incident, null, "evidence_collected", evidence);
        }

        messages.add(ToolExecutionResultMessage.from(
                request.id(), toolName,
                buildToolResultContent(success, resultText, error, stopLoss)));
        return success ? resultText : null;
    }

    /**
     * 组装回填给模型的工具结果文本：失败带错误前缀；成功且结果全空时累计计数，
     * 累计达到止损阈值后一次性附加止损提示（此后不再附加），拦截失控扩窗循环
     */
    String buildToolResultContent(boolean success, String resultText, String error, StopLossState stopLoss) {
        String content = success ? resultText : "工具执行失败: " + error;
        if (!success || stopLoss == null) {
            return content;
        }
        if (isEmptyResult(resultText)) {
            stopLoss.emptyCount++;
        }
        if (stopLoss.emptyCount >= EMPTY_RESULT_STOP_LOSS && !stopLoss.injected) {
            stopLoss.injected = true;
            log.info("Empty-result stop loss triggered after {} empty tool calls", stopLoss.emptyCount);
            return content + STOP_LOSS_HINT;
        }
        return content;
    }

    /**
     * 工具结果是否全空：数据类工具 {total, items} 的 total=0、countLogs 的数值 0 记全空；
     * 其他结构（手册文本、非 JSON、无 total 字段）不算。失败的调用（resultText=null）不计。
     */
    boolean isEmptyResult(String resultText) {
        if (resultText == null || resultText.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(resultText);
            if (node.isNumber()) {
                return node.asInt() == 0;
            }
            return node.path("total").isNumber() && node.get("total").asInt() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 止损状态：一次 run/resume 的工具循环共享（resume 重新计数，不跨尝试累计）。
     * 包私有以便单元测试构造。
     */
    static final class StopLossState {
        /** 成功执行且结果全空的调用累计次数 */
        int emptyCount = 0;
        /** 止损提示是否已注入（只注入一次） */
        boolean injected = false;
    }

    /**
     * 报告落库 + report_finalized 事件 + 状态置 COMPLETED（同一事务）
     */
    private void finish(Incident incident, AnalysisReport report) {
        reportMapper.insert(report);
        incident.setStatus(IncidentStatus.COMPLETED.name());
        incident.setReportId(report.getId());
        incident.setCompletedAt(LocalDateTime.now().format(FMT));

        ObjectNode finalized = objectMapper.createObjectNode().put("reportId", report.getId());
        eventService.appendAndPush(ssePushService, incident, "done", "report_finalized", finalized);
        log.info("LLM analysis completed for incident {}, report {}", incident.getId(), report.getId());
    }

    /**
     * 从最终回答文本中提取最后一个 ```json 围栏块并解析为报告，失败返回 null
     */
    AnalysisReport parseReport(String text) {
        String json = extractLastJsonBlock(text);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            AnalysisReport report = new AnalysisReport();
            JsonNode summary = root.path("eventSummary");
            report.setIsNoise(summary.path("isNoise").asBoolean(false));
            report.setNeedsHandling(summary.path("needsHandling").asBoolean(false));
            report.setRootCauseHypothesis(root.path("rootCauseHypothesis").asText(null));
            report.setJudgmentLogic(root.path("judgmentLogic").asText(null));
            JsonNode actions = root.path("recommendedActions");
            List<Object> rawActions = actions.isMissingNode()
                    ? List.of()
                    : objectMapper.convertValue(actions, new TypeReference<>() {});
            List<Map<String, Object>> normalizedActions = RecommendedActionsNormalizer.normalize(rawActions);
            // SOP 归一后为空（输出纯字符串以外的非法元素、或 action 全为空）→ 视为报告无效，走重试
            if (normalizedActions.isEmpty()) {
                return null;
            }
            report.setRecommendedActions(objectMapper.writeValueAsString(normalizedActions));
            double confidence = root.path("confidence").asDouble(0.5);
            report.setConfidence(Math.max(0.0, Math.min(1.0, confidence)));
            report.setConfidenceReason(root.path("confidenceReason").asText(null));
            if (report.getRootCauseHypothesis() == null || report.getJudgmentLogic() == null) {
                return null;
            }
            return report;
        } catch (Exception e) {
            log.warn("Report JSON invalid: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取最后一个 ```json ...``` 围栏内的内容，无围栏时返回 null
     */
    static String extractLastJsonBlock(String text) {        if (text == null) {
            return null;
        }
        int fence = text.lastIndexOf("```json");
        if (fence < 0) {
            fence = text.lastIndexOf("```");
        }
        if (fence < 0) {
            return null;
        }
        int start = text.indexOf('\n', fence);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf("```", start);
        if (end < 0) {
            end = text.length();
        }
        String body = text.substring(start + 1, end).trim();
        return body.isEmpty() ? null : body;
    }

    /**
     * 根据告警开始时间构建默认查询窗口（前 10 分钟 ~ 后 5 分钟）
     */
    private ToolContext buildContext(Alert alert) {
        long start;
        try {
            start = LocalDateTime.parse(alert.getStartsAt(), FMT)
                    .atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception e) {
            log.warn("Invalid startsAt {}, using now as window anchor", alert.getStartsAt());
            start = Instant.now().getEpochSecond();
        }
        return new ToolContext(alert.getService(), start - 600, start + 300);
    }

    /**
     * 首条用户消息：告警信息。附上开始时间的 Unix 秒值——模型自行换算时间戳经常出错，
     * 导致查询窗口整体偏移而查不到任何数据
     */
    private String alertInfoText(Alert alert) {
        long startsAtEpoch;
        try {
            startsAtEpoch = LocalDateTime.parse(alert.getStartsAt(), FMT)
                    .atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception e) {
            startsAtEpoch = 0;
        }
        return "告警信息：\n"
                + "告警名：" + alert.getAlertName() + "\n"
                + "服务：" + alert.getService() + "\n"
                + "严重级别：" + alert.getSeverity() + "\n"
                + "开始时间：" + alert.getStartsAt() + "（Unix 秒：" + startsAtEpoch + "）\n"
                + "标签：" + (alert.getLabels() == null ? "{}" : alert.getLabels());
    }

    /**
     * 工具名 → 证据来源映射；返回 null 表示该工具不产生证据事件
     */
    private static String evidenceSource(String toolName) {
        return switch (toolName) {
            case "getMetrics", "getKpi" -> "metrics";
            case "getLogs", "countLogs" -> "logs";
            case "getSlowSpans" -> "trace";
            case "getRunbook" -> "runbook";
            default -> null;
        };
    }

    /**
     * 重复调用检测的键：工具名 + 规范化后的参数 JSON
     */
    private String dedupKey(ToolExecutionRequest request) {
        return request.name() + "|" + canonicalArgs(request.arguments());
    }

    /**
     * 参数 JSON 规范化（解析后重序列化），消除模型输出间的空白/字段顺序差异；解析失败原样返回
     */
    private String canonicalArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "{}";
        }
        try {
            return objectMapper.readTree(argsJson).toString();
        } catch (Exception e) {
            return argsJson;
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(截断)";
    }
}
