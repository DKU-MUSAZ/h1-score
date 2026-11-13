# History and profile presentation implementation plan

> Execute inline with superpowers:executing-plans; one independent review after implementation.

**Goal:** Week-scoped history with a clear day-filter reset, cohesive result summary, and requested badge/profile refinements.
**Architecture:** Keep existing saved records, scoring, rewards and vehicle integrations. Apply date-range filtering in HistoryDisplay and coordinate selected day/week in AppFrame. Compose existing result data inside one left summary surface. Reuse a single profile action row for nickname and tutorial.
**Tech Stack:** Kotlin, Jetpack Compose, java.time, existing Android instrumentation.
**Spec:** User's five approved requests in this chat; no changes to game calculations or SDK/navigation connections.

## Tasks
- [x] HistoryDisplay.kt + H1App.kt: default to displayed Monday–Sunday only; show 이 주 전체 보기 for a selected date; clear day on changing weeks. Test Korean midnight, year boundary, month-to-day and wheel-edge selection.
- [x] SavedDriveDetailScreen.kt: one white summary panel. Score gauge, ranking and mileage in three aligned columns with independent detail actions; good/improve feedback stacked below a divider. Preserve all detail dialogs and readable narrow layouts.
- [x] BadgeCollectionScreen.kt: subtitle 16→20sp; remove slider icon only, preserve filter icons and selection.
- [x] MyProfileScreen.kt: larger/lower portrait and nickname; move nickname action outside the navy card, immediately above tutorial with identical white row treatment and existing enabled rules.
- [x] Build current branch, run focused unit/UI tests, inspect emulator screenshots for history list/detail, badges and profile. Run one independent review and resolve material findings.

## Review focus
- Date selection from month or faded edge must survive pager callbacks.
- Changing weeks must not leave an invisible stale date filter.
- Undated records stay stored even though week filters cannot place them.
- Long nickname, unranked result and long feedback must remain reachable.
- No writes to scoring/rewards or real SDK state through presentation changes.

## Ledger
Existing working tree includes earlier authorized work; preserve it. The user subsequently requested feature-sized commits for all branch changes.

Verification: core/app unit tests and debug/test APK builds passed. 10 focused emulator tests passed (weekly reset/swipe/month selection, history persistence, result dialogs and minimum-size layouts, badge filters, profile nickname persistence). Screenshots checked for each changed screen. Independent read-only review found no concrete bugs. No scoring/reward/SDK implementation changes.
