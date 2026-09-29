package com.lyl.backend.model;

import lombok.Data;

@Data
public class Incident {
    private Long id;
    private Long alertId;
    private String status;
    private Long reportId;
    private String createdAt;
    private String completedAt;
}
