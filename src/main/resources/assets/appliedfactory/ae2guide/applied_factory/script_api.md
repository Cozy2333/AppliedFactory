---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: Script API
  icon: appliedfactory:factory_controller
---

# Applied Factory Script API

> This document explains the controller's programming model, runtime semantics and common workflows. The complete types and function signatures are defined by `applied_factory.d.ts` in the workspace; see `demo.ts` for runnable examples.

## 1. Starting from a runnable program

Controller scripts use TypeScript. The program below finds a furnace on the controller front, registers an iron-smelting processing pattern with AE2, and on each order pushes the inputs fully into the furnace, waits, then returns the extractable products to the ordering network.

```ts
const production = network("front");

registerProcessingPattern(
  [{
    orderNetwork: "north",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)]
  }],
  function* (order) {
    const furnace = production.buses.find(bus =>
      bus.target.id === "minecraft:furnace"
    );
    if (furnace === undefined) return;

    yield order.input.pushExactlyInto(furnace);
    yield sleep(200);
    yield furnace.extract().to(order.network);
  }
);
```

Recommended order of use:

1. Create or select a `.ts` file under `appliedscripts/`;
2. Type-check it in your IDE using `applied_factory.d.ts`;
3. Upload through the GUI, or first run a probe via MCP's `appliedfactory_execute` (inline `code` is precompiled as a virtual file in the `appliedscripts/` root, with no file written to disk);
4. Once the network, buses, machines and resources are confirmed, upload the production program.

## 2. Compile, upload and run lifecycle

Uploading does not execute TypeScript directly; it proceeds in order:

1. Save the raw `.ts` file in the workspace;
2. Expand relative JSON default imports and the `require_recipes()` macro;
3. Transpile to ES2022 JavaScript;
4. Store the editable TypeScript, the executable JavaScript and the workspace-relative path in world-level data;
5. GraalJS evaluates the JavaScript once, registering patterns and starting workflows.

The upload stage only guarantees that the syntax can be transpiled; full type checking is the IDE's responsibility. Source code or expanded executable code over 128k characters is rejected. Remote source without a matching local backup must be pulled first and cannot be overwritten directly.

## 3. Core concepts and global entry points

| Concept | Purpose |
| --- | --- |
| `Network` | An AE network on one controller side; both queryable and usable as a resource target |
| `Bus` | A Factory Bus on the network; accesses the machine or world block it faces |
| `Resource` | An immutable resource handle with origin, channel, key and amount |
| `ResourceArray` | A read-only resource array preserving resource slot order, with bulk transfer methods |
| `Action` | A yieldable action; transfer actions also offer `.now()` for a single attempt |
| `Order` | An AE2 processing order containing input resources and the ordering network |

Common global entry points:

- `network(side)`: gets the network on one controller side;
- `item(...)` / `fluid(...)`: construct common flat resource queries and specs;
- `registerProcessingPattern(...)`: register processing patterns and order handlers;
- `go(...)`: start an independent generator workflow;
- `sleep(ticks)`: create a wait action;
- `log(message)`: write to the controller log;
- `rename(...)` / `itemNbt(...)`: item-only helpers.

## 4. Network and Bus

### 4.1 Sides and networks

`network(side)` accepts the absolute world directions `up/down/north/south/west/east`, and the directions `front/back/left/right` relative to the controller front. Left and right use the perspective of a player standing in front and looking at the controller. Relative directions are resolved to absolute faces when the handle is created, so `network("front").side` returns the actual world direction. `PatternDefinition.orderNetwork` follows the same rules.

Do not compare two `Network` wrapper objects with `===`. To compare whether they currently belong to the same AE grid, use:

```ts
if (network("left").isSameNetwork(network("right"))) {
  log("both sides are on the same AE grid");
}
```

`isSameNetwork` returns `false` when either side is offline; two disconnected sides are never considered the same. The result tracks the live topology as grids merge or split.

### 4.2 Bus discovery and stable handles

`network.buses` returns the current topology snapshot on every read. `network.onChange(callback)` is called synchronously only when a Factory Bus joins or leaves the AE network; ordinary machine block updates do not trigger it. The callback can rebuild cached handle arrays but cannot `yield`.

### 3.1 Proactive network orders

`network.canOrder(resourceSpec)` synchronously checks whether the network currently exposes a crafting pattern for that key. It does not run a full crafting simulation, so it does not guarantee sufficient ingredients or a free CPU.

`network.order(resourceSpec)` returns a yield-only crafting Action. The scheduler computes the AE crafting plan asynchronously; it retries once per second while ingredients or a CPU are unavailable, and once the order completes the yield expression returns a `Resource` whose origin is `escrow`, ready to be transferred to a network or bus:

```ts
go(function* () {
  const storage = network("back");
  const requested = item("minecraft:iron_ingot", 16);
  if (storage.canOrder(requested)) {
    const result: Resource = yield storage.order(requested);
    yield result.to(network("front"));
  }
});
```

Script workflows and proactive orders do not persist across controller unload or script reload; AE orders still running at that point are canceled, and partial outputs already received in controller escrow are returned following the resource recovery rules.

`Network`, `Bus`, storage endpoints and resource origins store stable addresses, and are re-resolved on every query or action execution:

- After replacing a machine in place, old `Bus` handles operate on the new machine currently faced;
- When a machine, network or chunk is temporarily unavailable, waitable actions keep waiting;
- When resources are short or the target is full, waitable actions keep waiting;
- After the bus is removed, the handle cannot resolve and waitable actions keep waiting.

If a script depends on the target machine type, re-upload the script, or re-enumerate the buses in `onChange`.

### 4.3 Capabilities and block snapshots

`bus.channels` returns the AE channel IDs the target face currently supports for input or output, such as `"ae2:i"` and `"ae2:f"`. It reflects capabilities rather than current contents and covers all registered extension channels; appearing in the array only means at least one of input or output is supported, not necessarily both.

`bus.target` is a `BlockView` snapshot containing the block ID, state, coordinates, properties, block entity type and NBT. Compare two block positions with `isSameBlock()`; do not compare JavaScript wrapper object identity.

## 5. Resource and inventory queries

### 5.1 Resource identity

`Resource` is an immutable exact-origin handle `(origin, channel, key, amount)`. Creating a handle does not immediately extract or lock inventory. If another device consumes the resource before execution, waitable actions wait for the same AE key to satisfy the amount again; a handle does not track a particular slot or entity and does not provide an exclusive lock.

`channel` is the registry ID of `AEKeyType#getId()`, and `key` is the NBT for that type's codec. Built-in items and fluids use `"ae2:i"` and `"ae2:f"` respectively; extension channels need no new script resource type.

`ResourceArray` extends `ReadonlyArray<Resource>` and supports indexing, `find()`, `filter()`, `map()` and `for...of`. Order inputs preserve the pattern's slot order: duplicate resources are not merged and can be routed separately; bulk transfers still operate on the total amount.

### 5.2 `extract()`: resources extractable from a given face

`Network`, `Bus` and `Slot` provide one flat `extract(query?)` query and always return a `ResourceArray`:

```ts
const firstChannel = network("north").extract();
const items = network("north").extract({ channel: "ae2:i" });
const coal = network("north").extract({ channel: "ae2:i", id: "minecraft:coal" });
const ingots = network("north").extract({
  channel: "ae2:i",
  id: "minecraft:*_ingot",
  amount: 8,
});
const tagged = network("north").extract({
  channel: "ae2:i",
  $tag: "c:ingots",
  amount: 8,
});
```

- `channel` is optional but always exact. When omitted, the first matching resource selects the channel and resources from every other channel are silently ignored, so one result never spans channels;
- `$tag` is optional and matches one exact tag ID. Wildcards are not accepted in tag IDs;
- Every other field is matched recursively against the AE key's encoded fields. Missing fields are ignored, so `{ id: "minecraft:paper" }` also matches renamed paper;
- String fields support `*` (any sequence) and `?` (one character) glob wildcards. Regular expressions are not supported;
- `amount` is a positive integer cap applied independently to each exact resource: every returned entry has `min(available, amount)`. When omitted, each resource keeps its full extractable amount;
- Returns an empty array when nothing matches;
- Throws a runtime error for an unregistered channel, invalid tag ID, invalid amount, or unknown `$` operator.

### 5.3 `storage()`: whole-target inventory snapshot

`storage(channel?)` is a read-only inventory query. On a `Bus` it views every non-empty slot of the target with no face, independent of the bus face's input/output restrictions; for example a furnace exposes its input, fuel and output slots at once.

```ts
const contents = furnace.storage();
const itemContents = furnace.storage("ae2:i");
```

`extract()` is suited to getting what the face can actually extract; `storage()` is suited to diagnosing machine jams or observing in-flight inputs. `storage()` still returns source handles and can `.to()`, but calling `pushExactlyInto()` on resources that may not be extractable from that face is not recommended, since it may wait forever.

### 5.4 Single-slot handles `bus.slot(n)`

`bus.slot(n)` returns a handle to the target container's `n`-th item slot (0-based), which can bypass the face's input/output restrictions and operate on one slot directly:

```ts
const output = furnaceBus.slot(2);        // furnace output slot
const ingots = output.extract();          // take only the items in that slot
yield ingots.to(order.network);
yield someResource.pushExactlyInto(output);
```

- The index is resolved against the target block's whole item inventory (`ae2:i`), independent of the bus face; a furnace's input/fuel slots usually cannot be extracted from that face, but are directly accessible through `slot()`;
- Slot handles cover items only; queries with another channel return an empty array;
- `exists` means the bus resolves and the slot number is valid; when out of range or after the bus is removed, related transfer actions keep waiting as if the resource did not exist;
- As a transfer target it inserts only into that slot; it keeps waiting when the slot is full or the item is incompatible;
- A resource obtained from `slot.extract()` has `origin.kind` of `"slot"`, and `origin.endpoint` is that slot handle;
- In game, pointing at a Factory Bus makes Jade list every item slot of the container as item icons (empty slots shown as empty), while WTHIT / The One Probe show a text list; the numbering matches `slot(n)`.

## 6. Actions and resource transfers

`yield action` inside a generator attempts once immediately on the current tick. On success execution continues; on failure it retries every tick until it succeeds. `action.now()` attempts exactly once and returns the result, without entering the wait scheduler.

### 6.1 Partial transfer `to(target)`

`resource.to(target)` moves whatever is currently possible each time. It waits when the source is short or the target has no capacity; after partial progress it keeps only the not-yet-moved `remaining`.

```ts
yield products.to(order.network);
const remaining = products.to(order.network).now();
```

On a `ResourceArray`, each resource advances independently, so one blocked resource does not hold up the others. An empty array succeeds immediately and is a safe no-op. `.now()` returns the still-unmoved resource handle or array, or `null` when everything is done.

### 6.2 Atomic transfer `pushExactlyInto(target)`

`pushExactlyInto` requires the source to hold the full amount and the target to accept the full amount at once; if either condition fails, nothing moves.

```ts
yield order.input.pushExactlyInto(machine);
const inserted = resource.pushExactlyInto(machine).now();
```

On an array, the whole array is one indivisible batch: all resources must be satisfiable at the same time. An empty array succeeds immediately. `.now()` returns a boolean.

### 6.3 Sleeping and concurrency

`yield sleep(ticks)` waits for the given ticks. Multiple workflows and order handlers all advance on the server main thread; when several actions compete for the same inventory, the first to execute takes the resource and later ones wait when it is short.

## 7. Processing patterns and workflows

`registerProcessingPattern(definitions, handler)` registers an AE2 processing pattern for each definition. `orderNetwork` decides which side receives orders; `inputs` and `outputs` are resource specs. The handler receives:

- `order.input`: the `ResourceArray` for this order, preserving input slot order;
- `order.network`: a live network handle for the network that placed the order.

`order.cancel()` returns whether the cancel was issued successfully. After success, handlers spawned by the same parent AE request stop, unused `order.input` still held in controller escrow is returned to the ordering network, and material not yet used in the AE CPU is returned by AE2. Material already transferred into an external machine, dropped into the world, or consumed by a synchronous operation is no longer under controller escrow and cannot be automatically recovered.

AE2 considers a processing task complete once all pattern outputs have returned to the ordering network, regardless of which machine produced them. It is usually simpler to have the order handler only push inputs reliably, then let an independent `go(function* () { ... })` workflow continuously pull outputs. Multi-step processing should usually be split into multiple patterns and left for AE2 to orchestrate; high-frequency small orders add I/O overhead, so inputs and outputs can be scaled up with machine throughput.

## 8. Items and world interaction

`rename`, `use`, `place`, `drop`, `break` and `redstone` are all one-shot synchronous operations.

### 8.1 NBT and renaming

```ts
const sword = network("north")
  .extract({ channel: "ae2:i", id: "minecraft:diamond_sword", amount: 1 })[0];

if (sword !== undefined) {
  const data = itemNbt(sword); // { id, count, components }
  const named = rename(sword, "Factory Pickaxe");
}
```

`item(id, amount?, components?)` and `fluid(id, amount?, components?)` construct flat objects for the `ae2:i` and `ae2:f` channels. They do not parse IDs or validate fields, so string fields such as `id` can use `*` and `?` globs. Without `amount`, the object is an unbounded query for `extract()`; with a positive amount and codec-valid exact key fields it can also declare a processing pattern or crafting order. Other channels can be queried by passing their flat fields directly, for example `{ channel: "addon:energy", id: "addon:*" }`. Query matching treats missing fields as unconstrained; `channel` and `$tag` are exact. Actual transfers, pattern registration and order creation validate their resource specs at use time. To provide components while omitting the amount, use `item(id, undefined, components)` or `fluid(id, undefined, components)`. `itemNbt()` accepts `ae2:i` resources only.

`rename()` immediately replaces the old key with the new one in the original source; it returns `null` when resources are insufficient, and a new source handle on success.

### 8.2 Using, placing, dropping and breaking

```ts
go(function* () {
  const storage = network("north");
  const bus = storage.buses[0];
  if (bus === undefined) return;

  bus.use(true); // sneak empty-handed use of the target

  const blocks = storage.extract({ channel: "ae2:i", id: "minecraft:stone", amount: 1 });
  if (blocks[0] !== undefined) bus.place(blocks[0], false);

  const tools = storage.extract({ channel: "ae2:i", id: "minecraft:diamond_pickaxe", amount: 1 });
  if (tools[0] !== undefined) {
    let tool = tools[0];
    let drops, success;
    [tool, drops, success] = bus.break(tool);
  }

  const cobble = storage.extract({ channel: "ae2:i", id: "minecraft:cobblestone", amount: 16 });
  if (cobble[0] !== undefined) bus.drop(cobble[0]);
});
```

- `use(item?, shift?)` first tries to right-click the target, then falls back to the item's in-air use. An item call returns `[current, success]`; on success `current` is the remainder written back to the source (for example the damaged tool), or `null` when fully consumed; failure returns the original handle and `false`;
- `place(block, shift?)` requires `amount === 1` and a BlockItem resource;
- `drop(item)` deducts the resource exactly and spawns an item entity along the bus facing;
- `break(tool)` returns `[tool, drops, success]`; on success `tool` is the damaged tool written back to the source (`null` when destroyed) and `drops` is the drop `ResourceArray`; failure returns the original tool, an empty array and `false`.

Item-holding operations extract the exact old key from the handle's source, then write the remainder, container items, damaged tool and drops back to the same source. If a third-party storage rejects the write-back, the result enters recovery escrow and the workflow fails, to avoid duplicating or deleting items.

### 8.3 Redstone

- `bus.redstone()`: reads the strong signal the target block emits toward the bus face, 0–15; 0 when the target is unavailable;
- `bus.redstone(level)`: makes the bus emit a redstone level of 0–15 outward; returns `true` on success, `false` when the bus is unavailable, and throws for an invalid level.

The output level persists in the bus NBT. Reading uses strong-signal semantics, so weak sources such as redstone dust on the target block may not be read.

## 9. Block NBT snapshots

`BlockView.nbt` is a read-only snapshot of the block entity NBT, `null` for ordinary blocks and air; `blockEntityType` is also `null` when there is no block entity.

```ts
const target = bus.target;
if (target.blockEntityType === "minecraft:chest") {
  log(JSON.stringify(target.nbt?.Items));
}
```

NBT is converted to JavaScript objects, arrays, strings and numbers. Longs beyond the JavaScript safe integer range become strings. The snapshot is fully detached from world state; modifying the object does not write back to the world. A single conversion is limited to 24 levels and 4096 nodes.

## 10. Precompiled data: recipes and JSON

### 10.1 `require_recipes(filter)`

`require_recipes()` is a client-side precompile macro, not a runtime function. Before upload, the client reads `processing_recipes.json`; when filtering by `machine` it also reads `recipe_types.json`, then replaces the call with a recipe array literal.

The same precompile logic is used for MCP probes: inline `code` is treated as a virtual file in the `appliedscripts/` root. So if baked recipes are genuinely needed, leave a reusable baking script so data can be regenerated after the modpack updates.

Each recipe is `{ id, type, inputs, outputs, json }`. Inputs and outputs use the flat `{ channel, ...keyFields, amount }` shape and can be passed directly to `extract()` or `registerProcessingPattern`. A multi-choice ingredient slot uses its own flat fields as the representative and keeps every candidate in `options`.

The workspace exporter omits recipe families that do not make useful processing patterns: vanilla crafting/stonecutting/smithing, Create automatic shaped/shapeless/packing and all mixing, Create Dragons Plus coloring, Mekanism painting/pigment processing, IDs ending in `_as_coloring`, and recipe/type IDs containing `copycat`, `facade`, `camo` or `mimic`.

Filter field values may be a string or an array of strings (any-of):

| Field | Match rule |
| --- | --- |
| `id` | Exact recipe ID match |
| `type` | Exact recipe type ID match |
| `machine` | Machine block ID that can process the type, resolved through `recipe_types.json` |
| `input` | Flat `id` of any input representative or option |
| `output` | Flat `id` of any output resource |

Multiple fields are ANDed. A zero result expands to `[]`; a missing data file, unknown field or non-literal argument fails the precompile.

```ts
const iron = require_recipes({
  type: "minecraft:smelting",
  output: "minecraft:iron_ingot"
});

registerProcessingPattern(
  [{ orderNetwork: "west", inputs: iron[0].inputs, outputs: iron[0].outputs }],
  function* (order) {
    yield order.input.pushExactlyInto(furnaceBus);
  }
);
```

The expanded result is a real read-only array and can be further `filter()`ed, `map()`ped or `find()`ed. Keep the filter as narrow as possible so the expansion does not exceed the 128k limit.

### 10.2 Relative JSON default imports

```ts
import recipes from "./data/recipes.json";
```

Only JSON default imports relative to the entry `.ts` file are supported. The IDE provides field completion through `resolveJsonModule`; on upload the import is replaced with an embedded constant, so the server does not need the JSON file. Paths cannot escape `appliedscripts/`.

Named imports, dynamic `import()`, absolute paths and TypeScript/JavaScript module imports are not supported and fail during precompilation.

## 11. Common troubleshooting

- `network.online === false`: check whether the corresponding controller face is connected to an AE grid and whether the chunk is loaded;
- `network.buses` is empty: check whether the Factory Bus has actually joined the network on that face;
- `bus.channels` has channels but `extract()` is empty: the capability exists, but there is currently no resource extractable from that face;
- `storage()` has resources but `extract()` does not: the resources are in the machine's whole inventory, but that face does not allow extraction;
- A workflow keeps waiting: check the complete input amount, target capacity, whether the bus still exists, and whether atomic bulk transfer was used by mistake;
- The recipe macro fails: click **Export Workspace** in the controller screen to regenerate the latest recipe data;
