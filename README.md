<p align="center">
  <img src="/stuff/bigger-icon.jpg" alt="Nexo logo" title="cool freaking logo :D">
</p>

## Overview

**Nexo** is a modern scripting plugin for Minecraft servers, based off of [Skript.](https://github.com/skriptlang/skript) Nexo lets server staff write server logic using familiar **Python** or **JavaScript** syntax, while keeping the things that Skript is well known for.

### Design
* **Not Backwards Compatible:** Nexo is a ground-up redesign and does NOT support `.sk` files.
* **Minimal Bloat:** Redundant or otherwise niche Skript expressions, events, effects (e.g., `[the] (duplicat(e|ing|ion)|clon(e|ing)) cool[ ]down [time] [of %living entities%]`, `[on] bat toggle sleep`, `make [the] brewing stand consume [its|the] fuel`) are removed to keep Nexo fast.
* **Built-In Addon Ecosystem:** Popular addons are reimagined as first-party modules with Nexo syntax.
[Skellett](https://docs.skunity.com/syntax/search/addon:skellett), [SkQuery](https://docs.skunity.com/syntax/search/addon:skquery), [skRayFall](https://docs.skunity.com/syntax/search/addon:skrayfall), [TuSKe](https://docs.skunity.com/syntax/search/addon:tuske), [SkBee](https://docs.skunity.com/syntax/search/addon:skbee) and more are here.

---

## Syntax

Every `.nx` file must declare its syntax mode on the very first line using either `using py` or `using js`.

### Python Mode (`using py`) example

```python
using py

require(skbee)

@event("player_join")
def on_join(event):
    player = event.player
    player.send_message(f"&aWelcome to the server, {player.name}!")
    player.give(item("diamond_sword", amount=1))
```

### JavaScript Mode (`using js`) example

```javascript
using js

require(skbee);

event("player_join", (e) => {
    const player = e.player;
    player.sendMessage(`&aWelcome to the server, ${player.name}!`);
    player.give(item("diamond_sword", { amount: 1 }));
});
```

---

## Module & Addon System

In Nexo, addons are treated as modular libraries rather than global syntax dumps. You import them at the top of your script using `require()`.

### Planned Built-In Modules
Nexo will feature custom, syntax-unified versions of classic Skript addons:
* **Skellett** (`skellett`)
* **SkQuery** (`skquery`)
* **skRayFall** (`skrayfall`)
* **TuSKe** (`tuske`)
* **SkBee** (`skbee`)

### Conflict Resolution: Aliases vs. Priorities
To prevent syntax collisions between modules, Nexo enforces two mutually exclusive import rules: **Aliases** or **Priorities**. You may use one or the other per module, never both.

#### 1. Basic Import
```javascript
require(skellett)
```

#### 2. Namespaced Import (`with alias`)
```javascript
require(skquery) with alias query

// Usage example:
query.openGui(player, "Server Menu", 3)
```

#### 3. Priority Import (`with priority`)
If two modules define the same function or event, the module with the higher priority takes over. `1` (highest) to `5` (lowest).
```javascript
require(tuske) with priority 1
require(skbee) with priority 2
```

> **Note:** Combining `with alias` and `with priority` is invalid, as namespaced modules cannot conflict with global modules.

---

## Proudly licensed under MIT
