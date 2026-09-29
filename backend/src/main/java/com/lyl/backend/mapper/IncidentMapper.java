package com.lyl.backend.mapper;

import com.lyl.backend.model.Incident;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IncidentMapper {

    @Select("SELECT id, alert_id, status, report_id, created_at, completed_at FROM incident WHERE id = #{id}")
    Incident selectById(Long id);
}
