CREATE TABLE IF NOT EXISTS alert (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_name VARCHAR(191) NOT NULL,
    severity VARCHAR(64) NOT NULL,
    service VARCHAR(191) NOT NULL,
    starts_at VARCHAR(19) NOT NULL,
    labels TEXT,
    incident_id BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_key (service, alert_name, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS analysis_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    root_cause_hypothesis TEXT,
    confidence DOUBLE,
    risk_level VARCHAR(16),
    recommended_actions TEXT,
    evidence_chain TEXT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS incident (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    report_id BIGINT DEFAULT NULL,
    created_at VARCHAR(19) NOT NULL,
    completed_at VARCHAR(19) DEFAULT NULL,
    error_message VARCHAR(1024) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_incident_alert FOREIGN KEY (alert_id) REFERENCES alert (id),
    CONSTRAINT fk_incident_report FOREIGN KEY (report_id) REFERENCES analysis_report (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
