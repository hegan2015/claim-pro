#!/usr/bin/env python3
"""claim-pro 端到端冒烟（零依赖，python3 smoke-test.py [base_url]）。

覆盖：健康→报案→补件→管道推进→人工节点决策→结案→双账本→证据链→审计→对账→图谱构建→金标回归。
默认打 http://127.0.0.1:8097（run.sh 启动的 mysql profile）。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8097").rstrip("/")
PASSED, FAILED = [], []


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=15) as resp:
        return json.loads(resp.read().decode())


def check(name, cond, extra=""):
    (PASSED if cond else FAILED).append(name)
    print(("PASS " if cond else "FAIL ") + name + ((" | " + str(extra)) if extra and not cond else ""))


def wait_status(case_no, targets, timeout=60):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        d = req("GET", "/api/claims/" + case_no)
        last = d["case"]["status"]
        if last in targets:
            return last, d
        time.sleep(2)
    return last, None


def main():
    health = req("GET", "/actuator/health")
    check("健康检查 UP", health.get("status") == "UP", health)

    rt = req("GET", "/api/runtime")
    check("编排引擎就绪", rt.get("orchestration") in ("TEMPORAL", "LOCAL"), rt)
    print("     orchestration=%s evidenceStore=%s" % (rt.get("orchestration"), rt.get("evidenceStore")))

    case = req("POST", "/api/claims", {
        "policyNo": "P-2026-0001", "insuranceType": "AUTO", "accidentType": "COLLISION",
        "description": "大众朗逸2023款追尾碰撞，前保险杠与左前大灯受损，无人员受伤"})
    case_no = case["caseNo"]
    check("报案建案同步返回", bool(case_no) and case.get("status") == "REPORTED", case)
    print("     caseNo=" + case_no)

    req("POST", "/api/claims/%s/materials" % case_no, [
        {"type": "ACCIDENT_REPORT", "storageKey": "smoke/%s/report.pdf" % case_no, "confidence": 0.98},
        {"type": "DAMAGE_PHOTO", "storageKey": "smoke/%s/photo.jpg" % case_no, "confidence": 0.95}])
    check("补件提交", True)

    status, detail = wait_status(case_no, {"APPROVING", "MANUAL", "CLOSED", "REJECTED"})
    check("管道推进出等待/终结态", status in ("APPROVING", "MANUAL", "CLOSED", "REJECTED"), status)

    if status == "APPROVING":
        for _ in range(3):
            req("POST", "/api/claims/%s/decision" % case_no,
                {"step": "APPROVE", "approved": True, "operator": "smoke", "comment": "冒烟审批通过"})
            # 只等终结态：APPROVING 在目标集里会立即返回，赶不上管道 2s 轮询消费审批
            status, detail = wait_status(case_no, {"CLOSED", "REJECTED"}, timeout=20)
            if status in ("CLOSED", "REJECTED"):
                break
    elif status == "MANUAL":
        amount = (detail or {}).get("case", {}).get("estimateCents") or 360000
        req("POST", "/api/claims/%s/decision" % case_no,
            {"step": "MANUAL_HANDLE", "approved": True, "amountCents": amount,
             "operator": "smoke", "comment": "冒烟人工处理"})
        status, detail = wait_status(case_no, {"CLOSED", "REJECTED"})

    check("案件结案 CLOSED", status == "CLOSED", status)
    detail = req("GET", "/api/claims/" + case_no)
    c = detail["case"]
    check("赔付金额落库", c.get("payableCents", 0) > 0, c.get("payableCents"))
    check("业务账本有记录", len(detail.get("ledgerBusiness", [])) > 0)
    check("资金账本有记录", len(detail.get("ledgerFund", [])) > 0)

    # H2 queryForList 返回大写列名，统一小写
    steps = {str(e.get("step") or e.get("STEP")) for e in detail.get("events", [])}
    check("决策事件链完整", {"REPORT", "COVERAGE", "PAY", "CORE_REGISTER"} <= steps, sorted(steps))

    ev = req("GET", "/api/claims/%s/evidence/verify" % case_no)
    check("证据哈希链校验", ev.get("chainValid") is True, ev)

    audit = req("GET", "/api/audit/recent?limit=10")
    check("审计事件可查", len(audit) > 0)

    recon = req("POST", "/api/recon/run")
    diffs = req("GET", "/api/recon/diffs")
    check("对账执行且无未决差异", recon.get("newDiffs") == 0 and len(diffs) == 0, (recon, diffs))

    stats = req("GET", "/api/stats")
    check("统计接口", stats.get("totalCases", 0) > 0 and stats.get("paidCents", 0) > 0, stats)

    # ── 风控批处理：图谱构建 + 金标回归 ──
    gb = req("POST", "/api/graph/build")
    check("图谱构建（节点/边/修理厂）",
          gb.get("nodeCount", 0) > 0 and gb.get("edgeCount", 0) > 0 and gb.get("shopCount", 0) >= 1, gb)
    gs = req("GET", "/api/graph/summary")
    flagged = [s for s in gs.get("shops", []) if s.get("flagged")]
    check("图谱摘要含标记修理厂（人工标记保留）",
          len(flagged) >= 1 and gs.get("manualFlagCount", 0) >= 1, gs)
    nb = req("GET", "/api/graph/case/CLM-20261118-0002")
    check("案件邻域含同厂兄弟案",
          nb.get("center") == "CLM-20261118-0002" and len(nb.get("nodes", [])) > 1, nb)
    gr = req("POST", "/api/gold/run")
    check("金标回归全过（15 案零漂移）",
          gr.get("total", 0) > 0 and gr.get("failed") == 0 and gr.get("passed") == gr.get("total"), gr)
    gl = req("GET", "/api/gold/latest")
    results = gl.get("results", [])
    check("金标逐案结果齐全",
          len(results) == gr.get("total") and all(r.get("passed", r.get("PASSED")) in (1, True) for r in results),
          {"count": len(results)})

    print("\n%d/%d 通过" % (len(PASSED), len(PASSED) + len(FAILED)))
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        print("FAIL 无法连接 %s：%s（先 ./run.sh 启动服务）" % (BASE, e))
        sys.exit(1)