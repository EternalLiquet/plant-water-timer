import test from 'node:test';
import assert from 'node:assert/strict';
import {today, dueLabel, formatDate} from '../apps/api/src/main/resources/static/garden-utils.mjs';
test('local calendar date is not converted through UTC',()=>assert.equal(today(new Date(2026,2,8,23,30)),'2026-03-08'));
test('due labels cover unknown, today, tomorrow and overdue',()=>{
 assert.equal(dueLabel(null,'2026-03-08'),'Ready for a first check');
 assert.equal(dueLabel('2026-03-08','2026-03-08'),'Check today');
 assert.equal(dueLabel('2026-03-09','2026-03-08'),'Check tomorrow');
 assert.equal(dueLabel('2026-03-07','2026-03-08'),'Check overdue');
});
test('date-only formatting does not become the previous date',()=>assert.match(formatDate('2026-01-01','en-US'),/Jan 1/));
test('a plant keeps its home calendar day when the phone travels',async()=>{
 const {todayInZone}=await import('../apps/api/src/main/resources/static/garden-utils.mjs');
 const instant=new Date('2026-10-09T03:30:00Z');
 assert.equal(todayInZone('America/New_York',instant),'2026-10-08');
 assert.equal(todayInZone('Asia/Tokyo',instant),'2026-10-09');
});
