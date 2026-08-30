# RCPuttPutt

Turn-based putt-putt (mini-golf) for Purpur: rolling-ball physics, a config-driven surface registry,
ball-to-ball collision, a shot clock, leader-punishing turn order, party rounds, scorecards and
per-course leaderboards.

Implements **RC-SPEC-PUTTPUTT-001 v2**, built to **RC-DEV-STD-001**.

- **Target:** Paper/Purpur 1.21.11 / Java 21
- **Depends on:** RCParties (hard), Vault (soft — economy is a stubbed seam in v1)

> **This is the `legacy/1.21.11` branch.** The mainline targets Purpur 26.2 / Java 25; this branch
> exists for servers still on 1.21.11. The two differ only in `pom.xml`, `plugin.yml`'s
> `api-version` and `build.sh` — **every line of Java is identical**, because each API the plugin
> touches (`DataComponentTypes.CUSTOM_MODEL_DATA`, `ItemDisplay` interpolation, the Brigadier
> lifecycle registrar, `ItemStack.editPersistentDataContainer`) has the same shape in both. Port
> fixes across with `git cherry-pick`; if a change ever needs different code per version, that is
> the signal to introduce a compatibility seam rather than let the branches drift.

## Build

```sh
./build.sh package        # wrapper that pins JAVA_HOME to a JDK 21
```

The jar lands in `target/RCPuttPutt-<version>.jar`. SQLite is not shaded — `plugin.yml` declares it
under `libraries:` and Paper resolves it at load time.

## How it plays

**One ball is struck at a time.** You can walk the course freely, but you can only putt on your
turn. When it comes round you are teleported to your ball facing the cup, handed the putter, and
put on a shot clock.

The putter is a **shovel**. Hold right-click to charge — a **boss bar power meter** shown to the
whole party sweeps 0→100→0 — and release to strike. A shovel has no vanilla use animation, so the
putter carries a `consumable` component purely to make "still holding" observable; the plugin owns
the charge curve outright, which is the point, because it is then fully tunable.

The ball is an Item Display rendering a snowball: no AI, no gravity to fight, and client-side
interpolation between ticks, so a 20 tps roll looks smooth.

### Turn order punishes the leader

Hole 1 is random. Every hole after that re-sorts **ascending by total strokes, so the player in the
lead putts first** — reading the green for everyone else with no information of their own. That is
deliberate: it hands trailing players a small edge and keeps rounds close. Set
`turn-order.mode: descending` to reward the leader instead.

A turn ends when the player strikes, or when the 30-second shot clock expires (forfeit, +1 stroke).
Three forfeits in a row caps the player out on that hole so one AFK player cannot stall the round.
No new turn begins until **every** ball has come to rest, so knock-ons fully resolve first.

## Physics

One synchronous task at 20 tps steps every ball in motion:

1. Integrate `position + velocity`.
2. Resolve walls one axis at a time, reflecting that axis and scaling by the wall's `restitution`.
3. Ball-to-ball collision, swept along the path travelled (see below).
4. Sample the surface, apply any `impulse` or `current`, then `friction`.
5. Clamp to `max_velocity`, move the display, then rest / hazard / sink checks.

**The tunneling guard is not optional.** `max_velocity` must stay below 1 block/tick — a ball that
travels more than a block between samples can step straight over a 1-block wall and never register
the collision. The config loader rejects anything ≥ 1.0 and falls back to defaults.

**Ball-ball collision is swept, not endpoint-tested.** At putting speeds a ball covers more ground
in one tick than the contact zone is wide, so checking only where it *ended up* lets it pass clean
through a resting ball — the same tunneling failure the wall guard exists to prevent. The engine
solves for the earliest point along the tick's travel at which the two come into contact. Collision
is affordable at all only because play is turn-based: at most one ball is ever under power.

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

Types: `roll` (default), `wall`, `hazard`, `impulse`, `current`, `hole`. Per-course overrides live
in the course file and win over the global map; anything unmapped rolls like plain green.

A **river** is a `current`: a wide, sustained push paired with `preventRest: true`, which skips both
friction and the rest check so the ball drifts downstream instead of parking mid-water. It only
settles once it leaves the current.

Collision assumes axis-aligned (or 45°) walls. That is a course-builder rule, not an accident —
it is what keeps the block-based sweep both cheap and correct.

## Rounds

A round is one party playing one course, everyone on the same hole, taking turns. Solo is a party of
one; nothing else special-cases it. Starting a round takes an RCParties **activity lock** so members
cannot leave or disband out from under it, and the lock is released on close.

A hole ends when every player has sunk or hit `max-strokes-per-hole` (default 10). A ball knocked
into the cup by someone else counts as sunk for its owner — set `ball-collision.allow-knock-in: false`
for purists.

Several parties can share a course. Balls are keyed to `(roundId, player)`, and collision is
**intra-round only** — balls never interact across rounds.

### Crash resilience

Live rounds snapshot to SQLite every 15s and on clean shutdown. On startup a round is resumed only
if **every** original member is back online inside the resume window (default 10 minutes) —
a half-restored round with missing players creates more edge cases than it solves. Anything else is
archived. Parties themselves are not persisted.

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

The ball (snowball) and putter (shovel) models are selected with `custom_model_data`, **not**
`item_model` overrides:
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

## Not in v2

Slopes and ramps, moving obstacles, tournaments and betting, spectator mode, per-surface particles
and SFX.

Turn-based play costs throughput by design — **build shorter courses.** Six holes for a party of
four is a very different length of session from eighteen.
