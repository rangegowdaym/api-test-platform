/* Approval UI: vanilla JS, no build step. Talks to /api (ApprovalController, PipelineController). */
(() => {
  const $ = (id) => document.getElementById(id);
  const state = { cases: [], selected: new Set(), current: null, index: -1 };

  async function api(path, opts = {}) {
    const res = await fetch('/api' + path, {
      headers: { 'X-Requested-With': 'fetch', ...(opts.body && !(opts.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) },
      credentials: 'same-origin',
      ...opts,
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error((data && data.error) || res.status + ' ' + res.statusText);
    return data;
  }

  function toast(msg, bad) {
    const t = $('toast');
    t.textContent = msg;
    t.className = 'toast' + (bad ? ' bad' : '');
    clearTimeout(t._h);
    t._h = setTimeout(() => (t.className = 'toast hidden'), 3500);
  }

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const badge = (text, cls) => `<span class="badge ${cls || ''}">${esc(text)}</span>`;
  const statusClass = (s) => ({ APPROVED: 'ok', REJECTED: 'bad', NEEDS_CHANGES: 'warn', OBSOLETE: 'muted', PENDING_REVIEW: 'info' }[s] || '');

  async function loadSummary() {
    const s = await api('/summary');
    const by = s.byStatus || {};
    $('counters').innerHTML =
      badge(`Pending ${by.PENDING_REVIEW || 0}`, 'info') + badge(`Approved ${by.APPROVED || 0}`, 'ok') +
      badge(`Needs changes ${by.NEEDS_CHANGES || 0}`, 'warn') + badge(`Rejected ${by.REJECTED || 0}`, 'bad') +
      badge(`Changed since last spec ${s.changed || 0}`, '');
    const tagSel = $('f-tag');
    const current = tagSel.value;
    tagSel.innerHTML = '<option value="">All</option>' + (s.tags || []).map((t) => `<option>${esc(t)}</option>`).join('');
    tagSel.value = current;
  }

  async function loadList() {
    const p = new URLSearchParams();
    for (const k of ['q', 'status', 'tag', 'category', 'priority']) {
      const v = $('f-' + k).value;
      if (v) p.set(k, v);
    }
    state.cases = await api('/testcases?' + p.toString());
    state.selected.clear();
    renderRows();
  }

  function renderRows() {
    $('rows').innerHTML = state.cases.map((c, i) => `
      <tr data-i="${i}" class="${state.current && state.current.id === c.id ? 'active' : ''}">
        <td><input type="checkbox" class="row-check" data-id="${esc(c.id)}" ${state.selected.has(c.id) ? 'checked' : ''}></td>
        <td><div class="title">${esc(c.title)}</div><div class="muted mono">${esc(c.id)}${c.change ? ' ' + badge(c.change, c.change === 'CHANGED' ? 'warn' : '') : ''}</div></td>
        <td class="mono">${esc(c.source.method)} ${esc(c.source.path)}</td>
        <td>${esc(c.category)}</td>
        <td>${esc(c.priority)}</td>
        <td>${esc(c.expected.status)}</td>
        <td>${badge(c.status.replace('_', ' '), statusClass(c.status))}</td>
      </tr>`).join('');
    $('list-count').textContent = `${state.cases.length} test cases`;
    updateBulk();
  }

  function updateBulk() {
    const n = state.selected.size;
    $('bulk-approve').disabled = $('bulk-reject').disabled = n === 0;
    $('bulk-approve').textContent = n ? `Approve ${n}` : 'Approve selected';
    $('bulk-reject').textContent = n ? `Reject ${n}` : 'Reject selected';
  }

  // same as StepGenerator.stepText: the step generated into <Tag>Steps
  const stepTag = (t) => String(t || 'default').trim().replace(/[^A-Za-z0-9_.-]+/g, '-');

  function gherkin(c) {
    const auth = c.request.auth;
    const authLine = !auth || auth === 'none' ? 'And I send no credentials'
      : auth === 'invalid' ? 'And I use invalid credentials' : `And I am authenticated as "${auth}"`;
    const lines = [
      `@${c.id} ${c.tags.map((t) => '@' + t).join(' ')}`,
      `Scenario: ${c.title}`,
      `  Given test case "${c.id}"`,
      `  ${authLine}`,
      `  # ${c.source.method} ${c.source.path}`,
      `  When I call the ${stepTag(c.tags[0])} operation "${c.source.operationId}"`,
      `  Then the response status should be ${c.expected.status}`,
    ];
    if (c.expected.schema) lines.push('  And the response should match the documented schema');
    if (c.expected.assertions && c.expected.assertions.length) lines.push('  And the response should meet the test case assertions');
    if (c.expected.maxResponseTimeMs) lines.push(`  And the response time should be under ${c.expected.maxResponseTimeMs} ms`);
    return lines.join('\n');
  }

  async function open(i) {
    if (i < 0 || i >= state.cases.length) return;
    state.index = i;
    const c = await api('/testcases/' + encodeURIComponent(state.cases[i].id));
    state.current = c;
    $('detail').classList.remove('hidden');
    $('d-id').textContent = c.id + '  ·  v' + c.version;
    $('d-title').textContent = c.title;
    $('d-badges').innerHTML = badge(c.status, statusClass(c.status)) + badge(c.category) + badge(c.priority) +
      c.tags.map((t) => badge('@' + t, 'muted')).join('') + (c.change ? badge(c.change, 'warn') : '');
    $('d-notes').innerHTML = (c.notes || []).map((n) => `<div class="note">${esc(n)}</div>`).join('') +
      (c.review ? `<div class="muted">Last review: ${esc(c.review.action)} by ${esc(c.review.reviewer)} ${c.review.comment ? '– ' + esc(c.review.comment) : ''}</div>` : '');
    $('d-request').textContent = JSON.stringify({ method: c.source.method, path: c.source.path, ...c.request }, null, 2);
    $('d-expected').textContent = JSON.stringify(c.expected, null, 2);
    $('d-gherkin').textContent = gherkin(c);
    $('d-json').value = JSON.stringify(c, null, 2);
    $('d-json-error').textContent = '';
    $('d-comment').value = '';
    const h = await api('/testcases/' + encodeURIComponent(c.id) + '/history');
    $('d-history').innerHTML = h.length ? h.map((e) => `<li><b>${esc(e.action)}</b> by ${esc(e.actor)} · v${e.caseVersion} · ${new Date(e.at).toLocaleString()}${e.comment ? '<div class="muted">' + esc(e.comment) + '</div>' : ''}</li>`).join('') : '<li class="muted">No review actions yet.</li>';
    renderRows();
  }

  async function act(action) {
    const c = state.current;
    if (!c) return;
    const comment = $('d-comment').value.trim();
    if (action === 'reject' && !comment) { toast('Add a comment to reject', true); $('d-comment').focus(); return; }
    try {
      await api(`/testcases/${encodeURIComponent(c.id)}/${action}`, { method: 'POST', body: JSON.stringify({ comment }) });
      toast(`${c.id}: ${action.replace('-', ' ')}d`);
      const next = state.index;
      await Promise.all([loadSummary(), loadList()]);
      if (state.cases.length) open(Math.min(next, state.cases.length - 1)); else close();
    } catch (e) { toast(e.message, true); }
  }

  async function bulk(action) {
    const ids = [...state.selected];
    let comment = '';
    if (action === 'REJECT') {
      comment = prompt(`Reason for rejecting ${ids.length} test cases:`) || '';
      if (!comment.trim()) return;
    } else if (!confirm(`Approve ${ids.length} test cases?`)) return;
    try {
      const r = await api('/testcases/bulk', { method: 'POST', body: JSON.stringify({ ids, action, comment }) });
      toast(`${r.updated} updated${r.failed.length ? ', ' + r.failed.length + ' failed' : ''}`, r.failed.length > 0);
      await Promise.all([loadSummary(), loadList()]);
    } catch (e) { toast(e.message, true); }
  }

  function close() {
    state.current = null;
    $('detail').classList.add('hidden');
    renderRows();
  }

  async function saveEdit() {
    let body;
    try { body = JSON.parse($('d-json').value); } catch (e) { $('d-json-error').textContent = 'Not valid JSON: ' + e.message; return; }
    try {
      await api('/testcases/' + encodeURIComponent(state.current.id), {
        method: 'PUT', body: JSON.stringify({ testCase: body, comment: $('d-edit-comment').value }),
      });
      toast('Saved. The case is back in review.');
      await Promise.all([loadSummary(), loadList()]);
      const i = state.cases.findIndex((c) => c.id === body.id);
      if (i >= 0) open(i);
    } catch (e) { $('d-json-error').textContent = e.message; }
  }

  async function doImport(ev) {
    ev.preventDefault();
    const file = $('import-file').files[0];
    const url = $('import-url').value.trim();
    const fd = new FormData();
    let path = '/imports';
    if (file) fd.append('file', file);
    else if (url) path += '?url=' + encodeURIComponent(url);
    else { $('import-result').textContent = 'Choose a file or enter a URL.'; return; }
    $('import-result').textContent = 'Importing…';
    try {
      const r = await api(path, { method: 'POST', body: fd });
      $('import-result').textContent = `${r.spec}: ${r.endpoints} endpoints · new ${r.added} · changed ${r.changed} · removed ${r.removed} · unchanged ${r.unchanged}`;
      await Promise.all([loadSummary(), loadList()]);
    } catch (e) { $('import-result').textContent = e.message; }
  }

  // ---- pipeline: 1 features -> 2 services & steps -> 3 execute -> 4 report ----
  const run = { options: null, from: 0, timer: 0, id: null, featuresText: '' };
  function stepState(id, cls, html) {
    const el = $('ps-' + id);
    el.classList.remove('done', 'warn', 'fail', 'busy');
    if (cls) el.classList.add(cls);
    if (html != null) $('st-' + id).innerHTML = html;
  }
  async function busy(btn, fn) {
    btn.disabled = true;
    try { return await fn(); } finally { btn.disabled = false; }
  }

  async function features() {
    stepState('features', 'busy', 'Writing feature files&hellip;');
    try {
      const r = await busy($('btn-features'), () => api('/features/generate', { method: 'POST' }));
      const missing = r.missingSteps || [];
      run.featuresText = `<b>${r.scenarios}</b> approved scenarios in <b>${r.features}</b> feature files`;
      stepState('features', r.scenarios ? (missing.length ? 'warn' : 'done') : 'warn',
        run.featuresText + (missing.length ? `<br>${missing.length} operation(s) need step definitions: run step 2` : ''));
      if (missing.length) stepState('services', 'warn', `Needed for: ${missing.slice(0, 4).map(esc).join(', ')}${missing.length > 4 ? '&hellip;' : ''}`);
      toast(r.next || 'Features written', !r.scenarios);
    } catch (e) { stepState('features', 'fail', esc(e.message)); toast(e.message, true); }
  }

  async function services() {
    stepState('services', 'busy', 'Generating &lt;Tag&gt;Api and &lt;Tag&gt;Steps&hellip;');
    try {
      const r = await busy($('btn-services'), () => api('/services/generate', { method: 'POST' }));
      const ok = r.specs.filter((x) => x.status === 'OK');
      const bad = r.specs.filter((x) => x.status !== 'OK');
      const lines = ok.map((x) => `<b>${esc(x.spec)}</b>: ${x.apiClasses} Api, ${x.stepClasses} Steps (${x.steps} steps)`)
        .concat(bad.map((x) => `<span class="error">${esc(x.spec)}: ${esc(x.message)}</span>`));
      const missing = r.missingSteps || [];
      stepState('services', !r.specs.length || bad.length ? 'fail' : missing.length ? 'warn' : 'done',
        (lines.join('<br>') || 'No spec in specs/') + (missing.length ? `<br>Still missing: ${missing.map(esc).join(', ')}` : ''));
      if (ok.length && !missing.length && $('ps-features').classList.contains('warn') && run.featuresText) stepState('features', 'done', run.featuresText);
      toast(r.next, bad.length > 0 || !r.specs.length);
    } catch (e) { stepState('services', 'fail', esc(e.message)); toast(e.message, true); }
  }

  // ---- execute dialog ----
  async function openRun() {
    $('run-error').textContent = '';
    $('run-dialog').showModal();
    try {
      run.options = await api('/run/options');
      const envs = run.options.environments || [];
      const envSel = $('run-env');
      const prev = envSel.value || localStorage.getItem('run.env') || 'qa';
      envSel.innerHTML = envs.map((e) => `<option${e === prev ? ' selected' : ''}>${esc(e)}</option>`).join('');
      if (!envs.length) $('run-error').textContent = 'No config/env/*.properties found.';
      renderTags();
      $('run-llm-row').classList.toggle('hidden', !run.options.llmAvailable);
      $('run-llm-note').classList.toggle('hidden', !!run.options.llmAvailable);
      if (!run.options.llmAvailable) $('run-llm').checked = false;
      const cur = run.options.current;
      if (cur) { run.from = 0; $('run-log').textContent = ''; showRun(cur); if (cur.status === 'RUNNING') poll(); else pollOnce(); }
    } catch (e) { $('run-error').textContent = e.message; }
  }
  function renderTags() {
    const tags = (run.options && run.options.tags) || {};
    const f = $('run-tag-filter').value.trim().toLowerCase();
    const checked = new Set([...document.querySelectorAll('.run-tag:checked')].map((x) => x.value));
    const names = Object.keys(tags).filter((t) => !f || t.toLowerCase().includes(f))
      // test-case ids last: there are many of them and they are rarely what you filter by
      .sort((a, b) => (a.startsWith('@TC-') - b.startsWith('@TC-')) || a.localeCompare(b));
    $('run-tags').innerHTML = names.length
      ? names.map((t) => `<label class="tag-item"><input type="checkbox" class="run-tag" value="${esc(t)}"${checked.has(t) ? ' checked' : ''}><span class="mono">${esc(t)}</span><span class="n">${tags[t]}</span></label>`).join('')
      : `<div class="tag-empty">${Object.keys(tags).length ? 'No tag matches the filter.' : 'No feature files yet: run step 1.'}</div>`;
    $('run-tag-count').textContent = `${Object.keys(tags).length} tags · ${run.options ? run.options.features : 0} feature files`;
  }
  function buildExpr() {
    const picked = [...document.querySelectorAll('.run-tag:checked')].map((x) => x.value);
    const join = document.querySelector('input[name="run-join"]:checked').value;
    let expr = picked.length > 1 ? '(' + picked.join(' ' + join + ' ') + ')' : picked[0] || '';
    if ($('run-no-wip').checked) expr = expr ? expr + ' and not @wip' : 'not @wip';
    $('run-expr').value = expr;
  }
  async function startRun() {
    $('run-error').textContent = '';
    const body = { env: $('run-env').value, tags: $('run-expr').value.trim(), threads: Number($('run-threads').value) || 4, llm: $('run-llm').checked };
    localStorage.setItem('run.env', body.env);
    try {
      const r = await api('/runs', { method: 'POST', body: JSON.stringify(body) });
      run.from = 0;
      $('run-log').textContent = '';
      showRun(r);
      poll();
    } catch (e) { $('run-error').textContent = e.message; }
  }
  // GET /api/doctor: the same checks as "java -jar target/api-test-platform.jar doctor --env <env>"
  async function doctor() {
    const env = $('run-env').value;
    const log = $('run-log');
    log.innerHTML = `<span class="l-plat">[doctor] checking ${esc(env)}…</span>\n`;
    $('run-state').className = 'badge';
    $('run-state').textContent = 'checking';
    try {
      const r = await busy($('run-doctor'), () => api('/doctor?env=' + encodeURIComponent(env)));
      const cls = { OK: 'l-ok', WARN: 'l-plat', FAIL: 'l-err' };
      log.innerHTML = r.checks.map((c) => `<span class="${cls[c.level]}">${c.level.padEnd(4)}</span> ${esc(c.name.padEnd(28))} ${esc(c.detail || '')}` +
        (c.hint && c.level !== 'OK' ? `\n       <span class="l-plat">-&gt; ${esc(c.hint)}</span>` : '')).join('\n') + '\n';
      const ok = r.checks.every((c) => c.level !== 'FAIL');
      $('run-state').className = 'badge ' + (ok ? 'ok' : 'bad');
      $('run-state').textContent = ok ? 'environment ok' : 'environment not ready';
      $('run-summary').textContent = ok ? `Ready to run tests on ${env}.` : 'Fix the FAIL lines (config/env/' + env + '.properties or exported variables), then check again.';
    } catch (e) { log.textContent = e.message; $('run-state').textContent = 'check failed'; }
  }

  // ---- AI assist: server LLM when configured, else the commands for Copilot agent mode ----
  const AI_AGENT_CMD = (task, spec) => `java -jar target/api-test-platform.jar ai ${task}${spec && (task === 'spec-audit' || task === 'testcase-augment') ? ' --spec ' + spec : ''} --mode agent`;
  let aiOptions = null;
  async function openAi() {
    $('ai-dialog').showModal();
    try {
      aiOptions = await api('/ai/options');
      $('ai-spec').innerHTML = (aiOptions.specs || []).map((x) => `<option>${esc(x)}</option>`).join('') || '<option value="">(no spec in specs/)</option>';
      $('ai-status').innerHTML = aiOptions.llmAvailable
        ? `Runs on the server with <b>${esc(aiOptions.provider)}</b>. Masked data only; categories and approvals stay with the rules and with you.`
        : 'No LLM token on this server. Each button shows the command for <b>Copilot agent mode</b> (or Claude Code): the agent answers, then <span class="mono">--apply</span> validates it. See README "LLM".';
    } catch (e) { $('ai-status').textContent = e.message; }
  }
  async function runAi(task) {
    const spec = $('ai-spec').value;
    const out = $('ai-out');
    if (!aiOptions || !aiOptions.llmAvailable) {
      const cmd = AI_AGENT_CMD(task, spec);
      out.textContent = `In VS Code, Copilot Chat (Agent mode), ask:\n\n  Run "${cmd}", answer every request in target/ai/${task}/requests.md into its answer file,\n  then run "java -jar target/api-test-platform.jar ai ${task} --apply --by copilot-agent".\n\nOr use the prompt file:  /ai ${task}`;
      return;
    }
    out.textContent = `Running ${task} with ${aiOptions.provider}…`;
    document.querySelectorAll('[data-ai]').forEach((b) => (b.disabled = true));
    try {
      const r = await api('/ai/' + task, { method: 'POST', body: JSON.stringify({ spec, context: $('ai-context').value }) });
      out.textContent = `${r.applied} of ${r.requests} applied\n` + r.messages.join('\n') + (r.rejected.length ? '\nrejected:\n' + r.rejected.join('\n') : '');
      if (task === 'testcase-augment' && r.applied) { await loadSummary(); await loadList(); toast('Proposed cases added as Pending review'); }
    } catch (e) { out.textContent = e.message; }
    finally { document.querySelectorAll('[data-ai]').forEach((b) => (b.disabled = false)); }
  }

  async function cancelRun() {
    try { showRun(await api('/runs/cancel', { method: 'POST' })); } catch (e) { $('run-error').textContent = e.message; }
  }
  function poll() { clearTimeout(run.timer); run.timer = setTimeout(pollOnce, 700); }
  async function pollOnce() {
    try {
      const r = await api('/runs/current?from=' + run.from);
      if (!r.run) return;
      if (r.run.id !== run.id) { run.id = r.run.id; }
      appendLog(r.lines);
      run.from = r.from + r.lines.length;
      showRun(r.run);
      if (r.run.status === 'RUNNING') poll();
    } catch (e) { $('run-error').textContent = e.message; poll(); }
  }
  function appendLog(lines) {
    if (!lines.length) return;
    const log = $('run-log');
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    const html = lines.map((l) => {
      const cls = /^\[platform\]/.test(l) ? 'l-plat' : /\b(FAIL|ERROR|BUILD FAILURE|Exception)\b|✘/.test(l) ? 'l-err' : /\b(PASSED|BUILD SUCCESS)\b|✔/.test(l) ? 'l-ok' : '';
      return cls ? `<span class="${cls}">${esc(l)}</span>` : esc(l);
    }).join('\n') + '\n';
    log.insertAdjacentHTML('beforeend', html);
    if (atBottom) log.scrollTop = log.scrollHeight;
  }
  const RUN_BADGE = { RUNNING: 'run', PASSED: 'ok', FAILED: 'bad', ERROR: 'bad', CANCELLED: 'muted' };
  function showRun(r) {
    if (!r) return;
    const running = r.status === 'RUNNING';
    $('run-state').className = 'badge ' + (RUN_BADGE[r.status] || '');
    $('run-state').textContent = running ? 'running · ' + r.phase : r.status.toLowerCase();
    $('run-meta').textContent = `${r.env} · ${r.tags} · ${r.threads} threads · by ${r.startedBy}`;
    $('run-go').disabled = running;
    $('run-cancel').classList.toggle('hidden', !running);
    const done = !running && r.status !== 'CANCELLED' && r.summary && !/^Build failed/.test(r.summary);
    $('run-report').classList.toggle('hidden', !done);
    $('run-report').href = (r.reportUrl || '/reports/index.html') + (r.status === 'FAILED' ? '#rca' : '#dashboard');
    $('run-summary').textContent = r.summary || (running ? 'Running…' : '');
    stepState('run', running ? 'busy' : r.status === 'PASSED' ? 'done' : r.status === 'FAILED' ? 'warn' : r.status === 'CANCELLED' ? '' : 'fail',
      running ? `Running on <b>${esc(r.env)}</b> (${esc(r.phase)})&hellip;` : `<b>${esc(r.status.toLowerCase())}</b>: ${esc(r.summary || '')}`);
    if (done) {
      $('btn-report').href = $('run-report').href;
      stepState('report', r.status === 'PASSED' ? 'done' : 'warn', r.status === 'FAILED' ? 'Failures analysed: open the <b>RCA</b> view' : 'Report ready');
    }
  }

  // ---- wiring ----
  ['q', 'status', 'tag', 'category', 'priority'].forEach((k) => $('f-' + k).addEventListener(k === 'q' ? 'input' : 'change', debounce(loadList, 250)));
  $('rows').addEventListener('click', (e) => {
    if (e.target.classList.contains('row-check')) {
      const id = e.target.dataset.id;
      e.target.checked ? state.selected.add(id) : state.selected.delete(id);
      updateBulk();
      return;
    }
    const tr = e.target.closest('tr');
    if (tr) open(Number(tr.dataset.i));
  });
  $('check-all').addEventListener('change', (e) => {
    state.selected = new Set(e.target.checked ? state.cases.map((c) => c.id) : []);
    renderRows();
  });
  $('bulk-approve').onclick = () => bulk('APPROVE');
  $('bulk-reject').onclick = () => bulk('REJECT');
  $('d-approve').onclick = () => act('approve');
  $('d-reject').onclick = () => act('reject');
  $('d-changes').onclick = () => act('request-changes');
  $('d-close').onclick = close;
  $('d-save').onclick = saveEdit;
  $('btn-import').onclick = () => { $('import-result').textContent = ''; $('import-dialog').showModal(); };
  $('import-go').onclick = doImport;
  $('btn-features').onclick = features;
  $('btn-services').onclick = services;
  $('btn-run').onclick = openRun;
  $('run-go').onclick = startRun;
  $('run-cancel').onclick = cancelRun;
  $('run-doctor').onclick = doctor;
  $('btn-ai').onclick = openAi;
  $('ai-close').onclick = () => $('ai-dialog').close();
  document.querySelectorAll('[data-ai]').forEach((b) => (b.onclick = () => runAi(b.dataset.ai)));
  $('run-close').onclick = () => { $('run-dialog').close(); };
  $('run-dialog').addEventListener('close', () => clearTimeout(run.timer));
  $('run-tags').addEventListener('change', buildExpr);
  $('run-no-wip').addEventListener('change', buildExpr);
  document.querySelectorAll('input[name="run-join"]').forEach((x) => x.addEventListener('change', buildExpr));
  $('run-tag-filter').addEventListener('input', renderTags);
  document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === b));
    document.querySelectorAll('.tab-body').forEach((x) => x.classList.toggle('hidden', x.id !== 'tab-' + b.dataset.tab));
  }));
  document.addEventListener('keydown', (e) => {
    if (document.querySelector('dialog[open]')) return;
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) {
      if (e.key === 'Escape') document.activeElement.blur();
      return;
    }
    const k = e.key.toLowerCase();
    if (k === 'j') open(Math.min(state.index + 1, state.cases.length - 1));
    else if (k === 'k') open(Math.max(state.index - 1, 0));
    else if (k === 'a') act('approve');
    else if (k === 'r') act('reject');
    else if (k === 'c') act('request-changes');
    else if (k === 'escape') close();
  });

  function debounce(fn, ms) { let t; return () => { clearTimeout(t); t = setTimeout(fn, ms); }; }

  api('/me').then((m) => ($('user').textContent = m.user)).catch(() => {});
  api('/run/options').then((o) => { if (o.current) { showRun(o.current); if (o.current.status === 'RUNNING') { run.from = o.current.lines; poll(); } } }).catch(() => {});
  // deep link from the report: /#TC-createOrder-positive-1 opens that case
  const deepLink = decodeURIComponent(location.hash.replace(/^#/, ''));
  if (deepLink.startsWith('TC-')) { $('f-q').value = deepLink; $('f-status').value = ''; }
  loadSummary().then(loadList).then(() => { if (deepLink.startsWith('TC-') && state.cases.length) open(0); })
    .catch((e) => toast(e.message, true));
})();
