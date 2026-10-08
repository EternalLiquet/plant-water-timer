export function today(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}
/** A calendar date such as "Mar 3"; the year is shown only when it differs from `now`'s year. */
export function formatDate(value, locale, now) {
  if (!value) return 'Not recorded';
  const [y,m,d]=value.split('-').map(Number);
  const sameYear=now && now.slice(0,4)===value.slice(0,4);
  return new Intl.DateTimeFormat(locale,{month:'short',day:'numeric',...(sameYear?{}:{year:'numeric'})}).format(new Date(y,m-1,d,12));
}
function nextDay(now){const [y,m,d]=now.split('-').map(Number);return today(new Date(y,m-1,d+1,12));}
/** What to do next, in plain words. Dates are reminders to feel the soil, never orders to water. */
export function dueLabel(date, now=today()) {
  if (!date) return 'Check the soil when you can';
  if (date<now) return `Check the soil (it was due ${formatDate(date,undefined,now)})`;
  if (date===now) return 'Check the soil today';
  if (date===nextDay(now)) return 'Check the soil tomorrow';
  return `Next soil check: ${formatDate(date,undefined,now)}`;
}
export function todayInZone(zone, date=new Date()) {
  const parts=new Intl.DateTimeFormat('en-US',{timeZone:zone||'UTC',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(date);
  const value=type=>parts.find(part=>part.type===type).value;
  return `${value('year')}-${value('month')}-${value('day')}`;
}
const needsCheck=(plant,now)=>!!plant.nextCheck&&plant.nextCheck<=now;
/** Due or overdue plants first (most overdue first), then plants with no watering yet, then the rest by date. */
export function sortForCare(plants, todayFor=plant=>todayInZone(plant.zone)) {
  const rank=plant=>needsCheck(plant,todayFor(plant))?0:plant.nextCheck?2:1;
  return [...plants].sort((a,b)=>rank(a)-rank(b)||(a.nextCheck||'').localeCompare(b.nextCheck||'')||a.name.localeCompare(b.name));
}
export function careSummary(plants, todayFor=plant=>todayInZone(plant.zone)) {
  if (!plants.length) return '';
  const count=plants.filter(plant=>needsCheck(plant,todayFor(plant))).length;
  if (!count) return 'Nothing to check today.';
  return `${count} ${count===1?'plant':'plants'} to check today.`;
}
