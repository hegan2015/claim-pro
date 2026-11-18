#!/usr/bin/env python3
"""claim-pro 端到端冒烟（零依赖，python3 smoke-test.py [base_url]）。

覆盖：健康→报案→材料文件上传/查看→补件→管道推进→人工节点决策→结案→双账本→证据链→审计→对账→图谱构建→金标回归。
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

    req("POST", "/api/claims/%s/materials" % case_no, [
        {"type": "ACCIDENT_REPORT", "storageKey": "smoke/%s/report.pdf" % case_no, "confidence": 0.98}])
    check("补件提交（元数据登记）", True)
    detail0 = req("GET", "/api/claims/" + case_no)
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