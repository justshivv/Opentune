// A browser requesting an APK is not proof of a completed download or installation.
// Tracking never delays or blocks the APK link. Direct GitHub downloads remain untracked.
(() => {
  let api=null;
  fetch(new URL('usage-config.json',document.baseURI),{cache:'no-store'})
    .then(r=>r.ok?r.json():null).then(config=>{
      if(!config?.apiBase) return;
      const url=new URL(config.apiBase);
      if(url.protocol==='https:' && !url.username && !url.password) api=url.href.replace(/\/$/,'');
    }).catch(()=>{});
  const seen=new Set();
  function record(event) {
    if(!api || navigator.globalPrivacyControl || navigator.doNotTrack==='1') return;
    const link=event.target.closest('a[href]'); if(!link) return;
    const url=new URL(link.href);
    const match=url.pathname.match(/^\/justshivv\/Opentune\/releases\/download\/v(\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]{1,32})?)\/[^/]+\.apk$/);
    if(url.origin!=='https://github.com' || !match) return;
    let browserId;
    try {
      browserId=localStorage.getItem('opentune-download-id');
      if(!browserId) { browserId=crypto.randomUUID(); localStorage.setItem('opentune-download-id',browserId); }
    } catch { return; } // Don't mint a new ID on every click when storage is blocked.
    const release=match[1]; if(seen.has(release)) return;
    fetch(api+'/v1/download',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({browserId,release}),keepalive:true}).then(r=>{if(r.ok) seen.add(release);}).catch(()=>{});
  }
  document.addEventListener('click',record);
  document.addEventListener('auxclick',event=>{if(event.button===1) record(event);});
})();
