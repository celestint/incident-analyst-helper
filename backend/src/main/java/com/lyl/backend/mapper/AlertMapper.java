package com.lyl.backend.mapper;

import com.lyl.backend.model.Alert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertMapper {

    @Select("SELECT id, alert_name, severity, service, starts_at, labels FROM alert WHERE id = #{id}")
    Alert selectById(Long id);
}
