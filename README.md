# RCPuttPutt

Turn-based putt-putt (mini-golf) for Purpur: rolling-ball physics, a config-driven surface registry,
ball-to-ball collision, a shot clock, leader-punishing turn order, party rounds, scorecards and
per-course leaderboards.

Implements **RC-SPEC-PUTTPUTT-001 v2**, built to **RC-DEV-STD-001**.

- **Target:** Purpur 26.2 / Java 25
- **Depends on:** RCParties, RCUI (both hard; RCUI in turn requires RCPlatform), Vault (soft —
  economy is a stubbed seam)
- **Author:** Coffee ([@CoffeePNG](https://github.com/CoffeePNG)), RepubliCraft

> **RCUI and RCPlatform are not ours.** They are Bobo's, part of the wider RepubliCraft framework —
> the point being that shared concerns change in one plugin rather than seventeen. Consume their
> published APIs only: never fork, vendor, or patch them here. If something is missing, ask for it
> upstream as a generic primitive.

> **Upgrading an existing server?** `config.yml` migrates automatically on start: missing keys are
> filled from the packaged defaults and your own values kept, and values whose *meaning* changed are
> rewritten (`items.putter.material: BOW` becomes `IRON_SHOVEL` — v2 putters are shovels and a bow
> cannot drive the power meter). Every change is logged. The rewrite drops comments from your
> `config.yml`; the packaged file stays the annotated reference.
>
> **v3 moves messages out of `config.yml` entirely** — RCUI owns the catalog now. A leftover
> `messages:` block is no longer read; the migrator says so loudly rather than deleting your wording,
> so you can re-apply it in RCUI's catalog and then remove the block.

## Build

The plugin targets Java 25, which may not be your shell default:

```sh
./build.sh package        # wrapper that pins JAVA_HOME to a JDK 25
```

The jar lands in `target/RCPuttPutt-<version>.jar`. SQLite is not shaded: `paper-plugin.yml` has no
`libraries:` key, so a `PluginLoader` hands Paper the Maven coordinate and it resolves at load time.

Build order matters — RCPlatform, then RCUI, then RCParties must each be `mvn install`ed locally
before this will compile, since none are published to a public repository.

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

### Teleport pads

Pads are per-hole, so they live in the course file rather than the surface registry — a global
surface has no way to know where to send a ball. Place one with the existing two-corner selection:

```
# corner 1 = the pad you roll onto, corner 2 = where the ball comes out
/puttputt admin addtp          # ball keeps its speed and shoots out the far side
/puttputt admin addtp stop     # ball arrives stopped
/puttputt admin cleartp        # clears the selected hole's pads
```

A ball ignores pads for a second after using one, so two pads facing each other cannot trap it.
Pads outside the hole's region are ignored like any other out-of-region block.

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

No hand-editing geometry. Pick a course once, then walk the holes.

**With the wand (recommended):**

```
/puttputt admin create <course>       # creates and selects it, in your current world
/puttputt admin wand                  # hands you the builder wand
/puttputt admin hole 1                # which hole the wand edits
```

Then, holding the wand:

| Input | Sets |
|---|---|
| left-click a block | corner 1 |
| right-click a block | corner 2 |
| **sneak** + left-click | tee for the selected hole |
| **sneak** + right-click | cup for the selected hole |

…then `/puttputt admin setbounds` (no hole argument needed — it uses the wand's hole),
`/puttputt admin setpar <hole> <par>`, and `/puttputt admin hole 2` for the next one.

Wand marks land on the **top face** of the clicked block, which is exactly the plane the physics
rolls a ball along. That is the reason to prefer the wand: the stand-here commands capture your feet,
so standing on a slab, stair or carpet records a fractional Y and leaves the ball sampling the wrong
ground layer. The wand cannot make that mistake.

**Without the wand**, every mark has a command equivalent:

```
/puttputt admin select <course>       # switch to an existing one
/puttputt admin settee <hole>         # your position
/puttputt admin setcup <hole>         # your position
/puttputt admin setpar <hole> <par>
/puttputt admin pos1 / pos2           # two-corner capture from where you stand
/puttputt admin setbounds [hole]      # ...becomes the hole's AABB
/puttputt admin surface <material> <surfaceId> [hole]   # global, or per-hole override
/puttputt admin tphole <course> <hole>
/puttputt admin delhole <hole>
/puttputt admin info
/puttputt admin check                 # diagnose a hole that is not playing as built
/puttputt admin save | reload | delete <course>
```

**`/puttputt admin check`** is the one to reach for when a hole misbehaves. It reports what every
block in the hole's region actually maps to, and how many blocks are acting as walls at ball height.
The usual cause of *"the ball rolls straight through my walls"* is a wall material that is not in
`material_map` at all — an unmapped block reads as plain green, so the ball treats it as floor. The
second cause is a wall built level with the green instead of one block above it.

All admin commands need `rcputtputt.admin`. Edits live in memory until `save`.

### The bounds are the course, literally

A hole's bounds are not just a leash — they are the **read window**. The physics can only consult
blocks inside a hole's own region (plus one cell, so a perimeter wall standing on the boundary still
bounces). Outside that, every cell reports as wall without the world being touched at all.

Two consequences worth knowing:

- **Blocks elsewhere on your server are completely inert.** Build with green terracotta, sand, water
  or blue ice anywhere outside your courses; a ball can never reach or read them. There is no world
  scan anywhere in the plugin — exactly one `getBlockAt`, called only underneath a rolling ball.
- **You do not strictly need a physical perimeter wall.** The bounds behave as one. Real walls are
  still better for looks and for shaping bank shots.

Set `bounds.confine: false` to sample the world freely and fall back to the plain out-of-bounds
reset (+1 stroke) instead.

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

## Messages and prefixes

Player-facing text lives in RCUI, not in `config.yml`. The plugin ships
`src/main/resources/messages.yml` as its bundled defaults; RCUI copies that into an operator-facing
catalog at `plugins/RCUI/messages/rcputtputt.yml`, which is the file to edit on a live server.

The bundled catalog is a flat message tree plus RCUI's metadata keys — **not** the
`schema-version`/`messages:` wrapper, which is what RCUI writes into the *operator* file:

```yaml
prefix: '<gradient:#FF7F50:#DB7093:#9370DB:#87CEFA>RC Network</gradient> <#A9A9A9>»</#A9A9A9> '
legacy-prefixes:
  - '<dark_green>[<green>PuttPutt</green>]</dark_green>'
  - '<dark_gray>[<green>PuttPutt</green>]</dark_gray>'
putt:
  still-rolling: '<red>Your ball is still rolling.</red>'
```

That prefix is the **RC Network standard** and is deliberately identical across every RC plugin — it
brands the network, not the plugin, so there is no PuttPutt-specific label in it. Don't localise it.

`component(...)` lookups stay unprefixed for action bars and GUI text; `message`/`send`/`broadcast`
apply the prefix. `legacy-prefixes` lets RCUI strip our old branding once during adoption so migrated
wording doesn't end up double-prefixed.

> **RCUI seeds a prefix only once.** It records the adoption in `plugins/RCUI/migrations.yml` and
> thereafter treats the operator catalog as authoritative — correct, since that file is the
> operator's. The consequence: a server that already started an *earlier* RCPuttPutt build keeps
> whatever prefix it adopted, and changing the bundled default will not move it. Fix it by editing
> `prefix:` in `plugins/RCUI/messages/rcputtputt.yml` directly. Verified both ways on a live server.

`Messages` remains the seam rather than calling the bundle from ~60 sites, because it enforces one
rule RCUI cannot know: a `Component` placeholder is trusted and renders, anything else is inserted
as literal text. Course display names are authored config and are meant to render; a player name
must never be able to smuggle MiniMessage into a broadcast.

## Integration seams

- **RCParties** — a hard dependency, compiled against the published `rcparties-api` artifact and
  resolved through the Bukkit services manager. The dependency is `provided`: RCParties shades the
  API into its own jar and ships it at runtime, so bundling a second copy would cause a
  `LinkageError`. **Only `RCParties.jar` goes in `plugins/`, never the API jar.** Build the branch of
  RCParties that matches your target (`claude/running-agentic-i3mb79` for 26.2/JDK 25,
  `legacy/1.21.11` for 1.21.11/JDK 21) and `mvn install` it first — the API is compiled to that
  branch's bytecode level, so the 26.2 artifact will not load on Java 21.

  A player who is not in a party gets a party of one created for them, so solo is never
  special-cased: the round logic has exactly one path for "who is playing". Party snapshots are
  copied, never cached across ticks. `PartyDisbandEvent` and `PartyLeaveEvent` are handled so a
  round cannot outlive its party, and the activity lock is released on every teardown path
  (including a failed start) — a stuck lock would trap the party until an admin runs
  `/party admin clearlocks`.
- **RCUI** — a hard dependency (which in turn requires **RCPlatform**, so both must be installed).
  Resolved via `RCUI.messages(this).register(this, "rcputtputt", "messages.yml")`. `provided` scope:
  RCUI ships its API classes in its own plugin jar. A failed registration disables RCPuttPutt rather
  than letting it run mute.
- **Vault** — `economy.enabled` is a v1 flag only; entry fees and payouts are not implemented.
- **Betting** — `RCPuttPuttRoundCompleteEvent` fires once a round is fully torn down, carrying
  totals and par diffs for a future wager layer to settle against. Unfinished scorecards are absent
  from it: an abandoned round is not a result anyone should be paid on. No betting logic lives here.

## Not in v2

Moving obstacles, tournaments and betting, spectator mode, per-surface particles and SFX.

**Elevation.** The ball rolls on a single plane per hole: its Y comes from the tee and never
changes. Multi-level greens and ramps therefore do not work yet — a hole must be flat. See the two
options sketched in the spec discussion (stepped drops vs. true slopes); slopes are the spec's
deferred item because they add a gravity component along the surface normal, which touches every
part of the integrator.

Turn-based play costs throughput by design — **build shorter courses.** Six holes for a party of
four is a very different length of session from eighteen.
