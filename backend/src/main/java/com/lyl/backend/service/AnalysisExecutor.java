package com.lyl.backend.service;

import com.lyl.backend.model.Alert;
import com.lyl.backend.model.Incident;

/**
 * 分析执行器接口：同步执行一次完整分析（调用方已在异步线程中）。
 * 正常结束时执行器负责落库报告并置 COMPLETED；异常直接抛出，由调度器统一转为 FAILED + error 事件。
 */
public interface AnalysisExecutor {

    /**
     * 从头执行完整分析
     *
     * @param incident 分析事件（RUNNING，含 lastSeq 游标，执行过程中由事件服务推进）
     * @param alert    关联告警
     */
    void run(Incident incident, Alert alert);

    /**
     * 断点续跑：从最近一次失败的点继续（重建上下文）。默认退化为从头执行。
     */
    default void resume(Incident incident, Alert alert) {
        run(incident, alert);
    }
}
