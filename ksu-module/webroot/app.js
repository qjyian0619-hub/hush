import {exec} from './vendor/kernelsu.js';
import {attachLens} from './glass.js';

const BRIDGE = '/system/bin/sh /data/adb/modules/regionguard/bridge.sh';
const SAVE_LABEL = '保存配置';
const LANGUAGES = new Map([
  ['en-US', '英语（美国）'],
  ['en-GB', '英语（英国）'],
  ['en-SG', '英语（新加坡）'],
  ['ja-JP', '日语'],
  ['zh-TW', '繁体中文']
]);

const form = document.getElementById('settings');
const hero = document.getElementById('hero');
const status = document.getElementById('status');
const statusDetail = document.getElementById('status-detail');
const feedback = document.getElementById('feedback');
const refreshButton = document.getElementById('refresh');
const saveButton = document.getElementById('save');
const enabled = document.getElementById('enabled');
const browsers = document.getElementById('browsers');
const zone = document.getElementById('zone');
const zoneTrigger = document.getElementById('zone-trigger');
const zoneDialog = document.getElementById('zone-dialog');
const zones = new Map(Array.from(zone.options, option => [option.value, option.textContent]));
const zoneChoices = new Map();
const meta = Object.fromEntries(['language', 'zone', 'time', 'browsers']
  .map(key => [key, document.getElementById(`meta-${key}`)]));

let busy = false;
let loaded = false;
let savedZone = '';

// Time zone helpers. Formatters are built once; the WebView's ICU data follows real DST rules.

const clockFormats = new Map();

function zoneOffset(timeZone) {
  try {
    const part = new Intl.DateTimeFormat('en-US', {timeZone, timeZoneName: 'longOffset'})
      .formatToParts(new Date()).find(item => item.type === 'timeZoneName');
    return `UTC${part?.value.replace(/^GMT/, '') || '+00:00'}`;
  } catch {
    return '';
  }
}

function renderClock() {
  if (!savedZone) return;
  try {
    if (!clockFormats.has(savedZone)) {
      clockFormats.set(savedZone, new Intl.DateTimeFormat('en-GB',
        {timeZone: savedZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}));
    }
    meta.time.textContent = clockFormats.get(savedZone).format(new Date());
  } catch {
    meta.time.textContent = '--:--';
  }
}

function startClock() {
  const now = new Date();
  setTimeout(() => {
    renderClock();
    setInterval(renderClock, 60_000);
  }, (60 - now.getSeconds()) * 1000 - now.getMilliseconds() + 50);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') renderClock();
  });
}

// Bridge and state

function setBusy(value, action = '') {
  busy = value;
  refreshButton.disabled = busy;
  for (const control of [saveButton, enabled, browsers, zone, zoneTrigger]) {
    control.disabled = busy || !loaded;
  }
  if (action === 'refresh') refreshButton.setAttribute('aria-busy', String(busy));
  if (action === 'save') saveButton.textContent = busy ? '正在保存…' : SAVE_LABEL;
}

async function run(command) {
  const result = await exec(`${BRIDGE} ${command}`).catch(() => {
    throw new Error('无法连接 KernelSU，请从模块 WebUI 重新打开');
  });
  let state;
  try {
    state = JSON.parse(result.stdout.trim());
  } catch {
    throw new Error('无法读取模块状态，请确认后台组件与 LSPosed 已启用');
  }
  if (result.errno !== 0 || !state?.ok) {
    throw new Error('操作未完成，请确认模块已启用后重试');
  }
  return state;
}

function setStatus(state, title, detail = '') {
  hero.dataset.state = state;
  status.textContent = title;
  statusDetail.textContent = detail;
  statusDetail.hidden = !detail;
}

function renderState(state) {
  if (!zones.has(state.zone)) {
    throw new Error('当前时区不在可选列表内，请更新 hush 模块');
  }

  enabled.checked = state.enabled;
  browsers.checked = state.browsers;
  zone.value = state.zone;
  savedZone = state.zone;
  showSelectedZone();
  if (!state.enabled) {
    setStatus('inactive', '保护已关闭');
  } else if (state.managerEnabled && state.sharedPreferences) {
    setStatus('active', '运行中');
  } else {
    setStatus('pending', '等待生效', 'KernelSU 模块状态尚未同步');
  }

  const language = state.language || '—';
  meta.language.textContent = LANGUAGES.get(language) || language;
  meta.zone.textContent = zones.get(state.zone);
  meta.browsers.textContent = state.browsers ? '开启' : '关闭';
  renderClock();
  loaded = true;
}

function report(message, kind = '') {
  feedback.textContent = message;
  feedback.dataset.kind = kind;
}

// Time zone sheet

let offsetsReady = false;

function fillOffsets() {
  if (offsetsReady) return;
  offsetsReady = true;
  for (const [value, button] of zoneChoices) {
    button.querySelector('.zone-offset').textContent = zoneOffset(value);
  }
}

function showSelectedZone() {
  document.getElementById('zone-value').textContent = zones.get(zone.value);
  for (const [value, button] of zoneChoices) {
    if (value === zone.value) button.setAttribute('aria-current', 'true');
    else button.removeAttribute('aria-current');
  }
}

function setZoneDialog(open) {
  if (open === zoneDialog.open) return;
  if (open) {
    fillOffsets();
    zoneDialog.showModal();
    zoneChoices.get(zone.value).focus({preventScroll: true});
  } else {
    zoneDialog.close();
  }
  zoneTrigger.setAttribute('aria-expanded', String(open));
}

function dismissZoneDialog() {
  setZoneDialog(false);
  if (history.state?.zonePicker) history.back();
}

if (typeof zoneDialog.showModal === 'function') {
  const options = document.getElementById('zone-options');
  const template = document.getElementById('zone-option');
  for (const [value, label] of zones) {
    const row = template.content.cloneNode(true);
    const button = row.querySelector('button');
    row.querySelector('.zone-option-label').textContent = label;
    zoneChoices.set(value, button);
    button.addEventListener('click', () => {
      zone.value = value;
      zone.dispatchEvent(new Event('change', {bubbles: true}));
      dismissZoneDialog();
    });
    options.append(row);
  }

  zone.hidden = true;
  zoneTrigger.hidden = false;
  document.getElementById('zone-label').htmlFor = zoneTrigger.id;
  showSelectedZone();
  zone.addEventListener('change', showSelectedZone);
  zoneTrigger.addEventListener('click', () => {
    // KernelSU routes Android Back through the WebView's history.
    if (!history.state?.zonePicker) history.pushState({zonePicker: true}, '', '#zone-picker');
    setZoneDialog(true);
  });
  window.addEventListener('popstate', () => setZoneDialog(Boolean(history.state?.zonePicker)));
  document.getElementById('zone-close').addEventListener('click', dismissZoneDialog);
  zoneDialog.addEventListener('click', event => {
    if (event.target === zoneDialog) dismissZoneDialog();
  });
  zoneDialog.addEventListener('cancel', event => {
    event.preventDefault();
    dismissZoneDialog();
  });
}

async function refresh() {
  if (busy) return;
  setBusy(true, 'refresh');
  try {
    renderState(await run('status'));
    report('');
  } catch (error) {
    loaded = false;
    setStatus('error', '读取失败', error.message);
    report('');
  } finally {
    setBusy(false, 'refresh');
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !loaded || !zones.has(zone.value)) return;
  setBusy(true, 'save');
  try {
    renderState(await run(`save ${zone.value} ${enabled.checked} ${browsers.checked}`));
    report('已保存，强行停止并重新打开目标应用后生效', 'success');
  } catch (error) {
    report(error.message, 'error');
  } finally {
    setBusy(false, 'save');
  }
});

form.addEventListener('change', () => report('有未保存的更改'));
refreshButton.addEventListener('click', refresh);

attachLens(refreshButton, {depth: 12, scale: 10});
startClock();
refresh().then(() => setTimeout(fillOffsets, 300));
