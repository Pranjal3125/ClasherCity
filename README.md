# ClasherCity 1.1.0 — Clasher Network

ClasherCity is a Paper plugin for a separate flat city world, paid building plots, WorldGuard protection, physical/GUI maps, tournament phases, public voting, judge scoring, leaderboard calculation and configurable console-command rewards.

## Target environment

- Paper `26.2-10-dev` / API `26.2`
- Java **25** for building and running on Paper 26.x
- Multiverse-Core (required by default for city-world creation/import/loading)
- WorldGuard `7.0.19`
- FastAsyncWorldEdit `2.15.3` (WorldGuard's WorldEdit-compatible API provider on this server)
- Vault `1.7.3` with an economy provider such as EssentialsX Economy
- SmartSpawner `1.8.3` (optional at startup; required only for the reward commands you configure)

WorldGuard and Vault are hard dependencies. FAWE is declared as a soft dependency; ClasherCity does not call FAWE editing features. Existing GriefPrevention claims are not read, changed or migrated.

## Build the JAR

Paper 26.2 uses Java 25. Build with JDK 25 and Maven 3.9+ on a computer with internet access:

```bash
mvn -U clean package
```

The shaded plugin JAR will be:

`target/ClasherCity-1.1.0.jar`

The SQLite JDBC driver is shaded into this JAR. Paper, WorldGuard, FAWE and Vault APIs are provided by the server and are not bundled.

Windows users can run `build.bat`; Linux/macOS users can run `./build.sh` after installing JDK 25 and Maven.

## Install on the TEST server first

1. Stop the test server and back up the test server folder.
2. Confirm that WorldGuard, Vault, an economy provider and FastAsyncWorldEdit are installed.
3. Copy `target/ClasherCity-1.1.0.jar` into the test server's `plugins/` folder.
4. Start the server. ClasherCity asks Multiverse-Core to create/load/import the `clasher_city` flat world and creates `plugins/ClasherCity/config.yml` and `clashercity.db`.
5. Check console for `ClasherCity enabled` and run `/clcity info`.
6. Do **not** install this on the live Clasher Network server until all tests below pass.

## Admin setup

The city world is named `clasher_city` by default and is managed through Multiverse-Core. Keep `city.multiverse.enabled: true` in `plugins/ClasherCity/config.yml`. If the world folder already exists but is not registered in Multiverse, ClasherCity imports it as a NORMAL environment world; if it does not exist, ClasherCity runs the equivalent of `/mv create clasher_city normal --world-type flat --no-structures`. If it is already registered but unloaded, ClasherCity loads it. Existing world data is never regenerated or overwritten. Install Multiverse-Core before ClasherCity. Set `city.multiverse.enabled: false` only if you intentionally want Bukkit's native world creation instead.

1. Go to the city world (or run `/clcity create` to make sure it is loaded).
2. Run `/clcity setspawn` while standing at your intended city spawn.
3. Run `/clcity wand`.
4. Left-click one corner and right-click the opposite corner of a plot.
5. Run `/clcity createplot A-01 10000` (ID and price are examples; use your own prices).
6. Repeat with the other mixed-size plots.
7. Select road corners and run `/clcity createroad ROAD-01`.
8. Select public-area corners and run `/clcity createpublic PARK-01`.
9. Run `/clcity mapitem` and put the resulting filled map in an item frame at the city spawn. `/citymap` also opens a GUI map.

Plot and road/public-area rectangles must not overlap. The selection is X/Z based; WorldGuard regions span the full world height. Plot minimum width/depth is `plots.minimum-size` (default 10 blocks).

## WorldGuard protection model

- City global region: build/interact/chest access denied.
- Plot region: build/interact/chest access allowed for that plot's **members only**; the buyer is added as the WorldGuard owner.
- Road and public regions: build/interact/chest access denied at a higher priority.
- ClasherCity also checks plot ownership and tournament phase for block place/break, buckets and entity/hanging interactions.
- Explosions, fire ignition/burn, pistons and fluid flow across plot boundaries are blocked in the city world.
- The plugin does not create GriefPrevention claims or alter GriefPrevention configuration in survival worlds.

WorldGuard's own bypass permission is separate from `clashercity.bypass`. To let a non-OP admin bypass WorldGuard's city global region, grant the WorldGuard per-world bypass permission for `clasher_city` (for example via LuckPerms):

```text
/lp group admin permission set worldguard.region.bypass.clasher_city true
```

Only give this to trusted admins.

## Player commands

- `/city` — main GUI
- `/city visit` — teleport to the city spawn
- `/plots` — plot browser
- `/citymap` — GUI city map
- `/plot claim <id>` — opens a purchase confirmation GUI
- `/plot enter <id>` — register an already-owned plot for the current tournament
- `/plot home [id]` — teleport to an owned plot
- `/plot info <id>` — plot information
- `/plot abandon <id>` — release an owned plot (refund controlled by config)
- `/buildvote` — vote during the voting phase
- `/buildtop` — leaderboard/results

Plots can only be purchased during the `REGISTRATION` phase. Players can build only on their own plot during the `BUILDING` phase.

## Tournament workflow

1. `/clcity start City Building Championship` — creates a new tournament ID and opens registration.
2. Players buy plots using `/plots` or `/plot claim <id>`. A newly purchased plot is automatically entered into this tournament.
3. A player who already owns a plot from an earlier event can enter it during registration with `/plot enter <id>` (subject to the per-player entry limit). Only plots entered for the current tournament can be voted on, judged or ranked.
4. `/clcity phase building` — enables construction on entered plot owners' own plots only.
5. `/clcity judge add <player>` — registers each judge for this tournament.
6. `/clcity phase voting` — freezes construction and opens player voting.
7. Players use `/buildvote`. One vote per Minecraft account per tournament; a player cannot vote for their own plot. The minimum account playtime is configurable (default 0 minutes).
8. Registered judges use `/clcity score <plot-id> <category> <marks>` for each category:
   - `creativity` max 25
   - `exterior` max 25
   - `interior` max 20
   - `detailing` max 15
   - `theme` max 15
9. Optional admin override: `/clcity winner <1|2|3> <plot-id>` forces a podium position; `/clcity winner <rank> auto` restores the computed order.
10. `/clcity closevote` finalizes results, announces the top 3 and queues configured rewards.
11. `/buildtop` displays the leaderboard.

Default score weighting is 50% normalized player-vote share + 50% average completed judge score. Both weights and minimum playtime are configurable. Judges need to submit all five categories for a plot for their score to count.

To re-open or move a phase manually, use `/clcity phase <setup|registration|building|voting|results>`.

## Prize commands — important setup required

ClasherCity deliberately does **not** guess the private/third-party command syntax for Infinity Caves or SmartSpawner. Open those plugins' help/config on the test server and put the verified console commands in `plugins/ClasherCity/config.yml`:

```yaml
rewards:
  first-commands:
    - "<verified Infinity Caves console command using {player} and/or {uuid}>"
  second-commands:
    - "<verified SmartSpawner second-place command using {player}>"
  third-commands:
    - "<verified SmartSpawner third-place command using {player}>"
```

Supported placeholders: `{player}`, `{uuid}`, `{plot}`, `{rank}`. Do not leave the angle-bracket examples in the config; replace them with real commands first. Empty lists intentionally do not dispatch anything and produce a console warning at award time.

Offline winners are queued and their reward commands are dispatched when they next join. The command dispatcher can confirm whether Bukkit found/accepted a command, but it cannot verify the third-party plugin actually granted the item. Test all three reward commands manually on the test server before the tournament. If a multi-command reward partially succeeds, verify the reward plugin state before using `/clcity reward retry <player>` to avoid duplication.

If commands were blank when the results were finalized, set them in config and run `/clcity reward distribute`. A command Bukkit cannot dispatch is marked `REVIEW`, not auto-retried, to reduce accidental duplicate grants. Verify whether the item was actually granted before `/clcity reward retry <player>`.

## Important behaviour and current boundaries

- One tournament is active at a time; tournament phase/votes/judging are persistent in SQLite.
- Plot ownership is persistent and remains assigned between tournaments until the owner abandons it or an admin removes an unowned plot. Tournament entries are tracked separately: buying a plot during registration enters it automatically; an already-owned plot must use `/plot enter <id>` for each new tournament.
- Plot transfer/resale, automatic refunds on admin deletion, historical tournament browsing, and multi-city support are not included in 1.0.0.
- An owned plot can only be abandoned during SETUP or REGISTRATION. During REGISTRATION, its current tournament entry is removed; after a completed tournament, the stored result entry is retained.
- Physical map is a custom schematic map; the GUI map shows plot statuses. It is not a full live 3D minimap.
- Voting uses Minecraft account UUID. It prevents duplicate votes per account, but cannot guarantee detection of alternate accounts.
- The plugin is designed for Paper, not Folia.
- The exact SmartSpawner/Infinity Caves item command must be configured by the server owner.

## Admin commands

- `/clcity help`
- `/clcity admin`
- `/clcity create`
- `/clcity wand`
- `/clcity createplot <id> <price>`
- `/clcity createroad <id>`
- `/clcity createpublic <id>`
- `/clcity setprice <id> <price>`
- `/clcity deleteplot <id> confirm` (unowned plots only)
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

## Test checklist before live deployment

1. Start with the exact test-server versions: Paper 26.2, WorldGuard 7.0.19, FAWE 2.15.3, Vault 1.7.3, EssentialsX economy and SmartSpawner 1.8.3.
2. Create at least one plot, one road and one public region.
3. Verify a non-owner cannot place/break/interact on a plot; owner can build only in `BUILDING`.
4. Verify roads/public areas deny build and interact, including buckets and explosions.
5. Buy a plot with two test accounts; verify only one purchase succeeds, balance is charged once and DB/WorldGuard owner agree.
6. Restart and verify ownership and WorldGuard regions persist.
7. Try `/plot abandon`; verify membership removed and refund only if configured.
8. Test every vote rule, judge score validation, leaderboard weighting and tie ordering.
9. Configure test reward commands that are harmless (e.g. a console message command) before testing real items.
10. Back up the test world, `plugins/ClasherCity/` and WorldGuard region data before migrating to live.
