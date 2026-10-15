# Android garden contract (design for #14)

This contract guides #15–#19. The Java/H2 garden remains the shared record seen by the browser
and MCP. One Android phone keeps a durable local copy and an ordered outbox, so care can be
recorded without a connection and reconciled when the app is open again. The bundled APK in #15
only proves packaging; it does not implement this data contract.

## Identity and first connection

The server must generate a random garden-instance UUID once, persist it with the H2 database,
and return it through an authenticated endpoint. The existing owner UUID is derived from the
fixed username `gardener`, so it cannot identify a particular server. A restored database keeps
its garden ID; a fresh database gets a new one. Never infer identity from URL or owner ID.

The phone has a random installation ID and locally generated stable plant and watering UUIDs.
An imported plant or event retains its server ID. For an offline-created plant, keep a durable
local-ID → server-ID mapping after the server assigns its ID. Every queued command refers to the
stable local ID and carries a random mutation ID. Store the garden-instance ID alongside all
imported records and outbox entries.

The first pairing reads the selected server garden, including full relevant watering history,
before enabling replay. An empty local store imports it. An empty server does **not** authorize
uploading an unrelated local garden: show both sides and ask which garden to use. With nonempty
phone and server data, require an explicit import/reconciliation choice and show conflicts;
never replace either silently. If the saved garden ID differs on reconnect, stop writes and show
"This is a different garden" with a way to return to the original connection. Moving records
between gardens needs a separately reviewed import flow. This milestone supports one phone and
one selected server garden, not concurrent phones.

## Local records and time

Use Capacitor SQLite for structured plants, watering events, outbox, identity mappings,
revisions and tombstones. Keep a numbered local schema and transactional migrations; migration
failure leaves the previous data intact and surfaces an error. A care action and its outbox
entry commit together before showing "Saved on this phone". Keep any photo bytes in private app
storage with database references; no raw photo, password or token in logs. App updates with the
same application ID and signing key retain app data. Uninstall or clearing app storage normally
removes it; backup/export is a later, separately verified feature, not a recovery promise.

Watering and next-check values are ISO calendar dates interpreted in each plant's stored IANA
time zone. Travel does not change a plant's date. Event creation, revision, receipt and sync
times are UTC instants. Default reminder time is editable 09:00 in that plant's zone. On a DST
gap, schedule at the first valid local time after the gap; on an overlap, use the earlier offset
once. Recompute future reminders when the app opens, on relevant care changes, after reboot and
on time-zone/clock changes. Use inexact local notifications when #17 adds scheduling; an OS may
deliver late. A missed reminder is coalesced to at most one current check per plant rather than
replaying every missed date. Snooze is local reminder state only and never records watering.

## Ordered mutations and reconciliation

Queue create, edit, watering, Undo and delete as immutable commands in local commit order per
plant. Retry the same mutation ID and payload until the server confirms it; do not generate a
new ID on retry. Store a server receipt for every accepted mutation, including delete, beyond
the lifetime of its plant. Reusing an ID with different payload returns conflict. A local delete
creates a tombstone and hides the plant but keeps IDs, pending commands and receipts until the
server confirms and every earlier command resolves. Server deletes also arrive as tombstones.
Receipts and tombstones need a documented retention/compaction rule before #18 ships; they
cannot be dropped merely because a plant row was deleted.

Watered today uses the plant's calendar day and one stable proposed event ID per attempted save.
The server admits at most one active event per plant/day, including independent browser/MCP taps.
Its durable receipt records the request ID, matching event ID and outcome: **created** by this
request or **deduplicated** against a pre-existing event. A deduplicated phone request is a no-op:
the phone replaces its tentative event with the matching server event for display, but must not
offer or queue Undo of that event as the inverse of the phone tap. Undo of a phone-created event
references its confirmed ID and is idempotent. An explicit later Undo of an imported event needs
its own user action and revision check; it is never inferred from a deduplicated receipt. If a
same-day event was already undone, a fresh watering action may create a new event. After a lost
response, reconcile or retry the **same** request ID and payload to learn its recorded outcome
before enabling Undo; never guess from the current plant summary or issue an inverse command.
Backdated watering does not move the latest care date backwards; the latest active event by
calendar date determines it. The existing browser API already deduplicates active same-day
watering, but a duplicate currently returns only a plant. Its `lastEventId` names the latest
active event, which may belong to a different date than a backdated duplicate. It does not
provide the matching event ID, created-versus-deduplicated outcome, or a deletion-safe receipt.
#18 must close these gaps.

Required #18 tests use synthetic data: (1) browser/MCP records today's event B before phone
request A arrives; A returns `deduplicated(B)` and phone Undo of A cannot remove B; (2) the
server creates A but its response is lost; retry of A returns `created(A)` and one later Undo
undoes A once; (3) the plant's latest event is on the 9th but a phone request for the 7th
deduplicates; its receipt names the 7th's event, never `lastEventId` from the 9th, and the last
care date remains the 9th. Test the same outcomes across app restart and remote deletion.

Revisions are monotonic per server plant, changed for browser, phone and MCP mutations. Import
captures a base revision. A stale edit or delete must return conflict with current server data;
the phone keeps its local change pending and offers a visible choice to keep local values,
accept server values or edit again. An Undo targeting an event already undone is a successful
no-op; an event removed by a remote delete is a conflict, not a recreated plant. Server watering
events can merge by stable event ID and same-day rule, but mutable fields never use silent
last-write-wins. When the phone is offline, reminders use last-known records. On app open,
manual Sync or foreground reconnect, replay eligible commands in order, then fetch server
changes and reconcile local reminders. No background polling or immediate MCP-to-phone promise.
Show "Saved on this phone", "Waiting to sync" and "Synced" accurately, plus last successful
sync time and visible conflict/error state. Session expiry leaves commands queued.

## Server and security boundary for #18

Implement the garden ID, revision/change feed or bounded snapshot with tombstones, durable
receipts and idempotent conditional writes in append-only Flyway migrations. Current edits have
no revisions, delete removes history and MCP receipts, and MCP name confirmation writes SQL
directly rather than using GardenService. Route all future garden mutations through one service
boundary so revisions and change tracking include MCP changes. Do not retrofit these changes
into #15.

Before any real connection, design and test native HTTP transport against the existing session
cookie and CSRF flow: trusted HTTPS origin, cookie storage and SameSite behavior, CSRF retrieval,
expiry, redirects and sign-out. Native app origin differs from the browser origin. Do not open
permissive CORS, embed a garden or MCP credential, disable certificate verification or weaken
CSRF. Keep identification and photo upload online and opt-in; manual naming works offline.
If secure transport cannot preserve this boundary with a thin adapter, split #18 and stop
before connecting real data.

## Review and release gates

#15 may use synthetic shell data only. #16 implements durable local care and queueing; #17
implements native reminders; #18 adds authenticated reconciliation; #19 records physical-phone
results. Browser/emulator tests and a built APK do not prove offline care, notification delivery
or installation on a real phone. The server/database/photo backup remains the operator's task;
phone backup behavior must be verified before any promise. Open questions for #18 review are the
receipt retention/compaction limit and the exact native cookie/CSRF mechanism, both requiring
tests before real-data pairing. They do not change the #15 packaging boundary.
