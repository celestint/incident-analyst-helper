package com.lyl.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 工具执行上下文：由执行器根据告警信息构建。
 *
 * @param defaultService 告警所属服务，args 未传 service 时的默认值
 * @param windowStart    默认查询窗口起点（Unix 秒，告警开始前 10 分钟）
 * @param windowEnd      默认查询窗口终点（Unix 秒，告警开始后 5 分钟）
 */
public record ToolContext(String defaultService, long windowStart, long windowEnd) {

    /**
     * args 里的 service 优先，缺省用告警服务
     */
    public String serviceOr(Map<String, Object> args) {
        Object service = args.get("service");
        if (service != null && !service.toString().isBlank()) {
            return service.toString();
        }
        return defaultService;
    }

    /**
     * args 里的 startTime 优先，缺省用窗口起点
     */
    public Long startOr(Map<String, Object> args) {
        return args.get("startTime") instanceof Number n ? n.longValue() : windowStart;
    }

    /**
     * args 里的 endTime 优先，缺省用窗口终点
     */
    public Long endOr(Map<String, Object> args) {
        return args.get("endTime") instanceof Number n ? n.longValue() : windowEnd;
    }
}
