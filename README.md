# Applied Factory

<img src="modicon.png" alt="Factory Controller" width="128" height="128">

**TypeScript automation for Applied Energistics 2.**

Applied Factory lets you define AE2 processing patterns in code and control the machines that fulfill them. A **Factory Controller** runs your scripts, while **Factory Buses** move resources and interact with machines and blocks. Write programs in the in-game editor or an IDE, or connect an MCP-capable coding assistant to inspect your setup and develop scripts against the running world.

## Features

- **Script-defined processing:** register inputs, outputs and order handlers as AE2 processing patterns, then request their products from an AE2 terminal.
- **Continuous automation:** run independent workflows to collect outputs, supply consumables, move resources between networks or request AE2 crafting jobs.
- **Precise resource routing:** filter resources by IDs or tags, address individual item slots and choose between partial transfers and all-or-nothing batches. Items, fluids and resource channels registered by AE2 addons share the same API.
- **World interactions:** use items, place and break blocks, drop resources, inspect block state and NBT snapshots, and read or emit redstone signals through Factory Buses.
- **Modpack recipe data:** export local JEI recipes and select them with `require_recipes(...)` to use the pack's actual inputs and outputs.
- **An editable workspace:** TypeScript declarations, examples, logs, upload/download controls, external editor support and optional automatic re-upload when files change.

## Requirements

The current project targets **Minecraft 1.21.1**, **NeoForge 21.1.244 or newer**, and **Applied Energistics 2 19.2.17 or newer within the 19.x series**. Local JEI recipe data is used for recipe export.

## Getting started

1. Place a **Factory Controller** and connect it to a powered AE2 network containing your storage. Use your usual AE2 crafting CPU setup for autocrafting.
2. Attach **Factory Buses** to AE2 cables facing the machines you want to operate. Machine sidedness still applies: input, fuel and output may require buses on different faces.
3. Open the controller and click **Export Workspace**. This prepares `appliedscripts/` in the game directory with documentation, `demo.ts`, type declarations and exported recipe data.
4. Create or select a `.ts` file in the controller's file browser. Edit it in-game, or use **Open VS Code** / **Open Folder** to work externally.
5. Click `↑` to save and upload the script. Enable controller logs to check that it loaded and registered its patterns.
6. Request a registered output from an AE2 terminal.

For a simple setup, storage and machines can share one network. To separate the ordering network from the production network, connect them to different controller faces and select them with `network("back")`, `network("front")`, or absolute directions such as `"north"` and `"top"`. The controller shares AE energy across faces while keeping their item and channel networks separate.

**Save** (`Ctrl+S`) only writes the local file; `↑` also uploads it. If a controller already has a program without a matching local copy, use `↓` to download it before editing. Enable **Auto-reload** to upload changes from an external editor automatically.

## Example: furnace processing

Connect the ordering/storage network to the controller's **back**, and a separate machine network to its **front**. Put one Factory Bus on top of a furnace for input and another underneath for output, both on the machine network. Supply furnace fuel separately. This example assumes the standard iron ore smelting recipe; check exported recipes when playing a modified pack.

```ts
const production = network("front");
const storage = network("back");
const input = production.buses.find(bus =>
  bus.target.id === "minecraft:furnace" && bus.targetFace === "up"
);
const output = production.buses.find(bus =>
  bus.target.id === "minecraft:furnace" && bus.targetFace === "down"
);

if (input === undefined || output === undefined) {
  throw new Error("Connect input and output Factory Buses to the furnace.");
}

registerProcessingPattern(
  [{
    orderNetwork: "back",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)],
  }],
  function* (order) {
    yield order.input.pushExactlyInto(input);
  },
);

go(function* () {
  while (true) {
    yield output.extract(item("minecraft:iron_ingot")).to(storage);
    yield sleep(20);
  }
});
```

The order handler feeds the furnace, and a separate workflow collects finished ingots. AE2 considers processing complete when all declared outputs return to the ordering network. Multi-step recipes can therefore be split into separate patterns and left for AE2 to schedule.

`yield` waits for an action to complete. `pushExactlyInto(...)` waits until the entire batch can move at once; `.to(...)` can move resources in parts. Resource transfer actions also provide `.now()` for one immediate attempt.

## Using modpack recipes

After exporting the workspace, select recipes by ID, type, machine, input or output:

```ts
const recipes = require_recipes({
  type: "minecraft:smelting",
  output: "minecraft:iron_ingot",
});
```

Each result contains `id`, `type`, `inputs`, `outputs` and `json`. The input and output specifications can be used to register processing patterns. `require_recipes(...)` expands before upload, so keep filters specific and export the workspace again after recipes change. Some recipe families are excluded from export; see the Script API for details.

Scripts can also import workspace JSON with `import settings from "./settings.json"`. TypeScript/JavaScript module imports are not supported. Uploading checks that code can be transpiled; use the supplied type declarations in your IDE for full type checking.

## Working with a coding assistant

1. Export the workspace and open `appliedscripts/` as the project in your MCP-capable coding tool. Its `agents.md` describes the controller workflow and `SCRIPT_API.md` documents the API.
2. Configure the tool's project-level MCP connection to `http://127.0.0.1:39291/mcp`. The workspace includes an OpenCode configuration; other tools need their own configuration format. Restart the agent after changing MCP settings.
3. Open the target controller in-game and click **M** to bind it. Keep its chunk loaded and the game unpaused.
4. Have the assistant inspect the network and machines, run probe scripts, then save and upload a production script from the workspace.

| Tool | Purpose |
| --- | --- |
| `appliedfactory_status` | Read the connection, bound controller, workspace path and auto-reload status. |
| `appliedfactory_execute` | Run a temporary TypeScript probe and return its logs, result and completion reason. |
| `appliedfactory_upload` | Compile and replace the production program from a local workspace file; compilation failure leaves the existing program unchanged. |

Probe scripts operate on the actual world and can move resources or interact with machines. Tell the assistant which production line to work on, what should trigger it and whether to perform a trial run.

## Documentation and troubleshooting

The in-game AE2 guide includes English and Simplified Chinese documentation. The same sources are available here:

| Reference | English | 简体中文 |
| --- | --- | --- |
| Controller setup and interface | [Factory Controller](src/main/resources/assets/appliedfactory/ae2guide/applied_factory/factory_controller.md) | [工厂控制器](src/main/resources/assets/appliedfactory/ae2guide/_zh_cn/applied_factory/factory_controller.md) |
| Programming model and API | [Script API](src/main/resources/assets/appliedfactory/ae2guide/applied_factory/script_api.md) | [脚本 API](src/main/resources/assets/appliedfactory/ae2guide/_zh_cn/applied_factory/script_api.md) |
| Full type declarations | [applied_factory.d.ts](src/main/resources/assets/appliedfactory/appliedscripts/applied_factory.d.ts) | [applied_factory.d.ts](src/main/resources/assets/appliedfactory/appliedscripts/_zh_cn/applied_factory.d.ts) |
| Coding assistant instructions | [agents.md](src/main/resources/assets/appliedfactory/appliedscripts/agents.md) | [agents.md](src/main/resources/assets/appliedfactory/appliedscripts/_zh_cn/agents.md) |

See also the bundled [demo.ts](src/main/resources/assets/appliedfactory/appliedscripts/demo.ts) for topology discovery, output collection, redstone and addon resource channels.

- **Patterns are missing:** enable logs, check script errors and confirm `orderNetwork` points to the network where you request crafting.
- **Transfers keep waiting:** check available quantities, target capacity and the machine face exposed to the bus. `storage()` may show items that the face cannot extract.
- **Recipe expansion fails:** export the workspace again to refresh recipe data.
- **MCP cannot connect:** check the project configuration, controller binding, chunk loading and whether the game is paused.

When all controller faces lose power, scripts and orders pause until power returns. Running workflows and script-initiated crafting orders do not persist across controller unloads or script reloads; see the Script API for cancellation and resource recovery behavior.
