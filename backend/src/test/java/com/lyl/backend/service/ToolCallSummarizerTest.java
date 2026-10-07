package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyl.backend.tool.ToolContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolCallSummarizer 纯单元测试：六工具的折叠头/展开描述/证据结论文案
 */
class ToolCallSummarizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolCallSummarizer summarizer = new ToolCallSummarizer(objectMapper);

    /** 默认查询窗口：2026-01-01 11:40:00 ~ 11:45:00（系统时区） */
    private static final long WINDOW_START = Instant.parse("2026-01-01T03:40:00Z").getEpochSecond();
    private static final long WINDOW_END = Instant.parse("2026-01-01T03:45:00Z").getEpochSecond();
    private final ToolContext ctx = new ToolContext("apache01", WINDOW_START, WINDOW_END);

    private String hhmm(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // ---- getRunbook ----

    /** 命中手册：折叠"查阅 1 文件"，展开"读取 文件名"，证据含步骤数 */
    @Test
    void runbookMatched() {
        String result = "{\"matched\":true,\"file\":\"HighMemoryUsage.md\","
                + "\"content\":\"## 1. 查内存指标\\n## 2. 查日志\\n\"}";
        var d = summarizer.summarize("getRunbook", "{\"alertName\":\"HighMemoryUsage\"}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 1 文件", d.collapsed());
        assertEquals("读取 HighMemoryUsage.md", d.detail());
        assertTrue(d.evidence().contains("HighMemoryUsage.md"));
        assertTrue(d.evidence().contains("2 个排查步骤"));
    }

    /** 未命中手册：折叠"查阅 0 文件"，描述转自主排查 */
    @Test
    void runbookNotMatched() {
        var d = summarizer.summarize("getRunbook", "{\"alertName\":\"Unknown\"}",
                "{\"matched\":false,\"message\":\"无匹配手册\"}", ctx);
        assertNotNull(d);
        assertEquals("查阅 0 文件", d.collapsed());
        assertTrue(d.detail().contains("自主排查"));
    }

    // ---- getMetrics ----

    /** 有数据：折叠头带条数，展开带查询条件与时间窗，证据提炼最新值/峰值 */
    @Test
    void metricsWithData() {
        long latest = WINDOW_END - 60;
        long earlier = WINDOW_START + 60;
        String result = "{\"total\":2,\"items\":["
                + "{\"timestamp\":" + latest + ",\"kpi_name\":\"memory_usage\",\"value\":95.2},"
                + "{\"timestamp\":" + earlier + ",\"kpi_name\":\"memory_usage\",\"value\":80}]}";
        var d = summarizer.summarize("getMetrics",
                "{\"service\":\"apache01\",\"metricName\":\"memory\"}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 2 条指标", d.collapsed());
        assertTrue(d.detail().contains("apache01"));
        assertTrue(d.detail().contains(hhmm(WINDOW_START) + "–" + hhmm(WINDOW_END)));
        assertTrue(d.detail().contains("名称含 memory"));
        assertTrue(d.detail().endsWith("找到 2 条。"));
        assertTrue(d.evidence().contains("峰值 95.20"));
        assertTrue(d.evidence().contains("共 2 条"));
    }

    /** 无数据：证据"未查询到数据" */
    @Test
    void metricsEmpty() {
        var d = summarizer.summarize("getMetrics", "{\"service\":\"apache01\",\"metricName\":\"memory\"}",
                "{\"total\":0,\"items\":[]}", ctx);
        assertNotNull(d);
        assertEquals("查阅 0 条指标", d.collapsed());
        assertTrue(d.detail().endsWith("找到 0 条。"));
        assertEquals("未查询到数据", d.evidence());
    }

    /** args 缺省 service/时间：回落到告警服务与默认窗口 */
    @Test
    void metricsUsesContextDefaults() {
        var d = summarizer.summarize("getMetrics", "{}", "{\"total\":0,\"items\":[]}", ctx);
        assertNotNull(d);
        assertTrue(d.detail().contains("apache01"));
        assertTrue(d.detail().contains(hhmm(WINDOW_START) + "–" + hhmm(WINDOW_END)));
    }

    // ---- getLogs / countLogs ----

    @Test
    void logsWithData() {
        String result = "{\"total\":1,\"items\":[{"
                + "\"timestamp\":" + (WINDOW_END - 30) + ",\"log_name\":\"catalina.out\","
                + "\"value\":\"java.lang.OutOfMemoryError: Java heap space\"}]}";
        var d = summarizer.summarize("getLogs", "{\"keyword\":\"error\"}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 1 条日志", d.collapsed());
        assertTrue(d.detail().contains("包含 error"));
        assertTrue(d.detail().endsWith("找到 1 条。"));
        assertTrue(d.evidence().contains("OutOfMemoryError"));
    }

    /** countLogs 结果是纯数字 */
    @Test
    void countLogsPlainNumber() {
        var d = summarizer.summarize("countLogs", "{\"keyword\":\"error\"}", "5", ctx);
        assertNotNull(d);
        assertEquals("查阅 5 条日志", d.collapsed());
        assertTrue(d.detail().contains("的日志数量：5 条"));
        assertEquals("共 5 条", d.evidence());
    }

    // ---- getKpi / getSlowSpans ----

    @Test
    void kpiWithData() {
        String result = "{\"total\":1,\"items\":[{"
                + "\"timestamp\":" + WINDOW_END + ",\"rr\":120,\"sr\":0.98,\"cnt\":120,\"mrt\":340}]}";
        var d = summarizer.summarize("getKpi", "{}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 1 条指标", d.collapsed());
        assertTrue(d.evidence().contains("成功率 0.98"));
        assertTrue(d.evidence().contains("平均响应 340"));
    }

    @Test
    void slowSpans() {
        String result = "{\"total\":1,\"items\":[{\"timestamp\":" + WINDOW_END
                + ",\"trace_id\":\"t1\",\"span_id\":\"s1\",\"parent_id\":\"p1\",\"duration\":1234}]}";
        var d = summarizer.summarize("getSlowSpans", "{}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 调用链", d.collapsed());
        assertTrue(d.detail().contains("最慢的 10 个调用"));
        assertTrue(d.evidence().contains("最慢调用 1234ms"));
        assertTrue(d.evidence().contains("trace_id=t1"));
    }

    /** getTraceCount 结果是纯数字（镜像 countLogs），0 条与慢调用查空文案区分 */
    @Test
    void traceCountPlainNumber() {
        var d = summarizer.summarize("getTraceCount", "{}", "42", ctx);
        assertNotNull(d);
        assertEquals("查阅 调用链", d.collapsed());
        assertTrue(d.detail().contains("的调用 span 总数：42 条"));
        assertEquals("共 42 条", d.evidence());
    }

    @Test
    void traceCountZero() {
        var d = summarizer.summarize("getTraceCount", "{}", "0", ctx);
        assertNotNull(d);
        assertEquals("查阅 调用链", d.collapsed());
        assertEquals("未查询到调用链数据", d.evidence());
    }

    // ---- getMemoryUsage ----

    /** 有数据：折叠头带点数，证据提炼最新两点占比与峰值 */
    @Test
    void memoryUsageWithData() {
        String result = "{\"total\":3,\"items\":["
                + "{\"time\":\"2021-03-04 11:48:00\",\"noCacheMemPerc\":\"70.0%\",\"memUsedMemPerc\":\"75.0%\"},"
                + "{\"time\":\"2021-03-04 11:49:00\",\"noCacheMemPerc\":\"95.2%\",\"memUsedMemPerc\":\"96.0%\"},"
                + "{\"time\":\"2021-03-04 11:50:00\",\"noCacheMemPerc\":\"91.0%\",\"memUsedMemPerc\":\"93.0%\"}]}";
        var d = summarizer.summarize("getMemoryUsage", "{\"service\":\"Mysql01\"}", result, ctx);
        assertNotNull(d);
        assertEquals("查阅 3 条指标", d.collapsed());
        assertTrue(d.detail().contains("Mysql01"));
        assertTrue(d.detail().endsWith("得到 3 个时间点。"));
        assertTrue(d.evidence().startsWith("最新（11:50）NoCacheMemPerc 91.0%、MEMUsedMemPerc 93.0%"));
        assertTrue(d.evidence().contains("峰值 NoCacheMemPerc 95.2%"));
        assertTrue(d.evidence().endsWith("共 3 个时间点"));
    }

    /** 无数据：证据"未查询到数据" */
    @Test
    void memoryUsageEmpty() {
        var d = summarizer.summarize("getMemoryUsage", "{}", "{\"total\":0,\"items\":[]}", ctx);
        assertNotNull(d);
        assertEquals("查阅 0 条指标", d.collapsed());
        assertEquals("未查询到数据", d.evidence());
    }

    // ---- calculate ----

    /** 正常计算：折叠"执行计算"，展开"计算 表达式，结果 值"，无证据行 */
    @Test
    void calculateWithResult() {
        var d = summarizer.summarize("calculate", "{\"expression\":\"(100-30)/(100+50)*100\"}",
                "{\"expression\":\"(100-30)/(100+50)*100\",\"value\":46.6667}", ctx);
        assertNotNull(d);
        assertEquals("执行计算", d.collapsed());
        assertEquals("计算 (100-30)/(100+50)*100，结果 46.6667", d.detail());
        assertNull(d.evidence());
    }

    /** 0-1 区间小数不丢精度，整数去小数部分，浮点尾 0 去除 */
    @Test
    void calculateValueFormatting() {
        var d = summarizer.summarize("calculate", "{\"expression\":\"5/8\"}",
                "{\"expression\":\"5/8\",\"value\":0.625}", ctx);
        assertEquals("计算 5/8，结果 0.625", d.detail());

        var intResult = summarizer.summarize("calculate", "{\"expression\":\"1+2\"}",
                "{\"expression\":\"1+2\",\"value\":3.0}", ctx);
        assertEquals("计算 1+2，结果 3", intResult.detail());

        var trailingZeros = summarizer.summarize("calculate", "{\"expression\":\"0.5+0.25\"}",
                "{\"expression\":\"0.5+0.25\",\"value\":0.75}", ctx);
        assertEquals("计算 0.5+0.25，结果 0.75", trailingZeros.detail());
    }

    /** 超长表达式截断到 40 字符加省略号 */
    @Test
    void calculateTruncatesLongExpression() {
        String longExpr = "(111111+222222)*(333333+444444)/(555555+666666)";
        var d = summarizer.summarize("calculate", "{\"expression\":\"" + longExpr + "\"}",
                "{\"expression\":\"" + longExpr + "\",\"value\":247.9339}", ctx);
        assertTrue(d.detail().startsWith("计算 (111111+222222)*(333333+444444)/(55555"));
        assertTrue(d.detail().contains("…，结果 247.9339"));
    }

    /** 结果缺 value 字段：只显示表达式不显示结果 */
    @Test
    void calculateMissingValue() {
        var d = summarizer.summarize("calculate", "{\"expression\":\"1+2\"}",
                "{\"expression\":\"1+2\"}", ctx);
        assertNotNull(d);
        assertEquals("执行计算", d.collapsed());
        assertEquals("计算 1+2", d.detail());
    }

    // ---- 降级 ----

    /** 结果不是 JSON 时返回 null，由调用方退回旧行为 */
    @Test
    void invalidResultReturnsNull() {
        assertNull(summarizer.summarize("getMetrics", "{}", "not-json", ctx));
        assertNull(summarizer.summarize("unknownTool", "{}", "{}", ctx));
    }
}
