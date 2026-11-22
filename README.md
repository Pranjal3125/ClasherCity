<div align="center">

# ClasherCity

### City Plots · Building Tournaments · Player Voting · Judge Scoring

A Paper 26.2 plugin for a dedicated flat city world with paid building plots, WorldGuard protection, physical & paginated GUI maps, tournament phases, public voting, judge rubric scoring, leaderboards, and queued rewards.

[![Maven build](https://github.com/Pranjal3125/ClasherCity/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Pranjal3125/ClasherCity/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Paper%2026.2-313131?logo=minecraft)
![Storage](https://img.shields.io/badge/Storage-SQLite-blue?logo=sqlite)
![Version](https://img.shields.io/badge/Version-1.1.0-brightgreen)

[Source](https://github.com/Pranjal3125/ClasherCity) · [Issues](https://github.com/Pranjal3125/ClasherCity/issues)

</div>

> **Note:** Build and test on a staging server before deploying to a live network.

## Contents

- [Target Environment](#target-environment)
- [Build & Tests](#build--tests)
- [Admin Setup](#admin-setup)
- [WorldGuard Protection Model](#worldguard-protection-model)
- [Player Commands](#player-commands)
- [Tournament Workflow](#tournament-workflow)
- [Prize Commands](#prize-commands--important-setup-required)
- [Admin Commands](#admin-commands)
- [Pre-Deployment Checklist](#test-checklist-before-live-deployment)

---

## Target Environment

- Paper `26.2` (Java **25**)
- WorldGuard `7.0.18+` (hard dependency)
- Vault `1.7+` with an economy provider such as EssentialsX Economy (hard dependency)
- FastAsyncWorldEdit *(optional soft dependency)*
- SmartSpawner *(optional; required only if used in configured reward commands)*

Existing GriefPrevention claims are never read, changed, or migrated.

## Build & Tests

```bash
mvn --batch-mode clean package
```

Compiles on Java 25 (`-Xlint:deprecation`), runs the JUnit 5 + SQLite test suite, and outputs the shaded JAR to:

```text
target/ClasherCity-1.1.0.jar
```

The SQLite JDBC driver is shaded into this JAR. Paper, WorldGuard, and Vault APIs are provided by the server.

## Admin Setup

The city world is named `clasher_city` by default and is auto-created/loaded from `config.yml`.

1. Go to the city world (or run `/clcity create` to make sure it is loaded).
2. Run `/clcity setspawn` while standing at your intended city spawn.
3. Run `/clcity wand`, left-click corner 1 and right-click corner 2.
4. Run `/clcity createplot A-01 10000` (replace ID and price).
5. Select road corners and run `/clcity createroad ROAD-01`; select public-area corners and run `/clcity createpublic PARK-01`.
6. Run `/clcity mapitem` and place the filled map in an item frame at the city spawn. `/citymap` also opens a paginated GUI map.

## WorldGuard Protection Model

- **City global region:** build, interact, chest access, PvP, and explosions denied.
- **Plot region:** build, interact, and chest access allowed for that plot's **members only**; the buyer is added as WorldGuard owner.
- **Road and public regions:** build, interact, and chest access denied at higher priority (`20`).
- ClasherCity also enforces plot ownership, interactable-block protection (regardless of held item), fluid flow boundaries, pistons, fire, explosions, and tournament `BUILDING` phase rules.

## Player Commands

All commands support full tab completion.

| Command | Description |
|---|---|
| `/city` | Open the main city GUI |
| `/city visit` | Teleport to the city spawn |
| `/plots` | Open the paginated plot marketplace |
| `/citymap` | Open the paginated GUI city map |
| `/plot claim <id>` | Open a purchase confirmation GUI (shows post-purchase balance) |
| `/plot enter <id>` | Register an already-owned plot for the current tournament |
| `/plot home [id]` | Teleport to an owned plot |
| `/plot info <id>` | View plot information |
| `/plot abandon <id>` | Release an owned plot (refund controlled by config) |
| `/buildvote` | Open paginated voting GUI (**Left-Click** to vote, **Right-Click** to visit & inspect!) |
| `/buildtop` | View the tournament leaderboard and results |

## Tournament Workflow

1. `/clcity start City Building Championship` — starts a new tournament and opens `REGISTRATION`.
2. Players buy plots via `/plots` or `/plot claim <id>` (automatically entered), or enter existing plots with `/plot enter <id>`.
3. `/clcity phase building` — enables construction on entered plots.
4. `/clcity judge add <player>` — registers judges (non-admin judges with `clashercity.judge` or tournament registration can submit scores directly via `/clcity score`).
5. `/clcity phase voting` — freezes construction and opens `/buildvote`.
6. Judges score entered plots with `/clcity score <plot-id> <category> <marks>` (`creativity`, `exterior`, `interior`, `detailing`, `theme`).
7. Optional podium override: `/clcity winner <1|2|3> <plot-id|auto>`.
8. `/clcity closevote` finalizes results, announces top 3, and queues configured rewards.

## Prize Commands — Important Setup Required

Configure verified console commands in `plugins/ClasherCity/config.yml`:

```yaml
rewards:
  first-commands: []
  second-commands: []
  third-commands: []
```

Supported placeholders: `{player}`, `{uuid}`, `{plot}`, `{rank}`. Offline winners are queued in SQLite and rewarded on their next join. Failed commands are marked `REVIEW` until an admin verifies and runs `/clcity reward retry <player>`.

## Admin Commands

- `/clcity help` | `/clcity admin` | `/clcity info` | `/clcity reload`
- `/clcity create` | `/clcity tp` | `/clcity setspawn` | `/clcity wand` | `/clcity mapitem`
- `/clcity createplot <id> <price>` | `/clcity createroad <id>` | `/clcity createpublic <id>`
- `/clcity setprice <id> <price>` | `/clcity deleteplot <id> confirm`
- `/clcity start [name]` | `/clcity phase <phase>` | `/clcity voting` | `/clcity closevote`
- `/clcity judge <add|remove> <player>` | `/clcity score <plot> <category> <marks>`
- `/clcity winner <1|2|3> <plot|auto>` | `/clcity results`
- `/clcity reward <distribute|retry <player>>`

## Test Checklist Before Live Deployment

- [ ] Start with Paper 26.2, WorldGuard, Vault + economy provider.
- [ ] Create at least one plot, road, and public region; verify non-owner build/interact protection.
- [ ] Test plot purchase, restart persistence, `/plot enter`, and `/plot abandon`.
- [ ] Test non-admin registered judge scoring via `/clcity score`.
- [ ] Test `/buildvote` (Left-Click vote, Right-Click visit), `/clcity closevote`, and `/buildtop`.
- [ ] Test reward dispatch with harmless console commands before configuring production prizes.
