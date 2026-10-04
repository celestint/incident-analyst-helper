package com.lyl.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 推荐SOP 归一化：LLM 偶发输出纯字符串数组或缺 action 字段的元素
 * （用户验收发现空 SOP 条目的根因），统一为 [{priority, action}] 结构。
 * 三个使用点共用同一份规则：
 * - 报告解析落库前（写入侧，保证存库结构正确；归一后为空视为报告无效触发 LLM 重试）
 * - 接口返回前（读取侧，兼容修复历史报告）
 */
public final class RecommendedActionsNormalizer {

    private RecommendedActionsNormalizer() {
    }

    /**
     * 归一化为 [{priority, action}] 列表：字符串元素按序补 priority；
     * 对象元素缺 priority 时同样按序补；action 为空/缺失的元素丢弃
     */
    public static List<Map<String, Object>> normalize(List<?> raw) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (raw == null) {
            return normalized;
        }
        int nextPriority = 1;
        for (Object item : raw) {
            String action;
            int priority;
            if (item instanceof String s) {
                action = s;
                priority = nextPriority;
            } else if (item instanceof Map<?, ?> m) {
                Object actionObj = m.get("action");
                action = actionObj == null ? "" : actionObj.toString();
                Object priorityObj = m.get("priority");
                priority = priorityObj instanceof Number n ? n.intValue() : nextPriority;
            } else {
                continue;
            }
            if (action.isBlank()) {
                continue;
            }
            normalized.add(Map.of("priority", priority, "action", action));
            nextPriority = Math.max(nextPriority, priority) + 1;
        }
        return normalized;
    }
}
