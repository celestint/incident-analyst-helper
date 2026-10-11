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
    /** 评测打标（三个独立布尔，可组合）：正式=isProd；评测集=isEval；测试=isTest。统计默认只算正式。新告警默认正式 */
    private Boolean isProd = true;
    private Boolean isEval = false;
    private Boolean isTest = false;
}
