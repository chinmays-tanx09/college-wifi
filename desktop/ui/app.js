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
  // sidebar footer + forms
  $('whoName').textContent = s.username || 'No login saved';
  $('whoSub').textContent = s.username ? (s.has_password ? 'Password in ' + shortKc(s) : 'Password missing') : 'Account → add your login';
  $('kcName').textContent = s.keychain;
  if (document.activeElement !== $('user')) $('user').value = s.username || '';
  $('autoLogin').checked = s.auto_login;
  $('startAtLogin').checked = s.start_at_login;
  $('readUsage').checked = s.read_usage;
  document.querySelectorAll('#theme button').forEach((b) => b.classList.toggle('on', b.dataset.v === s.theme));
  if (document.activeElement !== $('portal')) $('portal').value = s.portal || '';
  if (document.activeElement !== $('ssids')) $('ssids').value = (s.ssids || []).join('\n');
  $('ver').textContent = `Version ${s.version}`;
}
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
  $('saveLogin').onclick = async () => {
    try { await invoke('save_login', { username: $('user').value, password: $('pass').value }); $('pass').value = ''; note('accountNote', 'Saved. Signing in when you are on the college Wi-Fi.'); }
    catch (e) { note('accountNote', String(e), true); }
  };
  $('forgetLogin').onclick = async () => { await invoke('forget_login'); $('pass').value = ''; note('accountNote', 'Login removed from this computer.'); };
  $('autoLogin').onchange = (e) => setting({ auto_login: e.target.checked });
  $('startAtLogin').onchange = (e) => setting({ start_at_login: e.target.checked });
  $('readUsage').onchange = (e) => setting({ read_usage: e.target.checked });
  document.querySelectorAll('#theme button').forEach((b) => (b.onclick = () => { applyTheme(b.dataset.v); setting({ theme: b.dataset.v }); }));
  $('saveAdv').onclick = async () => {
    await setting({ portal: $('portal').value, ssids: $('ssids').value.split('\n') });
    if (!$('advNote').classList.contains('err')) note('advNote', 'Saved.');
  };
  $('quitBtn').onclick = () => invoke('quit');
  await T.event.listen('state', (e) => render(e.payload));
  render(await invoke('get_state'));
  const first = await invoke('get_state');
  if (first.demo_view === 'collapsed') setSidebar(false);
  else if (first.demo_view === 'sidebar') setSidebar(true);
  else if (first.demo_view) show(first.demo_view);
});
