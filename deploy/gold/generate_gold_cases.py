#!/usr/bin/env python3
"""生成扩充金标集（≥2000 案）。

⚠️ 合成金标：期望值由本脚本**独立实现需求文档规则**推导（Python 独立 oracle，
非调用生产 Java 代码反算），与生产共用同一份业务规格（REQUIREMENTS §5.2 / DESIGN §11）：
  核保（期间/险种/三法定免责）→ 欺诈评分（25/10/30cap/30/10）→ 定损门禁（空定损/索赔背离3倍/置信度0.85）
  → 三通道（深查→快赔→标准）→ 理算公式 → 审批分级（FAST 0 / >10万 2 / 其余 1）。
生产应以核赔师真实标注案逐步替换；本集用于回归防线规模化与阈值漂移检测。

运行：python3 deploy/gold/generate_gold_cases.py  （固定种子，可复现）
输出：claim-gateway/src/main/resources/gold-cases-extended.json
"""
import json
import os
import random

RNG = random.Random(20261120)
N = 2000

POLICIES = [
    {"policyNo": "P-GOLD-A", "effectiveDate": "2026-01-01", "expiryDate": "2026-12-31",
     "coverageTypes": ["COLLISION", "THEFT", "FLOOD", "FIRE"], "limitCents": 200000000,
     "deductibleCents": 50000, "depreciationPercent": 10, "clauseVersion": "clause-2026-v1"},
    {"policyNo": "P-GOLD-B", "effectiveDate": "2026-02-01", "expiryDate": "2027-01-31",
     "coverageTypes": ["COLLISION", "THEFT"], "limitCents": 300000000,
     "deductibleCents": 100000, "depreciationPercent": 15, "clauseVersion": "clause-2026-v1"},
    {"policyNo": "P-GOLD-C", "effectiveDate": "2026-01-01", "expiryDate": "2026-12-31",
     "coverageTypes": ["COLLISION", "FLOOD"], "limitCents": 200000000,
     "deductibleCents": 50000, "depreciationPercent": 10, "clauseVersion": "clause-2026-v1"},
    {"policyNo": "P-GOLD-D", "effectiveDate": "2026-01-01", "expiryDate": "2026-12-31",
     "coverageTypes": ["COLLISION"], "limitCents": 100000,
     "deductibleCents": 50000, "depreciationPercent": 10, "clauseVersion": "clause-2026-v1"},
    {"policyNo": "P-GOLD-E", "effectiveDate": "2026-03-01", "expiryDate": "2027-02-28",
     "coverageTypes": ["COLLISION", "THEFT", "FLOOD", "FIRE"], "limitCents": 250000000,
     "deductibleCents": 80000, "depreciationPercent": 12, "clauseVersion": "clause-2026-v1"},
]
TYPES = ["COLLISION", "THEFT", "FLOOD", "FIRE"]
LIAB = [("本车全责", 100), ("交警认定主要责任", 70), ("双方同等责任", 50), ("本车次要责任", 30)]


def liability(desc):
    for kw, pct in LIAB:
        if kw in desc:
            return pct
    return 100


def coverage(p, date, typ, drunk, unlic, intent):
    excl = []
    if date < p["effectiveDate"] or date > p["expiryDate"]:
        return False, ["EXCL-PERIOD"]
    if typ not in p["coverageTypes"]:
        return False, ["EXCL-TYPE"]
    if drunk:
        excl.append("EXCL-DRUNK")
    if unlic:
        excl.append("EXCL-UNLICENSED")
    if intent:
        excl.append("EXCL-INTENTIONAL")
    return (len(excl) == 0), excl


def fraud(estimate, prior, flagged, late):
    s = 0
    if estimate > 5000000:
        s += 25
    elif estimate > 1000000:
        s += 10
    s += min(30, prior * 10)
    if flagged:
        s += 30
    if late:
        s += 10
    return min(100, s)


def gate(estimate, claimed, conf):
    if estimate <= 0:
        return True
    if claimed > 0 and claimed > estimate * 3:
        return True
    if conf < 0.85:
        return True
    return False


def route(risk, estimate, conf):
    if risk >= 70 or estimate > 5000000:
        return "INVESTIGATE"
    if estimate < 500000 and risk < 30 and conf >= 0.85:
        return "FAST"
    return "STANDARD"


def calc(estimate, deductible, dep, limit, liab):
    depreciation = estimate * dep // 100
    after_liab = (estimate - depreciation) * liab // 100
    return max(0, min(limit, after_liab - deductible))


def approvers(payable, channel):
    if channel == "FAST":
        return 0
    if payable > 10000000:
        return 2
    return 1


cases = []
for i in range(N):
    p = RNG.choice(POLICIES)
    typ = RNG.choice(TYPES)
    # 日期：多数在保期内，少量期间外
    if RNG.random() < 0.08:
        date = RNG.choice(["2025-12-31", "2027-06-01", "2025-06-01"])
    else:
        date = "2026-%02d-%02d" % (RNG.randint(3, 12), RNG.randint(1, 28))
    drunk = RNG.random() < 0.06
    unlic = RNG.random() < 0.05
    intent = RNG.random() < 0.04
    estimate = RNG.choice([0, 50000, 200000, 420000, 1000000, 3200000, 6800000, 12000000, 6000000])
    prior = RNG.randint(0, 6)
    flagged = RNG.random() < 0.2
    late = RNG.random() < 0.25
    conf = RNG.choice([0.6, 0.8, 0.85, 0.9, 0.93, 0.95])
    liab_kw, liab_pct = RNG.choice(LIAB)
    claimed = -1
    desc_parts = ["车型 VW-LAVIDA-2023", liab_kw]
    if drunk:
        desc_parts.append("交警认定酒驾")
    if unlic:
        desc_parts.append("无证驾驶期间")
    if intent:
        desc_parts.append("故意制造事故")
    # 部分案件写索赔金额（触发背离门禁）
    if RNG.random() < 0.15 and estimate > 0:
        claimed = estimate * RNG.choice([4, 5, 10])
        desc_parts.append("索赔%d元" % (claimed // 100))
    desc = "，".join(desc_parts)

    cov, excl = coverage(p, date, typ, drunk, unlic, intent)
    expected = {"covered": cov, "exclusions": excl}
    if not cov:
        expected["payableCents"] = 0
    else:
        risk = fraud(estimate, prior, flagged, late)
        expected["riskScore"] = risk
        if gate(estimate, claimed, conf):
            expected["channel"] = "MANUAL"
            expected["payableCents"] = 0
            expected["requiredApprovers"] = 0
        else:
            ch = route(risk, estimate, conf)
            payable = calc(estimate, p["deductibleCents"], p["depreciationPercent"], p["limitCents"], liab_pct)
            expected["channel"] = ch
            expected["payableCents"] = payable
            expected["requiredApprovers"] = approvers(payable, ch)

    cases.append({
        "id": "GOLD-X%04d" % (i + 1),
        "name": "合成金标%04d（%s/%s）" % (i + 1, typ, expected.get("channel", "REJECT")),
        "policy": p, "accidentDate": date, "accidentType": typ, "description": desc,
        "estimateCents": estimate, "priorClaims": prior, "flaggedRepairShop": flagged,
        "lateNightReport": late, "recognitionConfidence": conf, "expected": expected,
    })

out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                   "claim-gateway", "src", "main", "resources", "gold-cases-extended.json"))
with open(out, "w") as f:
    json.dump(cases, f, ensure_ascii=False, indent=1)
print("生成 %d 案 → %s" % (len(cases), out))
from collections import Counter
print("通道分布:", dict(Counter(c["expected"].get("channel", "REJECT") for c in cases)))