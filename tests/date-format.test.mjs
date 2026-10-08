import test from 'node:test';
import assert from 'node:assert/strict';
import {today, dueLabel, formatDate} from '../apps/api/src/main/resources/static/garden-utils.mjs';
test('local calendar date is not converted through UTC',()=>assert.equal(today(new Date(2026,2,8,23,30)),'2026-03-08'));
test('due labels say plainly what to do and when',()=>{
 assert.equal(dueLabel(null,'2026-03-08'),'Check the soil when you can');
 assert.equal(dueLabel('2026-03-08','2026-03-08'),'Check the soil today');
 assert.equal(dueLabel('2026-03-09','2026-03-08'),'Check the soil tomorrow');
 assert.equal(dueLabel('2026-03-03','2026-03-08'),'Check the soil (it was due Mar 3)');
 assert.equal(dueLabel('2026-03-20','2026-03-08'),'Next soil check: Mar 20');
 assert.equal(dueLabel('2027-01-02','2026-12-30'),'Next soil check: Jan 2, 2027');
});
test('plants that need attention come first, soonest due first',async()=>{
 const {sortForCare}=await import('../apps/api/src/main/resources/static/garden-utils.mjs');
 const plants=[{name:'later',nextCheck:'2026-03-20'},{name:'never',nextCheck:null},{name:'today',nextCheck:'2026-03-08'},{name:'late',nextCheck:'2026-03-01'}];
 assert.deepEqual(sortForCare(plants,()=> '2026-03-08').map(p=>p.name),['late','today','never','later']);
});
test('the garden summary counts plants to check today',async()=>{
 const {careSummary}=await import('../apps/api/src/main/resources/static/garden-utils.mjs');
 const today=()=>'2026-03-08';
 assert.equal(careSummary([],today),'');
 assert.equal(careSummary([{nextCheck:'2026-03-09'}],today),'Nothing to check today.');
 assert.equal(careSummary([{nextCheck:'2026-03-08'},{nextCheck:'2026-03-09'}],today),'1 plant to check today.');
 assert.equal(careSummary([{nextCheck:'2026-03-01'},{nextCheck:'2026-03-08'}],today),'2 plants to check today.');
});
test('date-only formatting does not become the previous date',()=>assert.match(formatDate('2026-01-01','en-US'),/Jan 1/));
test('a plant keeps its home calendar day when the phone travels',async()=>{
 const {todayInZone}=await import('../apps/api/src/main/resources/static/garden-utils.mjs');
 const instant=new Date('2026-10-09T03:30:00Z');
 assert.equal(todayInZone('America/New_York',instant),'2026-10-08');
 assert.equal(todayInZone('Asia/Tokyo',instant),'2026-10-09');
});
