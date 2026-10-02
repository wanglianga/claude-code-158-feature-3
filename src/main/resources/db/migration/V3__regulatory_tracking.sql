-- 监管抽查追踪扩展
-- 1) 现场检查任务：长期无回收的疑似违规商户自动生成，监管回填照片/约谈结果/整改期限
-- 2) 门店动态风险画像：风险状态随回收情况逐步升降，持续无回收进入重点巡查名单

-- ============ 现场检查任务 ============
CREATE TABLE site_inspection_tasks (
    id                 BIGSERIAL PRIMARY KEY,
    task_no            VARCHAR(32)   NOT NULL UNIQUE,   -- 任务编号，如 SI20261002-0001
    store_id           BIGINT        NOT NULL REFERENCES stores (id),
    merchant_id        BIGINT        NOT NULL REFERENCES merchants (id),
    source             VARCHAR(16)   NOT NULL,          -- RISK_SCAN 巡查扫描自动生成 / MANUAL 监管手工发起
    reason             VARCHAR(1024) NOT NULL,         -- 疑似违规事由
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
        -- PENDING 待现场检查 / RECTIFYING 责令整改中 / COMPLETED 现场核实合规已完成 / VERIFIED_CLOSED 整改复核通过关闭
    inspector          VARCHAR(64),                    -- 指派监管人员
    check_result       VARCHAR(20),                    -- COMPLIANT 合规 / NON_COMPLIANT 违规 / RECTIFYING 责令整改
    photo_urls         VARCHAR(2048),                  -- 现场检查照片（多个 URL 逗号分隔）
    interview_result   VARCHAR(2048),                  -- 约谈结果
    rectify_deadline   DATE,                           -- 整改期限
    inspected_by       VARCHAR(64),
    inspected_at       TIMESTAMPTZ,
    close_note         VARCHAR(1024),                  -- 整改复核结论
    closed_by          VARCHAR(64),
    closed_at          TIMESTAMPTZ,
    last_recycling_at  TIMESTAMPTZ,                    -- 生成任务时该商户最近一次合规回收时间
    days_no_recycling  INT,                            -- 生成任务时已连续无回收天数
    version            INT           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_site_insp_store  ON site_inspection_tasks (store_id);
CREATE INDEX idx_site_insp_status ON site_inspection_tasks (status);

CREATE SEQUENCE site_inspection_no_seq START 1;

-- ============ 门店动态风险画像（风险逐步升降 / 重点巡查名单） ============
CREATE TABLE store_risk_profiles (
    store_id                       BIGINT PRIMARY KEY REFERENCES stores (id),
    risk_level                     VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
        -- NORMAL 正常 / WATCH 关注（疑似违规） / KEY 重点巡查
    risk_score                     INT         NOT NULL DEFAULT 0,   -- 动态风险分 0-100
    key_patrol                     BOOLEAN     NOT NULL DEFAULT FALSE, -- 是否列入重点巡查名单
    consecutive_no_recycling_scans INT         NOT NULL DEFAULT 0,   -- 连续无回收巡查扫描次数
    normal_recycling_streak        INT         NOT NULL DEFAULT 0,   -- 恢复正常回收后的连续扫描次数
    last_recycling_at              TIMESTAMPTZ,                      -- 最近一次合规回收时间
    last_scanned_at                TIMESTAMPTZ,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_profile_level ON store_risk_profiles (risk_level);
CREATE INDEX idx_risk_profile_patrol ON store_risk_profiles (key_patrol);

-- ============ 演示数据：开业 40 天、从未交运过废油的餐饮门店 ============
-- 监管巡查扫描首扫即命中 WATCH（>=30 天无回收）并自动生成现场检查任务
INSERT INTO stores (id, merchant_id, name, address, district, open_time, close_time,
                    contact_name, contact_phone, storage_location, scale_tier,
                    expected_daily_oil_kg, qr_code, status, created_at) VALUES
    (4, 2, '老城夜市小炒店', '夜市街 18 号', '老城夜市', '10:00', '23:00',
     '赵老板', '13900000004', '后厨后门角落', 'SMALL', 12.00, 'STORE-QR-4', 'ACTIVE',
     now() - interval '40 days');

INSERT INTO barrels (id, code, capacity_kg, store_id, status) VALUES
    (9,  'BAR-301', 150, 4, 'AT_STORE'),
    (10, 'BAR-302', 150, 4, 'AT_STORE');

INSERT INTO weighing_devices (id, code, store_id, last_heartbeat_at, status) VALUES
    (4, 'DEV-004', 4, now(), 'ONLINE');

-- 序列与显式 id 对齐
SELECT setval('stores_id_seq', 10);
SELECT setval('barrels_id_seq', 20);
SELECT setval('weighing_devices_id_seq', 10);
