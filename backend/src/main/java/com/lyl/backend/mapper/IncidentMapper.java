package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.Incident;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 分析事件表（incident）数据访问
 */
@Mapper
public interface IncidentMapper {

    /**
     * 按主键查询分析事件
     */
    @Select("SELECT id, alert_id, status, phase, last_seq, report_id, created_at, updated_at, completed_at, error_message " +
            "FROM incident WHERE id = #{id}")
    Incident selectById(Long id);

    /**
     * 按告警 ID 查询分析事件（一个告警只有一个 incident）
     */
    @Select("SELECT id, alert_id, status, phase, last_seq, report_id, created_at, updated_at, completed_at, error_message " +
            "FROM incident WHERE alert_id = #{alertId}")
    Incident selectByAlertId(Long alertId);

    /**
     * 新增分析事件，回填自增主键
     */
    @Insert("INSERT INTO incident(alert_id, status, phase, last_seq, report_id, created_at, updated_at) " +
            "VALUES(#{alertId}, #{status}, #{phase}, #{lastSeq}, #{reportId}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Incident incident);

    /**
     * 全量更新状态相关字段（status/phase/last_seq/report_id/completed_at/error_message/updated_at）
     */
    @Update("UPDATE incident SET status = #{status}, phase = #{phase}, last_seq = #{lastSeq}, report_id = #{reportId}, " +
            "completed_at = #{completedAt}, error_message = #{errorMessage}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(Incident incident);

    /**
     * 崩溃恢复扫描：查询状态为 RUNNING 且 updated_at 早于 threshold 的记录（threshold 为 yyyy-MM-dd HH:mm:ss）
     */
    @Select("SELECT id, alert_id, status, phase, last_seq, report_id, created_at, updated_at, completed_at, error_message " +
            "FROM incident WHERE status = 'RUNNING' AND updated_at < #{threshold}")
    List<Incident> selectStaleRunning(String threshold);

    /**
     * 查询全部分析事件
     */
    @Select("SELECT id, alert_id, status, phase, last_seq, report_id, created_at, updated_at, completed_at, error_message " +
            "FROM incident ORDER BY id")
    List<Incident> selectAll();

    /**
     * 清空表（仅测试用）
     */
    @Delete("DELETE FROM incident")
    int deleteAll();
}
