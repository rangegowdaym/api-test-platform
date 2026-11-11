/* Report UI: vanilla JS, inlined into index.html by org.rg.reporter.HtmlReportWriter.
 * RCA view/tab: DATA.rca is merged into the page after the run by com.qa.platform.report.ReportRcaMerger. */
(() => {
  'use strict';

  // ---------- safe HTML ----------
  class Safe {
    constructor(value) { this.value = value; }
    toString() { return this.value; }
  }
  const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ESCAPES[c]);
  const raw = (s) => new Safe(String(s));
  const toHtml = (v) => (v == null || v === false ? '' : v instanceof Safe ? v.value : Array.isArray(v) ? v.map(toHtml).join('') : esc(v));
  // Tagged template: every interpolated value is HTML-escaped unless it is already Safe.
  const html = (strings, ...values) => new Safe(strings.reduce((out, s, i) => out + toHtml(values[i - 1]) + s));
  const mount = (el, view) => { el.innerHTML = toHtml(view); };
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const clamp = (v, min, max) => Math.min(max, Math.max(min, v));

  // ---------- data ----------
  const DATA = JSON.parse($('#report-data').textContent || '{}');
  const TESTS = DATA.tests || [];
  const TREND = DATA.trend || [];
  const byId = new Map(TESTS.map((t) => [t.id, t]));
  const CATS = ['passed', 'failed', 'broken', 'flaky', 'skipped'];
  const FAILING = ['failed', 'broken'];
  const LABEL = { passed: 'Passed', failed: 'Failed', broken: 'Broken', flaky: 'Flaky', skipped: 'Skipped', unknown: 'Unknown' };
  const STATUS_LABEL = { passed: 'Passed', failed: 'Failed', skipped: 'Skipped', flaky: 'Flaky', timedOut: 'Timed out', interrupted: 'Interrupted' };
  const ATTEMPT_CAT = { passed: 'passed', failed: 'failed', timedOut: 'broken', interrupted: 'broken', skipped: 'skipped' };
  const RUN_CAT = { passed: 'passed', failed: 'failed', timedout: 'broken', interrupted: 'broken' };
  const RUN_LABEL = { passed: 'Run passed', failed: 'Run failed', timedout: 'Run timed out', interrupted: 'Run interrupted' };
  const SEVERITY = { failed: 0, broken: 1, flaky: 2, passed: 3, skipped: 4, unknown: 5 };
  const STEP_LABEL = { 'test.step': 'step', expect: 'expect', 'pw:api': 'action', hook: 'hook', fixture: 'fixture', 'test.attach': 'attach' };
  const ALL_TAGS = [...new Set(TESTS.flatMap((t) => t.tags))].sort();
  const VIEWS = [
    { id: 'dashboard', label: 'Dashboard', icon: 'dashboard' },
    { id: 'failures', label: 'Failures', icon: 'failures' },
    { id: 'rca', label: 'RCA', icon: 'rca' },
    { id: 'tests', label: 'Tests', icon: 'tests' },
    { id: 'categories', label: 'Categories', icon: 'categories' },
    { id: 'tags', label: 'Tags', icon: 'tag' },
    { id: 'analytics', label: 'Analytics', icon: 'analytics' },
    { id: 'timeline', label: 'Timeline', icon: 'timeline' },
  ];
  const TABS = [['steps', 'Steps'], ['errors', 'Errors'], ['rca', 'RCA'], ['source', 'Source'], ['attachments', 'Attachments'], ['logs', 'Console'], ['history', 'History']];
  const LOG_ICON = { action: 'pointer', expect: 'check', step: 'box', hook: 'hook' };
  const TEST_MODES = [['suite', 'Suites', 'layers'], ['status', 'Status', 'failures'], ['tag', 'Tags', 'tag'], ['table', 'Table', 'table']];
  const MULTI_PROJECT = new Set(TESTS.map((t) => t.project)).size > 1;
  const logGroup = (cat) => (cat === 'pw:api' ? 'action' : cat === 'expect' ? 'expect' : cat === 'hook' || cat === 'fixture' ? 'hook' : 'step');
  const SOURCES = DATA.sources || {};

  // ---------- RCA data (rules-based category; LLM may only rewrite the narrative) ----------
  const RCA = DATA.rca && Array.isArray(DATA.rca.entries) ? DATA.rca : null;
  const RCA_ENTRIES = RCA ? RCA.entries : [];
  const RCA_BY_CASE = new Map();
  RCA_ENTRIES.forEach((r) => [r.caseId, ...(r.relatedCaseIds || [])].forEach((id) => { if (id && !RCA_BY_CASE.has(id)) RCA_BY_CASE.set(id, r); }));
  const RCA_TONE = { FUNCTIONAL_BUG: 'failed', SERVER_ERROR: 'failed', CONTRACT_DRIFT: 'broken', PERFORMANCE: 'broken', ENVIRONMENT: 'skipped', AUTH_CONFIG: 'flaky', TEST_DATA: 'flaky', TEST_DEFECT: 'flaky', FLAKY: 'flaky' };
  const RCA_HINT = {
    FUNCTIONAL_BUG: 'The API answered, but not as the approved test case expects.',
    SERVER_ERROR: 'The API returned a 5xx error.',
    CONTRACT_DRIFT: 'The response no longer matches the documented schema.',
    PERFORMANCE: 'The response was slower than the agreed limit.',
    ENVIRONMENT: 'The API could not be reached (DNS, connection, TLS, timeout).',
    AUTH_CONFIG: 'Credentials or tokens for the environment are wrong or missing.',
    TEST_DATA: 'The test data the case depends on is missing or stale.',
    TEST_DEFECT: 'The test itself is wrong (step, placeholder or expectation).',
    FLAKY: 'The case passes and fails on the same code.',
  };
  // Generated scenarios carry their test-case id as a tag, e.g. @TC-orders-createOrder-positive.
  const caseIdOf = (t) => { const tag = (t.tags || []).find((x) => /^@?TC-/.test(x)); return tag ? tag.replace(/^@/, '') : null; };
  const rcaOf = (t) => { const id = caseIdOf(t); return id ? RCA_BY_CASE.get(id) || null : null; };
  // test-case id tags are long and unique: lists show them in the drawer only (chips + Test case field)
  const listTags = (t) => t.tags.filter((x) => !/^@?TC-/.test(x));
  const rcaCases = (r) => [r.caseId, ...(r.relatedCaseIds || [])];
  const rcaTests = (r) => { const ids = new Set(rcaCases(r)); return TESTS.filter((t) => ids.has(caseIdOf(t))); };
  const rcaByCaseId = (id) => RCA_ENTRIES.find((r) => r.caseId === id);
  // Served by the approval app at /reports/: link back to the case in the approval UI. Not available from file://.
  const APPROVAL_URL = /^https?:$/.test(location.protocol) ? new URL('../', location.href).href : null;

  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem('pw-report:' + key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem('pw-report:' + key, JSON.stringify(value)); } catch { /* storage unavailable */ }
    },
  };

  const state = {
    view: 'dashboard',
    search: '',
    statuses: new Set(CATS),
    tag: '',
    sort: { key: 'default', dir: 1 },
    testsMode: TEST_MODES.some(([m]) => m === store.get('testsMode', 'suite')) ? store.get('testsMode', 'suite') : 'suite',
    closed: new Set(),
    selected: null,
    attempt: 0,
    tab: 'steps',
    showHooks: store.get('showHooks', true) !== false,
    sourceFile: null,
    zoom: 1,
    order: [],
  };

  const appEl = $('#app');
  const contentEl = $('#content');
  const drawerInner = $('#drawer-inner');
  const searchEl = $('#search');
  const tagSel = $('#tag-filter');
  const lightboxEl = $('#lightbox');
  const lbImg = $('#lb-img');
  const tipEl = $('#tooltip');

  // ---------- icons ----------
  const ICONS = {
    rca: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/><path d="M11.2 7.2l-2 3.3h2.6l-2 3.3"/>',
    bug: '<rect x="7" y="8" width="10" height="12" rx="5"/><path d="M9 8V6.5a3 3 0 016 0V8M3 13h4M17 13h4M4 19l3.2-1.8M20 19l-3.2-1.8M4 7l3.2 1.8M20 7l-3.2 1.8"/>',
    owner: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0116 0"/>',
    wrench: '<path d="M14.7 6.3a4 4 0 00-5.4 5.2L3 17.8V21h3.2l6.3-6.3a4 4 0 005.2-5.4l-2.6 2.6-2.4-.6-.6-2.4z"/>',
    external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5"/>',
    dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
    failures: '<circle cx="12" cy="12" r="9"/><path d="M12 8v4.5M12 16h.01"/>',
    tests: '<path d="M9 6h12M9 12h12M9 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
    categories: '<path d="M12 3l9 4.5-9 4.5-9-4.5z"/><path d="M3 12l9 4.5 9-4.5M3 16.5l9 4.5 9-4.5"/>',
    tag: '<path d="M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
    analytics: '<path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 5-6"/>',
    timeline: '<path d="M3 6h9M7 12h12M5 18h8"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
    close: '<path d="M18 6L6 18M6 6l12 12"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
    terminal: '<path d="M4 17l6-5-6-5M12 19h8"/>',
    up: '<path d="M18 15l-6-6-6 6"/>',
    down: '<path d="M6 9l6 6 6-6"/>',
    left: '<path d="M15 18l-6-6 6-6"/>',
    chevron: '<path d="M9 18l6-6-6-6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    retry: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
    file: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6"/>',
    layers: '<path d="M12 2l10 5-10 5L2 7z"/><path d="M2 17l10 5 10-5M2 12l10 5 10-5"/>',
    box: '<rect x="4" y="4" width="16" height="16" rx="3"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5M12 15V3"/>',
    expand: '<path d="M7 15l5 5 5-5M7 9l5-5 5 5"/>',
    collapse: '<path d="M7 20l5-5 5 5M7 4l5 5 5-5"/>',
    menu: '<path d="M3 6h18M3 12h18M3 18h18"/>',
    sidebar: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="M21 15l-5-5L5 21"/>',
    video: '<rect x="2" y="6" width="14" height="12" rx="2"/><path d="M22 8l-6 4 6 4z"/>',
    trace: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    git: '<circle cx="6" cy="6" r="2.5"/><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="8" r="2.5"/><path d="M6 8.5v7M18 10.5c0 4-6 3.5-10.5 6"/>',
    env: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
    bolt: '<path d="M13 2L4 14h8l-1 8 9-12h-8z"/>',
    tree: '<path d="M4 4v16M4 8h6M4 16h6"/><rect x="12" y="5" width="8" height="6" rx="1.5"/><rect x="12" y="13" width="8" height="6" rx="1.5"/>',
    table: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M9 10v10"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7"/>',
    x: '<path d="M7 7l10 10M17 7L7 17"/>',
    alert: '<path d="M12 7v6M12 17h.01"/>',
    flaky: '<path d="M3 12c2-5 4-5 6 0s4 5 6 0 4-5 6 0"/>',
    minus: '<path d="M7 12h10"/>',
    logo: '<path d="M4 12a8 8 0 1 1 8 8"/><path d="M8 12l3 3 5-6"/>',
    sparkle: '<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/>',
    pointer: '<path d="M5 3l14 8-6.5 1.8L10 19z"/>',
    hook: '<path d="M12 3v11a4 4 0 1 1-4-4"/><path d="M9 3h6"/>',
    code: '<path d="M8 6l-6 6 6 6M16 6l6 6-6 6"/>',
  };
  const icon = (name, cls = '') => raw('<svg class="i ' + cls + '" viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || '') + '</svg>');
  const GLYPH = { passed: 'check', failed: 'x', broken: 'alert', flaky: 'flaky', skipped: 'minus', unknown: 'minus' };

  // ---------- formatting ----------
  function fmtDur(ms) {
    if (ms == null || !isFinite(ms)) return '—';
    if (ms < 1000) return Math.round(ms) + 'ms';
    const s = ms / 1000;
    if (s < 60) return s.toFixed(s < 10 ? 2 : 1) + 's';
    const m = Math.floor(s / 60);
    if (m < 60) return m + 'm ' + Math.floor(s % 60) + 's';
    return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
  }
  const fmtDate = (ts) => (ts ? new Date(ts).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—');
  const fmtTime = (ts) => (ts ? new Date(ts).toLocaleTimeString() : '—');
  function fmtAgo(ts) {
    const d = (Date.now() - ts) / 1000;
    if (d < 60) return 'just now';
    if (d < 3600) return Math.floor(d / 60) + ' min ago';
    if (d < 86400) return Math.floor(d / 3600) + ' h ago';
    return Math.floor(d / 86400) + ' d ago';
  }
  function fmtBytes(n) {
    if (n == null) return '';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }
  const pct = (v, total) => (total ? Math.round((v / total) * 100) : 0);
  const plural = (n, one, many = one + 's') => n + ' ' + (n === 1 ? one : many);
  const firstLine = (s) => String(s || '').split('\n').map((l) => l.trim()).find(Boolean) || '';
  function truncLines(s, n) {
    const lines = String(s || '').split('\n');
    return lines.length > n ? lines.slice(0, n).join('\n') + '\n…' : lines.join('\n');
  }
  function safeUrl(u) {
    u = String(u || '');
    return /^data:image\/(png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=]+$/i.test(u) || /^data\/[\w.-]+$/.test(u) ? u : '#';
  }
  function hl(text) {
    text = String(text == null ? '' : text);
    const q = state.search;
    if (!q) return text;
    const lower = text.toLowerCase();
    const parts = [];
    let i = 0;
    let j;
    while ((j = lower.indexOf(q, i)) !== -1) {
      parts.push(text.slice(i, j), html`<mark>${text.slice(j, j + q.length)}</mark>`);
      i = j + q.length;
    }
    parts.push(text.slice(i));
    return parts;
  }

  // ---------- model helpers ----------
  function stats(list) {
    const s = { total: list.length, passed: 0, failed: 0, broken: 0, flaky: 0, skipped: 0 };
    for (const t of list) if (CATS.includes(t.category)) s[t.category]++;
    return s;
  }
  const worst = (s) => (s.failed ? 'failed' : s.broken ? 'broken' : s.flaky ? 'flaky' : s.passed ? 'passed' : 'skipped');
  function passRate(s) {
    const ran = s.total - s.skipped;
    return ran ? Math.round(((s.passed + s.flaky) / ran) * 1000) / 10 : 0;
  }
  const catOf = (c) => (CATS.includes(c) ? c : 'unknown');
  const sumDur = (list) => list.reduce((a, t) => a + (t.duration || 0), 0);
  const lastAttempt = (t) => t.attempts[t.attempts.length - 1];
  const failingAttempt = (t) => (t.category === 'flaky' ? t.attempts.find((a) => a.errors.length) : lastAttempt(t));
  const primaryError = (t) => { const a = failingAttempt(t); return a && a.errors[0]; };
  const imagesOf = (a) => (a ? a.attachments.filter((x) => x.kind === 'image' && x.src) : []);
  const runHistory = (t) => [...(t.history || []), { startTime: DATA.startTime, category: t.category, duration: t.duration, current: true }];
  function isUnstable(t) {
    const cats = new Set(runHistory(t).map((h) => h.category));
    return cats.has('flaky') || (cats.has('passed') && (cats.has('failed') || cats.has('broken')));
  }
  const countSteps = (steps) => steps.reduce((n, s) => n + 1 + countSteps(s.steps || []), 0);
  const flattenSteps = (steps, depth = 0) => steps.flatMap((s) => [{ step: s, depth }, ...flattenSteps(s.steps || [], depth + 1)]);
  function parseLoc(loc) {
    const m = /^(.*):(\d+):(\d+)$/.exec(loc || '');
    return m ? { file: m[1], line: Number(m[2]), column: Number(m[3]) } : null;
  }
  const shortLoc = (loc) => { const p = parseLoc(loc); return p ? p.file.split('/').pop() + ':' + p.line : loc || ''; };
  const fmtOffset = (ms) => (Math.max(0, ms) / 1000).toFixed(2) + 's';
  const traceCmd = (file) => 'mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="show-trace ' + file + '"';
  const rerunCmd = (t) => (caseIdOf(t) ? 'mvn test -Denv=' + (t.project && t.project !== 'default' ? t.project : 'qa') + ' -Dcucumber.filter.tags="@' + caseIdOf(t) + '"' : rerunCmdFile(t));
  const rerunCmdFile = (t) => 'mvn test -Dcucumber.features=' + JSON.stringify(t.file + ':' + t.line) + (t.browser ? ' -Dbrowser=' + t.browser : '');
  const statusLabel = (t) => (t.category === 'broken' && t.status === 'failed' ? 'Broken' : STATUS_LABEL[t.status] || t.status);
  const niceMax = (m) => (m <= 4 ? 4 : Math.ceil(m / 4) * 4);
  function quantile(sorted, q) {
    if (!sorted.length) return 0;
    const pos = (sorted.length - 1) * q;
    const lo = Math.floor(pos);
    return sorted[lo] + (sorted[Math.min(lo + 1, sorted.length - 1)] - sorted[lo]) * (pos - lo);
  }
  function groupBy(list, keyFn) {
    const map = new Map();
    for (const t of list) {
      const k = keyFn(t) || 'unknown';
      if (!map.has(k)) map.set(k, []);
      map.get(k).push(t);
    }
    return map;
  }

  // ---------- filtering ----------
  function passesBase(t) {
    if (state.tag === '__untagged') { if (t.tags.length) return false; }
    else if (state.tag && !t.tags.includes(state.tag)) return false;
    if (state.search) {
      const err = primaryError(t);
      const hay = [t.titlePath.join(' '), t.tags.join(' '), t.location, t.status, t.errorCategory || '', err ? firstLine(err.message) : ''].join(' ').toLowerCase();
      if (!hay.includes(state.search)) return false;
    }
    return true;
  }
  const filtersActive = () => !!(state.search || state.tag || state.statuses.size < CATS.length);
  function failuresOf(list) {
    return list
      .filter((t) => t.category === 'failed' || t.category === 'broken' || t.category === 'flaky')
      .sort((a, b) => SEVERITY[a.category] - SEVERITY[b.category] || a.title.localeCompare(b.title));
  }
  function sortTests(list) {
    const { key, dir } = state.sort;
    const val = {
      status: (t) => SEVERITY[t.category],
      name: (t) => t.title.toLowerCase(),
      project: (t) => t.project,
      duration: (t) => t.duration,
      retries: (t) => t.retries,
    }[key];
    if (!val) return list;
    return list.slice().sort((a, b) => { const x = val(a); const y = val(b); return (x < y ? -1 : x > y ? 1 : 0) * dir; });
  }

  // ---------- components ----------
  const statusDot = (c, size = '') => { c = catOf(c); return html`<span class="sd sd-${c} ${size}" title="${LABEL[c]}">${icon(GLYPH[c])}</span>`; };
  const pill = (c, label) => { c = catOf(c); return html`<span class="pill pill-${c}">${label || LABEL[c]}</span>`; };
  const tagChip = (tag) => html`<button type="button" class="tag${state.tag === tag ? ' is-active' : ''}" data-action="filter-tag" data-tag="${tag}" title="Filter by ${tag}">${tag}</button>`;
  function empty(title, sub = '', ic = 'search', ok = false) {
    return html`<div class="empty${ok ? ' empty-ok' : ''}"><div class="empty-ic">${icon(ic)}</div><b>${title}</b>${sub ? html`<span>${sub}</span>` : ''}</div>`;
  }
  function card(title, body, opts = {}) {
    return html`<section class="card ${opts.cls || ''}">${title ? html`<div class="card-h">${opts.icon ? icon(opts.icon) : ''}<h3>${title}</h3>${opts.actions ? html`<div class="card-actions">${opts.actions}</div>` : ''}</div>` : ''}<div class="card-b">${body}</div></section>`;
  }
  function pageHead(title, sub, actions) {
    return html`<div class="page-head"><div><h1 class="page-title">${title}</h1>${sub ? html`<div class="page-sub">${sub}</div>` : ''}</div>${actions || ''}</div>`;
  }
  function miniBar(s, cls = '') {
    const segs = CATS.filter((c) => s[c]);
    return html`<span class="mbar ${cls}" data-tip="${segs.map((c) => LABEL[c] + ' ' + s[c]).join(' · ') || 'No tests'}">${segs.map((c) => html`<i class="bg-${c}" style="flex:${s[c]}"></i>`)}</span>`;
  }
  function statusBar(s) {
    const segs = CATS.filter((c) => s[c]);
    return html`<div class="sbar">${segs.map((c) => html`<button type="button" class="sbar-seg bg-${c}" style="flex:${s[c]}" data-action="only-status" data-status="${c}" data-tip="${LABEL[c]}: ${s[c]} (${pct(s[c], s.total)}%)" aria-label="${LABEL[c]}"></button>`)}</div>
      <div class="sbar-legend">${CATS.map((c) => html`<button type="button" class="lg" data-action="only-status" data-status="${c}"><i class="dot bg-${c}"></i>${LABEL[c]}<b>${s[c]}</b><span>${pct(s[c], s.total)}%</span></button>`)}</div>`;
  }
  function ring(rate, { size = 132, stroke = 12, label = 'pass rate', text = true } = {}) {
    const r = (size - stroke) / 2;
    const c = 2 * Math.PI * r;
    const len = (c * rate) / 100;
    const tone = rate >= 90 ? 'good' : rate >= 70 ? 'warn' : 'bad';
    const mid = size / 2;
    return html`<svg class="ring" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" role="img" aria-label="${rate}% ${label}">
      <circle class="ring-track" cx="${mid}" cy="${mid}" r="${r}" stroke-width="${stroke}"/>
      ${rate > 0 ? html`<circle class="ring-val ring-${tone}" cx="${mid}" cy="${mid}" r="${r}" stroke-width="${stroke}" stroke-dasharray="${len.toFixed(2)} ${c.toFixed(2)}" transform="rotate(-90 ${mid} ${mid})"/>` : ''}
      ${text ? html`<text class="ring-num" x="50%" y="${label ? '47%' : '50%'}" text-anchor="middle" dominant-baseline="middle" font-size="${(size * 0.2).toFixed(1)}">${rate}%</text>` : ''}
      ${text && label ? html`<text class="ring-lbl" x="50%" y="66%" text-anchor="middle" font-size="${(size * 0.075).toFixed(1)}">${label}</text>` : ''}
    </svg>`;
  }
  function historyDots(t) {
    if (!t.history || !t.history.length) return '';
    return html`<span class="hdots">${runHistory(t).slice(-6).map((h) => html`<i class="hd bg-${catOf(h.category)}${h.current ? ' is-current' : ''}" data-tip="${h.current ? 'This run' : fmtDate(h.startTime)} · ${LABEL[catOf(h.category)]} · ${fmtDur(h.duration)}"></i>`)}</span>`;
  }
  function testRow(t, opts = {}) {
    return html`<div class="row${state.selected === t.id ? ' is-selected' : ''}" data-action="open-test" data-id="${t.id}" tabindex="0" role="button">
      ${statusDot(t.category)}
      <div class="row-main"><div class="row-title">${hl(t.title)}</div>${opts.path ? html`<div class="row-sub">${hl(groupPath(t).join(' › '))}</div>` : ''}</div>
      <div class="row-meta">${MULTI_PROJECT && t.project ? html`<span class="chip chip-proj" data-tip="Project · ${t.browser}">${t.project}</span>` : ''}${listTags(t).map(tagChip)}${t.retries ? html`<span class="meta-retry" data-tip="${plural(t.retries, 'retry', 'retries')}">${icon('retry')}${t.retries}</span>` : ''}${historyDots(t)}<span class="dur">${fmtDur(t.duration)}</span></div>
    </div>`;
  }

  // ---------- suite grouping (describe blocks; file name when a test has none) ----------
  function prettyFile(file) {
    const base = String(file || 'tests').split('/').pop().replace(/(\.(spec|test))?\.[cm]?[jt]sx?$/i, '');
    const words = base.replace(/[._-]+/g, ' ').trim();
    return words.charAt(0).toUpperCase() + words.slice(1);
  }
  const groupPath = (t) => (t.suitePath && t.suitePath.length ? t.suitePath : [prettyFile(t.file)]);
  function buildGroups(list) {
    const root = { name: '', items: [], children: new Map() };
    list.forEach((t) => {
      let node = root;
      groupPath(t).forEach((name) => {
        if (!node.children.has(name)) {
          const child = { name, items: [], children: new Map() };
          node.children.set(name, child);
          node.items.push({ group: child });
        }
        node = node.children.get(name);
      });
      node.items.push({ test: t });
    });
    return root;
  }
  const groupTests = (node) => node.items.flatMap((it) => (it.test ? [it.test] : groupTests(it.group)));
  function groupSummary(name, tests) {
    const s = stats(tests);
    return html`${statusDot(worst(s))}<span class="node-name">${hl(name)}</span><span class="count-pill">${plural(s.total, 'test')}</span><span class="node-stats"><span class="node-rate" data-tip="Pass rate">${passRate(s)}%</span>${miniBar(s)}<span class="dur">${fmtDur(sumDur(tests))}</span></span>`;
  }
  function suiteGroup(node, path) {
    const body = node.items.map((it) => (it.test ? testRow(it.test) : suiteGroup(it.group, [...path, it.group.name])));
    return group('grp:' + path.join('\u203a'), groupSummary(node.name, groupTests(node)), body, path.length > 1 ? 'node-describe' : 'node-suite');
  }
  function testsOrder(list) {
    switch (state.testsMode) {
      case 'table': return sortTests(list);
      case 'suite': return groupTests(buildGroups(list));
      case 'status': return list.slice().sort((a, b) => SEVERITY[a.category] - SEVERITY[b.category]);
      default: return list;
    }
  }
  function group(key, summary, body, cls = '') {
    return html`<details class="grp ${cls}" data-key="${key}" ${state.closed.has(key) ? '' : raw('open')}><summary>${icon('chevron', 'caret')}${summary}</summary><div class="grp-body">${body}</div></details>`;
  }
  const legendRow = () => html`<div class="legend-row">${CATS.map((c) => html`<span><i class="dot bg-${c}"></i>${LABEL[c]}</span>`)}</div>`;

  // ---------- charts ----------
  function hBars(rows) {
    if (!rows.length) return empty('No data', 'Nothing matches the current filters.', 'analytics');
    const max = Math.max(1, ...rows.map((r) => r.stats.total));
    return html`<div class="hbars">${rows.map((r) => html`<div class="hbar${r.tag ? ' is-click' : ''}" ${r.tag ? raw('data-action="tag-card" data-tag="' + esc(r.tag) + '"') : ''}>
      <span class="hbar-label" title="${r.label}">${r.label}</span>
      <span class="hbar-track">${CATS.filter((c) => r.stats[c]).map((c) => html`<i class="bg-${c}" style="width:${((r.stats[c] / max) * 100).toFixed(2)}%" data-tip="${r.label}\n${LABEL[c]}: ${r.stats[c]} of ${r.stats.total}"></i>`)}</span>
      <span class="hbar-total">${r.stats.total}</span></div>`)}</div>`;
  }
  function vBars(rows, height = 190) {
    const nice = niceMax(Math.max(1, ...rows.map((r) => r.stats.total)));
    const W = 520; const H = height; const top = 18; const bottom = 26; const left = 32; const plotH = H - top - bottom;
    const slot = (W - left) / rows.length; const bw = Math.min(44, slot * 0.62);
    const grid = [0, 1, 2, 3, 4].map((i) => {
      const y = top + plotH - (plotH * i) / 4;
      return html`<line class="grid-l" x1="${left}" x2="${W}" y1="${y}" y2="${y}"/><text class="axis" x="${left - 8}" y="${y + 4}" text-anchor="end">${(nice * i) / 4}</text>`;
    });
    const bars = rows.map((r, i) => {
      const x = left + slot * i + (slot - bw) / 2;
      let y = top + plotH;
      const segs = CATS.filter((c) => r.stats[c]).map((c) => {
        const h = (r.stats[c] / nice) * plotH;
        y -= h;
        return html`<rect class="bar fill-${c}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${h.toFixed(1)}" rx="3" data-tip="${r.label}\n${LABEL[c]}: ${r.stats[c]}"/>`;
      });
      return html`${segs}${r.stats.total ? html`<text class="val" x="${(x + bw / 2).toFixed(1)}" y="${(y - 6).toFixed(1)}" text-anchor="middle">${r.stats.total}</text>` : ''}<text class="lbl" x="${(x + bw / 2).toFixed(1)}" y="${H - 7}" text-anchor="middle">${r.label}</text>`;
    });
    return html`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img">${grid}${bars}</svg>`;
  }
  function trendChart(runs) {
    const W = 780; const H = 230; const top = 18; const bottom = 28; const left = 34; const right = 44; const plotH = H - top - bottom;
    const nice = niceMax(Math.max(1, ...runs.map((r) => r.stats.total)));
    const slot = (W - left - right) / runs.length; const bw = Math.min(30, slot * 0.58);
    const last = runs.length - 1;
    const labelEvery = Math.ceil(runs.length / 12);
    const grid = [0, 1, 2, 3, 4].map((i) => {
      const y = top + plotH - (plotH * i) / 4;
      return html`<line class="grid-l" x1="${left}" x2="${W - right}" y1="${y}" y2="${y}"/><text class="axis" x="${left - 8}" y="${y + 4}" text-anchor="end">${(nice * i) / 4}</text><text class="axis" x="${W - right + 8}" y="${y + 4}">${i * 25}%</text>`;
    });
    const pts = [];
    const bars = runs.map((r, i) => {
      const cx = left + slot * i + slot / 2;
      const x = cx - bw / 2;
      let y = top + plotH;
      const rate = passRate(r.stats);
      pts.push([cx, top + plotH - (rate / 100) * plotH, r, rate]);
      const tip = fmtDate(r.startTime) + '\n' + CATS.filter((c) => r.stats[c]).map((c) => LABEL[c] + ' ' + r.stats[c]).join(' · ') + '\nPass rate ' + rate + '% · ' + fmtDur(r.duration);
      const segs = CATS.filter((c) => r.stats[c]).map((c) => {
        const h = (r.stats[c] / nice) * plotH;
        y -= h;
        return html`<rect class="bar fill-${c}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${bw.toFixed(1)}" height="${h.toFixed(1)}" rx="2" data-tip="${tip}"/>`;
      });
      const lbl = i === last ? 'now' : '−' + (last - i);
      return html`${segs}${i % labelEvery === 0 || i === last ? html`<text class="lbl" x="${cx.toFixed(1)}" y="${H - 7}" text-anchor="middle">${lbl}</text>` : ''}`;
    });
    const line = pts.map((p) => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ');
    const dots = pts.map((p) => html`<circle class="trend-dot" cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="4" data-tip="${fmtDate(p[2].startTime)}\nPass rate ${p[3]}%"/>`);
    return html`<svg class="chart" viewBox="0 0 ${W} ${H}" role="img">${grid}${bars}<polyline class="trend-line" points="${line}"/>${dots}</svg>`;
  }
  function sparkline(values) {
    const W = 320; const H = 64; const pad = 4;
    const max = Math.max(1, ...values);
    const step = values.length > 1 ? (W - pad * 2) / (values.length - 1) : 0;
    const pts = values.map((v, i) => [pad + i * step, H - pad - (v / max) * (H - pad * 2)]);
    const line = pts.map((p) => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ');
    const area = 'M' + pad + ',' + (H - pad) + ' L' + line.replace(/ /g, ' L') + ' L' + (pad + (values.length - 1) * step).toFixed(1) + ',' + (H - pad) + ' Z';
    return html`<svg class="spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none"><path class="spark-area" d="${area}"/><polyline class="spark-line" points="${line}"/></svg>`;
  }
  function durationBuckets(list) {
    const b = [['<1s', 1000], ['1–5s', 5000], ['5–10s', 10000], ['10–30s', 30000], ['30–60s', 60000], ['>60s', Infinity]];
    return b.map(([label, max], i) => {
      const min = i ? b[i - 1][1] : 0;
      return { label, stats: stats(list.filter((t) => t.duration >= min && t.duration < max)) };
    });
  }

  // ---------- views ----------
  function viewDashboard(list) {
    const s = stats(list);
    const rate = passRate(s);
    const runCat = RUN_CAT[DATA.status] || 'unknown';
    const tone = runCat === 'passed' ? (s.flaky ? 'warn' : 'ok') : 'bad';
    const prev = !filtersActive() && TREND.length > 1 ? TREND[TREND.length - 2].stats : null;
    const git = DATA.git;
    const browsers = [...new Set(TESTS.map((t) => t.browser))].join(', ');
    const executed = list.filter((t) => t.attempts.length);

    const hero = html`<section class="hero hero-${tone}">
      <div class="hero-main">
        <div class="hero-top">${pill(runCat, RUN_LABEL[DATA.status] || DATA.status)}<span>${fmtDate(DATA.startTime)}</span><span class="muted">· generated ${fmtAgo(DATA.generatedAt)}</span></div>
        <h1 class="hero-title">${DATA.title}</h1>
        <div class="hero-meta">
          <span>${icon('clock')}${fmtDur(DATA.duration)} wall time</span>
          <span>${icon('tests')}${plural(s.total, 'test')}</span>
          <span>${icon('env')}${browsers}</span>
          ${git ? html`<span data-tip="${[git.message, git.author].filter(Boolean).join('\n')}">${icon('git')}${git.branch || 'HEAD'} · ${git.commit}</span>` : ''}
        </div>
        ${statusBar(s)}
      </div>
      <div class="hero-ring">${ring(rate, { size: 150, stroke: 14 })}</div>
    </section>`;

    const kpi = (key, label, value, cat) => {
      const delta = prev ? value - prev[key] : null;
      const tone = !delta || key === 'total' ? 'flat' : (delta > 0) === (key === 'passed') ? 'good' : 'bad';
      return html`<button type="button" class="kpi" data-action="${cat ? 'only-status' : 'show-all'}" data-status="${cat || ''}">
        <div class="kpi-top"><span>${label}</span>${cat ? statusDot(cat, 'sd-sm') : icon('tests', 'kpi-ic')}</div>
        <div class="kpi-value">${value}</div>
        <div class="kpi-foot"><span>${cat ? pct(value, s.total) + '% of tests' : fmtDur(sumDur(list)) + ' test time'}</span>${delta == null ? '' : html`<span class="delta delta-${tone}" data-tip="vs previous run">${delta > 0 ? '▲ ' : delta < 0 ? '▼ ' : ''}${delta === 0 ? '±0' : Math.abs(delta)}</span>`}</div>
        <div class="kpi-bar"><i class="bg-${cat || 'accent'}" style="width:${cat ? pct(value, s.total) : 100}%"></i></div>
      </button>`;
    };
    const kpis = html`<div class="kpis">${kpi('total', 'Total', s.total)}${CATS.map((c) => kpi(c, LABEL[c], s[c], c))}</div>`;

    const durs = executed.map((t) => t.duration).sort((a, b) => a - b);
    const durationCard = card('Duration', html`<div class="dstats">${[
      ['Average', durs.length ? sumDur(executed) / durs.length : 0], ['Median', quantile(durs, 0.5)], ['p95', quantile(durs, 0.95)], ['Longest', durs[durs.length - 1] || 0],
    ].map(([l, v]) => html`<div class="dstat"><span>${l}</span><b>${fmtDur(v)}</b></div>`)}</div>${vBars(durationBuckets(executed), 150)}`, { icon: 'clock', cls: TREND.length > 1 ? 'span-4' : 'span-5' });

    const trendCard = TREND.length > 1
      ? card('Run trend', html`${trendChart(TREND)}${legendRow()}`, { icon: 'analytics', cls: 'span-8', actions: html`<span class="muted">${plural(TREND.length, 'run')}</span>` })
      : card('Run trend', empty('Trend appears after the next run', 'Run history is stored in ' + DATA.outputFolder + '/history.json and kept between runs.', 'analytics'), { icon: 'analytics', cls: 'span-7' });

    const failures = failuresOf(list).filter((t) => FAILING.includes(t.category));
    const failCard = card('Top failures', failures.length
      ? html`<div class="list">${failures.slice(0, 6).map((t) => { const e = primaryError(t); return html`<div class="row${state.selected === t.id ? ' is-selected' : ''}" data-action="open-test" data-id="${t.id}" tabindex="0" role="button">${statusDot(t.category)}<div class="row-main"><div class="row-title">${t.title}</div><div class="row-err">${e ? firstLine(e.message) : ''}</div></div>${t.errorCategory ? html`<span class="chip-cat">${t.errorCategory}</span>` : ''}</div>`; })}</div>`
      : empty('No failures', 'Everything that ran in this selection passed.', 'check', true),
    { icon: 'failures', cls: 'span-4', actions: failures.length ? html`<button type="button" class="link" data-action="goto" data-view="failures">View all ${failures.length}${icon('chevron')}</button>` : '' });

    const slow = executed.slice().sort((a, b) => b.duration - a.duration).slice(0, 6);
    const maxD = slow.length ? slow[0].duration || 1 : 1;
    const slowCard = card('Slowest tests', slow.length
      ? html`<div class="list">${slow.map((t) => html`<div class="row${state.selected === t.id ? ' is-selected' : ''}" data-action="open-test" data-id="${t.id}" tabindex="0" role="button">${statusDot(t.category)}<div class="row-main"><div class="row-title">${t.title}</div><div class="row-bar"><i style="width:${((t.duration / maxD) * 100).toFixed(1)}%"></i></div></div><span class="dur">${fmtDur(t.duration)}</span></div>`)}</div>`
      : empty('No executed tests', '', 'clock'), { icon: 'bolt', cls: 'span-6' });

    const unstable = list.filter((t) => t.category === 'flaky' || (t.history && t.history.length && isUnstable(t)));
    const unstableCard = card('Flaky & unstable', unstable.length
      ? html`<div class="list">${unstable.slice(0, 6).map((t) => html`<div class="row" data-action="open-test" data-id="${t.id}" tabindex="0" role="button">${statusDot(t.category)}<div class="row-main"><div class="row-title">${t.title}</div><div class="row-sub">${t.category === 'flaky' ? 'Passed after ' + plural(t.retries, 'retry', 'retries') : 'Status changed across runs'}</div></div>${historyDots(t)}</div>`)}</div>`
      : empty('Nothing flaky', 'No test needed a retry or changed status between runs.', 'check', true), { icon: 'flaky', cls: 'span-4' });

    const steps = [];
    const walk = (t, arr) => arr.forEach((st) => { if (st.category === 'test.step' || st.category === 'pw:api' || st.category === 'expect') steps.push({ t, st }); walk(t, st.steps || []); });
    executed.forEach((t) => walk(t, lastAttempt(t).steps));
    const topSteps = steps.sort((a, b) => b.st.duration - a.st.duration).slice(0, 6);
    const maxS = topSteps.length ? topSteps[0].st.duration || 1 : 1;
    const stepsCard = card('Slowest steps', topSteps.length
      ? html`<div class="list">${topSteps.map(({ t, st }) => html`<div class="row" data-action="open-test" data-id="${t.id}" tabindex="0" role="button" data-tip="${st.title}"><div class="row-main"><div class="row-title">${st.title}</div><div class="row-sub">${t.title}</div><div class="row-bar"><i style="width:${((st.duration / maxS) * 100).toFixed(1)}%"></i></div></div><span class="dur">${fmtDur(st.duration)}</span></div>`)}</div>`
      : empty('No steps recorded', '', 'timeline'), { icon: 'timeline', cls: 'span-6' });

    const suites = buildGroups(list).items.map((it) => it.group);
    const suiteCard = card('Test suites', suites.length
      ? html`<div class="list">${suites.map((g) => {
        const ts = groupTests(g);
        const gs = stats(ts);
        const failing = gs.failed + gs.broken;
        return html`<button type="button" class="row suite-row" data-action="goto-group" data-key="${'grp:' + g.name}">${statusDot(worst(gs))}
          <div class="row-main"><div class="row-title">${g.name}</div><div class="row-sub">${plural(gs.total, 'test')} · ${passRate(gs)}% passed${failing ? ' · ' + failing + ' failing' : ''}${gs.flaky ? ' · ' + gs.flaky + ' flaky' : ''}</div>${miniBar(gs, 'mbar-lg')}</div>
          <span class="dur">${fmtDur(sumDur(ts))}</span></button>`;
      })}</div>`
      : empty('No test suites', '', 'layers'), { icon: 'layers', cls: 'span-4', actions: html`<button type="button" class="link" data-action="goto" data-view="tests">View all${icon('chevron')}</button>` });

    const envCard = card('Environment', html`<dl class="env">${(DATA.environment || []).map((e) => html`<div><dt>${e.name}</dt><dd>${e.value || '—'}</dd></div>`)}${git ? html`<div><dt>Git</dt><dd>${git.branch} @ ${git.commit}${git.author ? ' · ' + git.author : ''}</dd></div>` : ''}</dl>`, { icon: 'env', cls: 'span-12' });

    return html`${hero}${kpis}<div class="dash">${suiteCard}${unstableCard}${failCard}${trendCard}${durationCard}${slowCard}${stepsCard}${envCard}</div>`;
  }

  function viewFailures(list) {
    const items = failuresOf(list);
    const s = stats(items);
    const head = pageHead('Failures', items.length ? `${s.failed} failed · ${s.broken} broken · ${s.flaky} flaky` : 'Nothing to triage');
    if (!items.length) return html`${head}${card(null, empty('All clear', 'No failed, broken or flaky tests match the current filters.', 'check', true))}`;
    return html`${head}<div class="fcards">${items.map((t) => {
      const a = failingAttempt(t);
      const err = a && a.errors[0];
      const shot = imagesOf(a)[0];
      return html`<article class="fcard fcard-${t.category}">
        <header class="fcard-head" data-action="open-test" data-id="${t.id}">
          ${statusDot(t.category, 'sd-sm')}
          <div class="row-main"><div class="fcard-title">${hl(t.title)}</div><div class="row-sub">${hl(groupPath(t).join(' › '))}</div></div>
          <div class="row-meta">${listTags(t).map(tagChip)}${pill(t.category, statusLabel(t))}<span class="dur">${fmtDur(t.duration)}</span></div>
        </header>
        <div class="fcard-body">
          ${err ? errorPre(truncLines(err.message, 10), 'err-preview') : html`<div class="muted">No error message recorded.</div>`}
          ${shot ? html`<figure class="fcard-shot" data-gallery data-action="lightbox" data-tip="Click to enlarge"><img src="${safeUrl(shot.src)}" alt="${shot.name}" loading="lazy"></figure>` : ''}
        </div>
        <footer class="fcard-foot">
          ${rcaOf(t) ? html`<button type="button" class="btn btn-rca rca-${rcaTone(rcaOf(t))}" data-action="open-test" data-id="${t.id}" data-tab="rca" data-tip="${rcaOf(t).summary || ''}">${icon('rca')}${rcaLabel(rcaOf(t).category)}</button>` : t.errorCategory ? html`<span class="chip-cat">${t.errorCategory}</span>` : ''}
          <span class="mono muted">${t.location}</span>
          ${t.category === 'flaky' ? html`<span class="chip chip-warn">${icon('retry')}passed on retry ${t.retries}</span>` : ''}
          <span class="grow"></span>
          <button type="button" class="btn" data-action="copy" data-kind="error" data-id="${t.id}">${icon('copy')}Copy error</button>
          ${a && a.prompt ? html`<button type="button" class="btn" data-action="copy" data-kind="prompt" data-id="${t.id}">${icon('sparkle')}Copy prompt</button>` : ''}
          <button type="button" class="btn" data-action="copy" data-kind="rerun" data-id="${t.id}">${icon('terminal')}Re-run command</button>
          <button type="button" class="btn btn-primary" data-action="open-test" data-id="${t.id}">Details${icon('chevron')}</button>
        </footer>
      </article>`;
    })}</div>`;
  }

  // ---------- RCA ----------
  const rcaTone = (r) => RCA_TONE[r.category] || 'broken';
  const rcaLabel = (c) => String(c || 'UNKNOWN').toLowerCase().replace(/_/g, ' ').replace(/^./, (x) => x.toUpperCase());
  const rcaChip = (r) => html`<span class="chip-rca rca-${rcaTone(r)}" data-tip="${RCA_HINT[r.category] || ''}">${rcaLabel(r.category)}</span>`;
  function confMeter(r) {
    const v = Math.round((Number(r.confidence) || 0) * 100);
    return html`<span class="conf" data-tip="Rule confidence ${v}%${r.analysedBy ? ' · ' + r.analysedBy : ''}"><span class="conf-track"><i style="width:${v}%"></i></span><b>${v}%</b></span>`;
  }
  function rcaHistory(r) {
    const h = (r.history || []).slice(-10);
    if (!h.length) return '';
    return html`<span class="hdots">${h.map((s) => { const c = /PASS/i.test(s) ? 'passed' : /FAIL/i.test(s) ? 'failed' : 'skipped'; return html`<i class="hd bg-${c}" data-tip="Earlier run · ${LABEL[c]}"></i>`; })}<i class="hd bg-failed is-current" data-tip="This run · Failed"></i></span>`;
  }
  function rcaFacts(r) {
    const rows = [
      ['Probable cause', r.probableCause, 'rca'],
      ['Suggested fix', r.suggestedFix, 'wrench'],
      ['Owner', r.owner, 'owner'],
      ['Endpoint', r.endpoint, 'link'],
      ['First failed', r.firstFailedRun, 'clock'],
    ].filter(([, v]) => v);
    return html`<div class="rca-facts-wrap"><dl class="rca-facts">${rows.map(([k, v, ic]) => html`<div><dt>${icon(ic)}${k}</dt><dd>${v}</dd></div>`)}</dl>
      ${r.otherPossibleCauses && r.otherPossibleCauses.length ? html`<div class="rca-other"><span class="muted">Other possible causes</span><ul>${r.otherPossibleCauses.map((c) => html`<li>${c}</li>`)}</ul></div>` : ''}</div>`;
  }
  function rcaActions(r, withTests) {
    return html`${r.curl ? html`<button type="button" class="btn" data-action="copy" data-kind="rca-curl" data-case="${r.caseId}">${icon('terminal')}Copy cURL</button>` : ''}
      ${r.bugTicket ? html`<button type="button" class="btn" data-action="copy" data-kind="rca-ticket" data-case="${r.caseId}">${icon('bug')}Copy bug ticket</button>` : ''}
      <button type="button" class="btn" data-action="copy" data-kind="rca-summary" data-case="${r.caseId}">${icon('copy')}Copy RCA</button>
      ${APPROVAL_URL ? html`<a class="btn" href="${APPROVAL_URL + '#' + r.caseId}" target="_blank" rel="noopener">${icon('external')}Open test case</a>` : ''}
      ${withTests && rcaTests(r)[0] ? html`<button type="button" class="btn btn-primary" data-action="open-test" data-id="${rcaTests(r)[0].id}" data-tab="rca">Details${icon('chevron')}</button>` : ''}`;
  }
  function rcaCard(r) {
    const tests = rcaTests(r);
    return html`<article class="fcard fcard-${rcaTone(r)} rca-card" id="rca-${r.caseId}">
      <header class="fcard-head rca-head" ${tests[0] ? raw('data-action="open-test" data-id="' + esc(tests[0].id) + '" data-tab="rca"') : ''}>
        <span class="rca-ic rca-${rcaTone(r)}">${icon('rca')}</span>
        <div class="row-main"><div class="fcard-title">${hl(r.summary || rcaLabel(r.category))}</div><div class="row-sub">${hl([r.endpoint, r.scenario].filter(Boolean).join(' · '))}</div></div>
        <div class="row-meta">${r.newFailure ? html`<span class="chip chip-new">new</span>` : ''}${rcaChip(r)}${confMeter(r)}</div>
      </header>
      <div class="rca-body">
        ${rcaFacts(r)}
        <div class="rca-tests"><div class="sub-h">${icon('tests')}${plural(rcaCases(r).length, 'affected test case')}${rcaHistory(r)}</div>
          <div class="list">${rcaCases(r).map((id) => { const t = TESTS.find((x) => caseIdOf(x) === id); return t
            ? html`<div class="row" data-action="open-test" data-id="${t.id}" data-tab="rca" tabindex="0" role="button">${statusDot(t.category)}<div class="row-main"><div class="row-title">${t.title}</div><div class="row-sub mono">${id}</div></div><span class="dur">${fmtDur(t.duration)}</span></div>`
            : html`<div class="row">${statusDot('unknown')}<div class="row-main"><div class="row-title mono">${id}</div><div class="row-sub">not in this report</div></div></div>`; })}</div>
        </div>
      </div>
      <footer class="fcard-foot"><span class="mono muted">${r.signature ? 'signature ' + String(r.signature).slice(0, 12) : r.caseId}</span><span class="grow"></span>${rcaActions(r, true)}</footer>
    </article>`;
  }
  function countRows(map, toneOf) {
    const rows = Object.entries(map || {}).sort((a, b) => b[1] - a[1]);
    if (!rows.length) return empty('No data', '', 'analytics');
    const max = Math.max(1, ...rows.map(([, v]) => v));
    return html`<div class="hbars">${rows.map(([k, v]) => html`<div class="hbar"><span class="hbar-label" title="${k}">${toneOf ? rcaLabel(k) : k}</span><span class="hbar-track"><i class="bg-${toneOf ? (RCA_TONE[k] || 'broken') : 'accent'}" style="width:${((v / max) * 100).toFixed(2)}%" data-tip="${k}: ${v}"></i></span><span class="hbar-total">${v}</span></div>`)}</div>`;
  }
  function viewRca(list) {
    if (!RCA) {
      return html`${pageHead('Root cause analysis', 'Not analysed yet')}${card(null, empty('No RCA data in this report',
        'Run the tests from the approval UI (Execute tests), or run  java -jar target/api-test-platform.jar post-run  after mvn test.', 'rca'))}`;
    }
    const visible = new Set(list.map((t) => t.id));
    const entries = RCA_ENTRIES.filter((r) => !filtersActive() || rcaTests(r).some((t) => visible.has(t.id)))
      .filter((r) => !state.search || [r.summary, r.probableCause, r.category, r.endpoint, r.owner, ...rcaCases(r)].join(' ').toLowerCase().includes(state.search));
    const head = pageHead('Root cause analysis', `${plural(RCA.groups ?? RCA_ENTRIES.length, 'root cause')} · ${plural(RCA.failures ?? 0, 'failure')} · ${RCA.newFailures ?? 0} new${RCA.generatedAt ? ' · analysed ' + fmtDate(RCA.generatedAt) : ''}`);
    if (!RCA_ENTRIES.length) return html`${head}${card(null, empty('Nothing to analyse', 'No test failed in this run.', 'check', true))}`;
    const newCount = RCA_ENTRIES.filter((r) => r.newFailure).length;
    const kpi = (label, value, foot, cls) => html`<div class="kpi kpi-static"><div class="kpi-top"><span>${label}</span>${icon('rca', 'kpi-ic')}</div><div class="kpi-value ${cls || ''}">${value}</div><div class="kpi-foot"><span>${foot}</span></div></div>`;
    const kpis = html`<div class="kpis kpis-4">
      ${kpi('Root causes', RCA_ENTRIES.length, 'failures grouped by signature')}
      ${kpi('Failures', RCA.failures ?? RCA_ENTRIES.reduce((n, r) => n + rcaCases(r).length, 0), 'failed test cases analysed')}
      ${kpi('New', newCount, 'not failing in the previous run', newCount ? 'tx-failed' : '')}
      ${kpi('Product bugs', RCA_ENTRIES.filter((r) => r.category === 'FUNCTIONAL_BUG' || r.category === 'SERVER_ERROR' || r.category === 'CONTRACT_DRIFT').length, 'likely API defects, not test issues')}
    </div>`;
    const charts = html`<div class="dash">${card('By category', countRows(RCA.byCategory, true), { icon: 'categories', cls: 'span-6' })}${card('By owner', countRows(RCA.byOwner, false), { icon: 'owner', cls: 'span-6' })}</div>`;
    return html`${head}${kpis}${charts}<div class="sub-h rca-list-h">${icon('rca')}${plural(entries.length, 'root cause')}${entries.length < RCA_ENTRIES.length ? ' (filtered)' : ''}</div>
      ${entries.length ? html`<div class="fcards">${entries.map(rcaCard)}</div>` : card(null, empty('No root cause matches the filters', '', 'search'))}`;
  }
  function tabRca(t) {
    const r = rcaOf(t);
    if (!r) {
      if (!FAILING.includes(t.category) && t.category !== 'flaky') return empty('No failure', 'RCA is produced for failed tests only.', 'check', true);
      return empty(RCA ? 'No RCA entry for this test' : 'Not analysed yet', RCA ? 'The test has no @TC- tag, or it failed outside the evidence hooks.' : 'Run post-run (or Execute tests in the approval UI) to add RCA to this report.', 'rca');
    }
    const others = rcaCases(r).filter((id) => id !== caseIdOf(t));
    return html`<section class="rca-detail rca-${rcaTone(r)}">
        <div class="rca-detail-head">${rcaChip(r)}${r.newFailure ? html`<span class="chip chip-new">new failure</span>` : ''}<span class="grow"></span>${confMeter(r)}</div>
        <h3 class="rca-summary">${r.summary || rcaLabel(r.category)}</h3>
        <p class="muted rca-hint">${RCA_HINT[r.category] || ''}</p>
        ${rcaFacts(r)}
      </section>
      ${others.length ? html`<div class="sub-h">${icon('layers')}Same root cause as ${plural(others.length, 'other test case')}</div><div class="list">${others.map((id) => { const o = TESTS.find((x) => caseIdOf(x) === id); return o ? html`<div class="row" data-action="open-test" data-id="${o.id}" data-tab="rca" tabindex="0" role="button">${statusDot(o.category)}<div class="row-main"><div class="row-title">${o.title}</div><div class="row-sub mono">${id}</div></div></div>` : html`<div class="row"><div class="row-main mono">${id}</div></div>`; })}</div>` : ''}
      ${(r.history || []).length ? html`<div class="sub-h">${icon('analytics')}Recent runs${rcaHistory(r)}</div>` : ''}
      ${r.curl ? html`<div class="sub-h">${icon('terminal')}Reproduce<span class="grow"></span><button type="button" class="link" data-action="copy" data-kind="rca-curl" data-case="${r.caseId}">${icon('copy')}Copy</button></div><pre class="code rca-curl">${r.curl}</pre>` : ''}
      ${r.bugTicket ? html`<details class="stack rca-ticket"><summary>${icon('chevron', 'caret')}Bug ticket draft<span class="grow"></span><button type="button" class="link" data-action="copy" data-kind="rca-ticket" data-case="${r.caseId}">${icon('copy')}Copy</button></summary><pre class="code">${r.bugTicket}</pre></details>` : ''}
      <div class="rca-actions">${rcaActions(r, false)}</div>
      <p class="note">Category, confidence and owner come from deterministic rules${r.analysedBy && /llm|model/i.test(r.analysedBy) ? '; the summary text was rewritten by ' + r.analysedBy : ''}. Never change an assertion to make a failure pass: fix the API, the data or the configuration.</p>`;
  }
  function rcaCallout(t) {
    const r = rcaOf(t);
    if (!r) return '';
    return html`<button type="button" class="rca-callout rca-${rcaTone(r)}" data-action="tab" data-tab="rca">${icon('rca')}<span class="row-main"><b>Root cause: ${rcaLabel(r.category)}</b><span>${r.summary || r.probableCause || ''}</span></span>${icon('chevron')}</button>`;
  }
  function rcaText(r) {
    return [
      `RCA ${r.caseId} — ${r.category} (${Math.round((Number(r.confidence) || 0) * 100)}% confidence)${r.newFailure ? ' — NEW' : ''}`,
      r.summary && 'Summary: ' + r.summary,
      r.endpoint && 'Endpoint: ' + r.endpoint,
      r.probableCause && 'Probable cause: ' + r.probableCause,
      r.suggestedFix && 'Suggested fix: ' + r.suggestedFix,
      r.owner && 'Owner: ' + r.owner,
      (r.relatedCaseIds || []).length && 'Also affects: ' + r.relatedCaseIds.join(', '),
      r.curl && '\nReproduce:\n' + r.curl,
    ].filter(Boolean).join('\n');
  }

  function testsTable(list) {
    const cols = [['status', 'Status'], ['name', 'Test'], ['project', 'Project'], ['tags', 'Tags', false], ['retries', 'Retries'], ['duration', 'Duration'], ['history', 'History', false]];
    const head = cols.map(([k, label, sortable = true]) => (sortable
      ? html`<th class="${k === 'duration' || k === 'retries' ? 'num' : ''}"><button type="button" class="th-sort${state.sort.key === k ? ' is-active' : ''}" data-action="sort" data-key="${k}">${label}${state.sort.key === k ? icon(state.sort.dir > 0 ? 'up' : 'down') : ''}</button></th>`
      : html`<th>${label}</th>`));
    const rows = sortTests(list).map((t) => html`<tr class="${state.selected === t.id ? 'is-selected' : ''}" data-action="open-test" data-id="${t.id}" tabindex="0">
      <td>${pill(t.category, statusLabel(t))}</td>
      <td class="td-name"><div class="row-title">${hl(t.title)}</div><div class="row-sub">${hl(t.location)}</div></td>
      <td>${t.project}</td>
      <td><div class="td-tags">${listTags(t).map(tagChip)}</div></td>
      <td class="num">${t.retries || ''}</td>
      <td class="num">${fmtDur(t.duration)}</td>
      <td>${historyDots(t)}</td></tr>`);
    return html`<div class="table-wrap"><table class="tbl"><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table></div>`;
  }
  function viewTests(list) {
    const toggle = html`<div class="seg">${TEST_MODES.map(([m, l, ic]) => html`<button type="button" class="seg-btn${state.testsMode === m ? ' is-active' : ''}" data-action="tests-mode" data-mode="${m}">${icon(ic)}${l}</button>`)}</div>`;
    const head = pageHead('Tests', `${list.length} of ${plural(TESTS.length, 'test')} · ${fmtDur(sumDur(list))} total`, toggle);
    if (!list.length) return html`${head}${card(null, empty('No tests match', 'Try clearing the search or status filters.'))}`;
    if (state.testsMode === 'table') return html`${head}${card(null, testsTable(list), { cls: 'card-flush' })}`;
    let groups;
    if (state.testsMode === 'status') {
      groups = CATS.slice().sort((a, b) => SEVERITY[a] - SEVERITY[b])
        .map((c) => ({ key: 'st:' + c, name: LABEL[c], tests: list.filter((t) => t.category === c) }));
    } else if (state.testsMode === 'tag') {
      groups = ALL_TAGS.map((tag) => ({ key: 'tg:' + tag, name: tag, tests: list.filter((t) => t.tags.includes(tag)) }));
      groups.push({ key: 'tg:__untagged', name: 'Untagged', tests: list.filter((t) => !t.tags.length) });
    } else {
      return html`${head}${card(null, buildGroups(list).items.map((it) => suiteGroup(it.group, [it.group.name])), { cls: 'card-flush' })}`;
    }
    return html`${head}${card(null, groups.filter((g) => g.tests.length).map((g) => group(g.key, groupSummary(g.name, g.tests), g.tests.map((t) => testRow(t, { path: true })), 'node-suite')), { cls: 'card-flush' })}`;
  }

  function viewCategories(list) {
    const cats = new Map();
    list.forEach((t) => {
      if (!t.errorCategory) return;
      if (!cats.has(t.errorCategory)) cats.set(t.errorCategory, { tests: [], groups: new Map() });
      const c = cats.get(t.errorCategory);
      c.tests.push(t);
      const g = t.errorGroup || 'Unknown error';
      if (!c.groups.has(g)) c.groups.set(g, []);
      c.groups.get(g).push(t);
    });
    const entries = [...cats].sort((a, b) => b[1].tests.length - a[1].tests.length);
    const total = entries.reduce((n, [, c]) => n + c.tests.length, 0);
    const head = pageHead('Categories', total ? `${plural(total, 'failure')} in ${plural(entries.length, 'category', 'categories')}` : 'Failures grouped by root cause');
    if (!entries.length) return html`${head}${card(null, empty('No failures to categorise', 'Failed, broken and flaky tests are grouped here by error type.', 'check', true))}`;
    const cards = html`<div class="catcards">${entries.map(([name, c]) => { const s = stats(c.tests); return html`<button type="button" class="catcard" data-action="scroll-to" data-key="${'cat:' + name}">
      <div class="card-top">${statusDot(worst(s), 'sd-sm')}<span class="muted">${pct(c.tests.length, total)}% of failures</span></div>
      <div><div class="row-title">${name}</div><div class="card-num"><b>${c.tests.length}</b>${plural(c.groups.size, 'distinct message')}</div></div>${miniBar(s, 'mbar-lg')}</button>`; })}</div>`;
    const groups = entries.map(([name, c]) => {
      const s = stats(c.tests);
      const inner = [...c.groups].map(([msg, ts]) => group('cat:' + name + ':' + msg,
        html`<span class="node-name" title="${msg}">${hl(msg)}</span><span class="node-stats"><span class="node-count">${ts.length}</span></span>`,
        ts.map((t) => testRow(t, { path: true })), 'node-msg'));
      return group('cat:' + name, html`${statusDot(worst(s))}<span class="node-name">${name}</span><span class="node-stats">${miniBar(s)}<span class="node-count">${c.tests.length}</span></span>`, inner, 'node-category');
    });
    return html`${head}${cards}${card(null, groups, { cls: 'card-flush' })}`;
  }

  function viewTags(list) {
    const groups = ALL_TAGS.map((tag) => ({ key: tag, label: tag, tests: list.filter((t) => t.tags.includes(tag)) })).filter((g) => g.tests.length);
    const untagged = list.filter((t) => !t.tags.length);
    if (untagged.length) groups.push({ key: '__untagged', label: 'untagged', tests: untagged });
    const head = pageHead('Tags', `${plural(groups.length, 'group')} · click a card to filter every view`);
    if (!groups.length) return html`${head}${card(null, empty('No tests match', 'Try clearing the filters.'))}`;
    const cards = html`<div class="tagcards">${groups.map((g) => { const s = stats(g.tests); return html`<button type="button" class="tagcard${state.tag === g.key ? ' is-active' : ''}" data-action="tag-card" data-tag="${g.key}">
      <div class="card-top"><span class="tag tag-lg">${g.label}</span>${ring(passRate(s), { size: 54, stroke: 6, label: '' })}</div>
      <div class="card-num"><b>${s.total}</b>${plural(s.total, 'test')} · ${fmtDur(sumDur(g.tests))}</div>${miniBar(s, 'mbar-lg')}</button>`; })}</div>`;
    const lists = groups.map((g) => { const s = stats(g.tests); return group('tag:' + g.key, html`${statusDot(worst(s))}<span class="tag">${g.label}</span><span class="node-stats">${miniBar(s)}<span class="node-count">${s.total}</span><span class="dur">${fmtDur(sumDur(g.tests))}</span></span>`, g.tests.map((t) => testRow(t, { path: true })), 'node-tag'); });
    return html`${head}${cards}${card(null, lists, { cls: 'card-flush' })}`;
  }

  function viewAnalytics(list) {
    const toRows = (map) => [...map].map(([label, ts]) => ({ label, stats: stats(ts) }));
    const tagRows = ALL_TAGS.map((tag) => ({ label: tag, tag, stats: stats(list.filter((t) => t.tags.includes(tag))) }));
    tagRows.push({ label: 'untagged', tag: '__untagged', stats: stats(list.filter((t) => !t.tags.length)) });
    const fileRows = toRows(groupBy(list, (t) => t.file))
      .sort((a, b) => b.stats.failed + b.stats.broken - (a.stats.failed + a.stats.broken) || b.stats.total - a.stats.total)
      .slice(0, 10);
    const executed = list.filter((t) => t.attempts.length);
    const retryRows = [['0', (n) => n === 0], ['1', (n) => n === 1], ['2', (n) => n === 2], ['3+', (n) => n >= 3]]
      .map(([label, fn]) => ({ label: label + ' retries', stats: stats(executed.filter((t) => fn(t.retries))) }));
    return html`${pageHead('Analytics', `${plural(list.length, 'test')} · hover any bar for details`, legendRow())}<div class="dash">
      ${TREND.length > 1 ? card('Run trend', trendChart(TREND), { icon: 'analytics', cls: 'span-12', actions: html`<span class="muted">bars: results · line: pass rate</span>` }) : ''}
      ${card('Status by tag', hBars(tagRows.filter((r) => r.stats.total)), { icon: 'tag', cls: 'span-6' })}
      ${card('Duration distribution', vBars(durationBuckets(executed)), { icon: 'clock', cls: 'span-6' })}
      ${card('Tests per browser', hBars(toRows(groupBy(list, (t) => t.browser))), { icon: 'env', cls: 'span-4' })}
      ${card('Tests per project', hBars(toRows(groupBy(list, (t) => t.project))), { icon: 'layers', cls: 'span-4' })}
      ${card('Retries', vBars(retryRows, 170), { icon: 'retry', cls: 'span-4' })}
      ${card('Files', hBars(fileRows), { icon: 'file', cls: 'span-12', actions: html`<span class="muted">most failures first · top 10</span>` })}
    </div>`;
  }

  function viewTimeline(list) {
    const items = [];
    list.forEach((t) => t.attempts.forEach((a, i) => { if (a.workerIndex >= 0 && a.startTime) items.push({ t, a, i }); }));
    if (!items.length) return html`${pageHead('Timeline')}${card(null, empty('Nothing to plot', 'No executed tests match the current filters.', 'timeline'))}`;
    let min = Infinity;
    let max = -Infinity;
    items.forEach(({ a }) => { min = Math.min(min, a.startTime); max = Math.max(max, a.startTime + a.duration); });
    const span = Math.max(max - min, 1);
    const lanes = [...groupBy(items, (it) => String(it.a.parallelIndex))].sort((a, b) => Number(a[0]) - Number(b[0]));
    const bar = ({ t, a, i }) => {
      const left = ((a.startTime - min) / span) * 100;
      const width = Math.max((a.duration / span) * 100, 0.35);
      const tip = t.title + (i ? ' (retry ' + i + ')' : '') + '\n' + (STATUS_LABEL[a.status] || a.status) + ' · ' + fmtTime(a.startTime) + ' · ' + fmtDur(a.duration) + '\nworker #' + a.workerIndex + ' · ' + t.project;
      return html`<div class="tl-bar bg-${catOf(ATTEMPT_CAT[a.status])}${state.selected === t.id ? ' is-selected' : ''}" style="left:${left.toFixed(3)}%;width:${width.toFixed(3)}%" data-action="open-test" data-id="${t.id}" data-attempt="${i}" data-tip="${tip}"><span>${t.title}</span></div>`;
    };
    const ticks = [0, 1, 2, 3, 4, 5].map((i) => html`<span class="tl-tick" style="left:${i * 20}%">+${fmtDur((span * i) / 5)}</span>`);
    const lanesHtml = lanes.map(([k, its]) => {
      const busy = its.reduce((n, it) => n + it.a.duration, 0);
      return html`<div class="tl-lane"><div class="tl-label">Worker ${k}<span>${pct(busy, span)}% busy · ${its.length}</span></div><div class="tl-track">${its.map(bar)}</div></div>`;
    });
    return html`${pageHead('Timeline', `${plural(items.length, 'attempt')} on ${plural(lanes.length, 'worker')} · ${fmtDur(span)} wall time`)}
      ${card(null, html`<div class="tl-tools">${legendRow()}<span class="grow"></span><label class="zoom">${icon('search')}Zoom<input type="range" min="1" max="10" step="0.5" value="${state.zoom}" data-action="tl-zoom" aria-label="Zoom timeline"></label></div>
      <div class="tl-scroll"><div class="timeline" style="--zoom:${state.zoom}"><div class="tl-axis">${ticks}</div><div class="tl-lanes"><div class="tl-grid"></div>${lanesHtml}</div></div></div>`)}`;
  }

  // ---------- detail drawer ----------
  function lineClass(i, line, inLog) {
    if (i === 0) return 'l-head';
    if (/^\s*Call log:/.test(line)) return 'l-sec';
    if (inLog) return 'l-log';
    if (/^\s*Expected\b/.test(line) || /^\s*- /.test(line)) return 'l-exp';
    if (/^\s*Received\b/.test(line) || /^\s*\+ /.test(line)) return 'l-rec';
    return '';
  }
  function errorPre(message, cls = '') {
    let inLog = false;
    const lines = String(message || '').split('\n').map((line, i) => {
      if (/^\s*Call log:/.test(line)) inLog = true;
      return html`<span class="ln ${lineClass(i, line, inLog)}">${line || '\u200b'}</span>`;
    });
    return html`<pre class="code err ${cls}">${lines}</pre>`;
  }
  function snippetPre(snippet) {
    return html`<pre class="code">${String(snippet).split('\n').map((l) => html`<span class="ln ${/^\s*>/.test(l) ? 'l-hit' : /^\s*\|\s*\^/.test(l) ? 'l-caret' : ''}">${l || '\u200b'}</span>`)}</pre>`;
  }
  function stackPre(stack) {
    return html`<pre class="code">${String(stack).split('\n').map((l) => html`<span class="ln ${/node_modules|node:internal|\bat (java\.|javax\.|jdk\.|sun\.|org\.testng\.|io\.cucumber\.|com\.microsoft\.playwright\.|org\.assertj\.|org\.opentest4j\.|org\.apache\.maven\.|org\.picocontainer\.|io\.qameta\.|org\.rg\.reporter\.|io\.restassured\.|groovy\.|org\.codehaus\.groovy\.|org\.apache\.http\.)/.test(l) ? 'l-dim' : 'l-own'}">${l || '\u200b'}</span>`)}</pre>`;
  }
  function gallery(imgs) {
    return html`<div class="gallery" data-gallery>${imgs.map((x) => html`<figure class="shot" data-action="lightbox"><img src="${safeUrl(x.src)}" alt="${x.name}" loading="lazy"><figcaption>${x.name}</figcaption></figure>`)}</div>`;
  }

  function stepRows(steps, depth, ctx) {
    const visible = (list) => list.filter((s) => state.showHooks || (s.category !== 'hook' && s.category !== 'fixture'));
    return visible(steps).map((s) => {
      const kids = visible(s.steps || []);
      const failed = s.status === 'failed';
      const g = logGroup(s.category);
      const loc = parseLoc(s.location);
      const src = !kids.length && loc && SOURCES[loc.file] != null ? snippet(loc) : '';
      const childFailed = (s.steps || []).some((k) => k.status === 'failed');
      const err = s.error && !childFailed ? errorPre(s.error.message, 'err-sm') : '';
      const expandable = kids.length || src || err;
      const left = clamp(((s.startTime - ctx.t0) / ctx.total) * 100, 0, 100);
      const width = clamp((s.duration / ctx.total) * 100, 0.4, Math.max(0.4, 100 - left));
      const row = html`<span class="st-cell" style="--d:${depth}">${expandable ? icon('chevron', 'caret') : html`<span class="caret-sp"></span>`}<span class="xl-ic xl-${g}${failed ? ' is-failed' : ''}">${icon(failed ? 'x' : LOG_ICON[g])}</span><span class="st-title">${hl(s.title)}</span></span><span class="st-loc" dir="rtl">${shortLoc(s.location) ? '\u200e' + shortLoc(s.location) + '\u200e' : ''}</span><span class="wf"><i class="wf-bar${failed ? ' is-failed' : ''}" style="left:${left.toFixed(2)}%;width:${width.toFixed(2)}%"></i></span><span class="dur">${fmtDur(s.duration)}</span>`;
      const tip = s.title + '\n' + (STEP_LABEL[s.category] || s.category) + ' · started +' + fmtOffset(s.startTime - ctx.t0) + (s.location ? '\n' + s.location : '');
      if (!expandable) return html`<div class="st"><div class="st-row" data-tip="${tip}">${row}</div></div>`;
      const body = err || src ? html`<div class="st-body" style="--d:${depth}">${err}${src}</div>` : '';
      return html`<details class="st${failed ? ' is-failed' : ''}" ${failed ? raw('open') : ''}><summary class="st-row" data-tip="${tip}">${row}</summary>${body}${stepRows(kids, depth + 1, ctx)}</details>`;
    });
  }
  function tabSteps(a) {
    if (!a) return empty('Not executed', 'This test did not run.', 'minus');
    if (!a.steps.length) return empty('No steps recorded', '', 'tests');
    const ctx = { t0: a.startTime, total: Math.max(a.duration, 1) };
    return html`<div class="steps-tools">
        <label class="switch"><input type="checkbox" data-action="toggle-hooks" ${state.showHooks ? raw('checked') : ''}><span class="switch-ui"></span>Hooks &amp; fixtures</label>
        <span class="muted">${plural(countSteps(a.steps), 'step')}</span><span class="grow"></span>
        <button type="button" class="link" data-action="copy" data-kind="log">${icon('copy')}Copy log</button>
        <button type="button" class="link" data-action="steps-expand" data-open="1">${icon('expand')}Expand</button>
        <button type="button" class="link" data-action="steps-expand" data-open="0">${icon('collapse')}Collapse</button>
      </div>
      <div class="wf-head"><span>Step</span><span>Location</span><span>Timeline · ${fmtDur(a.duration)}</span><span class="num">Time</span></div>
      <div class="steps">${stepRows(a.steps, 0, ctx)}</div>`;
  }
  function promptCard(a) {
    if (!a || !a.prompt) return '';
    return html`<section class="prompt-card">
      <div class="prompt-head"><span class="prompt-ic">${icon('sparkle')}</span>
        <div class="row-main"><b>Fix with AI</b><div class="prompt-sub">A ready-made prompt with the error, test source, executed steps and the rule-based RCA. Paste it into Copilot Chat or any AI assistant.</div></div>
        <button type="button" class="btn btn-primary" data-action="copy" data-kind="prompt">${icon('copy')}Copy prompt</button></div>
      <details class="prompt-preview"><summary>${icon('chevron', 'caret')}Preview prompt<span class="muted">${fmtBytes(a.prompt.length)}</span></summary><pre class="code">${a.prompt}</pre></details>
    </section>`;
  }
  function tabErrors(t, a) {
    return html`${rcaCallout(t)}${tabErrorsBody(t, a)}`;
  }
  function tabErrorsBody(t, a) {
    if (!a || !a.errors.length) return empty('No errors', 'This attempt finished without errors.', 'check', true);
    const imgs = imagesOf(a);
    return html`${promptCard(a)}${a.errors.map((e, i) => html`<section class="err-card">
        <div class="err-card-head"><span class="err-kind">${a.errors.length > 1 ? 'Error ' + (i + 1) + ' of ' + a.errors.length : t.errorCategory || 'Error'}</span>${e.location ? html`<span class="mono muted">${e.location}</span>` : ''}<span class="grow"></span><button type="button" class="link" data-action="copy" data-kind="error" data-index="${i}">${icon('copy')}Copy</button></div>
        ${errorPre(e.message)}
        ${e.snippet ? html`<div class="sub-h">Source</div>${snippetPre(e.snippet)}` : ''}
        ${e.stack ? html`<details class="stack" open><summary>${icon('chevron', 'caret')}Stack trace<span class="grow"></span><button type="button" class="link" data-action="copy" data-kind="stack" data-index="${i}">${icon('copy')}Copy</button></summary>${stackPre(e.stack)}</details>` : ''}
      </section>`)}
      ${imgs.length ? html`<div class="sub-h">${icon('image')}Screenshots</div>${gallery(imgs)}` : ''}`;
  }
  const TOKEN_RE = /(\/\/.*$|^\s*#.*$|@\w+)|(\/\*.*?\*\/)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`)|\b(\d+(?:\.\d+)?)\b|\b(import|from|export|default|const|let|var|async|await|function|return|if|else|for|of|in|new|class|extends|implements|type|interface|true|false|null|undefined|this|try|catch|finally|throw|typeof|as|while|switch|case|break|public|private|protected|static|final|void|package|super|int|long|boolean|double|String|Feature|Scenario|Outline|Background|Rule|Examples|Given|When|Then|And|But)\b/g;
  function highlight(line) {
    const out = [];
    let last = 0;
    let m;
    TOKEN_RE.lastIndex = 0;
    while ((m = TOKEN_RE.exec(line))) {
      if (m.index > last) out.push(line.slice(last, m.index));
      out.push(html`<span class="${m[1] || m[2] ? 'tk-c' : m[3] ? 'tk-s' : m[4] ? 'tk-n' : 'tk-k'}">${m[0]}</span>`);
      last = m.index + m[0].length;
    }
    out.push(line.slice(last));
    return out;
  }
  function codeLines(file, from, to, marks) {
    const lines = (SOURCES[file] || '').split('\n');
    const rows = [];
    for (let n = Math.max(1, from); n <= Math.min(lines.length, to); n++) {
      const mk = marks.get(n);
      rows.push(html`<div class="src-ln ${mk ? mk.cls : ''}"><span class="src-no">${n}</span><span class="src-code">${highlight(lines[n - 1])}</span>${mk && mk.badge ? html`<span class="src-badge">${mk.badge}</span>` : ''}</div>`);
      if (mk && mk.note) rows.push(html`<div class="src-note">${mk.note}</div>`);
    }
    return html`<div class="src">${rows}</div>`;
  }
  function snippet(loc) {
    return html`<div class="xl-src"><div class="xl-src-head">${icon('code')}${loc.file}:${loc.line}</div>${codeLines(loc.file, loc.line - 2, loc.line + 2, new Map([[loc.line, { cls: 'is-hit' }]]))}</div>`;
  }

  const logText = (a) => flattenSteps(a.steps).map(({ step: s, depth }) => '+' + fmtOffset(s.startTime - a.startTime).padStart(8) + '  ' + '  '.repeat(depth) + (s.status === 'failed' ? '✘ ' : '✓ ') + s.title + ' (' + fmtDur(s.duration) + ')' + (s.location ? '  ' + s.location : '')).join('\n');

  function tabSource(t, a) {
    const stepLocs = a ? flattenSteps(a.steps).map((r) => parseLoc(r.step.location)).filter(Boolean) : [];
    const errLocs = a ? a.errors.map((e) => parseLoc(e.location)).filter(Boolean) : [];
    const files = [...new Set([t.file, ...errLocs.map((l) => l.file), ...stepLocs.map((l) => l.file)])].filter((f) => SOURCES[f] != null);
    if (!files.length) return empty('Source not available', 'The file could not be read when the report was generated.', 'code');
    const file = files.includes(state.sourceFile) ? state.sourceFile : files[0];
    const marks = new Map();
    if (file === t.file) marks.set(t.line, { cls: 'is-test', badge: 'test starts' });
    const hits = new Map();
    flattenSteps(a ? a.steps : []).forEach(({ step }) => {
      const l = parseLoc(step.location);
      if (!l || l.file !== file) return;
      const h = hits.get(l.line) || { n: 0, ms: 0, failed: false };
      h.n++;
      h.ms += step.duration;
      h.failed = h.failed || step.status === 'failed';
      hits.set(l.line, h);
    });
    hits.forEach((h, line) => marks.set(line, { cls: h.failed ? 'is-err' : 'is-exec', badge: (h.n > 1 ? '×' + h.n + ' · ' : '') + fmtDur(h.ms) }));
    (a ? a.errors : []).forEach((e) => {
      const l = parseLoc(e.location);
      if (l && l.file === file) marks.set(l.line, { cls: 'is-err', badge: 'failed here', note: errorPre(truncLines(e.message, 8), 'err-sm') });
    });
    const picker = files.length > 1 ? html`<div class="seg seg-sm">${files.map((f) => html`<button type="button" class="seg-btn${f === file ? ' is-active' : ''}" data-action="source-file" data-file="${f}" data-tip="${f}">${f.split('/').pop()}</button>`)}</div>` : '';
    return html`<div class="src-tools">${icon('file')}<span class="mono">${file}</span><span class="grow"></span>
        <span class="src-key"><i class="is-exec"></i>executed</span><span class="src-key"><i class="is-err"></i>failed</span><span class="src-key"><i class="is-test"></i>test</span>${picker}</div>
      ${codeLines(file, 1, SOURCES[file].split('\n').length, marks)}`;
  }

  function comparisons(imgs) {
    const map = new Map();
    imgs.forEach((x) => {
      const m = /^(.*)-(expected|actual|diff)(\.[a-z]+)?$/i.exec(x.name);
      if (!m) return;
      const g = map.get(m[1]) || { name: m[1] };
      g[m[2].toLowerCase()] = x;
      map.set(m[1], g);
    });
    const groups = [...map.values()].filter((g) => g.expected && g.actual);
    return { groups, used: new Set(groups.flatMap((g) => [g.expected, g.actual, g.diff].filter(Boolean))) };
  }
  function compareWidget(g) {
    const modes = [g.diff && 'diff', 'actual', 'expected', 'slider'].filter(Boolean);
    return html`<section class="cmp" data-mode="${modes[0]}">
      <div class="cmp-head">${icon('image')}<b>${g.name}</b><span class="muted">visual comparison</span><span class="grow"></span><div class="seg seg-sm">${modes.map((m) => html`<button type="button" class="seg-btn" data-action="cmp-mode" data-mode="${m}">${m[0].toUpperCase() + m.slice(1)}</button>`)}</div></div>
      <div class="cmp-stage"><img class="cmp-img cmp-expected" src="${safeUrl(g.expected.src)}" alt="Expected"><img class="cmp-img cmp-actual" src="${safeUrl(g.actual.src)}" alt="Actual">${g.diff ? html`<img class="cmp-img cmp-diff" src="${safeUrl(g.diff.src)}" alt="Diff">` : ''}</div>
      <input type="range" class="cmp-range" min="0" max="100" value="50" data-action="cmp-slide" aria-label="Compare actual and expected">
      <div class="cmp-hint"><span>◀ Actual</span><span>Expected ▶</span></div>
    </section>`;
  }
  function attachmentCard(x, index) {
    const url = x.src ? safeUrl(x.src) : '#';
    const ok = url !== '#';
    const ic = { video: 'video', trace: 'trace', text: 'file', image: 'image' }[x.kind] || 'file';
    const open = ok ? html`<a class="link" href="${url}" target="_blank" rel="noopener">${icon('download')}Open</a>` : '';
    let body = '';
    if (x.kind === 'video' && ok) body = html`<video controls preload="metadata" src="${url}"></video>`;
    else if (x.kind === 'trace' && ok) body = html`<div class="cmd"><code>${traceCmd(DATA.outputFolder + '/' + url)}</code><button type="button" class="link" data-action="copy" data-kind="trace" data-src="${url}">${icon('copy')}Copy</button></div><p class="note">Traces need the Trace Viewer: run the command above, or drop the file onto trace.playwright.dev.</p>`;
    else if (x.content != null) body = html`<pre class="code">${pretty(x.content, x.contentType)}</pre>`;
    return html`<section class="acard"><div class="acard-head"><span class="acard-ic">${icon(ic)}</span><div class="row-main"><div class="acard-name">${x.name}</div><div class="acard-meta">${x.contentType}${x.size != null ? ' · ' + fmtBytes(x.size) : ''}</div></div>${x.content != null ? html`<button type="button" class="link" data-action="copy" data-kind="attachment" data-index="${index}">${icon('copy')}Copy</button>` : ''}${open}</div>${body ? html`<div class="acard-body">${body}</div>` : ''}</section>`;
  }
  function pretty(text, type) {
    if (/json/i.test(type || '')) { try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; } }
    return text;
  }
  function tabAttachments(a) {
    if (!a || !a.attachments.length) return empty('No attachments', 'Screenshots, videos and traces appear here.', 'image');
    const imgs = imagesOf(a);
    const cmp = comparisons(imgs);
    const plain = imgs.filter((x) => !cmp.used.has(x));
    return html`${cmp.groups.map(compareWidget)}
      ${plain.length ? html`<div class="sub-h">${icon('image')}Screenshots</div>${gallery(plain)}` : ''}
      ${a.attachments.map((x, i) => (x.kind === 'image' && x.src ? '' : attachmentCard(x, i)))}`;
  }
  function tabLogs(a) {
    if (!a) return empty('Not executed', 'This test did not run.', 'minus');
    const block = (name, text) => html`<section class="log"><div class="log-head"><b>${name}</b><span class="muted">${text ? plural(text.replace(/\n$/, '').split('\n').length, 'line') : 'empty'}</span><span class="grow"></span>${text ? html`<button type="button" class="link" data-action="copy" data-kind="${name}">${icon('copy')}Copy</button>` : ''}</div>${text ? html`<pre class="code log-pre log-${name}">${text.replace(/\n$/, '').split('\n').map((l) => html`<span class="ln">${l || '\u200b'}</span>`)}</pre>` : html`<div class="log-empty">No output</div>`}</section>`;
    return html`${block('stdout', a.stdout)}${block('stderr', a.stderr)}`;
  }
  function tabHistory(t) {
    const attempts = t.attempts.length
      ? html`<div class="att-list">${t.attempts.map((a, i) => html`<button type="button" class="att-row${i === state.attempt ? ' is-active' : ''}" data-action="select-attempt" data-attempt="${i}">${statusDot(ATTEMPT_CAT[a.status])}<span class="att-name">${i ? 'Retry ' + i : 'Initial run'}</span><span class="muted">${fmtTime(a.startTime)} · worker #${a.workerIndex}</span><span class="att-err">${a.errors.length ? firstLine(a.errors[0].message) : ''}</span><span class="dur">${fmtDur(a.duration)}</span></button>`)}</div>`
      : empty('No attempts recorded', '', 'minus');
    const hist = runHistory(t);
    let across;
    if (hist.length > 1) {
      const hs = stats(hist);
      across = html`<div class="hist-card"><div class="hist-strip">${hist.map((h) => html`<span class="hs bg-${catOf(h.category)}${h.current ? ' is-current' : ''}" data-tip="${h.current ? 'This run' : fmtDate(h.startTime)}\n${LABEL[catOf(h.category)]} · ${fmtDur(h.duration)}"></span>`)}</div>
        ${sparkline(hist.map((h) => h.duration))}
        <div class="hist-sum"><span>Pass rate <b>${passRate(hs)}%</b></span><span>Runs <b>${hist.length}</b></span><span>Avg duration <b>${fmtDur(hist.reduce((n, h) => n + h.duration, 0) / hist.length)}</b></span>${isUnstable(t) ? html`<span class="chip chip-warn">unstable</span>` : ''}</div></div>`;
    } else {
      across = empty('No previous runs yet', 'Run the suite again to build history for this test.', 'clock');
    }
    return html`<div class="sub-h">${icon('retry')}This run</div>${attempts}${t.category === 'flaky' ? html`<p class="note">Marked flaky: the test failed and then passed on a retry.</p>` : ''}<div class="sub-h">${icon('analytics')}Across runs</div>${across}`;
  }

  function renderDetail() {
    const t = byId.get(state.selected);
    if (!t) { closeDetail(); return; }
    const a = t.attempts[state.attempt];
    const idx = state.order.indexOf(t.id);
    const counts = {
      steps: a ? countSteps(a.steps) : 0,
      errors: a ? a.errors.length : 0,
      attachments: a ? a.attachments.length : 0,
      logs: a ? (a.stdout ? 1 : 0) + (a.stderr ? 1 : 0) : 0,
      history: t.attempts.length + (t.history || []).length,
      rca: rcaOf(t) ? 1 : 0,
    };
    let body;
    switch (state.tab) {
      case 'errors': body = tabErrors(t, a); break;
      case 'source': body = tabSource(t, a); break;
      case 'attachments': body = tabAttachments(a); break;
      case 'logs': body = tabLogs(a); break;
      case 'history': body = tabHistory(t); break;
      case 'rca': body = tabRca(t); break;
      default: body = tabSteps(a);
    }
    const meta = [
      ['Project', t.project || '—'], ['Browser', t.browser], ['Location', t.location],
      ['Worker', a ? '#' + a.workerIndex : '—'], ['Started', a ? fmtDate(a.startTime) : '—'], ['Error type', t.errorCategory || '—'],
      ...(caseIdOf(t) ? [['Test case', caseIdOf(t)]] : []), ...(rcaOf(t) ? [['Root cause', rcaLabel(rcaOf(t).category)]] : []),
    ];
    mount(drawerInner, html`
      <header class="d-head d-head-${catOf(t.category)}">
        <div class="d-toolbar">
          <div class="d-crumbs">${[t.project, ...groupPath(t)].filter(Boolean).join(' › ')}</div>
          <div class="d-actions">
            <button type="button" class="ibtn" data-action="nav-test" data-dir="-1" data-tip="Previous test (k)" ${idx <= 0 ? raw('disabled') : ''}>${icon('up')}</button>
            <button type="button" class="ibtn" data-action="nav-test" data-dir="1" data-tip="Next test (j)" ${idx === -1 || idx >= state.order.length - 1 ? raw('disabled') : ''}>${icon('down')}</button>
            ${a && a.prompt ? html`<button type="button" class="ibtn ibtn-ai" data-action="copy" data-kind="prompt" data-tip="Copy AI prompt to fix this failure">${icon('sparkle')}</button>` : ''}
            <button type="button" class="ibtn" data-action="copy" data-kind="rerun" data-tip="Copy re-run command">${icon('terminal')}</button>
            <button type="button" class="ibtn" data-action="copy" data-kind="link" data-tip="Copy link to this test">${icon('link')}</button>
            <button type="button" class="ibtn" data-action="close-detail" data-tip="Close (Esc)">${icon('close')}</button>
          </div>
        </div>
        <div class="d-title">${statusDot(t.category, 'sd-lg')}<h2>${t.title}</h2></div>
        <div class="d-chips">${pill(t.category, statusLabel(t))}<span class="chip">${icon('clock')}${fmtDur(t.duration)}</span>${t.retries ? html`<span class="chip chip-warn">${icon('retry')}${plural(t.retries, 'retry', 'retries')}</span>` : ''}${t.history && t.history.length && isUnstable(t) ? html`<span class="chip chip-warn">unstable</span>` : ''}${t.tags.map(tagChip)}</div>
        <dl class="d-meta">${meta.map(([k, v]) => html`<div><dt>${k}</dt><dd>${v}</dd></div>`)}</dl>
        ${t.attempts.length > 1 ? html`<div class="d-attempts seg">${t.attempts.map((at, i) => html`<button type="button" class="seg-btn${i === state.attempt ? ' is-active' : ''}" data-action="select-attempt" data-attempt="${i}">${statusDot(ATTEMPT_CAT[at.status], 'sd-xs')}${i ? 'Retry ' + i : 'Run'}<span class="muted">${fmtDur(at.duration)}</span></button>`)}</div>` : ''}
        ${t.annotations.length ? html`<ul class="d-annots">${t.annotations.map((an) => html`<li><b>${an.type}</b>${an.description ? ': ' + an.description : ''}</li>`)}</ul>` : ''}
      </header>
      <nav class="tabs" role="tablist">${TABS.filter(([id]) => id !== 'rca' || rcaOf(t) || RCA_ENTRIES.length || FAILING.includes(t.category)).map(([id, label]) => html`<button type="button" role="tab" class="tab${state.tab === id ? ' is-active' : ''}" data-action="tab" data-tab="${id}" aria-selected="${state.tab === id}">${label}${counts[id] ? html`<span class="tab-n${id === 'errors' || id === 'rca' ? ' tab-n-err' : ''}">${counts[id]}</span>` : ''}</button>`)}</nav>
      <div class="d-body">${body}</div>`);
    if (state.tab === 'source') {
      const target = $('.src-ln.is-err', drawerInner) || $('.src-ln.is-test', drawerInner);
      if (target) target.scrollIntoView({ block: 'center' });
    }
  }

  function openTest(id, attempt) {
    const t = byId.get(id);
    if (!t) return;
    const changed = state.selected !== id;
    state.selected = id;
    state.attempt = attempt != null && t.attempts[attempt] ? attempt : Math.max(t.attempts.length - 1, 0);
    const a = t.attempts[state.attempt];
    if (changed || attempt != null) state.tab = a && a.errors.length ? 'errors' : 'steps';
    appEl.classList.add('has-drawer');
    renderDetail();
    if (changed) drawerInner.scrollTop = 0;
    markSelected();
    syncHash();
  }
  function closeDetail() {
    state.selected = null;
    appEl.classList.remove('has-drawer');
    drawerInner.innerHTML = '';
    markSelected();
    syncHash();
  }
  function markSelected() {
    $$('.row[data-id], .tbl tr[data-id], .tl-bar[data-id]').forEach((el) => el.classList.toggle('is-selected', el.dataset.id === state.selected));
  }
  function moveSelection(dir) {
    const idx = state.order.indexOf(state.selected);
    const next = state.order[idx + dir];
    if (!next) return;
    openTest(next);
    const el = $('[data-action="open-test"][data-id="' + CSS.escape(next) + '"]', contentEl);
    if (el) el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  // ---------- chrome: nav, filters, banner ----------
  function renderNav() {
    const badge = {
      failures: TESTS.filter((t) => FAILING.includes(t.category)).length,
      rca: RCA_ENTRIES.length,
      tests: TESTS.length,
      categories: new Set(TESTS.map((t) => t.errorCategory).filter(Boolean)).size,
      tags: ALL_TAGS.length,
    };
    mount($('#nav'), VIEWS.map((v) => html`<a href="#${v.id}" class="nav-item${state.view === v.id ? ' is-active' : ''}" data-action="nav" data-view="${v.id}" title="${v.label}">${icon(v.icon)}<span class="nav-label">${v.label}</span>${badge[v.id] ? html`<span class="nav-badge${v.id === 'failures' || v.id === 'rca' ? ' nav-badge-bad' : ''}">${badge[v.id]}</span>` : ''}</a>`));
  }
  function renderFilters(s) {
    mount($('#filters'), CATS.map((c) => html`<button type="button" class="fchip${state.statuses.has(c) ? ' is-on' : ''}" data-action="toggle-status" data-status="${c}" aria-pressed="${state.statuses.has(c)}" data-tip="${LABEL[c]} · click to toggle, Shift+click for only this"><i class="dot bg-${c}"></i><span class="fchip-label">${LABEL[c]}</span><b>${s[c]}</b></button>`));
  }
  function renderBanner(shown) {
    const el = $('#banner');
    if (!filtersActive()) { el.hidden = true; el.innerHTML = ''; return; }
    el.hidden = false;
    const hidden = CATS.filter((c) => !state.statuses.has(c));
    mount(el, html`<span>Showing <b>${shown}</b> of ${TESTS.length} tests</span>
      ${state.search ? html`<button type="button" class="fpill" data-action="clear-filter" data-kind="search">“${state.search}”${icon('close')}</button>` : ''}
      ${state.tag ? html`<button type="button" class="fpill" data-action="clear-filter" data-kind="tag">${state.tag === '__untagged' ? 'untagged' : state.tag}${icon('close')}</button>` : ''}
      ${hidden.length ? html`<button type="button" class="fpill" data-action="clear-filter" data-kind="status">hiding ${hidden.map((c) => LABEL[c].toLowerCase()).join(', ')}${icon('close')}</button>` : ''}
      <span class="grow"></span><button type="button" class="link" data-action="clear-filters">Clear all</button>`);
  }

  function render() {
    const base = TESTS.filter(passesBase);
    const list = base.filter((t) => state.statuses.has(t.category) || !CATS.includes(t.category));
    state.order = (state.view === 'failures' ? failuresOf(list) : state.view === 'tests' ? testsOrder(list) : list).map((t) => t.id);
    renderNav();
    renderFilters(stats(base));
    renderBanner(list.length);
    const views = { dashboard: viewDashboard, failures: viewFailures, rca: viewRca, tests: viewTests, categories: viewCategories, tags: viewTags, analytics: viewAnalytics, timeline: viewTimeline };
    mount(contentEl, html`<div class="page">${(views[state.view] || viewDashboard)(list)}</div>`);
    hideTip();
  }

  // ---------- routing ----------
  let ignoreHash = false;
  function parseHash() {
    let h = '';
    try { h = decodeURIComponent(location.hash.slice(1)); } catch { /* malformed hash */ }
    const [view, id] = h.split('/');
    return { view: VIEWS.some((v) => v.id === view) ? view : 'dashboard', id: id && byId.has(id) ? id : null };
  }
  function syncHash() {
    const h = '#' + state.view + (state.selected ? '/' + state.selected : '');
    if (location.hash !== h) { ignoreHash = true; location.replace(h); }
  }
  function navigate(view) {
    state.view = VIEWS.some((v) => v.id === view) ? view : 'dashboard';
    render();
    contentEl.scrollTop = 0;
    syncHash();
  }
  window.addEventListener('hashchange', () => {
    if (ignoreHash) { ignoreHash = false; return; }
    const { view, id } = parseHash();
    state.view = view;
    render();
    if (id && id !== state.selected) openTest(id);
    else syncHash();
  });

  // ---------- actions ----------
  function setTag(tag) {
    state.tag = tag || '';
    tagSel.value = state.tag;
    render();
  }
  function clearFilters() {
    state.search = '';
    searchEl.value = '';
    state.statuses = new Set(CATS);
    setTag('');
  }
  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    mount($('#theme-btn'), icon(theme === 'dark' ? 'sun' : 'moon'));
    try { localStorage.setItem('pw-report:theme', JSON.stringify(theme)); } catch { /* storage unavailable */ }
  }
  const toggleTheme = () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');

  function toast(message) {
    const el = document.createElement('div');
    el.className = 'toast';
    mount(el, html`${icon('check')}${message}`);
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), 1800);
  }
  async function copy(text, message = 'Copied to clipboard') {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); } finally { ta.remove(); }
    }
    toast(message);
  }
  function copyText(el) {
    const d = el.dataset;
    if (d.kind && d.kind.startsWith('rca-')) {
      const r = rcaByCaseId(d.case) || rcaOf(byId.get(state.selected) || {});
      if (!r) return '';
      return d.kind === 'rca-curl' ? r.curl || '' : d.kind === 'rca-ticket' ? r.bugTicket || '' : rcaText(r);
    }
    const t = byId.get(d.id || state.selected);
    if (!t) return '';
    const a = d.id ? failingAttempt(t) : t.attempts[state.attempt];
    const i = Number(d.index || 0);
    switch (d.kind) {
      case 'rerun': return rerunCmd(t);
      case 'link': return location.href.split('#')[0] + '#tests/' + t.id;
      case 'error': return a && a.errors[i] ? a.errors[i].message : '';
      case 'stack': return a && a.errors[i] ? a.errors[i].stack || '' : '';
      case 'stdout': return a ? a.stdout : '';
      case 'stderr': return a ? a.stderr : '';
      case 'prompt': return a ? (a.prompt || '') + (rcaOf(t) ? '\n\n# Rule-based RCA (category is authoritative)\n\n' + rcaText(rcaOf(t)) : '') : '';
      case 'log': return a ? logText(a) : '';
      case 'attachment': return a && a.attachments[i] ? a.attachments[i].content || '' : '';
      case 'trace': return traceCmd(DATA.outputFolder + '/' + d.src);
      default: return '';
    }
  }
  function exportCsv() {
    const list = TESTS.filter((t) => passesBase(t) && state.statuses.has(t.category));
    // Prefix formula-like cells so spreadsheets do not execute them (CSV injection).
    const cell = (v) => { let s = String(v == null ? '' : v); if (/^[=+\-@\t\r]/.test(s)) s = "'" + s; return '"' + s.replace(/"/g, '""') + '"'; };
    const rows = [['Title', 'Path', 'Project', 'Browser', 'Location', 'Status', 'Category', 'Duration (ms)', 'Retries', 'Tags', 'Error']].concat(
      list.map((t) => { const e = primaryError(t); return [t.title, t.titlePath.join(' > '), t.project, t.browser, t.location, t.status, t.category, t.duration, t.retries, t.tags.join(' '), e ? firstLine(e.message) : '']; }));
    const blob = new Blob(['\ufeff' + rows.map((r) => r.map(cell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = (DATA.title || 'report').replace(/[^\w-]+/g, '-').toLowerCase() + '.csv';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    toast('Exported ' + plural(list.length, 'test'));
  }

  // ---------- lightbox ----------
  const lb = { items: [], index: 0 };
  function openLightbox(el) {
    const scope = el.closest('[data-gallery]') || el;
    const imgs = $$('img', scope);
    const current = $('img', el);
    lb.items = imgs.map((i) => ({ src: i.getAttribute('src'), alt: i.getAttribute('alt') || '' }));
    lb.index = Math.max(0, imgs.indexOf(current));
    showLightbox();
  }
  function showLightbox() {
    const it = lb.items[lb.index];
    if (!it) return;
    lbImg.src = it.src;
    lbImg.alt = it.alt;
    $('#lb-caption').textContent = it.alt + (lb.items.length > 1 ? '  ·  ' + (lb.index + 1) + ' / ' + lb.items.length : '');
    $('#lb-prev').hidden = $('#lb-next').hidden = lb.items.length < 2;
    lightboxEl.hidden = false;
  }
  function stepLightbox(d) {
    if (lb.items.length < 2) return;
    lb.index = (lb.index + d + lb.items.length) % lb.items.length;
    showLightbox();
  }
  function closeLightbox() {
    lightboxEl.hidden = true;
    lbImg.removeAttribute('src');
  }

  // ---------- tooltip ----------
  function hideTip() { tipEl.hidden = true; }
  document.addEventListener('mouseover', (e) => {
    const el = e.target.closest ? e.target.closest('[data-tip]') : null;
    const text = el && el.getAttribute('data-tip');
    if (!text) { hideTip(); return; }
    tipEl.textContent = text;
    tipEl.hidden = false;
    const r = el.getBoundingClientRect();
    const tr = tipEl.getBoundingClientRect();
    const x = clamp(r.left + r.width / 2 - tr.width / 2, 8, window.innerWidth - tr.width - 8);
    let y = r.top - tr.height - 8;
    if (y < 8) y = r.bottom + 8;
    tipEl.style.transform = 'translate(' + x + 'px,' + y + 'px)';
  });
  document.addEventListener('scroll', hideTip, true);

  // ---------- events ----------
  document.addEventListener('click', (e) => {
    const target = e.target;
    if (!target.closest) return;
    if (appEl.classList.contains('sb-open') && !target.closest('.sidebar, .menu-btn')) appEl.classList.remove('sb-open');
    const el = target.closest('[data-action]');
    if (!el || el.disabled) return;
    if (el.tagName === 'BUTTON' && el.closest('summary')) e.preventDefault();
    const d = el.dataset;
    hideTip();
    switch (d.action) {
      case 'nav': e.preventDefault(); appEl.classList.remove('sb-open'); navigate(d.view); break;
      case 'goto': navigate(d.view); break;
      case 'open-test':
        openTest(d.id, d.attempt != null ? Number(d.attempt) : undefined);
        if (d.tab && TABS.some(([id]) => id === d.tab) && state.selected === d.id) { state.tab = d.tab; renderDetail(); }
        break;
      case 'close-detail': closeDetail(); break;
      case 'nav-test': moveSelection(Number(d.dir)); break;
      case 'tab': state.tab = TABS.some(([id]) => id === d.tab) ? d.tab : 'steps'; renderDetail(); break;
      case 'select-attempt': state.attempt = Number(d.attempt) || 0; renderDetail(); break;
      case 'toggle-status':
        if (e.shiftKey) state.statuses = new Set([d.status]);
        else if (state.statuses.has(d.status)) state.statuses.delete(d.status);
        else state.statuses.add(d.status);
        render();
        break;
      case 'only-status': state.statuses = new Set([d.status]); navigate(state.view === 'dashboard' ? 'tests' : state.view); break;
      case 'show-all': state.statuses = new Set(CATS); navigate('tests'); break;
      case 'filter-tag': e.preventDefault(); setTag(state.tag === d.tag ? '' : d.tag); break;
      case 'tag-card': setTag(d.tag); navigate('tests'); break;
      case 'clear-filters': clearFilters(); break;
      case 'clear-filter':
        if (d.kind === 'search') { state.search = ''; searchEl.value = ''; render(); }
        else if (d.kind === 'tag') setTag('');
        else { state.statuses = new Set(CATS); render(); }
        break;
      case 'scroll-to': {
        const target = $$('details[data-key]', contentEl).find((x) => x.dataset.key === d.key);
        if (target) { target.open = true; target.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
        break;
      }
      case 'expand-all':
        state.closed.clear();
        $$('details', drawerInner).forEach((x) => { x.open = true; });
        render();
        break;
      case 'collapse-all':
        $$('details[data-key]', contentEl).forEach((x) => state.closed.add(x.dataset.key));
        $$('details.st', drawerInner).forEach((x) => { x.open = false; });
        render();
        break;
      case 'steps-expand': $$('details.st', drawerInner).forEach((x) => { x.open = d.open === '1'; }); break;
      case 'source-file': state.sourceFile = d.file; renderDetail(); break;
      case 'toggle-hooks': state.showHooks = el.checked; store.set('showHooks', state.showHooks); renderDetail(); break;
      case 'tests-mode': state.testsMode = TEST_MODES.some(([m]) => m === d.mode) ? d.mode : 'suite'; store.set('testsMode', state.testsMode); render(); break;
      case 'goto-group': {
        state.testsMode = 'suite';
        state.closed.delete(d.key);
        navigate('tests');
        const target = $$('details[data-key]', contentEl).find((x) => x.dataset.key === d.key);
        if (target) target.scrollIntoView({ block: 'start', behavior: 'smooth' });
        break;
      }
      case 'sort':
        state.sort = state.sort.key === d.key ? { key: d.key, dir: -state.sort.dir } : { key: d.key, dir: d.key === 'duration' || d.key === 'retries' ? -1 : 1 };
        render();
        break;
      case 'copy': copy(copyText(el), d.kind === 'prompt' ? 'Prompt copied — paste it into your AI assistant' : undefined); break;
      case 'cmp-mode': el.closest('.cmp').dataset.mode = d.mode; break;
      case 'lightbox': openLightbox(el); break;
      case 'lb-prev': stepLightbox(-1); break;
      case 'lb-next': stepLightbox(1); break;
      case 'close-lightbox': closeLightbox(); break;
      case 'toggle-theme': toggleTheme(); break;
      case 'toggle-sidebar': appEl.classList.toggle('sb-open'); break;
      case 'toggle-collapse': store.set('collapsed', appEl.classList.toggle('sb-collapsed')); break;
      case 'export-csv': exportCsv(); break;
      default: break;
    }
  });

  // <details> toggle does not bubble, so listen in the capture phase.
  document.addEventListener('toggle', (e) => {
    const key = e.target.dataset && e.target.dataset.key;
    if (!key) return;
    if (e.target.open) state.closed.delete(key);
    else state.closed.add(key);
  }, true);

  let searchTimer = 0;
  document.addEventListener('input', (e) => {
    const el = e.target;
    if (el === searchEl) {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => { state.search = searchEl.value.trim().toLowerCase(); render(); }, 120);
    } else if (el.dataset.action === 'cmp-slide') {
      el.closest('.cmp').style.setProperty('--pos', el.value + '%');
    } else if (el.dataset.action === 'tl-zoom') {
      state.zoom = Number(el.value);
      const tl = $('.timeline', contentEl);
      if (tl) tl.style.setProperty('--zoom', String(state.zoom));
    }
  });
  tagSel.addEventListener('change', () => setTag(tagSel.value));

  document.addEventListener('keydown', (e) => {
    const t = e.target;
    const typing = t.matches && t.matches('input, select, textarea');
    if (!lightboxEl.hidden) {
      if (e.key === 'Escape') closeLightbox();
      else if (e.key === 'ArrowRight') stepLightbox(1);
      else if (e.key === 'ArrowLeft') stepLightbox(-1);
      return;
    }
    if (e.key === 'Escape') {
      if (typing) t.blur();
      else if (state.selected) closeDetail();
      return;
    }
    if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
    if ((e.key === 'Enter' || e.key === ' ') && t.dataset && t.dataset.action === 'open-test') { e.preventDefault(); openTest(t.dataset.id); }
    else if (e.key === '/') { e.preventDefault(); searchEl.focus(); searchEl.select(); }
    else if (e.key === 'j' && state.selected) moveSelection(1);
    else if (e.key === 'k' && state.selected) moveSelection(-1);
    else if (e.key === 't') toggleTheme();
    else if (/^[1-9]$/.test(e.key) && VIEWS[Number(e.key) - 1]) navigate(VIEWS[Number(e.key) - 1].id);
  });

  const resizer = $('#drawer-resize');
  resizer.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    resizer.setPointerCapture(e.pointerId);
    appEl.classList.add('is-resizing');
    const move = (ev) => {
      const w = clamp(window.innerWidth - ev.clientX, 380, Math.max(380, window.innerWidth - 520));
      appEl.style.setProperty('--drawer-w', w + 'px');
    };
    const up = () => {
      resizer.removeEventListener('pointermove', move);
      resizer.removeEventListener('pointerup', up);
      appEl.classList.remove('is-resizing');
      store.set('drawerWidth', parseInt(appEl.style.getPropertyValue('--drawer-w'), 10) || null);
    };
    resizer.addEventListener('pointermove', move);
    resizer.addEventListener('pointerup', up);
  });

  // ---------- init ----------
  $$('[data-icon]').forEach((el) => el.insertAdjacentHTML('afterbegin', icon(el.dataset.icon).value));
  setTheme(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
  if (store.get('collapsed', false)) appEl.classList.add('sb-collapsed');
  const savedWidth = store.get('drawerWidth', null);
  if (typeof savedWidth === 'number') appEl.style.setProperty('--drawer-w', savedWidth + 'px');
  mount(tagSel, html`<option value="">All tags</option>${ALL_TAGS.map((tag) => html`<option value="${tag}">${tag}</option>`)}<option value="__untagged">Untagged</option>`);
  $('#brand-title').textContent = DATA.title || 'Test report';
  $('#brand-sub').textContent = fmtDate(DATA.startTime);
  const overall = passRate(DATA.stats || stats(TESTS));
  mount($('#sb-foot'), html`<div class="sb-run">${ring(overall, { size: 38, stroke: 5, text: false })}<div class="sb-foot-text"><b>${overall}% passed</b>${plural(TESTS.length, 'test')} · ${fmtDur(DATA.duration)}</div></div>
    <div class="sb-foot-text sb-keys"><kbd>/</kbd>search <kbd>j</kbd><kbd>k</kbd>next <kbd>1-8</kbd>views <kbd>t</kbd>theme</div>
    <button type="button" class="sb-collapse" data-action="toggle-collapse" data-tip="Collapse sidebar">${icon('sidebar')}</button>`);

  const initial = parseHash();
  state.view = initial.view;
  render();
  if (initial.id) openTest(initial.id);
  else syncHash();
})();
