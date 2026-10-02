package com.lyl.backend.model;

import lombok.Data;

@Data
public class Alert {
    private Long id;
    private String alertName;
    private String severity;
    private String service;
    private String startsAt;
    private String labels;
    private Long incidentId;
}
