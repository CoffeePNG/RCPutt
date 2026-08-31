# Handoff: integrating RCUI into RCParties

Written by the RCPuttPutt side after doing this integration end to end, including the parts we got
wrong first. Follow it and you should not repeat them.

**What you get:** all player-facing strings move into RCUI's catalog, RCParties picks up the shared
RC prefix, and operators edit one file per plugin under `plugins/RCUI/messages/`.

---

## 1. Ground rules

RCUI and RCPlatform are **Bobo's**, part of the wider RepubliCraft framework. Consume their
published APIs only — never fork, vendor, or patch them into your repo. If something is missing,
ask for it upstream as a generic primitive rather than working around it locally.

RCUI **requires RCPlatform**, so adopting RCUI means the server runs both.

---

## 2. Dependencies

Neither artifact is published to a public Maven repository. Build them into your local `~/.m2`
first, **in this order** — RCUI compiles against `rcplatform-api`:

```sh
git clone <rcplatform> && (cd rcplatform && mvn -B install -DskipTests)
git clone <rcui>       && (cd rcui       && mvn -B install -DskipTests)
```

Then in `pom.xml`:

```xml
<dependency>
  <groupId>net.republicraft</groupId>
  <artifactId>RCUI</artifactId>
  <version>3.0.0</version>
  <scope>provided</scope>
</dependency>
<dependency>
  <groupId>net.republicraft.platform</groupId>
  <artifactId>rcplatform-api</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>
</dependency>
```

`provided` is not optional. RCUI ships its API classes inside its own plugin jar; bundling a second
copy causes `LinkageError` at runtime. **Only `RCUI.jar` goes in `plugins/`, never the API jar.**

Both target **Java 25**. A dependency built with a different JDK does not fail at build time — it
fails when the server loads the plugin, which is a much worse place to find out.

---

## 3. Declare the dependency

In `paper-plugin.yml`:

```yaml
dependencies:
  server:
    RCUI:
      load: BEFORE
      required: true
      join-classpath: true
```

If you are still on legacy `plugin.yml`, note it has **no `libraries:` equivalent** in
`paper-plugin.yml` — anything you were resolving that way needs a `PluginLoader` instead.

---

## 4. The bundled catalog — read this before writing the file

**This is the part we got wrong.** RCUI's README shows a catalog with `schema-version` and a
`messages:` wrapper. That is the **operator-facing split file RCUI writes for you**. A plugin's
*bundled* resource is a different shape, and getting it wrong makes RCUI reject the catalog and
disable your plugin at boot:

```
RCParties bundled messages entry 'schema-version' must be a string
```

A bundled catalog is a **flat message tree at the root**, plus RCUI's metadata keys. No
`schema-version`, no `messages:` wrapper:

```yaml
# src/main/resources/messages.yml
prefix: '<gradient:#FF7F50:#DB7093:#9370DB:#87CEFA>RCParties</gradient> <#A9A9A9>»</#A9A9A9> '
party:
  created: '<#A9A9A9>Party created.</#A9A9A9>'
  invite-sent: '<#A9A9A9>Invited <white><player></white>.</#A9A9A9>'
lock:
  refused: '<#A9A9A9>Leave <white><activity></white> first.</#A9A9A9>'
```

Rules RCUI's validator enforces (`MessageServiceImpl.validateLeaves`):

- Every leaf must be a **non-blank string** (only `prefix` may be blank).
- Keys must match `[a-z0-9]+([-_.][a-z0-9]+)*` — lowercase, hyphens/underscores/dots.
- Every value must parse as valid MiniMessage.
- The only permitted non-message root keys are `prefix`, `legacy-prefixes`, `removed-paths`.

`legacy-prefixes` strips a prefix baked **inline into message text** during adoption. If your old
prefix was a separate setting concatenated at send time (ours was), you have nothing inline to strip
and should omit the key entirely.

---

## 5. The prefix

The RC house format is a four-stop warm-to-cool gradient plus a dark-gray guillemet:

```
<gradient:#FF7F50:#DB7093:#9370DB:#87CEFA>LABEL</gradient> <#A9A9A9>»</#A9A9A9>
```

Shared network catalogs use `RC Network` as the label, identical across every plugin. RCPuttPutt
deliberately uses its own name instead so the plugin identifies itself in chat. **Pick one and be
deliberate** — that is the whole point of centralising this. Keep the gradient either way.

Body-copy convention, from the shipped RCPhone catalog: chat messages are `<#A9A9A9>` with `<white>`
for interpolated values; semantic colours are reserved for GUI/menu text.

---

## 6. Register and send

```java
private MessageBundle messages;

@Override
public void onEnable() {
    try {
        messages = RCUI.messages(this).register(this, "rcparties", "messages.yml");
    } catch (RuntimeException ex) {
        getLogger().log(Level.SEVERE, "Could not register the RCUI catalog", ex);
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
}
```

Fail loudly. RCUI is a required dependency, so a failed registration means something is genuinely
wrong — running mute is worse than not running.

Then:

| Call | Prefix? | Use for |
|---|---|---|
| `bundle.send(audience, key, ...)` | yes | chat messages |
| `bundle.message(key, ...)` | yes | building a prefixed component |
| `bundle.component(key, ...)` | **no** | action bars, GUI titles, lore, composition |

Wire `RCUI.messages(this).reload()` into your own reload command so message edits apply without a
restart.

---

## 7. Keep a seam, and keep the trust boundary

Do not call the bundle from every site. Keep a thin `Messages` class in front of it — that is where
you enforce something RCUI cannot know:

```java
// A Component value is trusted and renders. Anything else is inserted as literal text.
return value instanceof Component c
        ? Placeholder.component(name, c)
        : Placeholder.unparsed(name, String.valueOf(value));
```

Config-authored text (a party name, a course display name) is *meant* to render as MiniMessage. A
**player name must never be able to smuggle MiniMessage tags into a broadcast.** If you pass
everything through `unparsed` you break the first; if you pass everything through `component` you
open the second. Test both directions.

---

## 8. `migrations.yml` — the redeploy trap

RCUI keeps `plugins/RCUI/migrations.yml` as its own bookkeeping:

```yaml
imports:
  messages:
    rcparties: true          # bundled defaults imported
defaults:
  message-prefixes:
    rcparties: true          # prefix seeded once
```

**RCUI seeds a prefix exactly once**, then treats the operator catalog as authoritative — correct,
since that file belongs to the operator. The consequence: once a server has started your plugin,
changing the bundled `prefix` **will not** update it. You must edit
`plugins/RCUI/messages/rcparties.yml` directly, or clear the `defaults.message-prefixes.rcparties`
flag *and* blank the prefix so RCUI re-seeds.

Verify this yourself both ways — fresh install and already-adopted — before believing a prefix
change shipped.

---

## 9. Migrating operators off your old config

If your strings currently live in `config.yml`, note that `saveDefaultConfig()` only writes when the
file is **absent**, so an in-place upgrade keeps every stale value. Add a config version and, on
upgrade, **warn rather than delete**:

> Message customisation has moved to RCUI. Your config.yml still has a 'messages' block; it is no
> longer read. Re-apply your wording in RCUI's catalog, then delete the block.

Silently dropping an operator's wording is the worst outcome. Also note RCUI's rewrite drops YAML
comments from files it manages — keep the packaged file as the annotated reference.

---

## 10. Verify on a real server

Unit tests will not catch any of the above. Boot the full stack and check the order:

```
RCPlatform → RCUI → RCParties
```

Confirm: no `Disabling RCParties`, the catalog appears at `plugins/RCUI/messages/rcparties.yml`, and
a real command renders with the prefix. Then restart a **second** time with a changed bundled prefix
to see the once-only seeding behaviour for yourself.

---

## Pitfalls, condensed

1. Bundled catalog is a flat tree — **not** the `schema-version`/`messages:` shape from the README.
2. `provided` scope, or `LinkageError`.
3. RCUI drags in RCPlatform; both must be installed and built, RCPlatform first.
4. Prefix seeds once; later changes need a manual edit.
5. `component()` is unprefixed, `send()`/`message()` are not.
6. Keep the Component-vs-String placeholder boundary.
7. Everything targets Java 25.
