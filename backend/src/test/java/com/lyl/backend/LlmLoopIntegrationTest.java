package com.lyl.backend;

import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentEventMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.mapper.ToolIdempotencyMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentEvent;
import com.lyl.backend.model.ToolIdempotency;
import com.lyl.backend.service.CrashRecoveryRunner;
import com.lyl.backend.service.LlmChatClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LLM 闭环集成测试：用桩 StreamingChatModel 模拟"先调工具、再出报告"两轮，
 * 验证事件落库、工具幂等、报告生成与 SSE 重放。
 */
@SpringBootTest
@ActiveProfiles({"test-mysql", "test-llm"})
@AutoConfigureMockMvc
@Import(LlmLoopIntegrationTest.StubConfig.class)
class LlmLoopIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertMapper alertMapper;

    @Autowired
    private IncidentMapper incidentMapper;

    @Autowired
    private IncidentEventMapper incidentEventMapper;

    @Autowired
    private ToolIdempotencyMapper toolIdempotencyMapper;

    @Autowired
    private CrashRecoveryRunner crashRecoveryRunner;

    private Long testAlertId;

    @Autowired
    private StubState stubState;

    /**
     * 桩模型状态：failOnToolResultTurn=true 时，带工具结果的下一轮调用注入错误（模拟限流失败）
     */
    static class StubState {
        volatile boolean failOnToolResultTurn = false;
    }

    /**
     * 桩模型：首轮返回工具调用（getLogs），带工具结果的轮次返回带 ```json 报告块的最终回答。
     * 用 @Primary 的 LlmChatClient 包裹桩模型，覆盖 AiClientConfig 装配的真实客户端。
     */
    @TestConfiguration
    static class StubConfig {
        @Bean
        public StubState stubState() {
            return new StubState();
        }

        @Bean
        @Primary
        public LlmChatClient stubLlmChatClient(StubState state) {
            return new LlmChatClient(List.of(new StreamingChatModel() {
                @Override
                public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                    List<ChatMessage> messages = request.messages();
                    ChatMessage last = messages.get(messages.size() - 1);
                    if (state.failOnToolResultTurn && last instanceof ToolExecutionResultMessage) {
                        handler.onError(new RuntimeException("模拟限流"));
                        return;
                    }
                    ChatResponse response;
                    if (last instanceof ToolExecutionResultMessage) {
                        String report = "{"
                                + "\"eventSummary\": {\"isNoise\": false, \"needsHandling\": true},"
                                + "\"rootCauseHypothesis\": \"dbservice1 日志异常导致告警\","
                                + "\"recommendedActions\": [{\"priority\":1,\"action\":\"检查日志来源\",\"risk\":\"LOW\"}],"
                                + "\"judgmentLogic\": \"getLogs 查到 error 日志（来源 logs），据此推断。\","
                                + "\"confidence\": 0.9"
                                + "}";
                        response = ChatResponse.builder()
                                .aiMessage(AiMessage.from("分析叙述开始。\n```json\n" + report + "\n```"))
                                .build();
                    } else {
                        response = ChatResponse.builder()
                                .aiMessage(AiMessage.from("先查日志验证。",
                                        List.of(ToolExecutionRequest.builder()
                                                .id("call-1")
                                                .name("getLogs")
                                                .arguments("{\"service\":\"dbservice1\",\"keyword\":\"error\"}")
                                                .build())))
                                .build();
                    }
                    handler.onCompleteResponse(response);
                }
            }));
        }
    }

    @BeforeEach
    void setUp() {
        incidentMapper.deleteAll();
        incidentEventMapper.deleteAll();
        toolIdempotencyMapper.deleteAll();
        alertMapper.deleteAll();

        Alert alert = new Alert();
        alert.setAlertName("HighMemoryUsage");
        alert.setSeverity("critical");
        alert.setService("dbservice1");
        alert.setStartsAt(Instant.now().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        alertMapper.insert(alert);
        testAlertId = alert.getId();
    }

    /** 完整闭环：start → 工具调用 → 报告落库，事件与幂等记录齐全 */
    @Test
    void testFullLoopWithStubLlm() throws Exception {
        int incidentId = startIncidentAndGetId();
        waitForCompletion(incidentId);

        // 详情：COMPLETED + 报告四段结构
        mockMvc.perform(get("/api/incidents/" + incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reportId").exists())
                .andExpect(jsonPath("$.data.report.rootCauseHypothesis").value("dbservice1 日志异常导致告警"))
                .andExpect(jsonPath("$.data.report.isNoise").value(false))
                .andExpect(jsonPath("$.data.report.needsHandling").value(true))
                .andExpect(jsonPath("$.data.report.judgmentLogic").exists());

        // 事件序列完整
        List<IncidentEvent> events = incidentEventMapper.selectAfter((long) incidentId, 0);
        List<String> types = events.stream().map(IncidentEvent::getEventType).toList();
        assertTrue(types.contains("incident_received"));
        assertTrue(types.contains("agent_thought"));
        assertTrue(types.contains("tool_call_start"));
        assertTrue(types.contains("tool_call_result"));
        assertTrue(types.contains("evidence_collected"));
        assertTrue(types.contains("report_finalized"));
        assertEquals("report_finalized", types.get(types.size() - 1));

        // 工具幂等记录已写入
        List<ToolIdempotency> idempotencies = toolIdempotencyMapper.selectByIncidentId((long) incidentId);
        assertEquals(1, idempotencies.size());
        assertEquals("getLogs", idempotencies.get(0).getToolName());
        assertNotNull(idempotencies.get(0).getResult());

        // SSE 重放：?since=0 返回全部落库事件
        mockMvc.perform(get("/api/incidents/" + incidentId + "/stream").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("incident_received")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("report_finalized")));

        // SSE 增量重放：since=lastSeq 之后再无更早事件重放
        int lastSeq = events.get(events.size() - 1).getSeq();
        mockMvc.perform(get("/api/incidents/" + incidentId + "/stream").param("since", String.valueOf(lastSeq)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("incident_received"))));
    }

    /** 断点续跑：第 2 轮 LLM 调用失败 → FAILED；resume 后从失败点续跑出报告，工具不重复执行 */
    @Test
    void testResumeContinuesFromFailurePoint() throws Exception {
        stubState.failOnToolResultTurn = true;
        int incidentId = startIncidentAndGetId();
        waitForStatus(incidentId);

        // 失败链路：有工具调用，无报告
        List<String> types = incidentEventMapper.selectAfter((long) incidentId, 0)
                .stream().map(IncidentEvent::getEventType).toList();
        assertTrue(types.contains("tool_call_start"));
        assertFalse(types.contains("report_finalized"));

        // 解除故障，断点续跑
        stubState.failOnToolResultTurn = false;
        mockMvc.perform(post("/api/incidents/" + incidentId + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        waitForCompletion(incidentId);

        // 报告落库
        mockMvc.perform(get("/api/incidents/" + incidentId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.report.judgmentLogic").exists());

        // 续跑写入新的 incident_received 边界事件（resumed 标记），前端据此隐藏续跑前失败
        List<IncidentEvent> afterResume = incidentEventMapper.selectAfter((long) incidentId, 0);
        long startMarkers = afterResume.stream()
                .filter(e -> "incident_received".equals(e.getEventType()))
                .count();
        assertEquals(2, startMarkers);
        assertTrue(afterResume.stream()
                .filter(e -> "incident_received".equals(e.getEventType()))
                .anyMatch(e -> e.getPayload() != null && e.getPayload().contains("\"resumed\":true")));

        // 工具幂等：续跑没有重复执行 getLogs（仍只有失败前那 1 条幂等记录）
        List<ToolIdempotency> idempotencies = toolIdempotencyMapper.selectByIncidentId((long) incidentId);
        assertEquals(1, idempotencies.size());

        // COMPLETED 后再 resume → 409
        mockMvc.perform(post("/api/incidents/" + incidentId + "/resume"))
                .andExpect(status().isConflict());
    }

    /** 崩溃恢复：超时 RUNNING 被扫描为 FAILED，并补写 error 事件（直接造库，不经 start，避免异步分析干扰） */
    @Test
    void testCrashRecoveryMarksStaleRunningAsFailed() {
        Incident stale = new Incident();
        stale.setAlertId(testAlertId);
        stale.setStatus("RUNNING");
        stale.setPhase("thinking");
        stale.setLastSeq(0);
        stale.setCreatedAt("2000-01-01 00:00:00");
        stale.setUpdatedAt("2000-01-01 00:00:00");
        incidentMapper.insert(stale);

        crashRecoveryRunner.run(null);

        Incident recovered = incidentMapper.selectById(stale.getId());
        assertEquals("FAILED", recovered.getStatus());
        assertNotNull(recovered.getErrorMessage());
        List<String> types = incidentEventMapper.selectAfter(stale.getId(), 0)
                .stream().map(IncidentEvent::getEventType).toList();
        assertTrue(types.contains("error"));
    }

    /** 启动分析并返回 incident id */
    private int startIncidentAndGetId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.incidentId");
    }

    /** 轮询直到异步分析结束（COMPLETED/FAILED），超时失败 */
    private void waitForCompletion(int incidentId) throws Exception {
        String status = waitForStatus(incidentId);
        assertEquals("COMPLETED", status, "分析应成功完成");
    }

    /** 轮询直到状态进入 COMPLETED/FAILED，返回最终状态 */
    private String waitForStatus(int incidentId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get("/api/incidents/" + incidentId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String status = com.jayway.jsonpath.JsonPath.read(body, "$.data.status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        fail("分析未在 15s 内完成, incidentId=" + incidentId);
        return "TIMEOUT";
    }
}
