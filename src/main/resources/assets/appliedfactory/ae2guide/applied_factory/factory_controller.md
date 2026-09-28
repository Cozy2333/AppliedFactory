---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: Factory Controller
  icon: appliedfactory:factory_controller
categories:
- applied factory devices
item_ids:
- appliedfactory:factory_controller
---

# Factory Controller

<BlockImage id="appliedfactory:factory_controller" scale="8" />

The Factory Controller operates machines through Factory Buses and provides AE2 with script-defined processing patterns. You can use it to write automation flows for furnaces, crushers, fillers and other equipment.

## Building

Every face of the controller can connect to AE2 cables. Scripts can select these connections by direction:

- `north/south/east/west/top/bottom`: absolute controller faces;
- `front/back/left/right`: directions relative to the controller front.

Attach a Factory Bus to an AE2 cable and make sure it touches a machine. The controller can detect all Factory Buses connected on one face.

The simplest setup shares one AE2 network between the controller, storage and all Factory Buses. When ingredient and machine networks must be separated, connect them to different controller faces.

Like quartz fiber, the controller shares energy across its faces through AE2's overlay energy grid while their item and channel networks remain separate. Each controller face connected to an AE network consumes 1 AE/t. When all faces lose power, the controller pauses scripts, passive workflows, and existing orders and accepts no new orders. Work resumes when power returns; `sleep` does not advance while unpowered.

**Note: each controller face is an independent part that can provide its own subnet environment. Each face can provide 7 channels without a controller, up to 42 channels total. However, if two faces are connected together, they consume one channel from each other, so only 6 channels can ultimately be provided in total.**

## Preparing the script workspace

Open the controller and click the **Export Workspace** button in the top-right toolbar. It regenerates `appliedscripts/` under the game directory from the mod's bundled documentation and local JEI recipe data, including examples, `SCRIPT_API.md`, the type declarations and the recipes exported from the current modpack.

Documentation is read through the resource manager, so a resource pack (or another language overlay) can replace the bundled files. Click the button again after recipes change.

## Editing and uploading

After opening the controller:

1. Select a `.ts` file in the left file list;
2. Edit the code on the right;
3. Click **Precompile & Upload** in the top-right;
4. Turn on the log subscription and check whether the script loaded successfully;
5. Check the processing patterns registered by the script in an AE2 terminal.

If the controller already has a program but there is no matching local file, click **Pull to Local** first, then continue editing.

### Buttons

Toolbar buttons (hover for their labels):

- **Export Workspace**: regenerate `appliedscripts/` from the bundled docs and local JEI data;
- **Enable/Disable Auto Reload** (refresh icon): automatically precompile and upload the selected script when its local file changes;
- **Subscribe/Unsubscribe Logs** (log icon): subscribe to or stop receiving this controller's logs;
- **Bind MCP / Unbind** (link icon): bind or unbind this controller for MCP;
- **Pull to Local** (download icon): copy the controller's program into `appliedscripts/`; confirm before overwriting a different local file;
- **Precompile & Upload** (upload icon): save the selected local file, precompile its TypeScript, then upload the program to the controller;
- **Save** (`Ctrl+S`): write the current script to the selected local file only, without contacting the controller;

File browser footer: **New**, **Delete**, **Rename**, **Refresh**, **Open Folder**, and **Open VS Code**. The arrows on the right change file pages.

## Writing a controller program

A processing program usually needs to do the following:

1. Select an AE2 network with `network(...)`;
2. Find the target machine's Factory Bus from `network.buses`;
3. Register inputs and outputs with `registerProcessingPattern(...)`;
4. On each order, feed the inputs into the machine;
5. Return the products to the ordering network.

The example below registers an iron-ore smelting pattern:

```ts
const machines = network("front");

registerProcessingPattern(
  [{
    orderNetwork: "back",
    inputs: [item("minecraft:iron_ore", 1)],
    outputs: [item("minecraft:iron_ingot", 1)]
  }],
  function* (order) {
    const furnace = machines.buses.find(bus =>
      bus.target.id === "minecraft:furnace"
    );
    if (furnace === undefined) return;

    yield order.input.pushExactlyInto(furnace);
    yield sleep(200);
    yield furnace.extract().to(order.network);
  }
);
```

See the [Script API](script_api.md) for all functions, objects and more examples.

## Using exported recipes

`require_recipes()` filters the exported modpack recipes. For example:

```ts
const recipes = require_recipes({
  machine: "minecraft:furnace",
  output: "minecraft:iron_ingot"
});
```

You can filter by recipe ID, recipe type, machine, input or output. Modpacks may modify recipes, so after refreshing the export, trust the data in the workspace.

Scripts can also import JSON from within the same workspace:

```ts
import settings from "./settings.json";
```

## Using MCP

MCP lets a coding assistant inspect controller status, run temporary scripts and upload programs.

1. Open the `appliedscripts/` folder in your coding tool;
2. Join the world and open the target controller;
3. Click **Bind MCP** in the controller toolbar;
4. First run a temporary script to check the network, buses and machines;
5. Upload the production program once the result is confirmed.

## Common problems

### Files do not appear on the left

Make sure the files are under `appliedscripts/`, then click Refresh. Controller programs should use the `.ts` extension.

### Cannot edit or upload an existing program

Click **Pull to Local** to copy the controller's program to a local file, then select that file.

### Processing patterns do not appear in AE2

Turn on the log and re-upload the script to check directions, inputs/outputs and script errors. Also confirm that `orderNetwork` points at the AE2 network that should use these patterns.

### MCP cannot connect

Confirm the game is still running and the controller's chunk is loaded, then click **Bind MCP** again in the controller screen, or restart the agent tool.
