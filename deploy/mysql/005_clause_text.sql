-- 005: 条款原文库（F3 核保解释 RAG 引用条款原文）。存量库增量 DDL+种子，幂等可重跑；新环境 001/002 已含。
USE claim;
CREATE TABLE IF NOT EXISTS clause_text (
    clause_id VARCHAR(40) PRIMARY KEY,
    version VARCHAR(32),
    code VARCHAR(40),
    title VARCHAR(128),
    body VARCHAR(2000),
    effective_from DATE,
    effective_to DATE,
    INDEX idx_clause_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO clause_text(clause_id, version, code, title, body, effective_from, effective_to) VALUES
('CT-001','clause-2026-v1','CLAUSE-PERIOD-OK','保险期间条款','被保险机动车在本保单载明的保险期间内发生保险事故的，保险人依照本保险合同的约定负责赔偿。','2026-01-01','2026-12-31'),
('CT-002','clause-2026-v1','CLAUSE-TYPE-OK','承保事故类型条款','本保单承保的事故类型以保单载明为准（碰撞/盗抢/水淹/火灾）。属于承保类型的事故，保险人负责赔偿。','2026-01-01','2026-12-31'),
('CT-003','clause-2026-v1','EXCL-PERIOD','保险期间外免责','保险期间外发生的事故，不属于保险责任，保险人不负责赔偿。','2026-01-01','2026-12-31'),
('CT-004','clause-2026-v1','EXCL-TYPE','事故类型不符免责','不属于本保单承保事故类型的损失，保险人不负责赔偿。','2026-01-01','2026-12-31'),
('CT-005','clause-2026-v1','EXCL-DRUNK','酒后驾驶免责','驾驶人饮酒、吸食或注射毒品、服用国家管制的精神药品或者麻醉药品后使用被保险机动车的，保险人不负责赔偿。','2026-01-01','2026-12-31'),
('CT-006','clause-2026-v1','EXCL-UNLICENSED','无证驾驶免责','无驾驶证，或驾驶证被依法扣留、暂扣、吊销、注销期间驾驶被保险机动车的，保险人不负责赔偿。','2026-01-01','2026-12-31'),
('CT-007','clause-2026-v1','EXCL-INTENTIONAL','故意行为免责','被保险人或驾驶人的故意行为造成被保险机动车的损失，保险人不负责赔偿。','2026-01-01','2026-12-31'),
('CT-008','clause-2026-v1','CLAUSE-CALC','理算公式条款','赔款 = min(保险限额, (核定损失 - 折旧) × 事故责任比例 - 免赔额)，最低为0。折旧率与免赔额以保单载明为准。','2026-01-01','2026-12-31');
