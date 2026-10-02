package com.lyl.backend.mapper;

import com.lyl.backend.model.AnalysisReport;
import com.lyl.backend.model.Incident;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface IncidentMapper {

    @Select("SELECT id, alert_id, status, report_id, created_at, completed_at, error_message FROM incident WHERE id = #{id}")
    Incident selectById(Long id);

    @Select("SELECT id, alert_id, status, report_id, created_at, completed_at, error_message FROM incident WHERE alert_id = #{alertId}")
    Incident selectByAlertId(Long alertId);

    @Insert("INSERT INTO incident(alert_id, status, report_id, created_at, completed_at) " +
            "VALUES(#{alertId}, #{status}, #{reportId}, #{createdAt}, #{completedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Incident incident);

    @Update("UPDATE incident SET status = #{status}, report_id = #{reportId}, completed_at = #{completedAt} WHERE id = #{id}")
    int update(Incident incident);

    @Select("SELECT id, alert_id, status, report_id, created_at, completed_at, error_message FROM incident ORDER BY id")
    List<Incident> selectAll();

    @Delete("DELETE FROM incident")
    int deleteAll();
}
