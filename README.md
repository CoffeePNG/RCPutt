# RCPuttPutt

Playable putt-putt (mini-golf) for Purpur: rolling-ball physics, a config-driven surface registry,
party-based rounds, scorecards and per-course leaderboards.

Implements **RC-SPEC-PUTTPUTT-001**, built to **RC-DEV-STD-001**.

- **Target:** Purpur 26.2 / Java 25
- **Depends on:** RCParties (hard), Vault (soft — economy is a stubbed seam in v1)

## Build

The plugin targets Java 25, which may not be your shell default:

```sh
./build.sh package        # wrapper that pins JAVA_HOME to a JDK 25
```

The jar lands in `target/RCPuttPutt-<version>.jar`. SQLite is not shaded — `plugin.yml` declares it
under `libraries:` and Paper resolves it at load time.

## How it plays

The putter is a bow. Face where you want the ball to go, draw to set power, release to putt. The
draw force arrives as 0–1 from `EntityShootBowEvent` and scales straight into launch velocity — no
custom input handling, no charge bar to learn.

The ball is an Item Display: no AI, no gravity to fight, and client-side interpolation between
ticks, so a 20 tps roll looks smooth.

## Physics

One synchronous task at 20 tps steps every ball in motion:

1. Integrate `position + velocity`.
2. Resolve walls one axis at a time, reflecting that axis and scaling by the wall's `restitution`.
3. Sample the surface the ball rolls over.
4. Add any `impulse` (push blocks / boosters).
5. Apply the surface's `friction`.
6. Clamp to `max_velocity`.
7. Move the display with `interpolation_duration = 1`.
8. Rest, hazard and sink checks.

**The tunneling guard is not optional.** `max_velocity` must stay below 1 block/tick — a ball that
travels more than a block between samples can step straight over a 1-block wall and never register
the collision. The config loader rejects anything ≥ 1.0 and falls back to defaults.

**The sink gate is what makes a putt feel earned.** Being within `sink_radius` of the cup is not
enough; a ball moving faster than `max_sink_speed` lips out and rolls on.

Coordinate convention: a ball's `y` is the *top face* of the green it rolls on, so the block it
rolls over is sampled at `y - 1` while walls it can hit stand at `y`. That is what lets water under
the ball be a hazard while stone beside it is a bounce.

## Surfaces

A surface is a named bundle of physics modifiers, and materials map onto surfaces. Sand traps, ice
and push blocks are the same system with different numbers — a new hazard, bumper or pad is a
config entry, not a code change.

```yaml
surfaces:
  sand:
    friction: 0.75
  booster_north:
    type: impulse
    impulse:
      direction: [0, 0, -1]
      strength: 0.35

material_map:
  SAND: sand
  LIGHT_BLUE_CONCRETE: booster_north
```

Types: `roll` (default), `wall`, `hazard`, `impulse`, `hole`. Per-course overrides live in the
course file and win over the global map; anything unmapped rolls like plain green.

Collision assumes axis-aligned (or 45°) walls. That is a course-builder rule, not an accident —
it is what keeps the block-based sweep both cheap and correct.

## Rounds

A round is one party playing one course. Solo is a party of one; nothing else special-cases it.
Starting a round takes an RCParties **activity lock** so members cannot leave or disband out from
under it, and the lock is released on close.

Several parties can share a course. Balls are keyed to `(roundId, player)` and only ever interact
with course geometry — **ball-to-ball collision is off**, which is what keeps simultaneous play
sane rather than a support queue.

## Commands

| Command | Permission |
|---|---|
| `/puttputt start <course>` | `rcputtputt.play` (party leader) |
| `/puttputt leave` | `rcputtputt.play` |
| `/puttputt finish` | `rcputtputt.play` (party leader) |
| `/puttputt scorecard` | `rcputtputt.play` |
| `/puttputt courses` | `rcputtputt.play` |
| `/puttputt leaderboard <course>` | `rcputtputt.play` |

Aliases: `/pp`, `/golf`.

### Building a course in-world

No hand-editing geometry. Pick a course once, then walk the holes:

```
/puttputt admin create <course>       # creates and selects it, in your current world
/puttputt admin select <course>       # switch to an existing one
/puttputt admin settee <hole>         # your position
/puttputt admin setcup <hole>         # your position
/puttputt admin setpar <hole> <par>
/puttputt admin pos1                  # two-corner capture...
/puttputt admin pos2
/puttputt admin setbounds <hole>      # ...becomes the hole's AABB
/puttputt admin surface <material> <surfaceId> [hole]   # global, or per-hole override
/puttputt admin tphole <course> <hole>
/puttputt admin delhole <hole>
/puttputt admin info
/puttputt admin save | reload | delete <course>
```

All admin commands need `rcputtputt.admin`. Edits live in memory until `save`.

A ball that leaves a hole's bounds is treated as a hazard — outside the AABB the block-based
collision model no longer holds, so it is reset with a penalty rather than allowed to roll off.

## Storage

- **Courses:** one YAML file per course under `courses/`. Git-diffable and hand-patchable.
- **Scores:** SQLite behind a `ScoreDao` interface, with a MySQL implementation seam. Every read
  and write runs off the main thread; reads hand their results back on it.

The schema adds a `player_name` column the spec's DDL does not carry — leaderboards have to render
names for offline players, and resolving UUIDs at read time would mean a blocking lookup per row.

## Resource pack

The ball and putter models are selected with `custom_model_data`, **not** `item_model` overrides:
LabyMod and some other clients render `custom_model_data` reliably but ignore `item_model` (the
same finding that drove WeaponMechanics and RCPhone). Pack assets for both are a build dependency —
flag them to whoever owns the pack pipeline. Without them, the items still work; they just render
as a plain snowball and bow.

## Integration seams

- **RCParties** — bound reflectively through the services manager. If its API drifts, RCPuttPutt
  logs it and degrades to solo play rather than throwing on every stroke.
- **Vault** — `economy.enabled` is a v1 flag only; entry fees and payouts are not implemented.
- **Betting** — `RCPuttPuttRoundCompleteEvent` fires once a round is fully torn down, carrying
  totals and par diffs for a future wager layer to settle against. Unfinished scorecards are absent
  from it: an abandoned round is not a result anyone should be paid on. No betting logic lives here.

## Not in v1

Slopes and ramps, moving obstacles, ball-to-ball collision, tournaments and betting,
disconnect-grace round resumption, spectator mode, per-surface particles and SFX.
