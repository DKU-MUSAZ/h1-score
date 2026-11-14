# Seat-belt validity implementation plan

**Goal:** Evaluate driver and occupied passenger belts only while moving in D/R; retain disqualified and unverifiable trips in history without rewards.
**Architecture:** Independent core belt state machine; timestamped seat/gear/signed-speed SDK adapter; session gating and outcome persistence; history outcome presentation. Existing ScoreEngine and LongitudinalRules remain unchanged.
**Spec:** User-approved safety-belt proposal in this chat, including 0.1 m/s movement threshold, 500 ms confirmation with distinct fresh belt observations, occupied-passenger-only checks, unknown-data holds and permanent disqualification.

## Constraints and rulings
- Preserve acceleration, deceleration and speed-limit formulas, thresholds, navigation and Kakao integration.
- Reservations may begin parked without fastening; moving unbuckled is evaluated even if already unbuckled before departure.
- DRIVER always required. PASSENGER required when occupied; an occupied seat cannot become exempt while moving. Stable empty readings while stopped release the requirement.
- Unknown inputs are not violations. An unverified moving interval prevents rewards/ranking/collection settlement; retain personal history with a distinct outcome.
- Confirmed disqualification is terminal, persisted immediately, never represented as a numeric zero score.
- D/R motion uses signed speed magnitude via separate telemetry; do not inject reverse speed into the unchanged scoring pipeline.
- No commits or push during implementation unless requested. Current isolated feature worktree is reused.

## Tasks
- [x] Core state machine and boundary/temporal tests (park/stop/reverse/passenger/unknown/stale/glitches).
- [x] SDK seat capability discovery, separate driver/passenger observations and signed motion telemetry.
- [x] Session integration, terminal persistence, exclusion of score rewards and collections; update obsolete belt tests.
- [x] History list, calendar and detail outcome/reason presentation; backward-compatible stored records.
- [x] Build and unit/instrumentation checks, available live Virtual Console cases and final review.
- [ ] Real-console independent driver/passenger buckle transitions: unavailable in the current console catalog; only common warning lamp and individual occupancy controls are exposed. Fixture matrix passed; do not equate it to real SDK verification.

## Verification focus
No cached sample can confirm a violation. Timestamp gaps, late callbacks and disconnects must not invent safety. Test both negative and positive reverse speeds. Empty seats never require a belt. DQ survives reopen/restart and cannot grant mileage/badges. Compare ScoreEngine/LongitudinalRules against baseline. Restore console to P, stationary and empty passenger after tests. Buckle remains the initial unbuckled state because this console cannot control it independently.

## Progress
Implementation and automated validation complete. Corrected raw SDK occupancy and buckle decoding using observed emulator values; propagated service loss immediately into validity evidence. See [verification report](../../verification/2026-11-14-seat-belt.md) for passed checks and the remaining real-console limitation.
