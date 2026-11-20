#!/usr/bin/env python3
"""claim-pro 端到端冒烟（零依赖，python3 smoke-test.py [base_url]）。

覆盖：健康→报案→材料文件上传/查看/幂等去重→补件→管道推进→人工节点决策→结案→双账本→证据链→审计→对账→定损门禁（空定损/索赔背离/金额校验）→图谱构建→金标回归。
默认打 http://127.0.0.1:8097（run.sh 启动的 mysql profile）。
"""
import base64
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

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


PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")


def upload_file(case_no, filename, data, mtype, confidence=0.95):
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in (("type", mtype), ("confidence", str(confidence))):
        parts.append(('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n'
                      % (boundary, name, value)).encode())
    parts.append(('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
                  'Content-Type: application/octet-stream\r\n\r\n' % (boundary, filename)).encode())
    parts.append(data)
    parts.append(('\r\n--%s--\r\n' % boundary).encode())
    r = urllib.request.Request(BASE + "/api/claims/%s/materials/upload" % case_no,
                               data=b"".join(parts), method="POST",
                               headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(r, timeout=15) as resp:
        return json.loads(resp.read().decode())


def raw_get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as resp:
        return resp.headers.get("Content-Type", ""), resp.read()


def expect_http_error(method, path, code, body=None):
    try:
        req(method, path, body)
        return False
    except urllib.error.HTTPError as e:
        return e.code == code


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


def wait_events(case_no, required, timeout=20):
    """MQ 档位事件经异步消费归档，轮询等待最终一致（LOCAL 档同步归档立即满足）。"""
    deadline = time.time() + timeout
    steps = set()
    while time.time() < deadline:
        d = req("GET", "/api/claims/" + case_no)
        steps = {str(e.get("step") or e.get("STEP")) for e in d.get("events", [])}
        if required <= steps:
            return steps, d
        time.sleep(1)
    return steps, None


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

    # ── 材料文件本体：真实上传 → 读回校验 → 仅登记/非法格式拒绝 ──
    up = upload_file(case_no, "smoke-photo.png", PNG_1X1, "DAMAGE_PHOTO")
    check("真实文件上传（内容 SHA-256 + 字节数）",
          up.get("sha256") == hashlib.sha256(PNG_1X1).hexdigest()
          and up.get("sizeBytes") == len(PNG_1X1) and up.get("fileName") == "smoke-photo.png", up)
    ct, content = raw_get("/api/claims/%s/materials/%s/content" % (case_no, up["materialId"]))
    check("文件内容读回一致", content == PNG_1X1, {"bytes": len(content)})
    check("Content-Type 正确", ct.startswith("image/png"), ct)
    up2 = upload_file(case_no, "smoke-photo.png", PNG_1X1, "DAMAGE_PHOTO")
    check("重复上传同内容文件幂等（返回原材料）", up2.get("materialId") == up["materialId"], up2)

    req("POST", "/api/claims/%s/materials" % case_no, [
        {"type": "ACCIDENT_REPORT", "storageKey": "smoke/%s/report.pdf" % case_no, "confidence": 0.98}])
    check("补件提交（元数据登记）", True)
    detail0 = req("GET", "/api/claims/" + case_no)
    n_before = len(detail0["materials"])
    req("POST", "/api/claims/%s/materials" % case_no, [
        {"type": "ACCIDENT_REPORT", "storageKey": "smoke/%s/report.pdf" % case_no, "confidence": 0.98}])
    detail0 = req("GET", "/api/claims/" + case_no)
    check("重复元数据登记幂等去重", len(detail0["materials"]) == n_before,
          {"before": n_before, "after": len(detail0["materials"])})
    meta_only = [m for m in detail0["materials"] if m.get("sizeBytes") is None]
    check("仅登记材料无文件本体（404）",
          bool(meta_only) and expect_http_error(
              "GET", "/api/claims/%s/materials/%s/content" % (case_no, meta_only[0]["materialId"]), 404))
    try:
        upload_file(case_no, "evil.txt", b"not an image", "OTHER")
        bad_ext_rejected = False
    except urllib.error.HTTPError as e:
        bad_ext_rejected = e.code == 404
    check("不支持的扩展名拒绝", bad_ext_rejected)

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

    # H2 queryForList 返回大写列名，统一小写；MQ 档事件异步归档，等最终一致
    steps, detail_ev = wait_events(case_no, {"REPORT", "COVERAGE", "PAY", "CORE_REGISTER"})
    if detail_ev:
        detail = detail_ev
    check("决策事件链完整", {"REPORT", "COVERAGE", "PAY", "CORE_REGISTER"} <= steps, sorted(steps))

    ev = req("GET", "/api/claims/%s/evidence/verify" % case_no)
    check("证据哈希链校验", ev.get("chainValid") is True, ev)

    steps2, _ = wait_events(case_no, {"CALC_DUAL_RUN", "REVIEW"})
    check("理算双跑+独立复核门禁事件落链", {"CALC_DUAL_RUN", "REVIEW"} <= steps2, sorted(steps2))
    dual = req("GET", "/api/calc-dual-run/stats")
    check("理算双跑监控统计", dual.get("total", 0) >= 1 and 0 <= dual.get("matchRate", -1) <= 1, dual)
    steps3, _ = wait_events(case_no, {"ASSESS_VISION"})
    check("视觉定损通道留痕（未配视觉Key时降级文本定损）", "ASSESS_VISION" in steps3, sorted(steps3))

    cs = req("GET", "/api/clauses/search?q=%E9%85%92%E5%90%8E%E9%A9%BE%E8%BD%A6%E8%83%BD%E8%B5%94%E5%90%97&limit=3")
    hits = cs.get("hits", [])
    check("条款语义检索（RAG 命中免责条款原文）",
          len(hits) >= 1 and any("EXCL-DRUNK" == h.get("code") for h in hits) and hits[0].get("body"),
          {"hitCodes": [h.get("code") for h in hits]})
    ce = req("POST", "/api/agent/coverage-explain/" + case_no)
    check("核保解释引用条款原文", bool(ce.get("explanation")) and len(ce.get("citedClauses", [])) >= 1,
          {"cited": len(ce.get("citedClauses", []))})

    audit = req("GET", "/api/audit/recent?limit=10")
    check("审计事件可查", len(audit) > 0)

    recon = req("POST", "/api/recon/run")
    diffs = req("GET", "/api/recon/diffs")
    check("对账执行且无未决差异", recon.get("newDiffs") == 0 and len(diffs) == 0, (recon, diffs))

    stats = req("GET", "/api/stats")
    check("统计接口", stats.get("totalCases", 0) > 0 and stats.get("paidCents", 0) > 0, stats)

    # ── 定损门禁：空定损 / 索赔背离 强制转人工；人工批准金额>0 服务端校验 ──
    def gate_case(desc):
        gc = req("POST", "/api/claims", {
            "policyNo": "P-2026-0001", "insuranceType": "AUTO", "accidentType": "COLLISION",
            "description": desc})
        gno = gc["caseNo"]
        req("POST", "/api/claims/%s/materials" % gno, [
            {"type": "ACCIDENT_REPORT", "storageKey": "smoke/%s/r.pdf" % gno, "confidence": 0.98},
            {"type": "DAMAGE_PHOTO", "storageKey": "smoke/%s/p.jpg" % gno, "confidence": 0.92}])
        st, dt = wait_status(gno, {"MANUAL", "CLOSED", "REJECTED", "APPROVING"}, timeout=90)
        return gno, st, dt

    g1, st1, dt1 = gate_case("夜间单方事故，修理厂报价异常，申请高额赔付，本车全责")
    ev1, _ = wait_events(g1, {"MANUAL"})
    # 弱证据案必须被拦截转人工：空定损门禁（LLM 未估价时）或独立复核质疑（LLM 猜车型估价时）
    check("弱证据案不得自动结案（空定损门禁/复核拦截）",
          st1 == "MANUAL" and ({"ASSESS_GATE", "REVIEW"} & ev1),
          {"status": st1, "steps": sorted(ev1)})

    g2, st2, dt2 = gate_case("大众朗逸2023款追尾碰撞，前保险杠受损，索赔6.8万，本车全责")
    ev2, _ = wait_events(g2, {"ASSESS_GATE"})
    check("索赔与估损严重背离强制转人工", st2 == "MANUAL" and "ASSESS_GATE" in ev2,
          {"status": st2, "steps": sorted(ev2)})

    check("人工批准金额0被服务端拒绝（400）",
          expect_http_error("POST", "/api/claims/%s/decision" % g2, 400,
                            {"step": "MANUAL_HANDLE", "approved": True, "amountCents": 0,
                             "operator": "smoke", "comment": "非法零金额批准"}))
    req("POST", "/api/claims/%s/decision" % g2,
        {"step": "MANUAL_HANDLE", "approved": True, "amountCents": 360000,
         "operator": "smoke", "comment": "冒烟人工核定金额"})
    st2b, _ = wait_status(g2, {"CLOSED", "REJECTED"}, timeout=30)
    check("人工填正金额后赔付结案", st2b == "CLOSED", st2b)
    req("POST", "/api/claims/%s/decision" % g1,
        {"step": "MANUAL_HANDLE", "approved": True, "amountCents": 200000,
         "operator": "smoke", "comment": "冒烟空定损人工核定"})
    st1b, _ = wait_status(g1, {"CLOSED", "REJECTED"}, timeout=30)
    check("空定损案人工核定后结案", st1b == "CLOSED", st1b)

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
    check("金标回归全过（2018 案零漂移）",
          gr.get("total", 0) > 0 and gr.get("failed") == 0 and gr.get("passed") == gr.get("total"), gr)
    gl = req("GET", "/api/gold/latest")
    results = gl.get("results", [])
    check("金标逐案结果齐全",
          len(results) == gr.get("total") and all(r.get("passed", r.get("PASSED")) in (1, True) for r in results),
          {"count": len(results)})

    # ── 影子双跑 + 灰度发布 ──
    sh = req("POST", "/api/shadow/run", {"riskLow": 0})  # 收紧快赔风险阈值 → 原 FAST 案应漂移
    check("影子双跑漂移检测（候选阈值重放历史案）",
          sh.get("total", 0) > 0 and sh.get("driftCount", 0) > 0 and len(sh.get("samples", [])) >= 1,
          {"total": sh.get("total"), "drift": sh.get("driftCount")})
    cc = req("GET", "/api/canary/config")
    check("灰度配置可查", "percent" in cc and "enabled" in cc, cc)
    cc2 = req("POST", "/api/canary/config", {"enabled": False, "percent": 0})
    check("灰度配置可运行时调整", cc2.get("percent") == 0 and cc2.get("enabled") is False, cc2)

    print("\n%d/%d 通过" % (len(PASSED), len(PASSED) + len(FAILED)))
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.URLError as e:
        print("FAIL 无法连接 %s：%s（先 ./run.sh 启动服务）" % (BASE, e))
        sys.exit(1)