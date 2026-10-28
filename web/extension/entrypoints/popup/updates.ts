/** Browser-managed installs can update in place. Unpacked builds still require browser approval. */
export function mountUpdates(parent: HTMLElement): void {
  const check = document.createElement('button');
  check.className = 'site';
  check.textContent = 'Check for updates';
  const status = document.createElement('p');
  status.className = 'update-status';
  status.setAttribute('role', 'status');
  const download = document.createElement('a');
  download.className = 'site';
  download.hidden = true;
  download.target = '_blank';
  download.rel = 'noreferrer';
  const manage = document.createElement('button');
  manage.className = 'site';
  manage.textContent = 'Open extension manager';
  manage.hidden = true;
  const firefox = navigator.userAgent.includes('Firefox/');
  manage.onclick = () => { void browser.tabs.create({ url: firefox ? 'about:addons' : 'chrome://extensions/' }).catch(() => {
    status.textContent = firefox ? 'Open about:addons in a new tab.' : 'Open chrome://extensions in a new tab.';
  }); };
  check.onclick = async () => {
    check.disabled = true;
    status.textContent = 'Checking for updates…';
    download.hidden = true;
    manage.hidden = true;
    try {
      // Let the browser validate and install its signed update. Never execute remote code here.
      if (typeof browser.runtime.requestUpdateCheck === 'function') {
        try {
          const result = await browser.runtime.requestUpdateCheck();
          if (result.status === 'update_available') {
            status.textContent = 'A browser-verified update is ready.';
            check.textContent = 'Restart extension to update';
            check.onclick = () => browser.runtime.reload();
            return;
          }
        } catch { /* Firefox and unpacked installs may not support this API. */ }
      }
      const endpoint = 'https://justshivv.github.io/Opentune/player/downloads/extension.json';
      const response = await fetch(endpoint, { cache: 'no-store', signal: AbortSignal.timeout(10_000) });
      if (!response.ok) throw new Error('Update service is unavailable. Try again later.');
      const release = await response.json() as { version?: string; files?: { chrome?: string; firefox?: string } };
      if (!release.version || !/^\d+(\.\d+){0,3}$/.test(release.version)) throw new Error('The update information is invalid.');
      const installed = browser.runtime.getManifest().version.split('.').map(Number);
      const latest = release.version.split('.').map(Number);
      let newer = false;
      for (let i = 0; i < Math.max(installed.length, latest.length); i++) {
        if ((latest[i] ?? 0) !== (installed[i] ?? 0)) { newer = (latest[i] ?? 0) > (installed[i] ?? 0); break; }
      }
      if (!newer) { status.textContent = `You're up to date (v${release.version}).`; return; }
      const file = firefox ? release.files?.firefox : release.files?.chrome;
      if (!file || !/^opentune-extension-[^/\\]+\.zip$/.test(file)) throw new Error('The update download is invalid.');
      status.textContent = `v${release.version} is available. Browser-store installs update automatically. For a ZIP install, download, replace the unpacked files, then reload it in the extension manager.`;
      download.href = new URL(encodeURIComponent(file), endpoint).href;
      download.textContent = `Download v${release.version}`;
      download.hidden = false;
      manage.hidden = false;
    } catch (error) {
      status.textContent = error instanceof Error ? error.message : 'Could not check for updates.';
    } finally { check.disabled = false; }
  };
  parent.append(check, status, download, manage);
}
