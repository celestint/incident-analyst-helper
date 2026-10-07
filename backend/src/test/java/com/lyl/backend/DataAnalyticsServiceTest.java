package com.lyl.backend;

import com.lyl.backend.service.DataAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DataAnalyticsServiceTest {

    @Autowired
    private DataAnalyticsService dataAnalyticsService;

    /** 测试按服务和分页查询日志，返回 items 与 total */
    @Test
    void testGetLogs() {
        Map<String, Object> result = dataAnalyticsService.getLogs("dbservice1", null, null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Log count: " + result.get("total"));
    }

    /** 测试按服务统计指定关键字（error）的日志数量 */
    @Test
    void testCountLogs() {
        int count = dataAnalyticsService.countLogs("dbservice1", "error", null, null);
        assertTrue(count >= 0);
        System.out.println("Error log count: " + count);
    }

    /** 测试按服务和分页查询指标数据，返回 items 与 total */
    @Test
    void testGetMetrics() {
        Map<String, Object> result = dataAnalyticsService.getMetrics("dbservice1", null, null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Metric count: " + result.get("total"));
    }

    /** 测试内存占比查询：Mysql01 有内存指标，应返回按时间升序的占比点，格式为 yyyy-MM-dd HH:mm:ss + "xx.x%" */
    @Test
    void testGetMemoryUsage() {
        Map<String, Object> result = dataAnalyticsService.getMemoryUsage("Mysql01", null, null, 10);
        assertNotNull(result);
        assertTrue(((Number) result.get("total")).intValue() > 0);
        List<?> items = (List<?>) result.get("items");
        assertFalse(items.isEmpty());
        assertTrue(items.size() <= 10);
        for (Object obj : items) {
            Map<?, ?> item = (Map<?, ?>) obj;
            assertTrue(String.valueOf(item.get("time")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
            assertTrue(String.valueOf(item.get("noCacheMemPerc")).matches("-?\\d+\\.\\d%"));
            assertTrue(String.valueOf(item.get("memUsedMemPerc")).matches("-?\\d+\\.\\d%"));
        }
        System.out.println("Memory usage points: " + result.get("total"));
    }

    /** 测试按服务和分页查询 KPI 数据，返回 items 与 total */
    @Test
    void testGetKpi() {
        Map<String, Object> result = dataAnalyticsService.getKpi("dbservice1", null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("KPI count: " + result.get("total"));
    }

    /** 测试按服务和分页查询慢调用 span，返回 items 与 total */
    @Test
    void testGetSlowSpans() {
        Map<String, Object> result = dataAnalyticsService.getSlowSpans("dbservice1", null, null, 10);
        assertNotNull(result);
        assertNotNull(result.get("items"));
        assertTrue(result.get("total") instanceof Number);
        System.out.println("Slow span count: " + result.get("total"));
    }

    /** 测试统计窗口内调用 span 总数（判定"调用链缺失"的依据），返回非负数字 */
    @Test
    void testGetTraceCount() {
        int total = dataAnalyticsService.getTraceCount("dbservice1", null, null);
        assertTrue(total >= 0);
        System.out.println("Trace count: " + total);
    }
}
