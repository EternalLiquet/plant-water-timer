const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {runAsyncRaceChecks}=require('./browser-races.cjs');
require('node:fs').mkdirSync('test-artifacts',{recursive:true});
const BASE=process.env.GARDEN_URL||'http://127.0.0.1:8080';
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,timezoneId:'America/New_York'});
 const page=await context.newPage();const add=page.locator('#add-dialog'),edit=page.locator('#edit-dialog');const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(BASE+'/');
 // A sign-in form left open after its session expired still signs in, instead of showing an error page.
 await page.getByLabel('Garden password').waitFor();await context.clearCookies();
 await page.getByLabel('Garden password').fill('synthetic-garden-test-2026');await page.getByRole('button',{name:'Open my garden'}).click();
 await page.getByRole('heading',{name:'Your plants'}).waitFor();
 await page.screenshot({path:'test-artifacts/01-empty-mobile.png',fullPage:true});
 await page.locator('#add-open').click();await page.locator('#photo').setInputFiles(process.env.PLANT_FIXTURE||'tests/fixtures/synthetic-plant.png');
 await page.getByRole('button',{name:/Monstera deliciosa/}).waitFor({timeout:45000});
 await page.screenshot({path:'test-artifacts/02-photo-match-mobile.png',fullPage:true});
 // Choosing a file the app cannot use must not leave the previous photo on screen as if it will be saved.
 await page.locator('#photo').setInputFiles({name:'notes.txt',mimeType:'text/plain',buffer:Buffer.from('not a photo')});
 assert.equal(await page.locator('#photo-preview').isVisible(),false);assert.equal(await page.locator('#candidates button').count(),0);
 await page.locator('#photo').setInputFiles(process.env.PLANT_FIXTURE||'tests/fixtures/synthetic-plant.png');
 await page.getByRole('button',{name:/Monstera deliciosa/}).click();await add.getByLabel('Plant name',{exact:true}).fill('Kitchen Monstera');await add.getByLabel('Last watered').fill('2026-01-01');
 assert.equal(await add.getByLabel('Check the soil every').inputValue(),'7');
 await page.getByRole('button',{name:'Save plant',exact:true}).click();await page.getByRole('heading',{name:'Kitchen Monstera',exact:true}).waitFor();
 assert.match(await page.locator('#care-summary').textContent(),/1 plant to check today/);
 await page.getByRole('button',{name:'Watered today, Kitchen Monstera'}).click();await page.getByRole('button',{name:'Watered today, done, Kitchen Monstera'}).waitFor();
 // Keyboard and screen-reader users stay on the plant they just watered.
 assert.equal(await page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'Undo, Kitchen Monstera');
 assert.match(await page.locator('#care-summary').textContent(),/Nothing to check today/);
 await page.screenshot({path:'test-artifacts/03-watered-mobile.png',fullPage:true});
 // A watering that happened on another day can still be recorded from History.
 await page.getByRole('button',{name:'History, Kitchen Monstera'}).click();await page.locator('.history-entry').first().waitFor();assert.equal(await page.locator('.history-entry').count(),2);
 await page.getByLabel('Watered on another day?').fill('2026-01-05');await page.getByRole('button',{name:'Add',exact:true}).click();
 await page.locator('.history-entry').nth(2).waitFor();assert.equal(await page.locator('.history-entry').count(),3);
 await page.getByRole('button',{name:'Close history'}).click();
 await page.reload();await page.getByRole('heading',{name:'Kitchen Monstera',exact:true}).waitFor();
 await page.getByRole('button',{name:'Undo, Kitchen Monstera'}).click();await page.getByRole('button',{name:'Watered today, Kitchen Monstera'}).waitFor();
 // Name, type and the reminder interval can be changed after adding a plant.
 await page.getByRole('button',{name:'Edit, Kitchen Monstera'}).click();
 assert.equal(await page.evaluate(()=>document.activeElement?.id),'edit-name');
 await edit.getByLabel('Plant name',{exact:true}).fill('Living room Monstera');await edit.getByLabel('Check the soil every').fill('10');
 await page.getByRole('button',{name:'Save changes',exact:true}).click();await page.getByRole('heading',{name:'Living room Monstera',exact:true}).waitFor();
 await page.screenshot({path:'test-artifacts/04-edited-mobile.png',fullPage:true});
 // Cancel/back/repeat-open is safe and restores the main screen.
 await page.locator('#add-open').click();await add.getByLabel('Plant name',{exact:true}).fill('Discard me');await page.keyboard.press('Escape');assert.equal(await page.locator('#add-dialog').isVisible(),false);
 await page.locator('#add-open').click();assert.equal(await add.getByLabel('Plant name',{exact:true}).inputValue(),'');await page.getByRole('button',{name:'Cancel',exact:true}).click();
 // Source data is treated as text, not markup.
 await page.locator('#add-open').click();await add.getByLabel('Plant name',{exact:true}).fill('<img src=x onerror=alert(1)>');await page.getByRole('button',{name:'Save plant',exact:true}).click();await page.getByRole('heading',{name:'<img src=x onerror=alert(1)>',exact:true}).waitFor();
 assert.equal(await page.locator('.plant-name img').count(),0);
 // Deleting asks first, and keeping the plant changes nothing.
 await page.getByRole('button',{name:'Edit, <img src=x onerror=alert(1)>'}).click();await page.getByRole('button',{name:'Delete this plant'}).click();
 await page.getByRole('button',{name:'Keep it'}).click();assert.equal(await page.locator('.plant').count(),2);
 await page.getByRole('button',{name:'Delete this plant'}).click();await page.getByRole('button',{name:'Delete',exact:true}).click();
 await page.getByRole('heading',{name:'<img src=x onerror=alert(1)>',exact:true}).waitFor({state:'detached'});assert.equal(await page.locator('.plant').count(),1);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 const manifest=await context.request.get(BASE+'/manifest.webmanifest');assert.equal(manifest.status(),200);
 await runAsyncRaceChecks(page,BASE);
 await page.getByRole('button',{name:'Sign out',exact:true}).click();await page.getByLabel('Garden password').waitFor();
 const privateResponse=await context.request.get(BASE+'/api/garden/plants');assert.equal(privateResponse.status(),401);
 assert.deepEqual(errors,[]);
 console.log('PASS: stale sign-in → photo with CI identification stub → unusable file clears preview → confirmed plant → one-tap water → past watering → reload → undo → edit → cancel/reopen → safe text → delete with confirmation → mobile overflow → logout access.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
