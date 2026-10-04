CREATE TABLE IF NOT EXISTS alert (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    alert_name VARCHAR(191) NOT NULL,
    severity VARCHAR(64) NOT NULL,
    service VARCHAR(191) NOT NULL,
    starts_at VARCHAR(19) NOT NULL,
    labels TEXT,
    incident_id BIGINT UNSIGNED DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_key (service, alert_name, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS analysis_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    is_noise TINYINT(1) NOT NULL DEFAULT 0 COMMENT '事件摘要：是否噪音报警',
    needs_handling TINYINT(1) NOT NULL DEFAULT 0 COMMENT '事件摘要：是否需要处理',
    root_cause_hypothesis TEXT COMMENT '当前报警产生原因',
    confidence DOUBLE COMMENT '结论置信度 0~1',
    confidence_reason TEXT COMMENT '置信度理由：LLM 按评分锚点给出的依据说明',
    recommended_actions TEXT COMMENT '推荐SOP，JSON数组字符串 [{"priority":1,"action":"..."}]',
    judgment_logic TEXT COMMENT '判断逻辑：证据融入推理链的叙述文本，1. xxx 分行格式',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 既有库升级（MySQL 不支持 ADD COLUMN IF NOT EXISTS，已建过 analysis_report 表时手动执行一次）：
-- ALTER TABLE analysis_report ADD COLUMN confidence_reason TEXT COMMENT '置信度理由：LLM 按评分锚点给出的依据说明' AFTER confidence;

CREATE TABLE IF NOT EXISTS incident (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    alert_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT '保留粗粒度: PENDING/RUNNING/COMPLETED/FAILED',
    phase VARCHAR(32) NULL COMMENT '细粒度阶段：received/thinking/tool_running/finalizing/done',
    last_seq INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '该 Incident 已写入的最大事件序号，前端增量拉取游标',
    report_id BIGINT UNSIGNED DEFAULT NULL,
    created_at VARCHAR(19) NOT NULL,
    updated_at VARCHAR(19) NOT NULL COMMENT '最后更新时间，用于崩溃恢复时判断 RUNNING 是否超时',
    completed_at VARCHAR(19) DEFAULT NULL,
    error_message VARCHAR(1024) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_status_updated (status, updated_at),
    CONSTRAINT fk_incident_alert FOREIGN KEY (alert_id) REFERENCES alert (id),
    CONSTRAINT fk_incident_report FOREIGN KEY (report_id) REFERENCES analysis_report (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 如果以后要多实例并发，再加下面两个字段：
-- ALTER TABLE incident
--   ADD COLUMN lease_owner VARCHAR(64) NULL COMMENT '当前处理该 Incident 的 worker 标识',
--   ADD COLUMN lease_until DATETIME(3) NULL COMMENT '租约到期时间，到期后可被其他 worker 接管',
--   ADD INDEX idx_lease (lease_until);

CREATE TABLE IF NOT EXISTS incident_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    incident_id BIGINT UNSIGNED NOT NULL COMMENT '所属 Incident ID',
    seq INT UNSIGNED NOT NULL COMMENT 'Incident 内事件序号，从 1 开始，前端按此增量拉取',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型：incident_received/agent_thought/tool_call_start/tool_call_result/evidence_collected/report_finalized/error',
    payload TEXT NULL COMMENT '事件负载，存储为json格式的字符串。结构随 event_type 变化；如 tool_call_start 存 tool/args，evidence_collected 存 source/content/timestamp',
    created_at VARCHAR(19) NOT NULL COMMENT '事件发生时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_incident_seq (incident_id, seq),
    KEY idx_incident_time (incident_id, created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='Incident 过程事件表，只追加不修改，事件溯源核心表';


CREATE TABLE IF NOT EXISTS tool_idempotency (
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键：SHA256(incident_id + step_seq + tool_name + args，args 是这次工具调用传给工具的 JSON 参数)',
    incident_id     BIGINT UNSIGNED NOT NULL COMMENT '所属 Incident ID',
    step_seq        INT UNSIGNED NOT NULL COMMENT '该工具调用在 Incident 内的步骤序号',
    tool_name       VARCHAR(64) NOT NULL COMMENT '工具名称',
    result          MEDIUMTEXT NULL COMMENT '工具执行结果 JSON 字符串（兼容 MySQL 5.6，不用 JSON 类型），成功后写入，崩溃重试时直接复用',
    created_at      VARCHAR(19) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (idempotency_key),
    KEY idx_incident (incident_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COMMENT='工具幂等表，防止崩溃重试导致重复副作用，工具执行后新增，不更新，按保留期清除';
