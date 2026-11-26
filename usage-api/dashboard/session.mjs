const pages=document.documentElement.dataset.host==='pages';
const key='opentune.owner.session';
const configured=pages ? document.querySelector('meta[name="usage-api"]').content : location.origin;
export const apiBase=configured+'/usage';
let token='';
try { if(pages) token=sessionStorage.getItem(key) || ''; } catch { /* Login still works in memory. */ }
function remember(value) { token=value;try { if(value) sessionStorage.setItem(key,value);else sessionStorage.removeItem(key); } catch {} }
function signOut() { remember('');location.reload(); }
export async function request(url,signal) {
  const privateRead=url.startsWith(apiBase+'/v1/');
  const result=await fetch(url,{cache:'no-store',credentials:pages?'omit':'same-origin',redirect:privateRead?'error':'follow',
    headers:pages && privateRead ? {Authorization:'Bearer '+token} : {},
    signal:AbortSignal.any([signal,AbortSignal.timeout(12000)])});
  if(result.status===401 && privateRead) { if(pages) signOut();else location.replace('/login'); }
  return result;
}
export function start(load) {
  if(!pages) { load();return; }
  const login=document.getElementById('owner-login'),dashboard=document.getElementById('owner-dashboard');
  const show=()=>{login.hidden=true;dashboard.hidden=false;load();};
  document.getElementById('owner-signout').addEventListener('submit',event=>{event.preventDefault();signOut();});
  login.querySelector('form').addEventListener('submit',async event=>{
    event.preventDefault();
    const form=event.currentTarget,button=form.querySelector('button'),message=document.getElementById('login-error');
    button.disabled=true;message.textContent='';
    try {
      const result=await fetch(configured+'/auth/login',{method:'POST',credentials:'omit',redirect:'error',
        headers:{Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},
        body:new URLSearchParams({username:form.elements.username.value,password:form.elements.password.value}),signal:AbortSignal.timeout(15000)});
      if(!result.ok) throw new Error(result.status===401?'Incorrect username or password.':result.status===429?'Too many attempts. Please wait a minute.':result.status===503?'Owner login is not configured yet.':'Unable to sign in. Please try again.');
      const data=await result.json();if(typeof data.token!=='string' || !data.token) throw new Error('Sign-in response was incomplete.');
      remember(data.token);form.reset();show();
    } catch(error) {message.textContent=error.message || 'Unable to connect. Please try again.';}
    finally {form.elements.password.value='';button.disabled=false;}
  });
  if(token) show();else login.hidden=false;
  // Do not restore a previous dashboard containing counts after sign-out/back navigation.
  addEventListener('pagehide',()=>{dashboard.hidden=true;});
  // Coming back to a still signed-in page shows the counts it already has: they are reloaded only
  // when the page is opened, the dates or calendar change, or Refresh is pressed.
  addEventListener('pageshow',event=>{
    if(!event.persisted) return;
    let kept='';try { kept=sessionStorage.getItem(key) || ''; } catch {}
    if(token && kept===token && login.hidden) dashboard.hidden=false; else location.reload();
  });
}

/** Owner-only catalog writes use the same authentication as private reads. */
export async function publishSources(body) {
  const result=await fetch(apiBase+'/v1/addons',{method:'POST',cache:'no-store',credentials:pages?'omit':'same-origin',redirect:'error',
    headers:{'Content-Type':'application/json',...(pages?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
  if(result.status===401){if(pages)signOut();else location.replace('/login');throw new Error('Sign in again');}
  const data=await result.json();if(!result.ok)throw new Error(data.error || 'Could not publish sources');return data;
}
