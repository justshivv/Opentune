let token = '', revision = 0, data, history = [];
const $ = id => document.getElementById(id);
const status = (message, error = false) => { $('status').textContent = message; $('status').className = error ? 'error' : ''; };
async function api(path, body) {
  const r = await fetch('/control/' + path, { method: body === undefined ? 'GET' : 'POST', credentials: 'omit', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const result = await r.json(); if (!r.ok) throw new Error(result.error || 'Request failed'); return result;
}
function node(tag, text) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; return n; }
function renderSections() {
  $('sections').replaceChildren();
  data.sections.forEach((section, index) => {
    const row = node('div'); row.className = 'section';
    const check = node('input'); check.type = 'checkbox'; check.checked = section.enabled; check.setAttribute('aria-label', 'Enable ' + section.id); check.onchange = () => section.enabled = check.checked;
    const label = node('label'); label.append(node('small', section.id));
    const title = node('input'); title.value = section.title; title.placeholder = 'Default title'; title.maxLength = 80; title.oninput = () => section.title = title.value; label.append(title);
    row.append(check, label);
    for (const [offset, name] of [[-1, '↑'], [1, '↓']]) {
      const button = node('button', name); button.className = 'quiet'; button.disabled = index + offset < 0 || index + offset >= data.sections.length;
      button.setAttribute('aria-label', `Move ${section.id} ${offset < 0 ? 'up' : 'down'}`);
      button.onclick = () => { [data.sections[index], data.sections[index + offset]] = [data.sections[index + offset], data.sections[index]]; renderSections(); };
      row.append(button);
    }
    $('sections').append(row);
  });
}
function renderSources() {
  $('manageSources').checked = data.sources !== null;
  $('addSource').disabled = data.sources === null || data.sources.length >= 20;
  $('sources').replaceChildren();
  (data.sources || []).forEach((source, index) => {
    const card = node('article'); card.className = 'source';
    for (const [key, title, max] of [['id', 'Stable ID', 64], ['name', 'Display name', 80], ['url', 'Manifest URL', 2048]]) {
      const label = node('label', title), input = node('input'); input.value = source[key]; input.maxLength = max;
      input.type = key === 'url' ? 'url' : 'text'; input.oninput = () => source[key] = input.value;
      label.append(input); card.append(label);
    }
    const label = node('label'); label.className = 'check';
    const check = node('input'); check.type = 'checkbox'; check.checked = source.enabled; check.onchange = () => source.enabled = check.checked;
    label.append(check, node('span', 'Available to listeners')); card.append(label);
    const actions = node('div'); actions.className = 'source-actions';
    for (const [offset, name] of [[-1, 'Move up'], [1, 'Move down']]) {
      const button = node('button', name); button.className = 'quiet'; button.disabled = index + offset < 0 || index + offset >= data.sources.length;
      button.onclick = () => { [data.sources[index], data.sources[index + offset]] = [data.sources[index + offset], data.sources[index]]; renderSources(); }; actions.append(button);
    }
    const remove = node('button', 'Remove'); remove.className = 'quiet'; remove.onclick = () => { data.sources.splice(index, 1); renderSources(); }; actions.append(remove);
    card.append(actions); $('sources').append(card);
  });
}
function render() {
  $('revision').textContent = revision ? 'Published revision ' + revision : 'No configuration published';
  renderSections(); $('links').replaceChildren();
  for (const [key, value] of Object.entries(data.links)) {
    const label = node('label', key === 'tv' ? 'TV information' : key); const input = node('input'); input.type = 'url'; input.value = value;
    input.oninput = () => data.links[key] = input.value; label.append(input); $('links').append(label);
  }
  renderSources();
  for (const key of ['banners', 'playlists']) $(key).value = JSON.stringify(data[key], null, 2);
  $('prompts').checked = data.communityPrompts; $('timeout').value = data.network.timeoutMs; $('retries').value = data.network.retries;
  $('history').replaceChildren();
  for (const old of history) {
    const row = node('div'); row.append(node('span', `Revision ${old.revision} · ${new Date(old.createdAt).toLocaleString()}`));
    const button = node('button', 'Restore'); button.className = 'quiet'; button.disabled = old.revision === revision;
    button.onclick = () => { if (confirm(`Publish revision ${old.revision} again as a new revision?`)) void action(async () => { await api('rollback', { revision: old.revision, expectedRevision: revision }); await load(); status('Restored as a new published revision.'); }); };
    row.append(button); $('history').append(row);
  }
}
function draft() {
  return { ...data, ...Object.fromEntries(['banners', 'playlists'].map(k => [k, JSON.parse($(k).value)])), communityPrompts: $('prompts').checked, network: { timeoutMs: Number($('timeout').value), retries: Number($('retries').value) } };
}
async function load() { const current = await api('config'); revision = current.revision; data = current.data; history = current.history; render(); }
async function action(fn) { $('publish').disabled = true; try { await fn(); } catch (e) { status(e.message, true); } finally { $('publish').disabled = false; } }
$('login').onsubmit = event => { event.preventDefault(); void action(async () => { const form = new FormData(event.target); const result = await api('login', Object.fromEntries(form)); token = result.token; event.target.reset(); await load(); $('login').hidden = true; $('editor').hidden = false; status('Connected. Changes stay in this draft until you publish.'); }); };
$('reload').onclick = () => void action(async () => { await load(); status('Loaded the latest published configuration.'); });
$('logout').onclick = () => { token = ''; $('editor').hidden = true; $('login').hidden = false; status('Signed out.'); };
$('publish').onclick = () => void action(async () => { await api('config', { expectedRevision: revision, data: draft() }); await load(); status('Published. Compatible apps receive this on their next refresh.'); });
$('previewButton').onclick = () => void action(async () => {
  const value = draft(); $('preview').replaceChildren();
  for (const banner of value.banners.filter(x => x.enabled)) { const card = node('article'); card.append(node('h3', banner.title), node('p', banner.body), node('small', banner.action + ' → ' + banner.url)); $('preview').append(card); }
  const list = node('ol'); value.sections.filter(x => x.enabled).forEach(x => list.append(node('li', x.title || x.id))); $('preview').append(list);
});
$('exampleBanner').onclick = () => void action(async () => { const values = JSON.parse($('banners').value); values.push({ id: 'community-' + Date.now(), enabled: true, title: 'Made for music. Built together.', body: 'Join the OpenTune community.', action: 'Join Discord', url: data.links.discord, platforms: ['android', 'tv', 'web'] }); $('banners').value = JSON.stringify(values, null, 2); });
$('examplePlaylist').onclick = () => void action(async () => { const values = JSON.parse($('playlists').value); values.push({ id: 'playlist-' + Date.now(), title: 'Your playlist title', browseId: 'VL_REPLACE_WITH_PLAYLIST_ID', artwork: '' }); $('playlists').value = JSON.stringify(values, null, 2); });

$('manageSources').onchange = () => { if (!$('manageSources').checked && !confirm('Return to the legacy catalog? This discards the catalog in this draft.')) { $('manageSources').checked = true; return; } data.sources = $('manageSources').checked ? [] : null; renderSources(); };
$('addSource').onclick = () => { if (data.sources === null || data.sources.length >= 20) return; data.sources.push({ id: 'source-' + crypto.randomUUID(), name: '', url: '', enabled: true }); renderSources(); };
