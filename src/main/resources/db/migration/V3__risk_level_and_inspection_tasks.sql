-- 监管抽查追踪扩展：风险分级（关注 / 重点巡查）+ 现场检查任务

-- ============ 风险名单：增加分级与升级/恢复追踪字段 ============
ALTER TABLE risk_entries
    ADD COLUMN risk_level                     VARCHAR(16) NOT NULL DEFAULT 'WATCH',   -- WATCH 疑似违规(关注) / KEY_PATROL 重点巡查
    ADD COLUMN consecutive_no_recycling_cycles INT        NOT NULL DEFAULT 0,         -- 连续无回收的扫描周期数
    ADD COLUMN last_pickup_at                 TIMESTAMPTZ,                            -- 最近一次合规回收时间
    ADD COLUMN last_pickup_manifest_id        BIGINT,                                 -- 最近一次已计入降级的回收批次(联单)ID
    ADD COLUMN recovered_at                   TIMESTAMPTZ,                            -- 恢复正常回收的起始时间（用于逐步降级）
    ADD COLUMN last_evaluated_at              TIMESTAMPTZ;

-- 存量风险条目（如抽查不合格）默认归入关注名单
UPDATE risk_entries SET risk_level = 'WATCH' WHERE risk_level IS NULL;

CREATE INDEX idx_risk_level ON risk_entries (risk_level);
CREATE INDEX idx_risk_store_status ON risk_entries (store_id, status);

-- ============ 现场检查任务 ============
-- 长期无回收被标记疑似违规后自动生成；监管人员回填现场照片、约谈结果、整改期限。
CREATE TABLE inspection_tasks (
    id                     BIGSERIAL PRIMARY KEY,
    task_no                VARCHAR(32)   NOT NULL UNIQUE,   -- 检查任务编号，如 INS20261002-0001
    store_id               BIGINT        NOT NULL REFERENCES stores (id),
    risk_entry_id          BIGINT        REFERENCES risk_entries (id),  -- 触发任务的风险条目
    trigger_reason         VARCHAR(32)   NOT NULL,           -- NO_RECYCLING 长期无回收 / KEY_PATROL 重点巡查 / MANUAL 人工发起
    status                 VARCHAR(16)   NOT NULL DEFAULT 'OPEN',  -- OPEN/IN_PROGRESS/COMPLETED/OVERDUE/CANCELLED
    inspector              VARCHAR(64),                      -- 承办监管人员
    due_date               DATE,                             -- 要求完成现场检查的期限
    inspection_photo_urls  VARCHAR(4096),                    -- 现场检查照片（多个 URL 逗号分隔）
    interview_result       VARCHAR(2048),                    -- 约谈结果
    rectification_deadline DATE,                             -- 责令整改期限
    inspection_result      VARCHAR(16),                      -- QUALIFIED 合格 / UNQUALIFIED 不合格
    note                   VARCHAR(2048),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ
);
CREATE INDEX idx_inspection_tasks_store ON inspection_tasks (store_id);
CREATE INDEX idx_inspection_tasks_status ON inspection_tasks (status);

CREATE SEQUENCE inspection_task_no_seq START 1;
