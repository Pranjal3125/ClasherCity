<div align="center">

# ClasherCity

### City Plots · Building Tournaments · Player Voting · Judge Scoring

A Paper plugin for a dedicated flat city world with paid building plots, WorldGuard protection, physical & GUI maps, tournament phases, public voting, judge scoring, leaderboards, and configurable rewards.

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Paper%2026.2-313131?logo=minecraft)
![Storage](https://img.shields.io/badge/Storage-SQLite-blue?logo=sqlite)
![Version](https://img.shields.io/badge/Version-1.0.0-brightgreen)

[Source](https://github.com/Pranjal3125/ClasherCity) · [Issues](https://github.com/Pranjal3125/ClasherCity/issues)

</div>

> **Note:** Build and test on a staging server before deploying to a live network.

## Contents

- [Target Environment](#target-environment)
- [Build the JAR](#build-the-jar)
- [Install on a Test Server](#install-on-a-test-server)
- [Admin Setup](#admin-setup)
- [WorldGuard Protection Model](#worldguard-protection-model)
- [Player Commands](#player-commands)
- [Tournament Workflow](#tournament-workflow)
- [Prize Commands](#prize-commands--important-setup-required)
- [Important Behaviour & Boundaries](#important-behaviour-and-current-boundaries)
- [Admin Commands](#admin-commands)
- [Pre-Deployment Checklist](#test-checklist-before-live-deployment)

---

## Target Environment

- Paper `26.2-10-dev` / API `26.2`
- Java **25** for building and running on Paper 26.x
- WorldGuard `7.0.19`
- FastAsyncWorldEdit `2.15.3` (WorldGuard's WorldEdit-compatible API provider)
- Vault `1.7.3` with an economy provider such as EssentialsX Economy
- SmartSpawner `1.8.3` (optional at startup; required only for configured reward commands)

WorldGuard and Vault are hard dependencies. FAWE is declared as a soft dependency; ClasherCity does not call FAWE editing features. Existing GriefPrevention claims are not read, changed, or migrated.

## Build the JAR

Paper 26.2 requires Java 25. Build with JDK 25 and Maven 3.9+:

```bash
mvn -U clean package
```

The shaded plugin JAR is output to:

```text
target/ClasherCity-1.0.0.jar
```

The SQLite JDBC driver is shaded into this JAR. Paper, WorldGuard, FAWE, and Vault APIs are provided by the server and are not bundled.

Windows users can run `build.bat`; Linux/macOS users can run `./build.sh` after installing JDK 25 and Maven.

## Install on a Test Server

1. Stop the test server and back up the server folder.
2. Confirm that WorldGuard, Vault, an economy provider, and FastAsyncWorldEdit are installed.
3. Copy `target/ClasherCity-1.0.0.jar` into the test server's `plugins/` folder.
4. Start the server. ClasherCity creates/loads the `clasher_city` flat world along with `plugins/ClasherCity/config.yml` and `clashercity.db`.
5. Check console for `ClasherCity enabled` and run `/clcity info`.
6. Do **not** install on the live Clasher Network server until all staging checks pass.

## Admin Setup

The city world is named `clasher_city` by default and is auto-created/loaded from `config.yml`.

1. Go to the city world (or run `/clcity create` to make sure it is loaded).
2. Run `/clcity setspawn` while standing at your intended city spawn.
3. Run `/clcity wand`.
4. Left-click one corner and right-click the opposite corner of a plot.
5. Run `/clcity createplot A-01 10000` (replace ID and price with your own values).
6. Repeat for additional plots.
7. Select road corners and run `/clcity createroad ROAD-01`.
8. Select public-area corners and run `/clcity createpublic PARK-01`.
9. Run `/clcity mapitem` and place the filled map in an item frame at the city spawn. `/citymap` also opens a GUI map.

Plot and road/public-area rectangles must not overlap. Selections are X/Z based, and WorldGuard regions span full world height. Minimum plot width/depth is `plots.minimum-size` (default `10` blocks).

## WorldGuard Protection Model

- **City global region:** build, interact, and chest access denied.
- **Plot region:** build, interact, and chest access allowed for that plot's **members only**; the buyer is added as the WorldGuard owner.
- **Road and public regions:** build, interact, and chest access denied at a higher priority.
- ClasherCity also enforces plot ownership and tournament phase checks for block place/break, buckets, and entity/hanging interactions.
- Explosions, fire ignition/burn, pistons, and fluid flow across plot boundaries are blocked in the city world.
- The plugin does not create GriefPrevention claims or alter GriefPrevention configuration in survival worlds.

WorldGuard's bypass permission is separate from `clashercity.bypass`. To let a non-OP admin bypass WorldGuard's city global region, grant the per-world bypass permission (e.g., via LuckPerms):

```text
/lp group admin permission set worldguard.region.bypass.clasher_city true
```

Only grant this to trusted admins.

## Player Commands

| Command | Description |
|---|---|
| `/city` | Open the main city GUI |
| `/city visit` | Teleport to the city spawn |
| `/plots` | Open the plot browser |
| `/citymap` | Open the GUI city map |
| `/plot claim <id>` | Open a purchase confirmation GUI |
| `/plot enter <id>` | Register an already-owned plot for the current tournament |
| `/plot home [id]` | Teleport to an owned plot |
| `/plot info <id>` | View plot information |
| `/plot abandon <id>` | Release an owned plot (refund controlled by config) |
| `/buildvote` | Vote during the voting phase |
| `/buildtop` | View the leaderboard and results |

Plots can only be purchased during `REGISTRATION`. Players can build only on their own plot during `BUILDING`.

## Tournament Workflow

1. `/clcity start City Building Championship` — creates a new tournament ID and opens registration.
2. Players buy plots using `/plots` or `/plot claim <id>`. Newly purchased plots are automatically entered into the tournament.
3. Players who already own a plot from an earlier event can enter it during registration with `/plot enter <id>` (subject to the per-player entry limit). Only entered plots can be voted on, judged, or ranked.
4. `/clcity phase building` — enables construction on entered plot owners' plots.
5. `/clcity judge add <player>` — registers each judge for the tournament.
6. `/clcity phase voting` — freezes construction and opens player voting.
7. Players use `/buildvote`. One vote per Minecraft account per tournament; players cannot vote for their own plot. Minimum account playtime is configurable (default `0` minutes).
8. Registered judges score plots with `/clcity score <plot-id> <category> <marks>`:
   - `creativity` (max 25)
   - `exterior` (max 25)
   - `interior` (max 20)
   - `detailing` (max 15)
   - `theme` (max 15)
9. Optional admin override: `/clcity winner <1|2|3> <plot-id>` forces a podium position; `/clcity winner <rank> auto` restores computed order.
10. `/clcity closevote` finalizes results, announces the top 3, and queues configured rewards.
11. `/buildtop` displays the leaderboard.

Default score weighting is **50% normalized player-vote share + 50% average completed judge score** (both configurable). Judges must submit all five categories for a plot for their score to count.

To change phases manually, use `/clcity phase <setup|registration|building|voting|results>`.

## Prize Commands — Important Setup Required

ClasherCity does **not** guess third-party command syntax for Infinity Caves or SmartSpawner. Verify the exact console commands on your test server and set them in `plugins/ClasherCity/config.yml`:

```yaml
rewards:
  first-commands:
    - "<verified Infinity Caves console command using {player} and/or {uuid}>"
  second-commands:
    - "<verified SmartSpawner second-place command using {player}>"
  third-commands:
    - "<verified SmartSpawner third-place command using {player}>"
```

Supported placeholders: `{player}`, `{uuid}`, `{plot}`, `{rank}`. Replace the placeholder examples before running a tournament. Empty lists intentionally dispatch nothing and log a warning at award time.

Offline winners are queued and rewarded on their next login. Test all reward commands manually on a staging server first. Commands that Bukkit cannot dispatch are marked `REVIEW` instead of auto-retried to prevent duplicate grants — verify the reward plugin state before running `/clcity reward retry <player>` or `/clcity reward distribute`.

## Important Behaviour and Current Boundaries

- One tournament is active at a time; phases, votes, and judging are persisted in SQLite.
- Plot ownership persists between tournaments until abandoned or removed by an admin (unowned plots only). Tournament entries are tracked separately per event.
- Plot transfer/resale, automatic refunds on admin deletion, historical tournament browsing, and multi-city support are not included in 1.0.0.
- Owned plots can only be abandoned during `SETUP` or `REGISTRATION`.
- Physical map is a custom schematic map; the GUI map shows plot statuses (not a live 3D minimap).
- Voting is tracked by Minecraft account UUID.
- Designed for **Paper**, not Folia.

## Admin Commands

- `/clcity help`
- `/clcity admin`
- `/clcity create`
- `/clcity wand`
- `/clcity createplot <id> <price>`
- `/clcity createroad <id>`
- `/clcity createpublic <id>`
- `/clcity setprice <id> <price>`
- `/clcity deleteplot <id> confirm` *(unowned plots only)*
- `/clcity setspawn`
- `/clcity mapitem`
- `/clcity start [name]`
- `/clcity phase <phase>`
- `/clcity judge <add|remove> <player>`
- `/clcity score <plot> <category> <marks>`
- `/clcity winner <1|2|3> <plot|auto>`
- `/clcity closevote`
- `/clcity results`
- `/clcity reward <distribute|retry <player>>`
- `/clcity info`

## Test Checklist Before Live Deployment

- [ ] Start with the target stack: Paper 26.2, WorldGuard 7.0.19, FAWE 2.15.3, Vault 1.7.3, EssentialsX Economy, and SmartSpawner 1.8.3.
- [ ] Create at least one plot, one road, and one public region.
- [ ] Verify non-owners cannot place/break/interact on a plot, and owners can build only during `BUILDING`.
- [ ] Verify roads and public areas deny build and interact, including buckets and explosions.
- [ ] Test concurrent plot purchase with two accounts; verify only one succeeds and DB/WorldGuard owners match.
- [ ] Restart and verify ownership and WorldGuard regions persist.
- [ ] Test `/plot abandon` and verify membership removal and refund behavior.
- [ ] Test voting rules, judge scoring, leaderboard weighting, and tie ordering.
- [ ] Test reward dispatch with harmless console commands before configuring real prizes.
- [ ] Back up the test world, `plugins/ClasherCity/`, and WorldGuard region data before migrating to live.
