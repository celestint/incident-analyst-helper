package com.lyl.backend.mapper;

import com.lyl.backend.model.IncidentEvent;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 过程事件表（incident_events）数据访问，只追加不修改
 */
@Mapper
public interface IncidentEventMapper {

    /**
     * 追加事件，回填自增主键。seq 由调用方在事务内分配（incident.last_seq + 1）
     */
    @Insert("INSERT INTO incident_events(incident_id, seq, event_type, payload, created_at) " +
            "VALUES(#{incidentId}, #{seq}, #{eventType}, #{payload}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IncidentEvent event);

    /**
     * 重放用：取某 incident 下 seq 大于 since 的事件，按 seq 升序返回
     */
    @Select("SELECT id, incident_id, seq, event_type, payload, created_at " +
            "FROM incident_events WHERE incident_id = #{incidentId} AND seq > #{since} ORDER BY seq")
    List<IncidentEvent> selectAfter(Long incidentId, int since);

    /**
     * 删除某 incident 的全部事件（仅测试用）
     */
    @Delete("DELETE FROM incident_events WHERE incident_id = #{incidentId}")
    int deleteByIncidentId(Long incidentId);

    /**
     * 思考循环失控信号：同一 incident 内完全相同的 agent_thought payload 出现 ≥2 次的 incident 集合。
     * 重复的工具调用被内存去重拦下不落库，重复思考会落库，是可观测的循环痕迹
     */
    @Select("SELECT DISTINCT incident_id AS incidentId FROM incident_events " +
            "WHERE event_type = 'agent_thought' GROUP BY incident_id, payload HAVING COUNT(*) >= 2")
    List<Long> selectThoughtLoopIncidentIds();

    /**
     * 全部工具结果事件（评测指标：工具成功率，success 字段在 Java 侧解析）
     */
    @Select("SELECT incident_id AS incidentId, payload FROM incident_events WHERE event_type = 'tool_call_result'")
    List<Map<String, Object>> selectToolResultRows();

    /**
     * 清空表（仅测试用）
     */
    @Delete("DELETE FROM incident_events")
    int deleteAll();
}
