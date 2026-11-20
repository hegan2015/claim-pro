/* CLAIMDESK 前端 — 零依赖原生 JS，hash 路由，对接 claim-pro REST API */
'use strict';

/* ───────── 基础工具 ───────── */
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const yuan = cents => '¥' + ((Number(cents) || 0) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const wan = cents => ((Number(cents) || 0) / 1000000).toLocaleString('zh-CN', { maximumFractionDigits: 1 }) + ' 万';
const pct = v => Math.round((Number(v) || 0) * 100) + '%';
const fmtTime = ts => { if (!ts) return '—'; const d = new Date(Number(ts)); return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };
const fmtFull = ts => { if (!ts) return '—'; const d = new Date(Number(ts)); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`; };
/* H2 返回大写列名、MySQL 返回小写：统一归一为小写下划线 */
const norm = rows => (rows || []).map(r => Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])));
const prettyJson = s => { try { return JSON.stringify(typeof s === 'string' ? JSON.parse(s) : s, null, 2); } catch { return String(s ?? ''); } };

async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  let data = null;
  try { data = await res.json(); } catch { /* 空响应 */ }
  if (!res.ok) {
    const msg = data?.error || data?.message || `HTTP ${res.status}`;
    const e = new Error(msg); e.status = res.status; throw e;
  }
  return data;
}

function toast(msg, kind = 'info', ms = 3800) {
  const rail = $('#toastRail');
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.innerHTML = `<span>${esc(msg)}</span>`;
  rail.appendChild(el);
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 320); }, ms);
}

function modal(html) {
  const scrim = $('#modalScrim'), box = $('#modalBox');
  box.innerHTML = html;
  scrim.hidden = false;
  scrim.onclick = e => { if (e.target === scrim) closeModal(); };
  return box;
}
function closeModal() { $('#modalScrim').hidden = true; $('#modalBox').innerHTML = ''; }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

/* ───────── 领域字典 ───────── */
const STATUS = {
  REPORTED: ['已报案', 'st-flow'], MATERIALS_PENDING: ['待补件', 'st-wait'],
  COVERAGE_CHECK: ['核保中', 'st-flow'], ASSESSING: ['定损中', 'st-flow'],
  ROUTED: ['已分级', 'st-flow'], CALCULATED: ['已理算', 'st-flow'],
  APPROVING: ['审批中', 'st-wait'], PAYING: ['支付中', 'st-flow'],
  SETTLED: ['已赔付', 'st-ok'], REJECTED: ['已拒赔', 'st-bad'],
  MANUAL: ['人工处理', 'st-man'], CLOSED: ['已结案', 'st-ok'],
};
const stBadge = s => { const [lab, cls] = STATUS[s] || [s, 'st-flow']; return `<span class="st ${cls}">${lab}</span>`; };
const CHANNEL = {
  FAST: ['快赔通道', '#fbbf24'], STANDARD: ['标准通道', '#60a5fa'],
  INVESTIGATE: ['调查通道', '#fb7185'], MANUAL: ['人工通道', '#a78bfa'],
};
const chBadge = c => c ? `<span class="badge b-${c}">${CHANNEL[c][0]}</span>` : '<span class="badge b-none">未分级</span>';
const STEPS = {
  REPORT: '报案受理', INTAKE: '智能受理', COVERAGE: '核保校验', FRAUD_SCORE: '欺诈评分',
  ASSESS: '智能定损', ROUTE: '通道分级', CALCULATE: '确定性理算', APPROVE: '赔付审批',
  REJECT_CONFIRM: '拒赔确认', MANUAL_HANDLE: '人工处理', MANUAL: '降级人工', PAY: '双账本支付',
  CORE_REGISTER: '核心登记', NOTIFY: '客户通知', REJECT: '拒赔结案', SETTLED: '赔付完成',
};
const stepLab = s => STEPS[s] || s;
const PHASES = [
  ['REPORTED', '报案'], ['MATERIALS_PENDING', '材料'], ['COVERAGE_CHECK', '核保'],
  ['ASSESSING', '定损'], ['ROUTED', '分级'], ['CALCULATED', '理算'],
  ['APPROVING', '审批'], ['PAYING', '支付'], ['CLOSED', '结案'],
];
const TERMINAL = new Set(['CLOSED', 'SETTLED', 'REJECTED']);
const ACCIDENT_TYPES = [['COLLISION', '碰撞'], ['FLOOD', '涉水'], ['THEFT', '盗抢'], ['FIRE', '火灾'], ['GLASS', '玻璃单独破碎'], ['OTHER', '其他']];
const MAT_TYPES = [['ACCIDENT_REPORT', '事故认定书'], ['DAMAGE_PHOTO', '损伤照片'], ['REPAIR_QUOTE', '维修报价单'], ['ID_CARD', '身份证明'], ['BANK_CARD', '银行卡信息'], ['OTHER', '其他材料']];
const matLab = t => (MAT_TYPES.find(x => x[0] === t) || [t, t])[1];
const accLab = t => (ACCIDENT_TYPES.find(x => x[0] === t) || [t, t])[1];
const POLICIES = ['P-2026-0001', 'P-2026-0002', 'P-2026-0003', 'P-2026-0004', 'P-2026-0005', 'P-2026-0006'];
const riskMeter = score => {
  const s = Number(score) || 0;
  const cls = s >= 60 ? 'high' : s >= 30 ? 'mid' : 'low';
  return `<span class="row" style="gap:7px"><span class="meter ${cls}" style="flex:1"><i style="width:${Math.min(100, s)}%"></i></span><b class="mono small">${s}</b></span>`;
};

/* ───────── 全局状态 ───────── */
const state = { availability: {}, runtime: null, detailTab: 'materials', detailNo: null, chat: [], caseQuery: '' };
let pollTimer = null;

function stopPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

async function refreshMeta() {
  try {
    const [av, rt] = await Promise.all([api('/agent/availability'), api('/runtime')]);
    state.availability = av; state.runtime = rt;
    const names = { intake: '受理', assess: '定损', 'coverage-explain': '核保解释', 'fraud-report': '欺诈报告', review: '复核', notify: '通知' };
    $('#agentStrip').innerHTML = Object.entries(av).map(([k, on]) =>
      `<span class="agent-chip ${on ? 'on' : ''}" title="${esc(k)} 端点${on ? '可用' : '不可用（未配置/熔断）'}"><i></i>${names[k] || k}</span>`).join('');
    const temporal = rt.orchestration === 'TEMPORAL';
    $('#engineDot').className = 'dot ' + (temporal ? 'ok' : 'warn');
    $('#engineName').textContent = temporal ? 'Temporal 生产编排' : '本地开发管道';
    $('#engineDesc').textContent = temporal ? '持久化工作流运行中' : '非生产 · 轮询审批表';
  } catch { /* 后端未就绪时静默 */ }
}

async function refreshQueueBadge() {
  try {
    const list = await api('/claims?limit=200');
    const n = list.filter(c => ['APPROVING', 'MANUAL', 'MATERIALS_PENDING'].includes(c.status)).length;
    const b = $('#navQueueBadge');
    b.hidden = n === 0; b.textContent = n;
  } catch { /* ignore */ }
}

/* ───────── 路由 ───────── */
const ROUTES = {
  dashboard: ['仪表盘', renderDashboard],
  report: ['报案登记', renderReport],
  cases: ['案件列表', renderCases],
  workbench: ['人工工作台', renderWorkbench],
  audit: ['审计追溯', renderAudit],
  batch: ['风控批处理', renderBatch],
};

function route() {
  stopPoll();
  const hash = location.hash.replace(/^#\/?/, '') || 'dashboard';
  const [name, arg] = hash.split('/');
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.route === name));
  $('#sidebar').classList.remove('open');
  const view = $('#view');
  if (name === 'case' && arg) {
    $('#crumb').innerHTML = `案件详情<small>${esc(arg)}</small>`;
    state.detailNo = arg;
    renderDetail(arg);
    return;
  }
  const r = ROUTES[name] || ROUTES.dashboard;
  $('#crumb').textContent = r[0];
  view.innerHTML = `<div class="grid g4">${'<div class="skeleton" style="height:110px"></div>'.repeat(4)}</div><div class="skeleton mt" style="height:280px"></div>`;
  r[1](view, arg);
}
window.addEventListener('hashchange', route);

/* ───────── 仪表盘 ───────── */
async function renderDashboard(view) {
  try {
    const [stats, audit, diffs, gold] = await Promise.all([
      api('/stats'), api('/audit/recent?limit=14'), api('/recon/diffs'), api('/gold/latest'),
    ]);
    const gr = gold.run ? norm([gold.run])[0] : null;
    const fastRatio = stats.totalCases ? Math.round(stats.fastCases / stats.totalCases * 100) : 0;
    const chEntries = Object.entries(stats.byChannel || {});
    const stEntries = Object.entries(stats.byStatus || {}).sort((a, b) => b[1] - a[1]);
    const maxSt = Math.max(1, ...stEntries.map(x => x[1]));
    view.innerHTML = `
      <div class="grid g4">
        ${statCard('c-teal', '案件总数', stats.totalCases, '全部报案累计', '件')}
        ${statCard('c-amber', '快赔通道占比', fastRatio + '%', `${stats.fastCases} 件走 FAST 免人工`, '')}
        ${statCard('c-blue', '累计赔付金额', yuan(stats.paidCents), '资金账本 OUT 方向合计', '')}
        ${statCard('c-rose', '待处理对账差异', stats.openReconDiffs, stats.openReconDiffs > 0 ? '存在 OPEN 差异，需核查' : '双账本日终对账平', '笔')}
      </div>
      <div class="grid g-2-1 mt">
        <div class="card">
          <h3>通道分布<span class="sub">三通道分级 + 人工降级</span></h3>
          <div class="donut-wrap">${donut(chEntries)}
            <div class="donut-legend">${chEntries.map(([c, n]) =>
              `<div class="li"><span class="sw" style="background:${CHANNEL[c]?.[1] || '#5c6b8a'}"></span>${CHANNEL[c]?.[0] || c}<b>${n}</b></div>`).join('') || '<span class="muted small">暂无已分级案件</span>'}
            </div>
          </div>
          <h3 class="mt">状态分布</h3>
          <div class="hbar">${stEntries.map(([s, n]) =>
            `<div class="hb"><span>${STATUS[s]?.[0] || s}</span><span class="track"><i style="width:${n / maxSt * 100}%"></i></span><b class="mono">${n}</b></div>`).join('')}
          </div>
        </div>
        <div class="card">
          <h3>模型网关<span class="sub">LLMOps 计量</span></h3>
          <div class="kv">
            <div><div class="k">Prompt Tokens</div><div class="v mono" style="color:var(--cyan)">${Number(stats.promptTokens).toLocaleString()}</div></div>
            <div><div class="k">Completion Tokens</div><div class="v mono" style="color:var(--cyan)">${Number(stats.completionTokens).toLocaleString()}</div></div>
          </div>
          <div class="chips mt">${Object.entries(state.availability).map(([k, on]) =>
            `<span class="chip ${on ? 'on' : ''}" style="cursor:default">${esc(k)} ${on ? '✓' : '✕'}</span>`).join('')}</div>
          <p class="hint mt">Agent 只做理解 / 查证 / 起草 / 沟通；资格、金额、审批、资金由确定性核心裁决。端点不可用时自动降级人工通道。</p>
          <h3 class="mt">对账中心<span class="sub">双账本一致性</span></h3>
          <div class="row spread">
            <span class="small muted">OPEN 差异 <b class="mono" style="color:${diffs.length ? 'var(--rose)' : 'var(--green)'}">${diffs.length}</b> 笔</span>
            <button class="btn sm" id="reconRun">执行对账</button>
          </div>
          <div id="reconList" class="mt">${diffTable(diffs)}</div>
          <div class="row spread mt">
            <span class="small muted">金标回归 <b class="mono" style="color:${gr ? (gr.failed > 0 ? 'var(--rose)' : 'var(--green)') : 'var(--muted)'}">${gr ? gr.passed + '/' + gr.total : '未运行'}</b></span>
            <a class="btn sm ghost" href="#/batch" style="text-decoration:none">批处理中心 →</a>
          </div>
        </div>
      </div>
      <div class="card mt">
        <h3>最近决策流水<span class="sub">证据哈希链归档 · 点击案件号跳转</span></h3>
        <div class="feed">${norm(audit).map(e => `
          <div class="feed-item">
            <span class="t">${fmtTime(e.ts)}</span>
            <div><span class="who">${esc(stepLab(e.step))}</span>
              <span class="what"> · ${esc(e.actor || '')}${e.model_version ? ' · ' + esc(e.model_version) : ''}</span>
              <div><a href="#/case/${esc(e.case_no)}" class="mono small" style="color:var(--teal);text-decoration:none">${esc(e.case_no)}</a>
              ${e.confidence != null ? `<span class="faint small"> · 置信度 ${pct(e.confidence)}</span>` : ''}</div>
            </div>
          </div>`).join('') || '<div class="empty"><em>◈</em>暂无决策记录</div>'}
        </div>
      </div>`;
    $('#reconRun').onclick = async () => {
      const btn = $('#reconRun'); btn.disabled = true; btn.textContent = '对账中…';
      try {
        const r = await api('/recon/run', { method: 'POST' });
        toast(r.newDiffs > 0 ? `对账完成，发现 ${r.newDiffs} 笔新差异` : '对账完成，双账本一致', r.newDiffs > 0 ? 'err' : 'ok');
        const d = await api('/recon/diffs');
        $('#reconList').innerHTML = diffTable(d);
      } catch (e) { toast('对账失败：' + e.message, 'err'); }
      btn.disabled = false; btn.textContent = '执行对账';
    };
    pollTimer = setInterval(async () => {
      try {
        const s = await api('/stats');
        const cards = $$('.stat-card .val', view);
        if (cards[0]) cards[0].textContent = s.totalCases;
      } catch { /* ignore */ }
    }, 15000);
  } catch (e) { view.innerHTML = errCard(e); }
}

function statCard(color, lab, val, foot, unit) {
  return `<div class="card stat-card ${color}"><div class="lab">${lab}</div><div class="val">${val}<small style="font-size:13px;color:var(--muted)"> ${unit}</small></div><div class="foot">${foot}</div></div>`;
}

function donut(entries) {
  const total = entries.reduce((a, [, n]) => a + n, 0);
  if (!total) return '<div class="empty" style="padding:20px"><em>◔</em>暂无数据</div>';
  const R = 54, C = 2 * Math.PI * R;
  let off = 0;
  const segs = entries.map(([c, n]) => {
    const frac = n / total, len = frac * C;
    const seg = `<circle r="${R}" cx="70" cy="70" fill="none" stroke="${CHANNEL[c]?.[1] || '#5c6b8a'}" stroke-width="17"
      stroke-dasharray="${Math.max(0, len - 2)} ${C - len + 2}" stroke-dashoffset="${-off}"
      transform="rotate(-90 70 70)" style="transition:stroke-dasharray .7s ease"/>`;
    off += len;
    return seg;
  }).join('');
  return `<svg width="140" height="140" viewBox="0 0 140 140" role="img" aria-label="通道分布">
    <circle r="${R}" cx="70" cy="70" fill="none" stroke="#1e2c50" stroke-width="17"/>${segs}
    <text x="70" y="66" text-anchor="middle" fill="#e8eefb" font-size="22" font-weight="700" font-family="monospace">${total}</text>
    <text x="70" y="84" text-anchor="middle" fill="#8fa0bf" font-size="10">已分级案件</text></svg>`;
}

function diffTable(diffs) {
  const rows = norm(diffs);
  if (!rows.length) return '<p class="hint">无未决差异 ✓</p>';
  return `<div class="table-wrap"><table><thead><tr><th>案件</th><th>类型</th><th>金额</th><th>状态</th></tr></thead><tbody>
    ${rows.map(d => `<tr><td class="mono small">${esc(d.case_no)}</td><td>${esc(d.diff_type || d.type || '')}</td>
      <td class="money small">${yuan(d.amount_cents ?? d.delta_cents ?? 0)}</td><td><span class="st st-bad">${esc(d.status)}</span></td></tr>`).join('')}
  </tbody></table></div>`;
}

function errCard(e) {
  return `<div class="card"><div class="empty"><em>⚠</em>加载失败：${esc(e.message)}<br>
    <span class="hint">请确认 claim-pro 已启动（./run.sh）</span><br>
    <button class="btn mt" onclick="route()">重试</button></div></div>`;
}

/* ───────── 报案登记 ───────── */
const SAMPLES = [
  ['追尾快赔', 'P-2026-0001', 'COLLISION', '追尾前车，本车全责，前保险杠与左前大灯受损，车型 VW-LAVIDA-2023，维修厂报价 4200 元'],
  ['暴雨涉水', 'P-2026-0006', 'FLOOD', '暴雨涉水行驶，发动机进水熄火，车型 TOYOTA-CAMRY-2024，已拖至 4S 店检修'],
  ['酒驾拒赔', 'P-2026-0005', 'COLLISION', '酒后驾车撞护栏，交警认定酒驾，车辆前部严重受损'],
  ['高额疑案', 'P-2026-0003', 'COLLISION', '夜间单方事故，修理厂报价异常偏高，申请高额赔付 6.8 万元'],
];

async function renderReport(view) {
  view.innerHTML = `
  <div class="grid g-2-1">
    <div class="card">
      <h3>新报案登记<span class="sub">提交后进入编排管道：材料门禁 → 核保 → 定损 → 分级 → 理算 → 审批 → 支付</span></h3>
      <form id="reportForm">
        <div class="grid g2">
          <div class="field"><label>保单号 <b>*</b></label>
            <select name="policyNo" required>${POLICIES.map(p => `<option>${p}</option>`).join('')}</select>
            <span class="hint">演示保单 P-2026-0001 ~ 0006（种子数据）</span></div>
          <div class="field"><label>事故日期 <b>*</b></label><input type="date" name="accidentDate" value="${new Date().toISOString().slice(0, 10)}" required></div>
        </div>
        <div class="grid g2">
          <div class="field"><label>事故类型 <b>*</b></label>
            <select name="accidentType">${ACCIDENT_TYPES.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select></div>
          <div class="field"><label>报案人手机号</label><input type="text" name="reporterPhone" placeholder="选填，用于客户通知" maxlength="11"></div>
        </div>
        <div class="field"><label>事故描述 <b>*</b></label>
          <textarea name="description" required placeholder="时间、地点、经过、责任认定、损伤部位、车型、维修报价…（描述越完整，智能定损置信度越高）"></textarea>
          <div class="row spread">
            <div class="chips">${SAMPLES.map((s, i) => `<button type="button" class="chip" data-sample="${i}">${s[0]}</button>`).join('')}</div>
            <button type="button" class="btn sm ghost" id="aiStructure">✦ AI 结构化抽取</button>
          </div>
        </div>
        <div id="aiOut"></div>
        <div class="row spread mt">
          <span class="hint">权力分层：AI 负责理解与起草，资格 / 金额 / 资金由确定性核心裁决</span>
          <button class="btn primary" type="submit" id="reportSubmit">提交报案 →</button>
        </div>
      </form>
    </div>
    <div>
      <div class="card">
        <h3>管道说明</h3>
        <div class="timeline" style="padding-left:22px">
          ${[['材料门禁', '事故认定书 + 损伤照片齐备才放行'], ['核保校验', '免责条款规则引擎：酒驾 / 无证 / 故意 / 等待期'], ['定损与欺诈', '欺诈评分 ∥ 智能定损，模型不可用降级人工'], ['三通道分级', 'FAST < 5000 元低风险 · STANDARD · INVESTIGATE > 5 万或高风险'], ['人工审批', '大额（>10 万元）双人审批，拒赔须人工确认'], ['双账本支付', '业务账 + 资金账，幂等键防重，日终对账']].map(([t, d]) =>
            `<div class="tl-item"><div class="tl-head"><b>${t}</b></div><div class="small muted">${d}</div></div>`).join('')}
        </div>
      </div>
      <div class="card mt">
        <h3>证据与合规</h3>
        <p class="small muted">每个 Agent 决策以 SHA-256 哈希链归档（digest = SHA-256(prevHash | outputJson)），工具调用全量审计，敏感字段（身份证 / 银行卡 / 手机号）出域前脱敏。</p>
      </div>
    </div>
  </div>`;

  $$('[data-sample]', view).forEach(btn => btn.onclick = () => {
    const [, policy, type, desc] = SAMPLES[+btn.dataset.sample];
    const f = $('#reportForm');
    f.policyNo.value = policy; f.accidentType.value = type; f.description.value = desc;
    $$('[data-sample]', view).forEach(b => b.classList.toggle('on', b === btn));
    toast('已填入示例场景：' + btn.textContent, 'info', 2000);
  });

  $('#aiStructure').onclick = async () => {
    const desc = $('#reportForm').description.value.trim();
    if (!desc) return toast('请先填写事故描述', 'err');
    const btn = $('#aiStructure'); btn.disabled = true; btn.textContent = '抽取中…';
    try {
      const r = await api('/agent/intake', { method: 'POST', body: { description: desc } });
      $('#aiOut').innerHTML = `<details class="json" open><summary>AI 结构化结果（仅供参考，以人工录入为准）</summary><pre>${esc(prettyJson(r.structured))}</pre></details>`;
      toast('AI 结构化完成', 'ok');
    } catch (e) {
      $('#aiOut').innerHTML = '';
      toast(e.status === 503 ? '模型端点未配置或熔断中，请人工录入（降级路径正常）' : 'AI 抽取失败：' + e.message, e.status === 503 ? 'info' : 'err');
    }
    btn.disabled = false; btn.textContent = '✦ AI 结构化抽取';
  };

  $('#reportForm').onsubmit = async e => {
    e.preventDefault();
    const f = e.target, btn = $('#reportSubmit');
    btn.disabled = true; btn.textContent = '提交中…';
    try {
      const c = await api('/claims', {
        method: 'POST', body: {
          policyNo: f.policyNo.value, accidentDate: f.accidentDate.value,
          accidentType: f.accidentType.value, reporterPhone: f.reporterPhone.value || null,
          description: f.description.value.trim(),
        },
      });
      toast(`报案成功：${c.caseNo}，管道已启动`, 'ok');
      refreshQueueBadge();
      location.hash = '#/case/' + c.caseNo;
    } catch (err) {
      toast('报案失败：' + err.message, 'err');
      btn.disabled = false; btn.textContent = '提交报案 →';
    }
  };
}

/* ───────── 案件列表 ───────── */
async function renderCases(view, presetQuery) {
  const q0 = presetQuery || state.caseQuery || '';
  let all = [];
  try { all = await api('/claims?limit=200'); } catch (e) { view.innerHTML = errCard(e); return; }
  const filters = { status: '', channel: '', q: q0, sort: 'time' };

  view.innerHTML = `
    <div class="card">
      <h3>案件列表<span class="sub" id="caseCount"></span></h3>
      <div class="row mb">
        <input type="search" id="caseQ" placeholder="案件号 / 保单号 / 描述…" style="max-width:300px" value="${esc(q0)}">
        <select id="caseSort" style="width:auto">
          <option value="time">最新报案</option><option value="amount">估损金额 ↓</option>
          <option value="risk">风险分 ↓</option><option value="conf">置信度 ↑</option>
        </select>
      </div>
      <div class="chips mb" id="statusChips">
        <button class="chip on" data-st="">全部状态</button>
        ${Object.entries(STATUS).map(([k, [l]]) => `<button class="chip" data-st="${k}">${l}</button>`).join('')}
      </div>
      <div class="chips mb" id="channelChips">
        <button class="chip on" data-ch="">全部通道</button>
        ${Object.entries(CHANNEL).map(([k, [l]]) => `<button class="chip" data-ch="${k}">${l}</button>`).join('')}
        <button class="chip" data-ch="NONE">未分级</button>
      </div>
      <div class="table-wrap"><table>
        <thead><tr><th>案件号</th><th>保单号</th><th>状态</th><th>通道</th><th>估损</th><th>应赔</th><th style="min-width:110px">风险</th><th>置信度</th><th>报案时间</th></tr></thead>
        <tbody id="caseRows"></tbody>
      </table></div>
    </div>`;

  const renderRows = () => {
    let rows = all.filter(c =>
      (!filters.status || c.status === filters.status) &&
      (!filters.channel || (filters.channel === 'NONE' ? !c.channel : c.channel === filters.channel)) &&
      (!filters.q || (c.caseNo + c.policyNo + (c.description || '')).toLowerCase().includes(filters.q.toLowerCase())));
    const sorters = {
      time: (a, b) => b.createdAt - a.createdAt,
      amount: (a, b) => b.estimateCents - a.estimateCents,
      risk: (a, b) => b.riskScore - a.riskScore,
      conf: (a, b) => (a.recognitionConfidence || 0) - (b.recognitionConfidence || 0),
    };
    rows.sort(sorters[filters.sort]);
    $('#caseCount').textContent = `${rows.length} / ${all.length} 件`;
    $('#caseRows').innerHTML = rows.map(c => `
      <tr class="clickable" data-no="${esc(c.caseNo)}">
        <td class="mono" style="color:var(--cyan)">${esc(c.caseNo)}</td>
        <td class="mono small muted">${esc(c.policyNo)}</td>
        <td>${stBadge(c.status)}</td><td>${chBadge(c.channel)}</td>
        <td class="money small">${c.estimateCents ? yuan(c.estimateCents) : '—'}</td>
        <td class="money small">${c.payableCents ? yuan(c.payableCents) : '—'}</td>
        <td>${riskMeter(c.riskScore)}</td>
        <td class="mono small">${c.recognitionConfidence ? pct(c.recognitionConfidence) : '—'}</td>
        <td class="small muted">${fmtTime(c.createdAt)}</td>
      </tr>`).join('') || '<tr><td colspan="9"><div class="empty"><em>▤</em>没有符合条件的案件</div></td></tr>';
    $$('#caseRows tr.clickable').forEach(tr => tr.onclick = () => location.hash = '#/case/' + tr.dataset.no);
  };

  $('#caseQ').oninput = e => { filters.q = e.target.value; state.caseQuery = filters.q; renderRows(); };
  $('#caseSort').onchange = e => { filters.sort = e.target.value; renderRows(); };
  $$('#statusChips .chip').forEach(ch => ch.onclick = () => {
    filters.status = ch.dataset.st;
    $$('#statusChips .chip').forEach(x => x.classList.toggle('on', x === ch)); renderRows();
  });
  $$('#channelChips .chip').forEach(ch => ch.onclick = () => {
    filters.channel = ch.dataset.ch;
    $$('#channelChips .chip').forEach(x => x.classList.toggle('on', x === ch)); renderRows();
  });
  renderRows();
}

/* ───────── 案件详情 ───────── */
async function renderDetail(caseNo, silent = false) {
  if (location.hash !== '#/case/' + caseNo) return; // 路由已切换时放弃过期重渲染
  const view = $('#view');
  let d, audit;
  try {
    [d, audit] = await Promise.all([api('/claims/' + caseNo), api('/claims/' + caseNo + '/tool-audit')]);
  } catch (e) { if (!silent) view.innerHTML = errCard(e); return; }
  const c = d.case;
  const materials = d.materials || [];
  const events = norm(d.events), approvals = norm(d.approvals);
  const biz = norm(d.ledgerBusiness), fund = norm(d.ledgerFund);
  const hasReport = materials.some(m => m.type === 'ACCIDENT_REPORT');
  const hasPhoto = materials.some(m => m.type === 'DAMAGE_PHOTO');
  const sig = JSON.stringify(d);

  const phaseIdx = PHASES.findIndex(([s]) => s === c.status);
  const stepper = PHASES.map(([s, lab], i) => {
    let cls = '';
    if (c.status === 'REJECTED') cls = i <= 2 ? 'done' : (i === 2 ? 'bad' : '');
    else if (c.status === 'MANUAL') cls = i < Math.max(phaseIdx, 3) ? 'done' : (i === Math.max(phaseIdx, 3) ? 'man' : '');
    else if (c.status === 'SETTLED') cls = i <= 7 ? 'done' : (i === 8 ? 'now' : '');
    else if (phaseIdx >= 0) cls = i < phaseIdx ? 'done' : i === phaseIdx ? 'now' : '';
    if (c.status === 'CLOSED') cls = 'done';
    return `<div class="step ${cls}"><i></i><span>${lab}</span></div>`;
  }).join('');

  const needApprove = c.status === 'APPROVING';
  const needManual = c.status === 'MANUAL';
  const canDecide = needApprove || needManual;
  const stepOptions = needManual ? [['MANUAL_HANDLE', '人工处理']] : [['APPROVE', '赔付审批'], ['REJECT_CONFIRM', '拒赔确认']];

  view.innerHTML = `
    <div class="card">
      <div class="detail-head">
        <div style="flex:1;min-width:220px">
          <div class="row"><span class="case-no">${esc(c.caseNo)}</span>${stBadge(c.status)}${chBadge(c.channel)}</div>
          <div class="small muted mt" style="margin-top:6px">${esc(c.description || '')}</div>
        </div>
        <div class="row">
          <button class="btn sm ghost" id="verifyBtn">⛓ 证据链校验</button>
          <button class="btn sm ghost" id="reviewBtn">✦ AI 复核</button>
          <button class="btn sm danger" id="disputeBtn">异议升级</button>
        </div>
      </div>
      <div class="stepper mt">${stepper}</div>
      ${c.rejectReason ? `<p class="small" style="color:var(--rose)">拒赔原因：${esc(c.rejectReason)}</p>` : ''}
      <div class="kv mt">
        ${kv('保单号', `<span class="mono">${esc(c.policyNo)}</span>`)}
        ${kv('机构 / 险种', `${esc(c.org)} / ${esc(c.insuranceType)}`)}
        ${kv('事故日期 / 类型', `${esc(c.accidentDate)} / ${accLab(c.accidentType)}`)}
        ${kv('估损金额', `<span class="money">${c.estimateCents ? yuan(c.estimateCents) : '—'}</span>`)}
        ${kv('应赔金额', `<span class="money">${c.payableCents ? yuan(c.payableCents) : '—'}</span>`)}
        ${kv('风险评分', riskMeter(c.riskScore))}
        ${kv('识别置信度', c.recognitionConfidence ? `<span class="mono" style="color:${c.recognitionConfidence >= 0.85 ? 'var(--green)' : 'var(--warn)'}">${pct(c.recognitionConfidence)}</span>` : '—')}
        ${kv('报案 / 更新', `<span class="small muted">${fmtFull(c.createdAt)}<br>${fmtFull(c.updatedAt)}</span>`)}
      </div>
      <div id="verifyOut"></div><div id="reviewOut"></div>
    </div>

    <div class="tabs" id="detailTabs">
      ${[['materials', `材料 (${materials.length})`], ['timeline', `决策时间线 (${events.length})`], ['approval', `人工审批 (${approvals.length})`], ['ledger', '双账本'], ['tools', `工具审计 (${audit.length})`], ['notify', '客户通知']].map(([k, l]) =>
        `<button data-tab="${k}" class="${state.detailTab === k ? 'on' : ''}">${l}</button>`).join('')}
    </div>
    <div id="tabBody"></div>`;

  const tabBody = $('#tabBody');
  const renderTab = () => {
    $$('#detailTabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === state.detailTab));
    if (state.detailTab === 'materials') {
      tabBody.innerHTML = `<div class="card">
        <h3>理赔材料<span class="sub">门禁：事故认定书 + 损伤照片齐备后管道自动放行</span></h3>
        <div class="mat-check">
          <span class="mc ${hasReport ? 'ok' : 'no'}">${hasReport ? '✓' : '○'} 事故认定书</span>
          <span class="mc ${hasPhoto ? 'ok' : 'no'}">${hasPhoto ? '✓' : '○'} 损伤照片</span>
        </div>
        <div class="table-wrap"><table><thead><tr><th>类型</th><th>文件</th><th>SHA-256</th><th>识别置信度</th><th>查看</th></tr></thead><tbody>
          ${materials.map(m => {
            const hasFile = m.sizeBytes != null && m.sizeBytes > 0;
            const url = `/api/claims/${c.caseNo}/materials/${esc(m.materialId)}/content`;
            const isImg = hasFile && (m.contentType || '').startsWith('image/');
            const size = hasFile ? (m.sizeBytes >= 1048576 ? (m.sizeBytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(m.sizeBytes / 1024)) + ' KB') : '';
            return `<tr><td>${matLab(m.type)}</td>
            <td class="small">${hasFile ? `${esc(m.fileName || m.materialId)} <span class="faint mono">${size}</span>` : `<span class="faint" title="${esc(m.storageKey || '')}">仅登记（无文件）</span>`}</td>
            <td class="mono small faint" title="${esc(m.sha256 || '')}">${esc((m.sha256 || '').slice(0, 16))}…</td>
            <td class="mono small">${m.confidence ? pct(m.confidence) : '—'}</td>
            <td>${hasFile ? `<span class="row" style="gap:8px">${isImg ? `<a href="${url}" target="_blank" rel="noopener" title="点击查看原图"><img class="mat-thumb" src="${url}" alt="${esc(m.fileName || '材料')}" loading="lazy"></a>` : ''}<a href="${url}" target="_blank" rel="noopener">查看</a></span>` : '<span class="faint">—</span>'}</td></tr>`;
          }).join('') || '<tr><td colspan="5"><div class="empty"><em>▣</em>暂无材料</div></td></tr>'}
        </tbody></table></div>
        ${!TERMINAL.has(c.status) ? `
        <h3 class="mt">补充材料</h3>
        <form id="matForm" class="row">
          <select name="type" style="width:auto">${MAT_TYPES.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select>
          <input type="file" name="file" accept=".jpg,.jpeg,.png,.gif,.webp,.pdf" style="flex:1;min-width:220px" required>
          <input type="number" name="confidence" placeholder="置信度 0~1" step="0.01" min="0" max="1" style="width:120px">
          <button class="btn primary sm" type="submit">上传文件</button>
          <button class="btn sm ghost" type="button" id="quickMat">一键补齐门禁材料</button>
        </form>
        <p class="hint">选择文件上传后可在线查看（SHA-256 按文件内容计算）；「一键补齐」为演示元数据登记，无文件本体。</p>` : '<p class="hint mt">案件已终结，材料归档只读。</p>'}
      </div>`;
      const mf = $('#matForm');
      if (mf) {
        mf.onsubmit = async e => {
          e.preventDefault();
          const f = e.target;
          const file = f.file.files[0];
          if (!file) return toast('请选择文件', 'err');
          const fd = new FormData();
          fd.append('file', file);
          fd.append('type', f.type.value);
          fd.append('confidence', String(Number(f.confidence.value) || 0.9));
          try {
            const res = await fetch(`/api/claims/${c.caseNo}/materials/upload`, { method: 'POST', body: fd });
            const data = await res.json().catch(() => null);
            if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
            toast('文件已上传，哈希已按内容计算', 'ok');
            renderDetail(c.caseNo, true);
          } catch (err) { toast('材料上传失败：' + err.message, 'err'); }
        };
        $('#quickMat').onclick = async () => {
          const batch = [];
          if (!hasReport) batch.push({ type: 'ACCIDENT_REPORT', storageKey: `uploads/${c.caseNo}-report.pdf`, confidence: 0.95 });
          if (!hasPhoto) batch.push({ type: 'DAMAGE_PHOTO', storageKey: `uploads/${c.caseNo}-photo.jpg`, confidence: 0.92 });
          if (!batch.length) return toast('门禁材料已齐备', 'info');
          try {
            await api(`/claims/${c.caseNo}/materials`, { method: 'POST', body: batch });
            toast(`已补齐 ${batch.length} 项门禁材料，管道将在 2 秒内放行`, 'ok');
            renderDetail(c.caseNo, true);
          } catch (err) { toast('补件失败：' + err.message, 'err'); }
        };
      }
    } else if (state.detailTab === 'timeline') {
      tabBody.innerHTML = `<div class="card"><h3>决策时间线<span class="sub">每步归档输出哈希，可追溯可 replay</span></h3>
        <div class="timeline">${events.map(e => `
          <div class="tl-item ${/APPROVE|REJECT_CONFIRM|MANUAL_HANDLE/.test(e.step) ? 'human' : ''} ${e.step === 'REJECT' ? 'bad' : ''}">
            <div class="tl-head"><b>${esc(stepLab(e.step))}</b>
              <span class="badge b-none" style="font-weight:400">${esc(e.actor || '')}</span>
              ${e.model_version ? `<span class="small faint mono">${esc(e.model_version)}</span>` : ''}
              ${e.confidence != null && e.confidence > 0 ? `<span class="small muted">置信度 ${pct(e.confidence)}</span>` : ''}
              ${(e.prompt_tokens || e.completion_tokens) ? `<span class="small faint mono">${e.prompt_tokens}↑ ${e.completion_tokens}↓ tk</span>` : ''}
              <span class="tm">${fmtFull(e.ts)}</span></div>
            ${e.output_json ? `<details class="json"><summary>输出快照</summary><pre>${esc(prettyJson(e.output_json))}</pre></details>` : ''}
          </div>`).join('') || '<div class="empty"><em>⌘</em>暂无决策记录</div>'}
        </div></div>`;
    } else if (state.detailTab === 'approval') {
      tabBody.innerHTML = `<div class="grid ${canDecide ? 'g-1-2' : ''}">
        ${canDecide ? `<div class="card" style="border-color:rgba(251,191,36,.35)">
          <h3>人工决策<span class="sub">${needManual ? 'MANUAL 通道处理' : '审批节点'}</span></h3>
          <form id="decForm">
            <div class="field"><label>决策步骤</label>
              <select name="step">${stepOptions.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select></div>
            <div class="field"><label>结论</label>
              <select name="approved"><option value="true">通过 / 确认</option><option value="false">驳回</option></select></div>
            <div class="field" id="amountField"><label>赔付金额（元）</label>
              <input type="number" name="amountYuan" step="0.01" min="0" value="${((c.payableCents || c.estimateCents || 0) / 100).toFixed(2)}">
              <span class="hint">人工处理通道：批准必须填金额 &gt; 0（服务端强制校验）；驳回/关闭则拒赔</span></div>
            <div class="field"><label>操作员</label><input type="text" name="operator" value="理赔员-01" required></div>
            <div class="field"><label>意见</label><textarea name="comment" placeholder="决策依据，将写入审批记录与证据链" style="min-height:60px"></textarea></div>
            <button class="btn primary" type="submit" style="width:100%">提交决策（signal 编排引擎）</button>
          </form>
        </div>` : ''}
        <div class="card"><h3>审批记录<span class="sub">大额 &gt; 10 万元需双人审批</span></h3>
          <div class="table-wrap"><table><thead><tr><th>步骤</th><th>操作员</th><th>结论</th><th>金额</th><th>意见</th><th>时间</th></tr></thead><tbody>
            ${approvals.map(a => `<tr><td>${esc(stepLab(a.step))}</td><td>${esc(a.operator)}</td>
              <td>${Number(a.approved) === 1 ? '<span class="st st-ok">通过</span>' : '<span class="st st-bad">驳回</span>'}</td>
              <td class="money small">${a.amount_cents ? yuan(a.amount_cents) : '—'}</td>
              <td class="small muted">${esc(a.comment || '')}</td><td class="small muted">${fmtTime(a.ts)}</td></tr>`).join('') || '<tr><td colspan="6"><div class="empty"><em>⚖</em>暂无人工决策</div></td></tr>'}
          </tbody></table></div>
          ${!canDecide && !TERMINAL.has(c.status) ? '<p class="hint mt">当前状态无需人工介入，管道自动推进中。</p>' : ''}
        </div>
      </div>`;
      const df = $('#decForm');
      if (df) {
        const syncAmount = () => {
          const isReject = df.step.value === 'REJECT_CONFIRM';
          $('#amountField').style.display = isReject ? 'none' : '';
        };
        df.step.onchange = syncAmount; syncAmount();
        df.onsubmit = async e => {
          e.preventDefault();
          const f = e.target;
          const approved = f.approved.value === 'true';
          const amountCents = Math.round((Number(f.amountYuan?.value) || 0) * 100);
          if (f.step.value === 'MANUAL_HANDLE' && approved && amountCents <= 0) {
            toast('人工批准必须填写大于0的赔付金额；不赔付请选驳回/关闭', 'err'); return;
          }
          try {
            await api(`/claims/${c.caseNo}/decision`, {
              method: 'POST', body: { step: f.step.value, approved, amountCents, operator: f.operator.value.trim(), comment: f.comment.value.trim() },
            });
            toast('决策已记录，编排引擎将在 2 秒内响应', 'ok');
            refreshQueueBadge();
            renderDetail(c.caseNo, true);
          } catch (err) { toast('决策提交失败：' + err.message, 'err'); }
        };
      }
    } else if (state.detailTab === 'ledger') {
      tabBody.innerHTML = `<div class="grid g2">
        <div class="card"><h3>业务账本<span class="sub">ledger_business</span></h3>${ledgerTable(biz, false)}</div>
        <div class="card"><h3>资金账本<span class="sub">ledger_fund · 幂等键防重</span></h3>${ledgerTable(fund, true)}</div>
      </div>
      <div class="card mt"><h3>一致性说明</h3><p class="small muted">支付以「案件号:步骤:批次」为幂等键写入 outbox，RocketMQ 事务消息（半消息 + 回查）保证业务账与资金账最终一致；日终对账差异进入 recon_diff 由人工核查。</p></div>`;
    } else if (state.detailTab === 'tools') {
      const rows = norm(audit);
      tabBody.innerHTML = `<div class="card"><h3>工具调用审计<span class="sub">工具网关白名单 + 全量留痕</span></h3>
        <div class="table-wrap"><table><thead><tr><th>工具</th><th>入参</th><th>结果</th><th>时间</th></tr></thead><tbody>
          ${rows.map(t => `<tr><td class="mono small" style="color:var(--cyan)">${esc(t.tool_name)}</td>
            <td><details class="json"><summary>入参</summary><pre>${esc(prettyJson(t.args_json))}</pre></details></td>
            <td><details class="json"><summary>结果</summary><pre>${esc(prettyJson(t.result_json))}</pre></details></td>
            <td class="small muted">${fmtFull(t.ts)}</td></tr>`).join('') || '<tr><td colspan="4"><div class="empty"><em>⌘</em>暂无工具调用</div></td></tr>'}
        </tbody></table></div></div>`;
    } else if (state.detailTab === 'notify') {
      tabBody.innerHTML = `<div class="card" style="max-width:720px">
        <h3>客户通知模拟<span class="sub">NotifyAgent · 只沟通不承诺</span></h3>
        <div class="chat-box" id="chatBox">${state.chat.map(m => `<div class="bubble ${m.role}">${esc(m.text)}</div>`).join('') || '<div class="bubble bot">您好，我是理赔通知助手。可以询问案件进度、赔付金额与到账时间。</div>'}</div>
        <form id="chatForm" class="row mt">
          <input type="text" name="q" placeholder="例如：我的赔款什么时候到账？" style="flex:1" required>
          <button class="btn primary sm" type="submit">发送</button>
        </form>
        <p class="hint mt">模型未配置时返回 503 降级提示，不影响案件主流程。</p>
      </div>`;
      $('#chatForm').onsubmit = async e => {
        e.preventDefault();
        const q = e.target.q.value.trim(); if (!q) return;
        state.chat.push({ role: 'user', text: q });
        e.target.q.value = '';
        const box = $('#chatBox');
        box.innerHTML = state.chat.map(m => `<div class="bubble ${m.role}">${esc(m.text)}</div>`).join('') + '<div class="bubble bot" id="typing">正在生成…</div>';
        box.scrollTop = box.scrollHeight;
        try {
          const r = await api('/agent/notify', { method: 'POST', body: { caseNo: c.caseNo, question: q } });
          state.chat.push({ role: 'bot', text: r.reply });
        } catch (err) {
          state.chat.push({ role: 'err', text: err.status === 503 ? '通知模型端点不可用（未配置 Key 或熔断中）。案件进度请以详情页状态为准。' : '请求失败：' + err.message });
        }
        renderTab();
        $('#chatBox').scrollTop = $('#chatBox').scrollHeight;
      };
    }
  };
  $$('#detailTabs button').forEach(b => b.onclick = () => { state.detailTab = b.dataset.tab; renderTab(); });
  renderTab();

  $('#verifyBtn').onclick = async () => {
    try {
      const r = await api(`/claims/${c.caseNo}/evidence/verify`);
      $('#verifyOut').innerHTML = `<p class="small mt" style="color:${r.chainValid ? 'var(--green)' : 'var(--rose)'}">
        ${r.chainValid ? '✓ 证据哈希链校验通过：全部决策快照未被篡改' : '✕ 证据链校验失败或无归档记录'}</p>`;
      toast(r.chainValid ? '证据链完整 ✓' : '证据链校验未通过', r.chainValid ? 'ok' : 'err');
    } catch (e) { toast('校验失败：' + e.message, 'err'); }
  };
  $('#reviewBtn').onclick = async () => {
    const btn = $('#reviewBtn'); btn.disabled = true; btn.textContent = '复核中…';
    try {
      const r = await api(`/agent/review/${c.caseNo}`, { method: 'POST' });
      $('#reviewOut').innerHTML = `<details class="json mt" open><summary>AI 复核意见（ReviewAgent · 仅建议，不改变裁决）</summary><pre>${esc(typeof r.review === 'string' ? r.review : prettyJson(r.review))}</pre></details>`;
    } catch (e) {
      toast(e.status === 503 ? '复核模型不可用，请人工审阅时间线与账本' : '复核失败：' + e.message, e.status === 503 ? 'info' : 'err');
    }
    btn.disabled = false; btn.textContent = '✦ AI 复核';
  };
  $('#disputeBtn').onclick = () => {
    const box = modal(`<h3>异议升级</h3>
      <p class="small muted mb">客户对结论有异议时，案件升级人工复核（MANUAL），编排引擎收到 dispute signal。</p>
      <div class="field"><label>异议理由</label><textarea id="disputeReason" placeholder="例如：对定损金额不认可，要求重新核价"></textarea></div>
      <div class="row spread"><button class="btn ghost" id="disputeCancel">取消</button>
      <button class="btn danger" id="disputeOk">确认升级</button></div>`);
    $('#disputeCancel', box).onclick = closeModal;
    $('#disputeOk', box).onclick = async () => {
      const reason = $('#disputeReason', box).value.trim();
      if (!reason) return toast('请填写异议理由', 'err');
      try {
        await api(`/claims/${c.caseNo}/dispute`, { method: 'POST', body: { reason } });
        closeModal(); toast('异议已登记，案件升级人工处理', 'ok');
        renderDetail(c.caseNo, true);
      } catch (e) { toast('异议提交失败：' + e.message, 'err'); }
    };
  };

  /* 非终结态轮询：管道推进实时可见；用户正在输入时不打断 */
  stopPoll();
  if (!TERMINAL.has(c.status)) {
    pollTimer = setInterval(async () => {
      if (location.hash !== '#/case/' + caseNo) return;
      try {
        const nd = await api('/claims/' + caseNo);
        if (JSON.stringify(nd) === sig) return;
        const active = document.activeElement;
        if (active && $('#view').contains(active) && /INPUT|TEXTAREA|SELECT/.test(active.tagName)) return;
        renderDetail(caseNo, true);
      } catch { /* ignore */ }
    }, 3000);
  }
}

function kv(k, v) { return `<div><div class="k">${k}</div><div class="v">${v}</div></div>`; }
function ledgerTable(rows, isFund) {
  if (!rows.length) return '<div class="empty"><em>▦</em>暂无账本记录</div>';
  return `<div class="table-wrap"><table><thead><tr><th>批次</th>${isFund ? '<th>资金渠道</th>' : ''}<th>方向</th><th>金额</th><th>时间</th></tr></thead><tbody>
    ${rows.map(r => `<tr><td class="mono small">${r.batch}</td>${isFund ? `<td class="small">${esc(r.fund_channel || '')}</td>` : ''}
      <td>${r.direction === 'OUT' ? '<span class="st st-bad">OUT 赔付</span>' : '<span class="st st-ok">IN</span>'}</td>
      <td class="money small">${yuan(r.amount_cents)}</td><td class="small muted">${fmtFull(r.ts)}</td></tr>`).join('')}
  </tbody></table></div>`;
}

/* ───────── 人工工作台 ───────── */
async function renderWorkbench(view) {
  stopPoll(); // 重渲染前先清旧定时器，防止轮询自我复制
  let all;
  try { all = await api('/claims?limit=200'); } catch (e) { view.innerHTML = errCard(e); return; }
  const queue = all.filter(c => ['APPROVING', 'MANUAL', 'MATERIALS_PENDING'].includes(c.status))
    .sort((a, b) => b.riskScore - a.riskScore);

  view.innerHTML = `
    <div class="row spread mb">
      <p class="muted small">待人工介入 <b class="mono" style="color:var(--amber)">${queue.length}</b> 件 · 按风险分降序 · 每 8 秒自动刷新</p>
      <button class="btn sm ghost" id="wbRefresh">立即刷新</button>
    </div>
    <div class="grid g2" id="queueGrid">
      ${queue.map(c => queueCard(c)).join('') || '<div class="card" style="grid-column:1/-1"><div class="empty"><em>✓</em>工作台已清空，没有待人工介入的案件</div></div>'}
    </div>`;

  const bind = () => {
    $$('.q-head .no', view).forEach(el => el.onclick = () => location.hash = '#/case/' + el.dataset.no);
    $$('form.q-dec', view).forEach(f => f.onsubmit = async e => {
      e.preventDefault();
      const caseNo = f.dataset.no;
      const approved = f.approved.value === 'true';
      const amountCents = Math.round((Number(f.amountYuan?.value) || 0) * 100);
      if (f.step.value === 'MANUAL_HANDLE' && approved && amountCents <= 0) {
        toast('人工批准必须填写大于0的赔付金额；不赔付请选驳回/关闭', 'err'); return;
      }
      try {
        await api(`/claims/${caseNo}/decision`, {
          method: 'POST', body: { step: f.step.value, approved, amountCents, operator: f.operator.value.trim() || '理赔员-01', comment: f.comment.value.trim() },
        });
        toast(`${caseNo} 决策已提交`, 'ok');
        refreshQueueBadge();
        if (location.hash.startsWith('#/workbench')) renderWorkbench(view);
      } catch (err) { toast('决策失败：' + err.message, 'err'); }
    });
    $$('button.q-mat', view).forEach(b => b.onclick = async () => {
      const caseNo = b.dataset.no;
      try {
        await api(`/claims/${caseNo}/materials`, {
          method: 'POST', body: [
            { type: 'ACCIDENT_REPORT', storageKey: `uploads/${caseNo}-report.pdf`, confidence: 0.95 },
            { type: 'DAMAGE_PHOTO', storageKey: `uploads/${caseNo}-photo.jpg`, confidence: 0.92 },
          ],
        });
        toast(`${caseNo} 门禁材料已补齐，管道自动放行`, 'ok');
        if (location.hash.startsWith('#/workbench')) renderWorkbench(view);
      } catch (err) { toast('补件失败：' + err.message, 'err'); }
    });
  };
  bind();
  $('#wbRefresh').onclick = () => renderWorkbench(view);
  pollTimer = setInterval(() => {
    if (!location.hash.startsWith('#/workbench')) return;
    const active = document.activeElement;
    if (active && $('#view').contains(active) && /INPUT|TEXTAREA|SELECT/.test(active.tagName)) return;
    renderWorkbench(view);
  }, 8000);
}

function queueCard(c) {
  const urgent = c.riskScore >= 60 || c.channel === 'INVESTIGATE';
  const lowConf = c.recognitionConfidence > 0 && c.recognitionConfidence < 0.85;
  const isApprove = c.status === 'APPROVING';
  const isManual = c.status === 'MANUAL';
  const isMat = c.status === 'MATERIALS_PENDING';
  const stepOptions = isManual ? [['MANUAL_HANDLE', '人工处理']] : isApprove ? [['APPROVE', '赔付审批'], ['REJECT_CONFIRM', '拒赔确认']] : [];
  return `<div class="card queue-card ${urgent ? 'urgent' : isApprove ? 'approve' : ''}">
    <div class="q-head">
      <span class="no" data-no="${esc(c.caseNo)}">${esc(c.caseNo)}</span>
      ${stBadge(c.status)}${chBadge(c.channel)}
      ${urgent ? '<span class="badge b-INVESTIGATE">高风险</span>' : ''}
      ${lowConf ? '<span class="badge b-MANUAL">低置信度</span>' : ''}
    </div>
    <div class="desc">${esc(c.description || '')}</div>
    <div class="q-meta">
      <span>估损 <b>${c.estimateCents ? yuan(c.estimateCents) : '—'}</b></span>
      <span>应赔 <b>${c.payableCents ? yuan(c.payableCents) : '—'}</b></span>
      <span>风险 <b style="color:${c.riskScore >= 60 ? 'var(--rose)' : c.riskScore >= 30 ? 'var(--warn)' : 'var(--green)'}">${c.riskScore}</b></span>
      ${c.recognitionConfidence ? `<span>置信度 <b>${pct(c.recognitionConfidence)}</b></span>` : ''}
    </div>
    ${isMat ? `<div class="inline-form">
        <span class="small muted">缺门禁材料，补齐后管道自动推进：</span>
        <button class="btn sm gold q-mat" data-no="${esc(c.caseNo)}">一键补件（认定书 + 照片）</button>
        <button class="btn sm ghost" onclick="location.hash='#/case/${esc(c.caseNo)}'">详情</button>
      </div>` : ''}
    ${stepOptions.length ? `<form class="inline-form q-dec" data-no="${esc(c.caseNo)}">
      <select name="step">${stepOptions.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}</select>
      <select name="approved"><option value="true">通过</option><option value="false">驳回</option></select>
      <input type="number" name="amountYuan" step="0.01" min="0" placeholder="金额(元)" value="${((c.payableCents || c.estimateCents || 0) / 100).toFixed(2)}" title="赔付金额（元）">
      <input type="text" name="operator" placeholder="操作员" value="理赔员-01">
      <input type="text" name="comment" placeholder="意见（选填）">
      <button class="btn sm primary" type="submit">提交</button>
    </form>` : ''}
  </div>`;
}

/* ───────── 审计追溯 ───────── */
async function renderAudit(view) {
  let rows;
  try { rows = norm(await api('/audit/recent?limit=200')); } catch (e) { view.innerHTML = errCard(e); return; }
  const steps = [...new Set(rows.map(r => r.step))];
  const filters = { step: '', q: '' };

  view.innerHTML = `
    <div class="card">
      <h3>决策审计流水<span class="sub">claim_decision · 哈希链归档 · 最多 200 条</span></h3>
      <div class="audit-stats mb">
        <span>记录 <b>${rows.length}</b></span>
        <span>Prompt Tokens <b>${rows.reduce((a, r) => a + (Number(r.prompt_tokens) || 0), 0).toLocaleString()}</b></span>
        <span>Completion Tokens <b>${rows.reduce((a, r) => a + (Number(r.completion_tokens) || 0), 0).toLocaleString()}</b></span>
        <span>涉及案件 <b>${new Set(rows.map(r => r.case_no)).size}</b></span>
      </div>
      <div class="row mb">
        <select id="auditStep" style="width:auto"><option value="">全部步骤</option>
          ${steps.map(s => `<option value="${esc(s)}">${esc(stepLab(s))}</option>`).join('')}</select>
        <input type="search" id="auditQ" placeholder="案件号 / 操作员 / 模型…" style="max-width:280px">
      </div>
      <div class="table-wrap"><table>
        <thead><tr><th>时间</th><th>案件</th><th>步骤</th><th>执行者</th><th>模型版本</th><th>置信度</th><th>Tokens</th><th>输出</th></tr></thead>
        <tbody id="auditRows"></tbody>
      </table></div>
    </div>`;

  const renderRows = () => {
    const list = rows.filter(r =>
      (!filters.step || r.step === filters.step) &&
      (!filters.q || ((r.case_no || '') + (r.actor || '') + (r.model_version || '')).toLowerCase().includes(filters.q.toLowerCase())));
    $('#auditRows').innerHTML = list.map(r => `
      <tr>
        <td class="small muted mono">${fmtFull(r.ts)}</td>
        <td><a href="#/case/${esc(r.case_no)}" class="mono small" style="color:var(--cyan);text-decoration:none">${esc(r.case_no)}</a></td>
        <td>${esc(stepLab(r.step))}</td>
        <td class="small">${esc(r.actor || '')}</td>
        <td class="small mono faint">${esc(r.model_version || '—')}</td>
        <td class="small mono">${r.confidence > 0 ? pct(r.confidence) : '—'}</td>
        <td class="small mono faint">${r.prompt_tokens || 0}↑ ${r.completion_tokens || 0}↓</td>
        <td>${r.output_json ? `<details class="json"><summary>查看</summary><pre>${esc(prettyJson(r.output_json))}</pre></details>` : '—'}</td>
      </tr>`).join('') || '<tr><td colspan="8"><div class="empty"><em>⌘</em>无匹配记录</div></td></tr>';
  };
  $('#auditStep').onchange = e => { filters.step = e.target.value; renderRows(); };
  $('#auditQ').oninput = e => { filters.q = e.target.value; renderRows(); };
  renderRows();
}

/* ───────── 风控批处理 ───────── */
async function renderBatch(view) {
  let gs, gl, gh, ghist, dual, shLatest, canary, fstrategy, nebula;
  try {
    [gs, gl, gh, ghist, dual, shLatest, canary, fstrategy, nebula] = await Promise.all([
      api('/graph/summary'), api('/gold/latest'), api('/gold/history'), api('/graph/history'),
      api('/calc-dual-run/stats'), api('/shadow/latest'), api('/canary/config'),
      api('/fraud/strategy'), api('/graph/nebula/status'),
    ]);
  } catch (e) { view.innerHTML = errCard(e); return; }
  const run = gs.latestRun ? norm([gs.latestRun])[0] : null;
  const gr = gl.run ? norm([gl.run])[0] : null;
  const results = norm(gl.results);
  const shops = gs.shops || [];
  const flaggedShops = shops.filter(s => s.flagged).length;
  const passRate = gr && gr.total ? Math.round(gr.passed / gr.total * 100) : 0;
  const shRun = shLatest.run ? norm([shLatest.run])[0] : null;

  view.innerHTML = `
    <div class="grid g4">
      ${statCard('c-teal', '图谱节点', gs.nodeCount ?? 0, '案件 / 保单 / 修理厂', '个')}
      ${statCard('c-blue', '图谱边', gs.edgeCount ?? 0, 'INSURED_BY / REPAIRED_AT', '条')}
      ${statCard('c-rose', '标记修理厂', flaggedShops, `人工标记 ${gs.manualFlagCount ?? 0} · 批处理标记 ${gs.batchFlagCount ?? 0}`, '家')}
      ${statCard('c-amber', '金标通过率', gr ? passRate + '%' : '—', gr ? `${gr.passed}/${gr.total} 案通过` : '尚未运行', '')}
    </div>
    <div class="grid g2 mt">
      <div class="card">
        <h3>欺诈关联图谱<span class="sub">T+1 批处理 · 全量重建 · 标记回写 graph.flagged 工具</span></h3>
        <div class="kv">
          ${kv('最近构建', run ? `${fmtFull(run.ts)}<br><span class="small muted">${esc(run.trigger_by)} · ${run.duration_ms}ms</span>` : '尚未运行')}
          ${kv('新增标记', run ? `<span class="mono">${run.new_flag_count}</span> 条` : '—')}
        </div>
        <div class="row spread mt">
          <span class="hint">标记规则：同厂 ≥3 案 或 ≥2 笔高额案（&gt;¥5 万）</span>
          <button class="btn sm" id="graphRun">立即构建</button>
        </div>
        <div class="table-wrap mt"><table>
          <thead><tr><th>修理厂</th><th>关联案件</th><th>高额案</th><th>累计估损</th><th>状态</th></tr></thead>
          <tbody>
            ${shops.map(s => `<tr>
              <td><b>${esc(s.shopName)}</b><div class="small faint">${(s.reasons || []).map(esc).join('；')}</div></td>
              <td>${(s.cases || []).map(no => `<a href="#/case/${esc(no)}" class="mono small" style="color:var(--cyan);text-decoration:none">${esc(no)}</a>`).join('<br>')}</td>
              <td class="mono small">${s.highAmountCount}</td>
              <td class="money small">${yuan(s.totalEstimateCents)}</td>
              <td>${s.flagged ? '<span class="st st-bad">已标记</span>' : '<span class="st st-ok">正常</span>'}</td>
            </tr>`).join('') || '<tr><td colspan="5"><div class="empty"><em>◉</em>暂无修理厂关联数据</div></td></tr>'}
          </tbody>
        </table></div>
        <h3 class="mt">构建历史<span class="sub">最近 5 次</span></h3>
        <div class="feed">${norm(ghist).slice(0, 5).map(r => `
          <div class="feed-item"><span class="t">${fmtTime(r.ts)}</span>
            <div><span class="who">${esc(r.trigger_by)}</span>
              <span class="what"> · ${r.node_count} 节点 / ${r.edge_count} 边 / 新标记 ${r.new_flag_count} · ${r.duration_ms}ms</span></div>
          </div>`).join('') || '<p class="hint">暂无构建记录</p>'}
        </div>
      </div>
      <div class="card">
        <h3>金标回归<span class="sub">确定性核心重放 · 漂移即红 · 不触碰 LLM</span></h3>
        <div class="kv">
          ${kv('最近运行', gr ? `${fmtFull(gr.ts)}<br><span class="small muted">${esc(gr.trigger_by)} · ${gr.duration_ms}ms</span>` : '尚未运行')}
          ${kv('结果', gr ? (gr.failed > 0 ? `<span style="color:var(--rose)">${gr.failed} 案漂移</span>` : '<span style="color:var(--green)">全部通过 ✓</span>') : '—')}
        </div>
        <div class="row spread mt">
          <span class="hint">重放：核保 → 欺诈评分 → 分级 → 理算 → 审批数</span>
          <button class="btn sm" id="goldRun">执行回归</button>
        </div>
        <h3 class="mt">运行历史<span class="sub">最近 8 次</span></h3>
        <div class="table-wrap"><table>
          <thead><tr><th>时间</th><th>触发</th><th>通过/总数</th><th>结果</th></tr></thead>
          <tbody>
            ${norm(gh).slice(0, 8).map(r => `<tr>
              <td class="small muted mono">${fmtFull(r.ts)}</td>
              <td class="small">${esc(r.trigger_by)}</td>
              <td class="mono small">${r.passed}/${r.total}</td>
              <td>${r.failed > 0 ? '<span class="st st-bad">漂移</span>' : '<span class="st st-ok">通过</span>'}</td>
            </tr>`).join('') || '<tr><td colspan="4"><div class="empty"><em>◉</em>暂无运行记录</div></td></tr>'}
          </tbody>
        </table></div>
        <p class="hint mt">金标案 = 核赔师标注的期望裁决。规则引擎 / 计算器 / 评分器 / 阈值任何改动导致裁决偏离金标，回归立即报红。生产由 XXL-Job 日终调度。</p>
      </div>
    </div>
    <div class="grid g2 mt">
      <div class="card">
        <h3>理算双跑监控<span class="sub">F6 LLM 草案 vs 确定性计算器 · 机器版人工推翻率</span></h3>
        <div class="kv">
          ${kv('比对总数', `<span class="mono">${dual.total ?? 0}</span>`)}
          ${kv('一致率', `<span class="mono" style="color:${(dual.matchRate ?? 1) >= 0.95 ? 'var(--green)' : 'var(--amber)'}">${((dual.matchRate ?? 1) * 100).toFixed(1)}%</span>`)}
          ${kv('差异数', `<span class="mono">${dual.mismatches ?? 0}</span>`)}
        </div>
        <p class="hint mt">最终金额以计算器为准；LLM 草案差异落 calc_dual_run 供监控。模型不可用自动降级单跑。</p>
      </div>
      <div class="card">
        <h3>影子双跑<span class="sub">候选阈值重放历史案 · 不影响生产案件</span></h3>
        <div class="kv">
          ${kv('最近重放', shRun ? `${fmtFull(shRun.ts)}<br><span class="small muted">${shRun.total} 案 · 漂移 <b style="color:${shRun.drift_count > 0 ? 'var(--amber)' : 'var(--green)'}">${shRun.drift_count}</b></span>` : '尚未运行')}
        </div>
        <div class="row spread mt">
          <span class="hint">上线新阈值前先跑影子，核查漂移面</span>
          <button class="btn sm" id="shadowRun">执行影子双跑</button>
        </div>
      </div>
    </div>
    <div class="grid g2 mt">
      <div class="card">
        <h3>灰度发布<span class="sub">案件号哈希分桶 + 机构/险种白名单 · 候选阈值</span></h3>
        <form id="canaryForm">
          <div class="field"><label>启用灰度</label>
            <select name="enabled"><option value="false" ${canary.enabled ? '' : 'selected'}>关闭</option><option value="true" ${canary.enabled ? 'selected' : ''}>开启</option></select></div>
          <div class="field"><label>灰度比例（%）</label>
            <input type="number" name="percent" min="0" max="100" value="${canary.percent ?? 0}"></div>
          <div class="field"><label>候选快赔阈值（元，留空沿用基线）</label>
            <input type="number" name="fastYuan" step="1" min="0" value="${canary.candidateFastLimitCents != null ? canary.candidateFastLimitCents / 100 : ''}" placeholder="如 3000"></div>
          <button class="btn sm primary" type="submit">保存灰度配置</button>
        </form>
        <p class="hint mt">CANARY 组用候选阈值分级，BASELINE 组与金标回归不受影响。</p>
      </div>
      <div class="card">
        <h3>图存储与评分引擎<span class="sub">可选增强 · 默认关系库 + 规则评分</span></h3>
        <div class="kv">
          ${kv('欺诈评分策略', fstrategy.strategy === 'XGBOOST' ? '<span style="color:var(--cyan)">XGBoost(JPMML) 模型</span>' : '确定性规则')}
          ${kv('NebulaGraph 实时图', nebula.enabled ? (nebula.healthy ? `<span style="color:var(--green)">在线</span> · ${nebula.caseVertices} 案件顶点` : '<span style="color:var(--rose)">离线</span>') : '未启用（关系库 T+1）')}
        </div>
        <p class="hint mt">XGBoost 演示模型由合成样本训练（deploy/model/），生产换真实标注案重训；NebulaGraph 实时增量与 T+1 重建互为对账。</p>
      </div>
    </div>
    <div class="card mt">
      <h3>金标逐案结果<span class="sub">${gr ? '运行 #' + gr.id + ' · ' + fmtFull(gr.ts) + ' · 共 ' + results.length + ' 案' : '尚未运行'}</span></h3>
      <div class="table-wrap"><table>
        <thead><tr><th>编号</th><th>场景</th><th>结果</th><th>漂移明细</th></tr></thead>
        <tbody>
          ${results.slice(0, 100).map(r => `<tr>
            <td class="mono small" style="color:var(--cyan)">${esc(r.gold_id)}</td>
            <td>${esc(r.name)}</td>
            <td>${Number(r.passed) === 1 ? '<span class="st st-ok">通过</span>' : '<span class="st st-bad">漂移</span>'}</td>
            <td>${Number(r.passed) === 1 ? '<span class="faint small">—</span>' : `<details class="json"><summary>查看差异</summary><pre>${esc(prettyJson(r.mismatches_json))}</pre></details>`}</td>
          </tr>`).join('') || '<tr><td colspan="4"><div class="empty"><em>◉</em>暂无结果，点击「执行回归」</div></td></tr>'}
        </tbody>
      </table></div>
      ${results.length > 100 ? `<p class="hint mt">仅显示前 100 案（共 ${results.length} 案），完整结果见 GET /api/gold/latest。</p>` : ''}
    </div>`;

  $('#graphRun').onclick = async () => {
    const btn = $('#graphRun'); btn.disabled = true; btn.textContent = '构建中…';
    try {
      const r = await api('/graph/build', { method: 'POST' });
      toast(`图谱构建完成：${r.nodeCount} 节点 / ${r.edgeCount} 边，新增标记 ${r.newFlagCount} 条`, 'ok');
      renderBatch(view);
    } catch (e) {
      toast('图谱构建失败：' + e.message, 'err');
      btn.disabled = false; btn.textContent = '立即构建';
    }
  };
  $('#goldRun').onclick = async () => {
    const btn = $('#goldRun'); btn.disabled = true; btn.textContent = '回归中…';
    try {
      const r = await api('/gold/run', { method: 'POST' });
      toast(r.failed > 0 ? `${r.failed} 案金标漂移，请立即核查规则改动` : `金标回归 ${r.passed}/${r.total} 全部通过`, r.failed > 0 ? 'err' : 'ok');
      renderBatch(view);
    } catch (e) {
      toast('金标回归失败：' + e.message, 'err');
      btn.disabled = false; btn.textContent = '执行回归';
    }
  };
  $('#shadowRun').onclick = async () => {
    const btn = $('#shadowRun'); btn.disabled = true; btn.textContent = '重放中…';
    try {
      const r = await api('/shadow/run', { method: 'POST', body: {} });
      toast(`影子双跑：${r.total} 案，漂移 ${r.driftCount}（${(r.driftRate * 100).toFixed(1)}%）`, r.driftCount > 0 ? 'err' : 'ok');
      renderBatch(view);
    } catch (e) {
      toast('影子双跑失败：' + e.message, 'err');
      btn.disabled = false; btn.textContent = '执行影子双跑';
    }
  };
  $('#canaryForm').onsubmit = async e => {
    e.preventDefault();
    const f = e.target;
    const body = { enabled: f.enabled.value === 'true', percent: Number(f.percent.value) || 0 };
    if (f.fastYuan.value !== '') body.candidateFastLimitCents = Math.round(Number(f.fastYuan.value) * 100);
    try {
      await api('/canary/config', { method: 'POST', body });
      toast('灰度配置已保存', 'ok');
      renderBatch(view);
    } catch (err) { toast('灰度配置保存失败：' + err.message, 'err'); }
  };
}

/* ───────── 启动 ───────── */
$('#menuBtn').onclick = () => $('#sidebar').classList.toggle('open');
$('#globalSearch').addEventListener('keydown', e => {
  if (e.key === 'Enter') {
    state.caseQuery = e.target.value.trim();
    location.hash = '#/cases/' + encodeURIComponent(state.caseQuery);
  }
});
setInterval(() => {
  const d = new Date();
  $('#clock').textContent = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
}, 1000);

refreshMeta(); refreshQueueBadge();
setInterval(refreshQueueBadge, 20000);
route();