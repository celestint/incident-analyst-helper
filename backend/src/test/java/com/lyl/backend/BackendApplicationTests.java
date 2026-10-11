package com.lyl.backend;

import com.lyl.backend.controller.AlertController;
import com.lyl.backend.controller.IncidentController;
import com.lyl.backend.exception.ResourceNotFoundException;
import com.lyl.backend.exception.ValidationException;
import com.lyl.backend.mapper.AlertMapper;
import com.lyl.backend.mapper.IncidentMapper;
import com.lyl.backend.model.Alert;
import com.lyl.backend.model.ApiResponse;
import com.lyl.backend.model.Incident;
import com.lyl.backend.model.IncidentStatus;
import com.lyl.backend.service.DataAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@AutoConfigureMockMvc
class BackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertMapper alertMapper;

    @Autowired
    private IncidentMapper incidentMapper;

    @Autowired
    private DataAnalyticsService dataAnalyticsService;

    private Long testAlertId;

    /** 每个测试前清空表并创建一条基础测试告警 */
    @BeforeEach
    void setUp() {
        // 清理测试数据（incident.alert_id 引用 alert，先删子表 incident）
        incidentMapper.deleteAll();
        alertMapper.deleteAll();

        // 创建测试告警
        Alert testAlert = new Alert();
        testAlert.setAlertName("TestHighCPU");
        testAlert.setSeverity("critical");
        testAlert.setService("dbservice1");
        testAlert.setStartsAt(Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        alertMapper.insert(testAlert);
        testAlertId = testAlert.getId();
    }

    // ========== Alert API 测试 ==========

    /** 测试创建告警成功，返回完整字段且状态为 PENDING */
    @Test
    void testCreateAlert() throws Exception {

        Map<String, Object> request = new HashMap<>();
        request.put("alertName", "TestAlert");
        request.put("severity", "warning");
        request.put("service", "testservice");
        request.put("startsAt", Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        mockMvc.perform(post("/api/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.alertName").value("TestAlert"))
                .andExpect(jsonPath("$.data.severity").value("warning"))
                .andExpect(jsonPath("$.data.service").value("testservice"))
                .andExpect(jsonPath("$.data.incidentId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /** 测试创建告警时缺少必需字段，返回 400 校验失败 */
    @Test
    void testCreateAlertValidationFailed() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("severity", "warning"); // 缺少必需字段

        mockMvc.perform(post("/api/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").exists());
    }

    /** 测试重复创建同名告警，返回 400 并提示已存在 */
    @Test
    void testCreateAlertDuplicate() throws Exception {
        // 创建第一个告警
        Map<String, Object> request = new HashMap<>();
        request.put("alertName", "DuplicateAlert");
        request.put("severity", "critical");
        request.put("service", "testservice");
        request.put("startsAt", Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        mockMvc.perform(post("/api/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isOk());

        // 尝试创建第二个相同告警
        mockMvc.perform(post("/api/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("该告警已存在，不能重复插入"));
    }

    /** 测试告警列表接口，返回 setUp 中创建的那一条 */
    @Test
    void testListAlerts() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /** 测试按 id 查询告警详情，字段与插入值一致 */
    @Test
    void testGetAlert() throws Exception {
        mockMvc.perform(get("/api/alerts/" + testAlertId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(testAlertId))
                .andExpect(jsonPath("$.data.alertName").value("TestHighCPU"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /** 测试查询不存在的告警，返回 404 */
    @Test
    void testGetAlertNotFound() throws Exception {
        mockMvc.perform(get("/api/alerts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ========== Incident 状态机测试 ==========

    /** 测试告警成功触发 incident 分析，状态为 RUNNING 且告警回填 incidentId */
    @Test
    void testStartIncidentSuccess() throws Exception {
        mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.incidentId").exists())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        // 验证告警状态已更新
        Alert alert = alertMapper.selectById(testAlertId);
        assertNotNull(alert.getIncidentId());
    }

    /** 测试对不存在的告警触发分析，返回 404 */
    @Test
    void testStartIncidentNotFound() throws Exception {
        mockMvc.perform(post("/api/incidents/999999/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 测试分析已完成后再触发，返回 409 冲突 */
    @Test
    void testStartIncidentAlreadyCompleted() throws Exception {
        // 先创建并等待完成一个 incident
        int incidentId = startIncidentAndGetId();
        waitForCompletion(incidentId);

        // 再次启动
        mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该告警已完成分析，不可重复触发"));
    }

    /** 测试同一告警分析进行中重复触发，返回同一个 incident 而不报错 */
    @Test
    void testStartIncidentAlreadyRunning() throws Exception {
        // 启动两次（第二次应该成功，因为第一个还没完成）
        mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isOk());

        // 第二次启动应该也成功（因为是同一个 alert 只能有一个 incident）
        mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isOk());
    }

    // ========== Incident API 测试 ==========

    /** 测试按 id 查询 incident 详情，关联的 alertId 正确 */
    @Test
    void testGetIncidentSuccess() throws Exception {
        // 先创建 incident
        int incidentId = startIncidentAndGetId();

        // 获取 incident
        mockMvc.perform(get("/api/incidents/" + incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(incidentId))
                .andExpect(jsonPath("$.data.status").value(org.hamcrest.Matchers.isIn(java.util.Arrays.asList("RUNNING", "COMPLETED"))))
                .andExpect(jsonPath("$.data.alertId").value(testAlertId));
    }

    /** 测试查询不存在的 incident，返回 404 */
    @Test
    void testGetIncidentNotFound() throws Exception {
        mockMvc.perform(get("/api/incidents/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 测试异步分析完成后查询 incident，状态为 COMPLETED 且关联 reportId */
    @Test
    void testGetIncidentCompleted() throws Exception {
        // 先创建 incident 并等待异步分析完成
        int incidentId = startIncidentAndGetId();
        waitForCompletion(incidentId);

        // 获取 incident
        mockMvc.perform(get("/api/incidents/" + incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reportId").exists());
    }

    /** 测试历史事件端点：完成后返回全部落库事件，首条 incident_received、末条 report_finalized */
    @Test
    void testGetIncidentEvents() throws Exception {
        int incidentId = startIncidentAndGetId();
        waitForCompletion(incidentId);

        mockMvc.perform(get("/api/incidents/" + incidentId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].type").value("incident_received"))
                .andExpect(jsonPath("$.data[0].sequence").value(1))
                .andExpect(jsonPath("$.data[?(@.type=='report_finalized')]").isNotEmpty());
    }

    /** 测试历史事件端点对不存在的 incident 返回 404 */
    @Test
    void testGetIncidentEventsNotFound() throws Exception {
        mockMvc.perform(get("/api/incidents/999999/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ========== SSE 测试 ==========

    /** 测试 SSE 流接口可建立连接，返回 text/event-stream */
    @Test
    void testSseStream() throws Exception {
        // 先创建 incident
        int incidentId = startIncidentAndGetId();

        // 获取 SSE 流
        mockMvc.perform(get("/api/incidents/" + incidentId + "/stream"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Type"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/event-stream")));

        // 注意：实际的 SSE 内容需要从独立线程异步推送，
        // 这里主要测试流是否可以建立连接
    }

    /** 测试对不存在的 incident 订阅 SSE 流，返回 404 */
    @Test
    void testSseStreamNotFound() throws Exception {
        mockMvc.perform(get("/api/incidents/999999/stream"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 测试分析完成后再次通过 start 接口触发，返回 409 冲突 */
    @Test
    void testStartIncidentCompletedReturns409() throws Exception {
        // 先创建并等待分析完成
        int incidentId = startIncidentAndGetId();
        waitForCompletion(incidentId);

        // 再次启动应该返回 409
        mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该告警已完成分析，不可重复触发"));
    }

    // ========== DataAnalyticsService 测试 ==========

    /** 测试按服务和分页查询日志，返回 items 与 total */
    @Test
    void testDataAnalyticsServiceGetLogs() {
        var result = dataAnalyticsService.getLogs("dbservice1", null, null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Log count: " + result.get("total"));
    }

    /** 测试按服务统计 error 级别日志数量 */
    @Test
    void testDataAnalyticsServiceCountLogs() {
        int count = dataAnalyticsService.countLogs("dbservice1", "error", null, null);
        assertTrue(count >= 0);
        System.out.println("Error log count: " + count);
    }

    /** 测试按服务和分页查询指标数据，返回 items 与 total */
    @Test
    void testDataAnalyticsServiceGetMetrics() {
        var result = dataAnalyticsService.getMetrics("dbservice1", null, null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Metric count: " + result.get("total"));
    }

    /** 测试按服务和分页查询 KPI 数据，返回 items 与 total */
    @Test
    void testDataAnalyticsServiceGetKpi() {
        var result = dataAnalyticsService.getKpi("dbservice1", null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("KPI count: " + result.get("total"));
    }

    /** 测试按服务和分页查询慢调用 span，返回 items 与 total */
    @Test
    void testDataAnalyticsServiceGetSlowSpans() {
        var result = dataAnalyticsService.getSlowSpans("dbservice1", null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Slow span count: " + result.get("total"));
    }

    // ========== 统一响应包装测试 ==========

    /** 测试 ApiResponse.ok 包装成功响应，code 为 200 */
    @Test
    void testApiResponseSuccess() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 1);
        data.put("name", "test");

        ApiResponse<Map<String, Object>> response = ApiResponse.ok(data);
        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertEquals(data, response.getData());
    }

    /** 测试 ApiResponse.error 包装错误响应，携带 code 与 message */
    @Test
    void testApiResponseError() {
        ApiResponse<Map<String, Object>> response = ApiResponse.error(400, "Test error message");
        assertEquals(400, response.getCode());
        assertEquals("Test error message", response.getMessage());
        assertNull(response.getData());
    }

    // ========== 工具方法 ==========

    /** 启动分析并返回新 incident 的 id（避免硬编码自增 id） */
    private int startIncidentAndGetId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/incidents/" + testAlertId + "/start"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.incidentId");
    }

    /** 轮询直到异步分析结束（COMPLETED/FAILED），超时则失败 */
    private void waitForCompletion(int incidentId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get("/api/incidents/" + incidentId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String status = com.jayway.jsonpath.JsonPath.read(body, "$.data.status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("分析未在 10s 内完成, incidentId=" + incidentId);
    }

    /** 将对象序列化为 JSON 字符串，用于构造请求体 */
    private String toJson(Object obj) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return mapper.writeValueAsString(obj);
    }
}
