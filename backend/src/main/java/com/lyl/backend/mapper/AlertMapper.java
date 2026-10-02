package com.lyl.backend.mapper;

import com.lyl.backend.model.Alert;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AlertMapper {

    @Select("SELECT id, alert_name, severity, service, starts_at, labels, incident_id FROM alert WHERE id = #{id}")
    Alert selectById(Long id);

    @Select("SELECT id, alert_name, severity, service, starts_at, labels, incident_id FROM alert WHERE incident_id IS NULL ORDER BY id")
    List<Alert> selectPendingAlerts();

    @Insert("INSERT INTO alert(alert_name, severity, service, starts_at, labels, incident_id) " +
            "VALUES(#{alertName}, #{severity}, #{service}, #{startsAt}, #{labels}, #{incidentId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Alert alert);

    @Select("SELECT id, alert_name, severity, service, starts_at, labels, incident_id FROM alert WHERE id = #{id}")
    Alert selectByIdForUpdate(Long id);

    @Select("SELECT id, alert_name, severity, service, starts_at, labels, incident_id FROM alert ORDER BY id")
    List<Alert> selectAll();

    @Update("UPDATE alert SET incident_id = #{incidentId} WHERE id = #{id}")
    int updateIncidentId(Long id, Long incidentId);

    @Delete("DELETE FROM alert")
    int deleteAll();
}
