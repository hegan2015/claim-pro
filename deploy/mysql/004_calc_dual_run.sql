-- 004: 理算双跑比对表（F6 LLM 草案 vs 确定性计算器）。存量库增量 DDL，只跑一次；新环境 001 已含。
USE claim;
CREATE TABLE IF NOT EXISTS calc_dual_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(40),
    calc_payable_cents BIGINT,
    draft_payable_cents BIGINT,
    diff_cents BIGINT,
    is_match INT,
    draft_json VARCHAR(2000),
    ts BIGINT,
    INDEX idx_dual_run_case (case_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;