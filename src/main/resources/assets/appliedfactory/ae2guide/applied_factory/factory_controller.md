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

The Factory Controller is a programmable AE2 processing provider. Each face connects to an independent AE2 network. Attach a Factory Bus to a face, point it at an external machine, then use the controller program to move resources and register processing patterns.

Open the controller and choose a TypeScript file from the `appliedscripts/` browser. “Precompile & Upload” saves the local file first, then uploads both its editable TypeScript and compiled JavaScript. Remote source without a matching local backup must be pulled before it can be edited or uploaded.

## Preparing the workspace

The **Export Workspace** button in the top-right toolbar regenerates `appliedscripts/` from the files bundled with the mod plus the local JEI recipe data, including examples, `SCRIPT_API.md`, the type declarations and the exported modpack recipes. Documentation is read through the resource manager, so a resource pack (or an additional language overlay) can replace the bundled files. Re-export after the modpack's recipes change.

The rest of the toolbar provides, from left to right:

- `○` / `●` — subscribe or unsubscribe controller logs;
- `↓` — pull the controller's program to a local file;
- `M` — bind or unbind MCP;
- `↑` — save and upload the current script;
- auto-reload toggle — re-upload automatically when the selected file changes on disk;
- **Export Workspace** — regenerate the workspace (see above);
- **Open VS Code** — open `appliedscripts/` in Visual Studio Code;
- **Open Folder** — open `appliedscripts/` in the system file explorer.

The file browser's bottom row offers **New**, **Delete**, **Rename**, **Page Up**, **Page Down** and **Refresh**.

## MCP

The controller can be linked to the local MCP server. Use `appliedfactory_execute` for probe programs and `appliedfactory_upload` only after validating the production program.

Probes sent as inline `code` are precompiled as a virtual `.ts` file in the `appliedscripts/` root, so recipe macros and relative JSON imports resolve as they would for a real file.

Only relative default JSON imports are supported, for example `import recipes from "./recipes.json"`. JSON imports and recipe macros resolve from the selected script's directory.

See the [Script API](script_api.md) for functions, objects and examples.
