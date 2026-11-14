# Profile driver license card

**Goal:** Apply the approved navy/silver vertical driver license treatment to the left profile, with a large mascot and nickname.
**Architecture:** A Compose-only presentation component uses the existing mascot and app mark. Keep profile actions and vehicle data unchanged.
**Spec:** User-approved proposal in this chat: small slot; compact logo and DRIVER LICENSE at top; large uncircled mascot overlapping the diagonal background; nickname below a small 무사즈 드라이버 label; no invented grade, date or number.

- [x] Replace the left navy profile with a responsive license card. Keep the tutorial action below the card; the final nickname edit action is a muted pencil beside the nickname.
- [x] Build and run the existing nickname/vehicle-state UI regression; inspect a new emulator capture and long-name fit.
- [x] Review the scoped change. The user subsequently requested feature-sized commits for all branch changes.

Constraints: no scoring, rewards, vehicle SDK, navigation or saved identity changes. Preserve all prior working-tree edits. White/silver and navy only, with no purple surface tint.
Review focus: minimum card height, portrait visibility, long nickname wrapping, action reachability, unchanged live/demo vehicle information.

Verification: debug and instrumentation APK builds passed. Existing profile UI regression passed on emulator-5554 (nickname validation/save, action ordering, demo data isolation). Emulator captures inspected: portrait, diagonal navy/silver field, nickname with pencil, and tutorial action fit; the vehicle panel aligns with the tutorial row. The nickname dialog uses larger input/help/action text. Long-name two-line behavior and scoped change reviewed independently; no actionable issues.
