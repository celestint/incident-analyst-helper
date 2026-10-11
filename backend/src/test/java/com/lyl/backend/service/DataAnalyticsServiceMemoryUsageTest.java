package com.lyl.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataAnalyticsService.toMemoryUsageItems 纯单元测试：直接值优先/公式计算/数据不全跳点/格式
 */
class DataAnalyticsServiceMemoryUsageTest {

    private static final String USER = "OSLinux-OSLinux_MEMORY_MEMORY_UserMem";
    private static final String CACHE = "OSLinux-OSLinux_MEMORY_MEMORY_CacheMem";
    private static final String FREE = "OSLinux-OSLinux_MEMORY_MEMORY_MEMFreeMem";
    private static final String NO_CACHE_PERC = "OSLinux-OSLinux_MEMORY_MEMORY_NoCacheMemPerc";
    private static final String USED_PERC = "OSLinux-OSLinux_MEMORY_MEMORY_MEMUsedMemPerc";

    private static Map<String, Object> row(long ts, String kpiName, double value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", ts);
        row.put("kpi_name", kpiName);
        row.put("value", value);
        return row;
    }

    /** 原始 NoCacheMemPerc/MEMUsedMemPerc 存在时优先取原始值，不用公式重算 */
    @Test
    void prefersDirectMetricValues() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row(1614787200, USER, 600), row(1614787200, CACHE, 100), row(1614787200, FREE, 300),
                // 原始值与公式结果不同（公式会算出 55.6%），应取 81.0
                row(1614787200, NO_CACHE_PERC, 81.0), row(1614787200, USED_PERC, 66.7)));
        List<Map<String, Object>> items = DataAnalyticsService.toMemoryUsageItems(rows);
        assertEquals(1, items.size());
        assertEquals("81.0%", items.get(0).get("noCacheMemPerc"));
        assertEquals("66.7%", items.get(0).get("memUsedMemPerc"));
    }

    /** 缺原始占比时按手册公式计算：(UserMem-CacheMem)/(UserMem+MEMFreeMem)*100 */
    @Test
    void computesByFormulaWhenDirectMissing() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row(1614787200, USER, 600), row(1614787200, CACHE, 100), row(1614787200, FREE, 300)));
        List<Map<String, Object>> items = DataAnalyticsService.toMemoryUsageItems(rows);
        assertEquals(1, items.size());
        // (600-100)/(600+300)*100 = 55.555... → 55.6%；600/900*100 = 66.666... → 66.7%
        assertEquals("55.6%", items.get(0).get("noCacheMemPerc"));
        assertEquals("66.7%", items.get(0).get("memUsedMemPerc"));
    }

    /** 占比不可得（指标缺失/分母为 0/非数值行）的时间点跳过 */
    @Test
    void skipsIncompletePoints() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                // 缺 CacheMem 与两个占比 → 跳过
                row(1614787200, USER, 600), row(1614787200, FREE, 300),
                // 分母为 0 → 跳过
                row(1614787260, USER, 0), row(1614787260, CACHE, 0), row(1614787260, FREE, 0),
                // 完整 → 保留
                row(1614787320, USER, 500), row(1614787320, CACHE, 100), row(1614787320, FREE, 400)));
        List<Map<String, Object>> items = DataAnalyticsService.toMemoryUsageItems(rows);
        assertEquals(1, items.size());
        // (500-100)/(500+400)*100 = 44.444... → 44.4%
        assertEquals("44.4%", items.get(0).get("noCacheMemPerc"));
    }

    /** 非数值 timestamp/value 的脏行忽略 */
    @Test
    void ignoresNonNumericRows() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("timestamp", "bad");
        bad.put("kpi_name", USER);
        bad.put("value", 1.0);
        List<Map<String, Object>> items = DataAnalyticsService.toMemoryUsageItems(
                new ArrayList<>(List.of(bad, row(1614787200, USER, 600), row(1614787200, CACHE, 100),
                        row(1614787200, FREE, 300))));
        assertEquals(1, items.size());
    }

    /** 按时间升序、time 为 yyyy-MM-dd HH:mm:ss 格式 */
    @Test
    void sortsAscendingWithFormattedTime() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(
                row(1614787320, USER, 500), row(1614787320, CACHE, 100), row(1614787320, FREE, 400),
                row(1614787200, USER, 600), row(1614787200, CACHE, 100), row(1614787200, FREE, 300)));
        List<Map<String, Object>> items = DataAnalyticsService.toMemoryUsageItems(rows);
        assertEquals(2, items.size());
        String first = String.valueOf(items.get(0).get("time"));
        String second = String.valueOf(items.get(1).get("time"));
        assertTrue(first.compareTo(second) < 0);
        assertTrue(first.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }
}
