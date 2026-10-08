import {today,todayInZone,formatDate,dueLabel} from './garden-utils.mjs';
const $=id=>document.getElementById(id);
let session, plants=[], formBusy=false, photoBusy=false, photoId=null, previewUrl=null, photoGeneration=0, requestId=null, historyGeneration=0;
const wateringRequests=new Map();
let daySignature='';
const plantDays=()=>plants.map(p=>todayInZone(p.zone)).join(',');
const zone=()=>Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC';
const uuid=()=>crypto.randomUUID();
function message(id,text,error=false){$(id).textContent=text;$(id).classList.toggle('error',error);}
function showSignedOut(text=''){
  $('garden').hidden=true;$('sign-in').hidden=false;
  for(const id of ['add-dialog','history-dialog'])if($(id).open)$(id).close();
  if(text)message('login-message',text,true);
}
async function api(path,{method='GET',body}={}){
  const headers={};
  if(method!=='GET')headers[session.csrfHeader]=session.csrfToken;
  if(body && !(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body);}
  let response;
  try{response=await fetch(path,{method,headers,body,credentials:'same-origin',cache:'no-store'});}
  catch{throw new Error('Not saved. Check your connection and try again.');}
  if(response.status===401){showSignedOut('Your session ended. Sign in to keep caring for your plants.');throw new Error('Please sign in again.');}
  let data=null;try{data=await response.json();}catch{/* An empty response is allowed. */}
  if(!response.ok){
    if(response.status===403)throw new Error('Your session may have expired. Refresh the page and try again.');
    throw new Error(data?.message || (response.status===413?'Choose a photo smaller than 5 MB.':'That did not save. Please try again.'));
  }
  return data;
}
async function refresh(){plants=await api('/api/garden/plants');render();}
function render(){
  daySignature=plantDays();
  const container=$('plants');container.replaceChildren();$('empty').hidden=plants.length>0;
  [...plants].sort((a,b)=>(a.nextCheck||'').localeCompare(b.nextCheck||'')).forEach(plant=>{
    const card=$('plant-template').content.firstElementChild.cloneNode(true);
    card.querySelector('.plant-name').textContent=plant.name;
    card.querySelector('.species').textContent=plant.species||'Your little green companion';
    const image=card.querySelector('img');
    if(plant.photoId){image.src=`/api/garden/photos/${plant.photoId}`;image.alt=plant.name;card.querySelector('.placeholder').hidden=true;image.addEventListener('error',()=>{image.hidden=true;card.querySelector('.placeholder').hidden=false;});}
    else image.hidden=true;
    const plantToday=todayInZone(plant.zone);
    const due=card.querySelector('.due');due.textContent=dueLabel(plant.nextCheck,plantToday);due.classList.toggle('overdue',!!plant.nextCheck&&plant.nextCheck<plantToday);
    card.querySelector('.last').textContent=plant.lastWatered?`Last watered ${formatDate(plant.lastWatered)}`:'Last watering not recorded';
    const water=card.querySelector('.water'), undo=card.querySelector('.undo'), status=card.querySelector('.card-message');
    const done=plant.lastWatered===plantToday;water.classList.toggle('done',done);water.textContent=done?'✓ Watered today':'Watered today';water.disabled=done;
    undo.hidden=!done||!plant.lastEventId;
    water.addEventListener('click',async()=>{
      if(water.disabled)return;water.disabled=true;water.textContent='Saving…';status.textContent='';
      const key=`${plant.id}:${todayInZone(plant.zone)}`;
      if(!wateringRequests.has(key))wateringRequests.set(key,uuid());
      try{
        const updated=await api(`/api/garden/plants/${plant.id}/water`,{method:'POST',body:{eventId:wateringRequests.get(key),date:todayInZone(plant.zone),zone:plant.zone}});
        wateringRequests.delete(key);replacePlant(updated);message('page-message',`${plant.name} watered. Next check: ${formatDate(updated.nextCheck)}.`);
      }catch(error){status.textContent=error.message;status.classList.add('error');water.disabled=false;water.textContent='Watered today';}
    });
    undo.addEventListener('click',async()=>{
      undo.disabled=true;try{replacePlant(await api(`/api/garden/plants/${plant.id}/water/${plant.lastEventId}/undo`,{method:'POST'}));message('page-message','Watering undone.');}
      catch(error){status.textContent=error.message;status.classList.add('error');undo.disabled=false;}
    });
    card.querySelector('.history').addEventListener('click',()=>openHistory(plant));
    container.append(card);
  });
}
function replacePlant(updated){plants=plants.map(p=>p.id===updated.id?updated:p);render();}
function openAdd(){
  if($('add-dialog').open)return;
  $('plant-form').reset();photoId=null;requestId=uuid();photoGeneration++;photoBusy=false;
  if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=null;$('photo-preview').hidden=true;
  $('candidates').replaceChildren();message('photo-message','');message('form-message','');
  $('last-watered').max=today();$('last-watered').min='1900-01-01';$('save-plant').disabled=false;
  $('add-dialog').showModal();$('photo').focus({preventScroll:true});
}
async function discard(id){if(id)try{await api(`/api/garden/photos/${id}`,{method:'DELETE'});}catch{/* Orphan photos remain private; operator retention is documented. */}}
function closeAdd(){
  if(formBusy)return;
  photoGeneration++;void discard(photoId);photoId=null;photoBusy=false;
  if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=null;
  $('add-dialog').close();$('add-open').focus();
}
$('add-open').addEventListener('click',openAdd);$('first-add').addEventListener('click',openAdd);
$('add-close').addEventListener('click',closeAdd);$('add-cancel').addEventListener('click',closeAdd);
$('add-dialog').addEventListener('cancel',event=>{event.preventDefault();closeAdd();});
$('photo').addEventListener('change',async()=>{
  const file=$('photo').files[0];if(!file)return;
  const generation=++photoGeneration;
  void discard(photoId);photoId=null;$('candidates').replaceChildren();
  if(!['image/jpeg','image/png'].includes(file.type)||file.size>5*1024*1024){message('photo-message','Choose a JPG or PNG photo smaller than 5 MB.',true);return;}
  if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=URL.createObjectURL(file);$('photo-preview').src=previewUrl;$('photo-preview').hidden=false;
  photoBusy=true;$('save-plant').disabled=true;message('photo-message','Looking for a match… You can enter a name while you wait.');
  const body=new FormData();body.append('file',file,'plant-photo');
  try{
    const result=await api('/api/garden/photos',{method:'POST',body});
    if(generation!==photoGeneration||!$('add-dialog').open){void discard(result.photoId);return;}
    photoId=result.photoId;message('photo-message',result.message);
    for(const candidate of result.candidates){
      const button=document.createElement('button');button.type='button';button.textContent=`${candidate.scientificName} · ${Math.round(candidate.confidence*100)}% match`;
      button.addEventListener('click',()=>{
        $('species').value=candidate.scientificName;if(!$('plant-name').value.trim())$('plant-name').value=candidate.scientificName;
        for(const sibling of $('candidates').children)sibling.setAttribute('aria-pressed','false');button.setAttribute('aria-pressed','true');message('photo-message','Name selected. You can change it below.');
      });button.setAttribute('aria-pressed','false');$('candidates').append(button);
    }
  }catch(error){if(generation===photoGeneration)message('photo-message',`${error.message} You can still add a plant by name.`,true);}
  finally{if(generation===photoGeneration){photoBusy=false;$('save-plant').disabled=false;}}
});
$('plant-form').addEventListener('submit',async event=>{
  event.preventDefault();if(formBusy||photoBusy)return;
  formBusy=true;$('save-plant').disabled=true;$('save-plant').textContent='Saving…';message('form-message','');
  try{
    const plant=await api('/api/garden/plants',{method:'POST',body:{requestId,name:$('plant-name').value.trim(),species:$('species').value.trim(),photoId,lastWatered:$('last-watered').value||null,intervalDays:Number($('interval').value),zone:zone()}});
    photoId=null;plants=plants.filter(p=>p.id!==plant.id);plants.unshift(plant);render();formBusy=false;closeAdd();message('page-message',`${plant.name} added.`);
  }catch(error){message('form-message',error.message,true);}
  finally{formBusy=false;$('save-plant').disabled=false;$('save-plant').textContent='Save plant';}
});
async function openHistory(plant){
  const generation=++historyGeneration;$('history-title').textContent=`${plant.name} · history`;$('history-list').textContent='Loading…';$('history-dialog').showModal();
  try{
    const events=await api(`/api/garden/plants/${plant.id}/history`);if(generation!==historyGeneration||!$('history-dialog').open)return;
    $('history-list').replaceChildren();
    if(!events.length){$('history-list').textContent='No watering recorded yet.';return;}
    for(const event of events){
      const row=document.createElement('div');row.className='history-entry';const text=document.createElement('p');text.textContent=`${formatDate(event.date)}${event.undone?' · Undone':''}`;row.append(text);
      if(!event.undone){const undo=document.createElement('button');undo.className='quiet';undo.textContent='Undo';undo.addEventListener('click',async()=>{
        undo.disabled=true;try{replacePlant(await api(`/api/garden/plants/${plant.id}/water/${event.id}/undo`,{method:'POST'}));text.textContent=`${formatDate(event.date)} · Undone`;undo.remove();}catch(error){undo.disabled=false;message('page-message',error.message,true);}
      });row.append(undo);}
      $('history-list').append(row);
    }
  }catch(error){if(generation===historyGeneration)$('history-list').textContent=error.message;}
}
$('history-close').addEventListener('click',()=>{historyGeneration++;$('history-dialog').close();});
$('history-dialog').addEventListener('close',()=>historyGeneration++);
$('help-open').addEventListener('click',()=>$('help-dialog').showModal());$('help-close').addEventListener('click',()=>$('help-dialog').close());
$('sign-out').addEventListener('click',async()=>{try{await api('/logout',{method:'POST'});location.assign('/');}catch(error){message('page-message',error.message,true);}});
let installPrompt;
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installPrompt=event;$('install').hidden=false;});
$('install').addEventListener('click',async()=>{if(installPrompt){await installPrompt.prompt();installPrompt=null;$('install').hidden=true;}});
window.addEventListener('online',()=>message('page-message','Back online. You can try saving again.'));
window.addEventListener('offline',()=>message('page-message','You are offline. Changes cannot be saved until you reconnect.',true));
async function start(){
  try{
    const response=await fetch('/api/session',{cache:'no-store'});if(!response.ok)throw new Error('Your garden is unavailable. Please refresh to try again.');session=await response.json();$('login-csrf').value=session.csrfToken;
    if(session.authenticated){$('sign-in').hidden=true;$('garden').hidden=false;await refresh();}
    else{showSignedOut();if(!session.configured){$('login-form').hidden=true;message('login-message','This garden is not ready yet. Ask the person running it to finish setup.');}
      else if(new URLSearchParams(location.search).get('login')==='failed')message('login-message','That password did not work. Please try again.',true);}
  }catch(error){showSignedOut(error.message);}
}
start();
if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
setInterval(()=>{if(session?.authenticated&&plantDays()!==daySignature)render();$('last-watered').max=today();},60000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&session?.authenticated)refresh().catch(error=>message('page-message',error.message,true));});
