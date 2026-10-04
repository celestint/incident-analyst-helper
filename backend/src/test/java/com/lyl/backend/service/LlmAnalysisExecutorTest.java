package com.lyl.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmAnalysisExecutor 报告解析的纯单元测试（不依赖 Spring 上下文）
 */
class LlmAnalysisExecutorTest {

    private final LlmAnalysisExecutor executor = new LlmAnalysisExecutor(
            null, null, null, null, null, null, null, new ObjectMapper(), null, new ToolCallSummarizer(new ObjectMapper()));
    /** 从 ```json 围栏中提取内容 */
    @Test
    void extractLastJsonBlockFindsFencedJson() {
        String text = "叙述内容...\n```json\n{\"a\":1}\n```\n后续";
        assertEquals("{\"a\":1}", LlmAnalysisExecutor.extractLastJsonBlock(text));
    }

    /** 多个围栏时取最后一个 */
    @Test
    void extractLastJsonBlockTakesLastFence() {
        String text = "```json\n{\"a\":1}\n```\n中段\n```json\n{\"b\":2}\n```";
        assertEquals("{\"b\":2}", LlmAnalysisExecutor.extractLastJsonBlock(text));
    }

    /** 无围栏返回 null */
    @Test
    void extractLastJsonBlockReturnsNullWhenNoFence() {
        assertNull(LlmAnalysisExecutor.extractLastJsonBlock("没有围栏的普通文本"));
        assertNull(LlmAnalysisExecutor.extractLastJsonBlock(null));
    }

    /** 正常解析报告 JSON：四段字段 + confidence/confidenceReason */
    @Test
    void parseReportParsesValidJson() {
        String text = "叙述\n```json\n{"
                + "\"eventSummary\": {\"isNoise\": false, \"needsHandling\": true},"
                + "\"rootCauseHypothesis\": \"内存泄漏\","
                + "\"recommendedActions\": [{\"priority\":1,\"action\":\"重启\"}],"
                + "\"judgmentLogic\": \"1. OOM 日志 3 条（来源 logs）。\\n2. 指标同步上涨（来源 metrics）。\","
                + "\"confidence\": 0.9,"
                + "\"confidenceReason\": \"指标与日志两类证据相互印证\""
                + "}\n```";
        var report = executor.parseReport(text);
        assertNotNull(report);
        assertFalse(report.getIsNoise());
        assertTrue(report.getNeedsHandling());
        assertEquals("内存泄漏", report.getRootCauseHypothesis());
        assertEquals(0.9, report.getConfidence());
        assertEquals("指标与日志两类证据相互印证", report.getConfidenceReason());
        assertTrue(report.getRecommendedActions().contains("重启"));
        assertTrue(report.getJudgmentLogic().contains("logs"));
    }

    /** confidence 越界时收敛到 0~1 */
    @Test
    void parseReportClampsConfidence() {
        String text = "```json\n{\"rootCauseHypothesis\":\"x\",\"judgmentLogic\":\"y\","
                + "\"recommendedActions\":[\"重启\"],\"confidence\":5.0}\n```";
        var report = executor.parseReport(text);
        assertNotNull(report);
        assertEquals(1.0, report.getConfidence());
    }

    /** recommendedActions 为纯字符串数组（LLM 偶发输出）：归一为 [{priority, action}] */
    @Test
    void parseReportNormalizesStringActions() {
        String text = "```json\n{\"rootCauseHypothesis\":\"x\",\"judgmentLogic\":\"y\","
                + "\"recommendedActions\":[\"检查监控采集\",\"确认服务状态\"],\"confidence\":0.4}\n```";
        var report = executor.parseReport(text);
        assertNotNull(report);
        assertTrue(report.getRecommendedActions().contains("\"priority\":1"));
        assertTrue(report.getRecommendedActions().contains("检查监控采集"));
        assertTrue(report.getRecommendedActions().contains("\"priority\":2"));
    }

    /** recommendedActions 归一后为空（缺字段/全空 action）：报告无效返回 null，走 LLM 重试 */
    @Test
    void parseReportReturnsNullWhenActionsEmpty() {
        assertNull(executor.parseReport("```json\n{\"rootCauseHypothesis\":\"x\",\"judgmentLogic\":\"y\","
                + "\"recommendedActions\":[{\"priority\":1}],\"confidence\":0.5}\n```"));
        assertNull(executor.parseReport("```json\n{\"rootCauseHypothesis\":\"x\",\"judgmentLogic\":\"y\","
                + "\"recommendedActions\":[],\"confidence\":0.5}\n```"));
        assertNull(executor.parseReport("```json\n{\"rootCauseHypothesis\":\"x\",\"judgmentLogic\":\"y\"}\n```"));
    }

    /** 缺少必填字段或 JSON 非法时返回 null（触发重试/失败） */
    @Test
    void parseReportReturnsNullForInvalid() {
        assertNull(executor.parseReport("```json\n{不是json}\n```"));
        assertNull(executor.parseReport("```json\n{\"confidence\":0.5}\n```"));
    }
}
