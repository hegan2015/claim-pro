-- 006: 影子双跑运行记录表。存量库增量 DDL，幂等可重跑；新环境 001 已含。
USE claim;
CREATE TABLE IF NOT EXISTS shadow_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts BIGINT,
    config_json VARCHAR(1000),
    total INT,
    drift_count INT,
    samples_json TEXT,
    duration_ms BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
