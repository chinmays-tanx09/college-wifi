// College WiFi desktop UI. Talks only to the app (no network calls from here).
const T = window.__TAURI__;
const invoke = (c, a) => T.core.invoke(c, a);
const $ = (id) => document.getElementById(id);
const CIRC = 2 * Math.PI * 110;
const WIDE = 760;
let S = null;

function applyTheme(t) { document.documentElement.dataset.theme = t || 'system'; }
function fmtTime(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }).toLowerCase().replace(/\s/g, '\u202f');
}
function show(view) {
  if (view === 'account') view = 'accounts';
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + view));
  document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  if (window.innerWidth < WIDE) setSidebar(false);
}
function setSidebar(open) { $('app').dataset.sidebar = open ? 'open' : 'closed'; }
function note(id, text, err) { const n = $(id); n.textContent = text || ''; n.classList.toggle('err', !!err); }

function render(s) {
  S = s;
  applyTheme(s.theme);
  const hero = document.querySelector('.hero');
  hero.dataset.state = s.busy && s.state !== 'busy' ? 'busy' : s.state;
  $('stateTitle').textContent = s.title || '';
  const net = [s.ssid, s.on_campus ? (s.network || 'campus portal') : null].filter(Boolean).join(' · ');
  $('netline').textContent = net || '\u00a0';
  $('detail').textContent = s.usage_note && s.state === 'connected' ? s.usage_note : (s.detail || '\u00a0');
  const u = s.usage;
  const today = (x) => (x && x.period !== 'daily' ? 'this cycle' : 'today');
  if (u) {
    $('leftBig').textContent = u.has_cap ? u.left : '—';
    $('leftLabel').textContent = `left ${today(u)}`;
    $('usedBig').textContent = u.used;
    $('usedLabel').textContent = `used ${today(u)}`;
    $('updatedBig').textContent = fmtTime(u.updated_at);
    $('ringArc').style.strokeDashoffset = String(CIRC * (1 - (u.has_cap ? u.fraction : 0)));
    $('barFill').style.width = `${Math.round((u.has_cap ? u.fraction : 0) * 100)}%`;
    $('sLeft').textContent = u.has_cap ? u.left : '—';
    $('sUsed').textContent = u.used;
    $('sCap').textContent = u.cap;
    $('usagePeriod').textContent = `${u.period === 'daily' ? 'Today' : 'This cycle'} · from the campus user portal`;
    $('usageUpdated').textContent = `Updated ${fmtTime(u.updated_at)}`;
  } else {
    $('leftBig').textContent = '—';
    $('usedBig').textContent = '—';
    $('updatedBig').textContent = s.checked_at ? fmtTime(s.checked_at) : '—';
    $('ringArc').style.strokeDashoffset = String(CIRC);
  }
  note('usageNote', s.usage_note || '', !!s.usage_note);
  $('power').title = s.state === 'connected' ? 'Log out' : 'Log in';
  $('power').disabled = !!s.busy;
  $('refreshBtn').classList.toggle('spin', !!s.busy);
  // sidebar footer + accounts
  const accs = s.accounts || [];
  const act = accs.find((a) => a.active);
  $('whoName').textContent = act ? act.user : 'No login saved';
  $('whoSub').textContent = act
    ? (accs.length > 1 ? `Account ${act.index} of ${accs.length}` : (act.has_password ? 'Password in ' + shortKc(s) : 'Password missing'))
    : 'Accounts → add your login';
  $('navCount').textContent = accs.length ? String(accs.length) : '';
  $('kcName').textContent = s.keychain;
  renderAccounts(s);
  $('logFile').checked = !!s.log_file;
  $('logPath').textContent = s.log_file ? 'Writing to ' + s.log_path : 'Keeps the connection log on disk too (no passwords)';
  $('autoLogin').checked = s.auto_login;
  $('startAtLogin').checked = s.start_at_login;
  $('readUsage').checked = s.read_usage;
  document.querySelectorAll('#theme button').forEach((b) => b.classList.toggle('on', b.dataset.v === s.theme));
  if (document.activeElement !== $('portal')) $('portal').value = s.portal || '';
  if (document.activeElement !== $('ssids')) $('ssids').value = (s.ssids || []).join('\n');
  $('ver').textContent = `Version ${s.version}`;
}
const ICON = {
  up: '<svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg>',
  down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
  edit: '<svg viewBox="0 0 24 24"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/></svg>',
  del: '<svg viewBox="0 0 24 24"><path d="M4 7h16"/><path d="M10 11v6M14 11v6"/><path d="M6 7l1 13h10l1-13"/><path d="M9 7V4h6v3"/></svg>',
};
let editing = null; // null = form closed, '' = adding, id = editing
let confirmDel = null;
function esc(t) { return String(t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function renderAccounts(s) {
  const accs = s.accounts || [];
  const connected = s.state === 'connected';
  $('accEmpty').hidden = accs.length > 0;
  $('accList').innerHTML = accs.map((a, i) => {
    const bad = a.state === 'WRONG_PASSWORD' || a.state === 'EXPIRED';
    const pills = [a.primary ? '<span class="pill">Primary</span>' : '', a.active && connected ? '<span class="pill on">Signed in</span>' : ''].join('');
    let sub = a.chip && a.chip !== 'Active' ? a.chip : (a.primary ? 'Tried first' : `Fallback ${i}`);
    if (!a.has_password) sub = 'Password missing · edit to enter it';
    if (a.message && a.state) sub += ' · Portal said: \u201c' + a.message + '\u201d';
    const warn = bad || !a.has_password || (a.state && a.chip && a.chip !== 'Active');
    const useBtn = a.active && connected ? '' : `<button class="use" data-act="use" data-id="${esc(a.id)}" ${s.busy ? 'disabled' : ''}>Use now</button>`;
    const delBtn = confirmDel === a.id
      ? `<button class="icon-btn confirm" data-act="del2" data-id="${esc(a.id)}">Remove?</button>`
      : `<button class="icon-btn" data-act="del" data-id="${esc(a.id)}" title="Remove">${ICON.del}</button>`;
    return `<div class="acc${a.active ? ' active' : ''}">
      <div class="acc-n">${a.index}</div>
      <div class="acc-main"><div class="acc-user">${esc(a.user)}${pills}</div><div class="acc-sub${warn ? ' warn' : ''}" title="${esc(sub)}">${esc(sub)}</div></div>
      <div class="acc-actions">${useBtn}
        <button class="icon-btn" data-act="up" data-id="${esc(a.id)}" title="Move up" ${i === 0 ? 'disabled' : ''}>${ICON.up}</button>
        <button class="icon-btn" data-act="down" data-id="${esc(a.id)}" title="Move down" ${i === accs.length - 1 ? 'disabled' : ''}>${ICON.down}</button>
        <button class="icon-btn" data-act="edit" data-id="${esc(a.id)}" title="Edit">${ICON.edit}</button>
        ${delBtn}
      </div></div>`;
  }).join('');
}
function openForm(id) {
  editing = id;
  const a = (S && S.accounts || []).find((x) => x.id === id);
  $('accFormTitle').textContent = a ? `Edit ${a.user}` : 'Add account';
  $('user').value = a ? a.user : '';
  $('pass').value = '';
  $('pass').placeholder = a ? 'Leave empty to keep the saved password' : '••••••••';
  $('accForm').hidden = false;
  $('addAcc').hidden = true;
  note('accountNote', '');
  (a ? $('pass') : $('user')).focus();
}
function closeForm() { editing = null; $('accForm').hidden = true; $('addAcc').hidden = false; $('pass').value = ''; }
async function accountAction(act, id) {
  try {
    if (act === 'use') { await invoke('use_account', { id }); note('accountNote', 'Switching…'); show('home'); }
    else if (act === 'up') await invoke('move_account', { id, by: -1 });
    else if (act === 'down') await invoke('move_account', { id, by: 1 });
    else if (act === 'edit') openForm(id);
    else if (act === 'del') { confirmDel = id; renderAccounts(S); setTimeout(() => { if (confirmDel === id) { confirmDel = null; renderAccounts(S); } }, 4000); }
    else if (act === 'del2') { confirmDel = null; await invoke('remove_account', { id }); if (editing === id) closeForm(); note('accountNote', 'Removed, and its password deleted from this computer.'); }
  } catch (e) { note('accountNote', String(e), true); }
}
async function loadLog() { try { $('logText').value = await invoke('get_log'); $('logText').scrollTop = $('logText').scrollHeight; } catch (e) { note('logNote', String(e), true); } }
function shortKc(s) { return s.os === 'windows' ? 'Credential Manager' : s.os === 'macos' ? 'Keychain' : 'keyring'; }

async function setting(patch) {
  try { await invoke('set_settings', { patch }); } catch (e) { note('advNote', String(e), true); }
}

let wasWide = window.innerWidth >= WIDE;
window.addEventListener('resize', () => {
  const wide = window.innerWidth >= WIDE;
  if (wide !== wasWide) setSidebar(wide); // docked on wide windows, tucked away on narrow ones
  wasWide = wide;
});

window.addEventListener('DOMContentLoaded', async () => {
  setSidebar(window.innerWidth >= WIDE);
  $('sbToggle').onclick = () => setSidebar(true);
  $('sbClose').onclick = () => setSidebar(false);
  $('scrim').onclick = () => setSidebar(false);
  document.querySelectorAll('.nav-item').forEach((b) => (b.onclick = () => show(b.dataset.view)));
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') { e.preventDefault(); setSidebar($('app').dataset.sidebar !== 'open'); }
    if (e.key === 'Escape') setSidebar(false);
  });
  $('power').onclick = () => invoke('toggle');
  $('refreshBtn').onclick = () => invoke('refresh');
  $('usageRefresh').onclick = () => invoke('refresh');
  $('addAcc').onclick = () => openForm('');
  $('cancelAcc').onclick = () => { closeForm(); note('accountNote', ''); };
  $('saveAcc').onclick = async () => {
    const wasNew = !editing;
    try {
      await invoke('save_account', { id: editing || null, username: $('user').value, password: $('pass').value });
      closeForm();
      note('accountNote', wasNew ? 'Saved. It signs in by itself when you are on the college Wi-Fi.' : 'Saved.');
    } catch (e) { note('accountNote', String(e), true); }
  };
  $('pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('saveAcc').click(); });
  $('accList').onclick = (e) => { const b = e.target.closest('button[data-act]'); if (b && !b.disabled) accountAction(b.dataset.act, b.dataset.id); };
  $('sbFoot').onclick = () => show('accounts');
  $('sbFoot').onkeydown = (e) => { if (e.key === 'Enter') show('accounts'); };
  $('logFile').onchange = (e) => setting({ log_file: e.target.checked });
  $('logBox').addEventListener('toggle', () => { if ($('logBox').open) loadLog(); });
  $('reloadLog').onclick = loadLog;
  $('copyLog').onclick = async () => {
    await loadLog();
    const t = $('logText');
    let ok = false;
    try { await navigator.clipboard.writeText(t.value); ok = true; } catch (_) {
      t.focus(); t.select(); try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
    }
    note('logNote', ok ? 'Copied. Paste it in a message to whoever is helping you.' : 'Select the text and copy it with Ctrl+C.', !ok);
  };
  $('autoLogin').onchange = (e) => setting({ auto_login: e.target.checked });
  $('startAtLogin').onchange = (e) => setting({ start_at_login: e.target.checked });
  $('readUsage').onchange = (e) => setting({ read_usage: e.target.checked });
  document.querySelectorAll('#theme button').forEach((b) => (b.onclick = () => { applyTheme(b.dataset.v); setting({ theme: b.dataset.v }); }));
  $('saveAdv').onclick = async () => {
    await setting({ portal: $('portal').value, ssids: $('ssids').value.split('\n') });
    if (!$('advNote').classList.contains('err')) note('advNote', 'Saved.');
  };
  $('quitBtn').onclick = () => invoke('quit');
  await T.event.listen('state', (e) => { render(e.payload); if ($('logBox').open && document.activeElement !== $('logText')) loadLog(); });
  render(await invoke('get_state'));
  const first = await invoke('get_state');
  if (first.demo_view === 'collapsed') setSidebar(false);
  else if (first.demo_view === 'sidebar') setSidebar(true);
  else if (first.demo_view) show(first.demo_view);
});
