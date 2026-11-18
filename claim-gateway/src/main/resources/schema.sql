CREATE TABLE IF NOT EXISTS claim_case (
    case_no VARCHAR(64) PRIMARY KEY,
    policy_no VARCHAR(64),
    org VARCHAR(32),
    insurance_type VARCHAR(32),
    accident_date DATE,
    accident_type VARCHAR(32),
    status VARCHAR(32),
    channel VARCHAR(32),
    estimate_cents BIGINT DEFAULT 0,
    payable_cents BIGINT DEFAULT 0,
    risk_score INT DEFAULT 0,
    recognition_confidence DOUBLE DEFAULT 0,
    reject_reason VARCHAR(512),
    description VARCHAR(2000),
    created_at BIGINT,
    updated_at BIGINT
);

CREATE TABLE IF NOT EXISTS claim_material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id VARCHAR(96) UNIQUE,
    case_no VARCHAR(64),
    type VARCHAR(32),
    storage_key VARCHAR(256),
    sha256 VARCHAR(64),
    confidence DOUBLE DEFAULT 0
);

CREATE TABLE IF NOT EXISTS claim_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    step VARCHAR(32),
    operator VARCHAR(64),
    approved INT,
    comment VARCHAR(512),
    amount_cents BIGINT DEFAULT 0,
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS ledger_business (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    batch INT,
    direction VARCHAR(16),
    amount_cents BIGINT,
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS ledger_fund (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    batch INT,
    fund_channel VARCHAR(32),
    direction VARCHAR(16),
    amount_cents BIGINT,
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS pay_outbox (
    idem_key VARCHAR(128) PRIMARY KEY,
    case_no VARCHAR(64),
    batch INT,
    amount_cents BIGINT,
    fund_channel VARCHAR(32),
    status VARCHAR(16),
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS recon_diff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    batch INT,
    biz_amount BIGINT,
    fund_amount BIGINT,
    status VARCHAR(16),
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS policy_snapshot (
    policy_no VARCHAR(64) PRIMARY KEY,
    effective_date DATE,
    expiry_date DATE,
    coverage_types VARCHAR(256),
    limit_cents BIGINT,
    deductible_cents BIGINT,
    depreciation_percent INT,
    clause_version VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS part_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_code VARCHAR(64),
    part_name VARCHAR(64),
    price_cents BIGINT,
    source_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS repair_shop_flag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    shop_name VARCHAR(128),
    reason VARCHAR(256)
);

CREATE TABLE IF NOT EXISTS evidence_index (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    seq INT,
    evidence_id VARCHAR(96),
    object_key VARCHAR(256),
    sha256 VARCHAR(64),
    prev_hash VARCHAR(64),
    step VARCHAR(32),
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS claim_decision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64),
    case_no VARCHAR(64),
    step VARCHAR(32),
    actor VARCHAR(64),
    model_version VARCHAR(64),
    prompt_version VARCHAR(64),
    input_hash VARCHAR(64),
    output_json TEXT,
    confidence DOUBLE,
    prompt_tokens BIGINT DEFAULT 0,
    completion_tokens BIGINT DEFAULT 0,
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS tool_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name VARCHAR(64),
    case_no VARCHAR(64),
    args_json VARCHAR(2000),
    result_json VARCHAR(2000),
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS core_register (
    case_no VARCHAR(64) PRIMARY KEY,
    result VARCHAR(64),
    ts BIGINT
);

-- ── 风控批处理：欺诈关联图谱（P0 关系库存储，生产同步 NebulaGraph）──
-- graph_case_shop = 案件↔修理厂关联（报案/定损登记）；repair_shop_flag = 风险标记登记表
-- source 区分人工标记(MANUAL)与图谱批处理自动标记(GRAPH_BATCH)，重建只清后者
ALTER TABLE repair_shop_flag ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'MANUAL';

CREATE TABLE IF NOT EXISTS graph_case_shop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(64),
    shop_name VARCHAR(128),
    source VARCHAR(16) DEFAULT 'INTAKE',
    ts BIGINT,
    CONSTRAINT uq_case_shop UNIQUE (case_no, shop_name)
);

CREATE TABLE IF NOT EXISTS graph_node (
    node_type VARCHAR(16),
    node_key VARCHAR(128),
    label VARCHAR(256),
    props_json VARCHAR(2000),
    ts BIGINT,
    PRIMARY KEY (node_type, node_key)
);

CREATE TABLE IF NOT EXISTS graph_edge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    src_type VARCHAR(16),
    src_key VARCHAR(128),
    dst_type VARCHAR(16),
    dst_key VARCHAR(128),
    edge_type VARCHAR(32),
    ts BIGINT
);

CREATE TABLE IF NOT EXISTS graph_build_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts BIGINT,
    trigger_by VARCHAR(16),
    node_count INT,
    edge_count INT,
    shop_count INT,
    new_flag_count INT,
    duration_ms BIGINT
);

-- ── 风控批处理：金标回归（确定性核心重放，漂移即红）──
CREATE TABLE IF NOT EXISTS gold_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts BIGINT,
    trigger_by VARCHAR(16),
    total INT,
    passed INT,
    failed INT,
    duration_ms BIGINT
);

CREATE TABLE IF NOT EXISTS gold_case_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT,
    gold_id VARCHAR(32),
    name VARCHAR(128),
    passed INT,
    mismatches_json VARCHAR(2000),
    ts BIGINT
);