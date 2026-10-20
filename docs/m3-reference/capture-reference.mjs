import fs from 'node:fs';
const tabs=await(await fetch('http://127.0.0.1:9233/json')).json();
const ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);
await new Promise(r=>ws.onopen=r);let n=0;const pending=new Map();ws.onmessage=e=>{const d=JSON.parse(e.data);if(d.id){pending.get(d.id)?.(d);pending.delete(d.id);}};
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++n;pending.set(id,d=>d.error?reject(d.error):resolve(d.result));ws.send(JSON.stringify({id,method,params}));});
const ev=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
const pause=()=>new Promise(r=>setTimeout(r,80)); const results=[];
await call('Page.enable');await call('Page.navigate',{url:new URL('./reference-v21-M3.html',import.meta.url).href});await new Promise(r=>setTimeout(r,500));
const shot=async(name)=>{await ev(`(()=>{let e=document.querySelector('#review-harness');if(!e){e=document.createElement('style');e.id='review-harness';e.textContent='.viewport-banner,.test-panel{display:none!important}html{scrollbar-width:none}';document.head.append(e)}window.scrollTo(0,0)})()`);await pause();const meta=await ev(`({title:document.title,scene:document.querySelector('#scene').value,text:document.querySelector('#app').innerText,buttons:[...document.querySelectorAll('#app button')].map(e=>({action:e.dataset.action,text:e.innerText,disabled:e.disabled})),overflow:document.documentElement.scrollWidth>innerWidth,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,clientWidth:document.documentElement.clientWidth},app:document.querySelector('#app').getBoundingClientRect().toJSON()})`);const r=await call('Page.captureScreenshot',{captureBeyondViewport:false,clip:{x:0,y:0,width:await ev('innerWidth'),height:900,scale:1}});fs.writeFileSync(new URL('./canonical-reference/'+name+'.png',import.meta.url),Buffer.from(r.data,'base64'));results.push({name,...meta});};
const reset=async scene=>{await ev(`document.querySelector('#scene').value=${JSON.stringify(scene)};document.querySelector('#scene').dispatchEvent(new Event('change'))`);};
const click=async a=>{const ok=await ev(`(()=>{const b=document.querySelector('[data-action="${a}"]');if(!b||b.disabled)return false;b.click();return true})()`);await pause();return ok;};
try{
for(const width of [1280,390]){
await call('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:false});
for(const scene of await ev('[...document.querySelector("#scene").options].map(e=>e.value)')){await reset(scene);await shot(`${width}-${scene}-initial`);}
await reset('local');await click('chooseGame');await shot(`${width}-game-dialog`);await click('cancelGame');await click('next');await shot(`${width}-service-choice`);await click('builtin');await shot(`${width}-account-login`);await click('login');await shot(`${width}-account-busy`);await new Promise(r=>setTimeout(r,600));await shot(`${width}-account-ready`);await click('next');await shot(`${width}-local-home`);await click('settings');await shot(`${width}-settings`);await click('editSaveLocation');await shot(`${width}-path-dialog`);await click('closePath');await click('guide');await shot(`${width}-guide`);await click('cancelSetup');await shot(`${width}-guide-cancelled`);
await reset('history');await click('askRestore');await shot(`${width}-restore-dialog`);await click('cancelRestore');await shot(`${width}-restore-cancelled`);
for(const scene of ['splitUpdates','sameRun','sameChanges','mergeFull','missingBase','missingIdentity','globalConflict','mergeFailure']){await reset(scene);await click('askRestore');await shot(`${width}-${scene}-merge-dialog`);await click('merge');await new Promise(r=>setTimeout(r,650));await shot(`${width}-${scene}-merge-result`);}
await reset('many');await click('older');await shot(`${width}-history-older`);await click('newer');await shot(`${width}-history-newer`);
await reset('connecting');await click('next');await click('webdav');await ev(`document.querySelector('#connection-form').dispatchEvent(new Event('submit',{cancelable:true}))`);await shot(`${width}-webdav-busy`);await click('cancelBusy');await shot(`${width}-webdav-cancelled`);
await reset('failure');await click('next');await click('webdav');await ev(`document.querySelector('#connection-form').dispatchEvent(new Event('submit',{cancelable:true}))`);await new Promise(r=>setTimeout(r,650));await shot(`${width}-webdav-failed`);
await reset('android');await click('chooseGame');await shot(`${width}-android-game-picker`);await click('cancelGame');await click('selectApkMode');await shot(`${width}-android-apk-mode`);await click('apk');await shot(`${width}-android-installing`);await click('cancelAccess');await shot(`${width}-android-cancelled`);
}
}finally{fs.writeFileSync(new URL('./canonical-reference/inventory.json',import.meta.url),JSON.stringify(results,null,2));ws.close();}
console.log(JSON.stringify({screenshots:results.length,overflow:results.filter(x=>x.overflow).map(x=>x.name)}));

