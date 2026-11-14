# History month expansion implementation plan

**Goal:** Preserve the eleven-day wheel while adding a full-content monthly score calendar.
**Architecture:** A fixed title toolbar controls month/list presentation. A shared calendar surface interpolates the selected week's date positions from seven month columns to the existing wheel's central seven slots; neighboring weeks fade, then the live eleven-day pager remains. Date selection updates the existing history filters and week together.
**Tech stack:** Jetpack Compose, java.time, existing CompletedDrive records.
**Spec:** User-approved design in this chat: retain 11 visible days; expand inside the same content region; month date selects week and filtered records; consistent width/background; fixed controls.

## Constraints
- Do not modify scoring, rewards, navigation integration or stored history.
- Group by existing Korea-local completion date; retain multiple records per day.
- Undated records remain accessible through the full list.
- Preserve selected date, week and sorting through detail navigation.

## Tasks
- [x] Add monthly date-grid helpers and boundary tests.
- [x] Implement shared monthly/weekly transition, month navigation and daily scores.
- [x] Move view/sort controls to title; retain stable positions and full-list access.
- [x] Verify same-day multiple scores, month boundaries, empty dates, wheel edges, detail return and no stored-data mutation.
- [x] Build/install current branch; inspect month, transition and selected-day screenshots on emulator.

## Review focus
External week selection must move pager without stale callbacks overwriting it. Zero/unknown scores must remain distinct. Six-week months must fit. Rapid mode toggles must not leave invisible click targets. Demo examples must never persist as actual records.

## Visual revision approved in chat
White monthly calendar with centered month heading and arrows on either side. Sunday–Saturday labels once above the grid, no individual card backgrounds. Scores immediately below date numerals; empty dates blank. Preserve the 11-day weekly wheel and date selection flow.

## Verification and review follow-up
Core/app unit tests and debug/test APK builds passed. Six emulator flows passed: sorting, demo isolation, wheel edge selection, detail return, monthly selection, and transition input isolation. Addressed independent review findings: remove the hidden list during transitions, use the actual weekly date as the transition anchor, cross-fade overlapping calendar layers, and fit multi-score labels at minimum window size. Sunday-first monthly layout follows the user reference; weekly Monday-based wheel is preserved.

## Explicit view selector
Replace the ambiguous toggle with fixed 주간 / 월간 tabs. Selected tab uses navy fill and white text. Clicking the current tab is a no-op; monthly date selection also selects 주간. 전체 기록 only clears the record date filter.
