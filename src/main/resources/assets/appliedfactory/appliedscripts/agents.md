You are operating a Minecraft **Applied Factory** controller through MCP. You can run TypeScript
probe programs against a factory controller, read their logs, iterate, and finally upload a
production program. The factory is real: networks, machines, resources and recipes exist in the
world and behave like Minecraft + Applied Energistics 2.
The "bus" below and in the API docs refers to the "Factory Bus", a kind of AE2 part added by this mod as world executor, interactor, and I/O endpoint. Available capabilities are defined on the "Bus" object in the docs.

## Tools

- `appliedfactory_status` — read-only status (connection, bound controller, MCP server state, `workspace` path).
- `appliedfactory_execute` — run a probe program; returns `logs` + `result` + `reason`. Main tool.
  Pass the probe inline as `code` by preference (there is no need to create a file): it is
  precompiled as a virtual `.ts` file in the appliedscripts root, so `require_recipes(...)` and
  relative JSON imports resolve exactly as they would for a real file. Use `file` (e.g.
  `file: "probe1.ts"`) only for long scripts you want to keep and reuse; `file` takes precedence
  over `code`.
- `appliedfactory_upload` — compile and replace the controller's production program from a real
  workspace `file` (compile-checked; on failure the existing program is untouched). Uploading always
  needs a local file so the controller GUI keeps an editable backup.

If these tools are missing, verify that this workspace is trusted and that its project-local MCP
configuration was loaded. The endpoint is
`http://127.0.0.1:39291/mcp`; restart the agent after changing its MCP configuration.

## Rules

- The controller is chosen by the player (implicit binding). If it's not online, remind the user to
  click the "Connect to MCP" button in the GUI, or remind the user to check whether the controller is loaded.
- Moving resources is allowed and expected for experimental production. If there are any actions
  you can't perform through script, ask the user for help.
- Output caps: 16,000 chars per log line, 120,000 chars total across all logs, 40,000 chars for
  `result`, 40 pending entries.
- `upload` replaces the live production program. Since you are not familiar with this domain, and your
  thinking will likely go in wrong directions. So you're allowed to upload a version that is not
  polished; the user will verify it and offer feedback.

## Important Notes

- A processing task in AE2 is treated as successful once all resources of its output table have been
  returned to the ordering network, no matter where they are returned from. To simplify the flow, it's
  better to do only the pushing in the handler and pull outputs in the passive workflow.
- For multi-step processing, it's better to write separate patterns so AE2 can organize the production.
- Handling a small pattern frequently to reach high production consumes a large amount of I/O time;
  you'd better multiply both the inputs and outputs so that they can be processed in one go.
- Use `require_recipes(spec)` for filtered recipe batches. General JSON data may be imported with a relative default import such as `import data from "./data.json"`; importing TypeScript/JavaScript modules is not supported.
- If baked recipes are needed, leave a reusable baking script in the workspace — for example a `.ts` probe or a small generator that reproduces the `require_recipes(...)` output or the imported JSON you used — instead of relying on a one-off inline batch, so the data can be regenerated after the modpack updates. 如果需要烘培配方，留下可复用的烘培脚本。
- Source files are TypeScript and are precompiled to ES2022 JavaScript before the GraalJS runtime loads them. The IDE owns full type checking; upload performs syntax-oriented transpilation only.
- The API reference can be found in this folder (`SCRIPT_API.md`).
- Most machines do not expose extraction capabilities for input resources, which is convenient because you can simply pull the outputs without filters. Meanwhile, in-flight inputs can still be inspected through `storage()`.
- All valid values of `ResourceChannel` can be processed by a pattern handler — some addons have registered them as available channels.
- Empty resources and ResourceArrays can be safely transferred (no-op), so extra checks are not needed.
- Those networks that contain lots of ingredients are likely the ordering networks, and those that only have machines are likely the production networks. They can also be the same one - order and produce all in one. 
- The ordering network may contain many kinds of resources, so try not to list all of them.
- Modpacks may modify the recipes so trust the exported rather than your memory.
- No extra information is needed outside the folder if the user does not mention it. If there is any 决策信息不足/不明确的实现路径/合成所需条件不具备的情况，**DO ASK THE USER INSTEAD OF CHECKING BY YOURSELF**, they are more familiar with the game and process.
- Different controllers and network structures basically mean multiple productions, do not treat them as one and merge the logic.
