-- 本地开发种子数据（MERGE 幂等，重启不重复）。生产库由迁移脚本管理，不执行本文件。
MERGE INTO policy_snapshot(policy_no, effective_date, expiry_date, coverage_types, limit_cents, deductible_cents, depreciation_percent, clause_version) KEY(policy_no) VALUES
('P-2026-0001', '2026-01-01', '2026-12-31', 'COLLISION,THEFT,FLOOD,FIRE', 200000000, 50000, 10, 'clause-2026-v1'),
('P-2026-0002', '2026-01-01', '2026-12-31', 'COLLISION,THEFT,FLOOD,FIRE', 200000000, 50000, 10, 'clause-2026-v1'),
('P-2026-0003', '2026-02-01', '2027-01-31', 'COLLISION,THEFT', 300000000, 100000, 15, 'clause-2026-v1'),
('P-2026-0004', '2026-03-01', '2027-02-28', 'COLLISION,THEFT,FLOOD', 200000000, 50000, 10, 'clause-2026-v1'),
('P-2026-0005', '2026-01-01', '2026-12-31', 'COLLISION,THEFT,FLOOD,FIRE', 200000000, 50000, 10, 'clause-2026-v1'),
('P-2026-0006', '2026-05-01', '2027-04-30', 'COLLISION,THEFT,FLOOD,FIRE', 250000000, 80000, 12, 'clause-2026-v1');

MERGE INTO part_price(id, model_code, part_name, price_cents, source_id) KEY(id) VALUES
(1, 'VW-LAVIDA-2023', '前保险杠', 180000, 'PP-1001'),
(2, 'VW-LAVIDA-2023', '左前大灯', 240000, 'PP-1002'),
(3, 'VW-LAVIDA-2023', '引擎盖', 320000, 'PP-1003'),
(4, 'VW-LAVIDA-2023', '前翼子板', 95000, 'PP-1004'),
(5, 'TOYOTA-CAMRY-2024', '前保险杠', 210000, 'PP-2001'),
(6, 'TOYOTA-CAMRY-2024', '左前大灯', 310000, 'PP-2002'),
(7, 'TOYOTA-CAMRY-2024', '左前车门', 280000, 'PP-2003');

MERGE INTO claim_case(case_no, policy_no, org, insurance_type, accident_date, accident_type, status, channel, estimate_cents, payable_cents, risk_score, recognition_confidence, reject_reason, description, created_at, updated_at) KEY(case_no) VALUES
('CLM-20261118-0001', 'P-2026-0001', 'HEAD', 'AUTO', '2026-11-17', 'COLLISION', 'CLOSED', 'FAST', 420000, 420000, 8, 0.93, NULL, '追尾前车，本车全责，前保险杠与左前大灯受损，车型 VW-LAVIDA-2023', 1794992400000, 1794994200000),
('CLM-20261118-0002', 'P-2026-0002', 'HEAD', 'AUTO', '2026-11-18', 'COLLISION', 'APPROVING', 'STANDARD', 3200000, 2850000, 25, 0.90, NULL, '停车场剐蹭，多处覆盖件损伤，车型 TOYOTA-CAMRY-2024', 1794996000000, 1794999600000),
('CLM-20261118-0003', 'P-2026-0003', 'HEAD', 'AUTO', '2026-11-18', 'COLLISION', 'MANUAL', 'INVESTIGATE', 6800000, 0, 85, 0.88, NULL, '夜间单方事故，修理厂报价异常，申请高额赔付', 1794988800000, 1795003200000),
('CLM-20261118-0004', 'P-2026-0004', 'HEAD', 'AUTO', '2026-11-18', 'COLLISION', 'MATERIALS_PENDING', NULL, 0, 0, 0, 0, NULL, '路口碰撞，事故认定书已传，损伤照片待补', 1795000000000, 1795000000000),
('CLM-20261117-0005', 'P-2026-0005', 'HEAD', 'AUTO', '2026-11-16', 'COLLISION', 'REJECTED', NULL, 0, 0, 40, 0.95, 'EXCL-DRUNK', '酒后驾车撞护栏，交警认定酒驾', 1794906000000, 1794913200000),
('CLM-20261118-0006', 'P-2026-0006', 'HEAD', 'AUTO', '2026-11-18', 'FLOOD', 'ASSESSING', NULL, 0, 0, 10, 0, NULL, '暴雨涉水行驶，发动机进水，车型 TOYOTA-CAMRY-2024', 1795001000000, 1795001000000);

MERGE INTO claim_material(id, material_id, case_no, type, storage_key, sha256, confidence) KEY(id) VALUES
(1, 'CLM-20261118-0001-M-01', 'CLM-20261118-0001', 'ACCIDENT_REPORT', 'seed/0001-report.pdf', 'seedhash01', 0.95),
(2, 'CLM-20261118-0001-M-02', 'CLM-20261118-0001', 'DAMAGE_PHOTO', 'seed/0001-photo.jpg', 'seedhash02', 0.93),
(3, 'CLM-20261118-0002-M-01', 'CLM-20261118-0002', 'ACCIDENT_REPORT', 'seed/0002-report.pdf', 'seedhash03', 0.92),
(4, 'CLM-20261118-0002-M-02', 'CLM-20261118-0002', 'DAMAGE_PHOTO', 'seed/0002-photo.jpg', 'seedhash04', 0.90),
(5, 'CLM-20261118-0003-M-01', 'CLM-20261118-0003', 'ACCIDENT_REPORT', 'seed/0003-report.pdf', 'seedhash05', 0.90),
(6, 'CLM-20261118-0003-M-02', 'CLM-20261118-0003', 'DAMAGE_PHOTO', 'seed/0003-photo.jpg', 'seedhash06', 0.88),
(7, 'CLM-20261118-0003-M-03', 'CLM-20261118-0003', 'REPAIR_QUOTE', 'seed/0003-quote.pdf', 'seedhash07', 0.86),
(8, 'CLM-20261118-0004-M-01', 'CLM-20261118-0004', 'ACCIDENT_REPORT', 'seed/0004-report.pdf', 'seedhash08', 0.94),
(9, 'CLM-20261117-0005-M-01', 'CLM-20261117-0005', 'ACCIDENT_REPORT', 'seed/0005-report.pdf', 'seedhash09', 0.96),
(10, 'CLM-20261117-0005-M-02', 'CLM-20261117-0005', 'DAMAGE_PHOTO', 'seed/0005-photo.jpg', 'seedhash10', 0.95),
(11, 'CLM-20261118-0006-M-01', 'CLM-20261118-0006', 'ACCIDENT_REPORT', 'seed/0006-report.pdf', 'seedhash11', 0.91),
(12, 'CLM-20261118-0006-M-02', 'CLM-20261118-0006', 'DAMAGE_PHOTO', 'seed/0006-photo.jpg', 'seedhash12', 0.89);

MERGE INTO repair_shop_flag(id, case_no, shop_name, reason, source) KEY(id) VALUES
(1, 'CLM-20261118-0003', '宏达汽修', '近90天3起同类高额案件，图谱关联同一账户', 'MANUAL');

MERGE INTO graph_case_shop(id, case_no, shop_name, source, ts) KEY(id) VALUES
(1, 'CLM-20261118-0003', '宏达汽修', 'INTAKE', 1794988800000),
(2, 'CLM-20261118-0002', '宏达汽修', 'INTAKE', 1794996000000),
(3, 'CLM-20261118-0004', '宏达汽修', 'INTAKE', 1795000000000);

MERGE INTO claim_approval(id, case_no, step, operator, approved, comment, amount_cents, ts) KEY(id) VALUES
(1, 'CLM-20261117-0005', 'REJECT_CONFIRM', '核赔师-王敏', 1, '酒驾免责条款成立，确认拒赔', 0, 1794913200000);

MERGE INTO ledger_business(id, case_no, batch, direction, amount_cents, ts) KEY(id) VALUES
(1, 'CLM-20261118-0001', 1, 'PAYABLE', 420000, 1794994000000);

MERGE INTO ledger_fund(id, case_no, batch, fund_channel, direction, amount_cents, ts) KEY(id) VALUES
(1, 'CLM-20261118-0001', 1, 'BANK_DIRECT', 'OUT', 420000, 1794994000000);

MERGE INTO pay_outbox(idem_key, case_no, batch, amount_cents, fund_channel, status, ts) KEY(idem_key) VALUES
('CLM-20261118-0001:PAY:1', 'CLM-20261118-0001', 1, 420000, 'BANK_DIRECT', 'COMMITTED', 1794994000000);

MERGE INTO core_register(case_no, result, ts) KEY(case_no) VALUES
('CLM-20261118-0001', 'OK', 1794994100000);

MERGE INTO claim_decision(id, trace_id, case_no, step, actor, model_version, prompt_version, input_hash, output_json, confidence, prompt_tokens, completion_tokens, ts) KEY(id) VALUES
(1, 'seed-0001-01', 'CLM-20261118-0001', 'REPORT', 'claim-pipeline', NULL, NULL, NULL, '追尾前车，本车全责，前保险杠与左前大灯受损', NULL, 0, 0, 1794992400000),
(2, 'seed-0001-02', 'CLM-20261118-0001', 'COVERAGE', 'claim-pipeline', 'clause-2026-v1', NULL, NULL, '{"covered":true,"hitClauses":["CLAUSE-PERIOD-OK","CLAUSE-TYPE-OK"],"exclusions":[]}', NULL, 0, 0, 1794992700000),
(3, 'seed-0001-03', 'CLM-20261118-0001', 'ASSESS', 'agent-assess', 'deepseek-flash', 'assess-v1', NULL, '{"items":[{"partName":"前保险杠","action":"REPLACE","priceCents":180000,"priceSourceId":"PP-1001"},{"partName":"左前大灯","action":"REPLACE","priceCents":240000,"priceSourceId":"PP-1002"}],"estimateCents":420000}', 0.93, 1800, 420, 1794993000000),
(4, 'seed-0001-04', 'CLM-20261118-0001', 'ROUTE', 'claim-pipeline', NULL, NULL, NULL, '{"channel":"FAST","risk":8}', NULL, 0, 0, 1794993300000),
(5, 'seed-0001-05', 'CLM-20261118-0001', 'CALCULATE', 'claim-pipeline', NULL, NULL, NULL, '{"payableCents":420000,"breakdown":"定损420000 - 折旧0 = 420000；×责任100% = 420000；- 免赔0 = 420000；限额200000000 → 赔付420000（分）"}', NULL, 0, 0, 1794993600000),
(6, 'seed-0001-06', 'CLM-20261118-0001', 'PAY', 'claim-pipeline', NULL, NULL, NULL, '{"amountCents":420000,"idemKey":"CLM-20261118-0001:PAY:1"}', NULL, 0, 0, 1794994000000),
(7, 'seed-0001-07', 'CLM-20261118-0001', 'SETTLED', 'claim-pipeline', NULL, NULL, NULL, '{"kind":"SETTLED"}', NULL, 0, 0, 1794994200000),
(8, 'seed-0002-01', 'CLM-20261118-0002', 'REPORT', 'claim-pipeline', NULL, NULL, NULL, '停车场剐蹭，多处覆盖件损伤', NULL, 0, 0, 1794996000000),
(9, 'seed-0002-02', 'CLM-20261118-0002', 'ASSESS', 'agent-assess', 'deepseek-flash', 'assess-v1', NULL, '{"estimateCents":3200000,"items":[{"partName":"左前车门","action":"REPLACE","priceCents":280000,"priceSourceId":"PP-2003"}]}', 0.90, 2100, 510, 1794998000000),
(10, 'seed-0002-03', 'CLM-20261118-0002', 'CALCULATE', 'claim-pipeline', NULL, NULL, NULL, '{"payableCents":2850000}', NULL, 0, 0, 1794999600000),
(11, 'seed-0003-01', 'CLM-20261118-0003', 'FRAUD_SCORE', 'claim-pipeline', 'rules-v1', NULL, NULL, '{"riskScore":85,"priorClaims":2,"flaggedRepairShop":true}', NULL, 0, 0, 1795000000000),
(12, 'seed-0003-02', 'CLM-20261118-0003', 'MANUAL', 'claim-pipeline', NULL, NULL, NULL, '高风险/大额，SIU 人工调查', NULL, 0, 0, 1795003200000),
(13, 'seed-0005-01', 'CLM-20261117-0005', 'COVERAGE', 'claim-pipeline', 'clause-2026-v1', NULL, NULL, '{"covered":false,"exclusions":["EXCL-DRUNK"]}', NULL, 0, 0, 1794909600000),
(14, 'seed-0005-02', 'CLM-20261117-0005', 'REJECT', 'claim-pipeline', NULL, NULL, NULL, 'EXCL-DRUNK', NULL, 0, 0, 1794913200000);