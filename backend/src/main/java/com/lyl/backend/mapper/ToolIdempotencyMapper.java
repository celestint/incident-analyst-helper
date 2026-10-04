package com.lyl.backend.mapper;

import com.lyl.backend.model.ToolIdempotency;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 工具幂等表（tool_idempotency）数据访问，工具执行后新增、不更新
 */
@Mapper
public interface ToolIdempotencyMapper {

    /**
     * 按幂等键查询，命中说明该工具调用已执行过，直接复用 result
     */
    @Select("SELECT idempotency_key, incident_id, step_seq, tool_name, result, created_at " +
            "FROM tool_idempotency WHERE idempotency_key = #{idempotencyKey}")
    ToolIdempotency selectByKey(String idempotencyKey);

    /**
     * 按 incident 查询全部幂等记录（测试与排查用）
     */
    @Select("SELECT idempotency_key, incident_id, step_seq, tool_name, result, created_at " +
            "FROM tool_idempotency WHERE incident_id = #{incidentId}")
    List<ToolIdempotency> selectByIncidentId(Long incidentId);

    /**
     * 写入幂等记录。幂等键冲突时抛 DuplicateKeyException，由调用方处理
     */
    @Insert("INSERT INTO tool_idempotency(idempotency_key, incident_id, step_seq, tool_name, result, created_at) " +
            "VALUES(#{idempotencyKey}, #{incidentId}, #{stepSeq}, #{toolName}, #{result}, #{createdAt})")
    int insert(ToolIdempotency record);

    /**
     * 删除某 incident 的全部幂等记录（仅测试用）
     */
    @Delete("DELETE FROM tool_idempotency WHERE incident_id = #{incidentId}")
    int deleteByIncidentId(Long incidentId);

    /**
     * 清空表（仅测试用）
     */
    @Delete("DELETE FROM tool_idempotency")
    int deleteAll();
}
