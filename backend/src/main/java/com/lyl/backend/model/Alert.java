package com.lyl.backend.model;

import lombok.Data;

@Data
public class Alert {
    private Long id;
    private String alertName;
    private String severity;
    private String service;
    private String startsAt;
    /** 告警结束时间，为空表示未结束 */
    private String endsAt;
    private String labels;
    private Long incidentId;
}
