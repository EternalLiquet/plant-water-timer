export function today(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
}
export function formatDate(value, locale) {
  if (!value) return 'Not recorded';
  const [y,m,d]=value.split('-').map(Number);
  return new Intl.DateTimeFormat(locale,{month:'short',day:'numeric',year:'numeric'}).format(new Date(y,m-1,d,12));
}
export function dueLabel(date, now=today()) {
  if (!date) return 'Ready for a first check';
  if (date<now) return 'Check overdue';
  if (date===now) return 'Check today';
  const [y,m,d]=now.split('-').map(Number);
  if (date===today(new Date(y,m-1,d+1,12))) return 'Check tomorrow';
  return `Check ${formatDate(date)}`;
}
export function todayInZone(zone, date=new Date()) {
  const parts=new Intl.DateTimeFormat('en-US',{timeZone:zone||'UTC',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(date);
  const value=type=>parts.find(part=>part.type===type).value;
  return `${value('year')}-${value('month')}-${value('day')}`;
}
