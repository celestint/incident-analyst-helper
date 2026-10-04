package com.lyl.backend.mapper;

import com.lyl.backend.model.IncidentEvent;
import org.apache.ibatis.annotations.*;

import java.util.List;

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
     * 清空表（仅测试用）
     */
    @Delete("DELETE FROM incident_events")
    int deleteAll();
}
