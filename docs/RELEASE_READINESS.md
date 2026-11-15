# ClasherCity 1.0.0 — Release Readiness Plan

_Last updated: 2026-11-15_

This checklist turns the installation and test guidance in the project README into a repeatable pre-release verification run. Complete it on a disposable test server before deploying to the live Clasher Network.

## 1. Test environment

Record the actual versions used for each run. Do not assume that a successful build means the plugin is compatible with every server build.

| Component | README target | Verified version |
|---|---|---|
| Paper | 26.2 build 10 dev / API 26.2 | _Pending test_ |
| Java | 25 | _Pending test_ |
| WorldGuard | 7.0.19 | _Pending test_ |
| FastAsyncWorldEdit | 2.15.3 | _Pending test_ |
| Vault | 1.7.3 API | _Pending test_ |
| Economy provider | EssentialsX Economy or equivalent | _Pending test_ |
| SmartSpawner | 1.8.3 (if reward commands use it) | _Pending test_ |

## 2. World and region checks

- [ ] Start with a backup of the test server and confirm the `clasher_city` world loads.
- [ ] Confirm the configured city spawn is saved and reachable.
- [ ] Create a small plot, a road region and a public-area region.
- [ ] Try creating overlapping selections; confirm invalid overlaps are rejected.
- [ ] Confirm the global city protection denies ordinary building and interaction.
- [ ] Confirm plot owners can build only in their own plot and only during the BUILDING phase.
- [ ] Confirm non-owners cannot build or interact in another player's plot.
- [ ] Confirm road and public-area regions remain protected.
- [ ] Check buckets, fire, explosions, pistons and fluid movement at plot boundaries.
- [ ] Confirm the test does not create, modify or migrate GriefPrevention claims in survival worlds.

## 3. Plot economy and persistence

- [ ] Buy a plot with a test account and record the starting and ending balance.
- [ ] Attempt a second purchase of the same plot from another account.
- [ ] Confirm a failed purchase does not charge the player.
- [ ] Confirm the database owner and WorldGuard region owner agree.
- [ ] Restart the server and confirm plot ownership and region protection persist.
- [ ] Test `/plot abandon` in an allowed phase and verify membership removal.
- [ ] Verify refund behaviour against the configured setting; do not assume a refund is enabled.

## 4. Tournament lifecycle

Run the complete flow with at least two ordinary test accounts and one admin account.

- [ ] Start a tournament and confirm registration opens.
- [ ] Buy a plot during registration and confirm it is entered automatically.
- [ ] Enter a previously owned plot with `/plot enter <id>`.
- [ ] Confirm the per-player entry limit is enforced.
- [ ] Switch to BUILDING and confirm only entered plot owners can build on their own plots.
- [ ] Switch to VOTING and confirm construction is frozen.
- [ ] Confirm one vote per account, no self-voting and the configured playtime requirement.
- [ ] Confirm voting is limited to plots entered in the current tournament.
- [ ] Register judges and submit scores for all five categories.
- [ ] Try invalid categories and marks outside the category maximums.
- [ ] Confirm incomplete judge scorecards do not count as completed scores.
- [ ] Check the 50/50 vote-share and judge-score weighting and inspect tie ordering.
- [ ] Test manual podium overrides and restoring automatic ranking.
- [ ] Close voting and confirm the results and leaderboard are consistent.

## 5. Reward safety

Reward commands are server-owner configuration, not built-in integrations with assumed third-party syntax.

- [ ] Keep reward lists empty until the exact console commands are verified from the installed reward plugins.
- [ ] Test the command dispatcher with harmless console-message commands first.
- [ ] Test first-, second- and third-place command lists separately.
- [ ] Test an offline winner and confirm the reward remains queued until they join.
- [ ] Test a deliberately invalid command and confirm it is marked for review rather than silently retried.
- [ ] If a reward partially succeeds, inspect the target plugin before using the retry command to avoid duplicate grants.
- [ ] Verify the actual item or entitlement was granted; command dispatch alone is not proof of delivery.

## 6. Data, restart and recovery checks

- [ ] Back up the test world, `plugins/ClasherCity/` (including SQLite data) and WorldGuard region data.
- [ ] Restart during a tournament and verify phase, entries, votes and judge scores recover.
- [ ] Confirm plot ownership persists independently of tournament entries.
- [ ] Confirm a new tournament does not automatically include old plot entries.
- [ ] Restore the backup in a separate test environment and verify the recovery procedure.

## 7. Release decision

Do not deploy to the live network until all critical protection, economy, persistence, tournament and reward checks pass.

| Area | Status |
|---|---|
| Build and startup | Not yet verified |
| World / region protection | Not yet verified |
| Plot purchase and persistence | Not yet verified |
| Tournament lifecycle and scoring | Not yet verified |
| Reward command delivery | Not yet verified |
| Backup and restore | Not yet verified |

A test is complete only after recording the server versions, test accounts, steps performed and observed result. Mark failed or untested cases explicitly; do not treat this checklist as evidence that a feature has already passed.
