# UI Race Repair Implementation Plan

**Goal:** Fix three reproduced asynchronous UI races while preserving the current design.
**Architecture:** Keep the existing event-driven client. Cancel failed queued saves, scope history mutations to their initiating dialog generation, and preserve card request feedback by plant ID.
**Tech stack:** Vanilla JavaScript, Node regression tests, existing Playwright CI.
**Spec:** The reviewed behavior at PR #11 head `8ec0150`: failed uploads must not silently save without a photo; old history requests must not affect a newly opened plant; other-card rerenders must not drop a pending request or its error.

## Constraints
- Preserve all existing UI edits and manual identification fallback.
- No backend changes, model downloads, redesign, merge or deployment.
- Test each failure before changing its production handler.

## Review focus
- Actual photo upload rejection versus successful upload with unavailable identification.
- Replacement/cancel of a pending photo and repeated Save.
- History success and failure after closing A and opening B, including B's own pending request.
- Water and Undo errors after another card succeeds and replaces the DOM.
- Duplicate actions remain disabled across rerenders and feedback survives subsequent renders.

## Tasks
1. Add deterministic delayed-response regressions against the production client and confirm each failure.
2. Fix failed-photo queued-save state; retain the entered form and an explicit subsequent save/retry.
3. Capture history plant/generation before POST; guard dialog success, error and cleanup.
4. Keep pending/error state by plant ID for card Water/Undo; render it into current cards.
5. Run all existing checks, obtain independent review, update draft PR #11 and wait for exact-head CI.

## Verification record
- Original head: `8ec01506f27188d6b5d6dcf398da2d4900a3eede`.
- Observed failing tests for silent photo-less save; stale history success/error/cleanup (including same-plant reopen and history Undo); lost card feedback and pending controls after rerender.
- A successful retained photo with unavailable identification passed before and after the repair.
- Focused review found a stale retry warning. Failure→successful retry was observed red, then fixed and verified green.
- Local `npm test`: 24/24 pass. JavaScript syntax and diff whitespace checks pass.
- Existing API/Python suites and real-browser delayed-response checks run in the exact-head GitHub Actions pipeline; no local browser or model setup was repeated.
- No backend, model, style or markup changes; no merge or deployment.
