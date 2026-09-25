You are operating a Minecraft **Applied Factory** controller through MCP. You can run TypeScript
probe programs against the controller, read their logs, iterate, and finally upload a production
program. The factory is real: networks, machines, resources and recipes all exist in the world and
behave like Minecraft + Applied Energistics 2.
Below and in the API docs, "bus" refers to the "Factory Bus", a kind of AE2 part added by this mod
as world executor, interactor and I/O endpoint. Available capabilities are defined on the "Bus"
object in the docs.

## Tools

- `appliedfactory_status` — read-only status (connection, MCP server port, bound controller coordinates and its current script file, `workspace` path, auto-reload toggle). When auto-reload is on, saving the controller's `programPath` in the workspace recompiles and re-uploads it automatically: edit that file instead of calling `appliedfactory_upload` again.
- `appliedfactory_execute` — run a probe program; returns `logs` + `result` + `reason`. Main tool.
- `appliedfactory_upload` — compile and replace the controller's production program from a real workspace `file` (compile-checked; on failure the existing program is untouched). Uploading must provide a local file so the controller GUI keeps an editable backup.

If these tools do not exist, verify: 1) the player installed the mod correctly and connected MCP in
the GUI; 2) the controller's chunk is loaded and the game is not paused; 3) your agent framework has
project-level MCP configured correctly.

If project-level MCP is not configured, remind the user to configure it for your agent framework.
The endpoint is `http://127.0.0.1:39291/mcp`; restart the agent after changing the MCP configuration.

## Important Notes

- The API reference is in this folder (`SCRIPT_API.md`).
- Moving resources for experimental production is allowed and encouraged.
- Log output caps: 16,000 chars per log line, 120,000 chars total across all logs, 40,000 chars for `result`, 40 pending entries.
- In AE2, a processing task is considered successful once all resources of its output table have returned to the ordering network, no matter where they are returned from. To simplify the flow, it is best to push only in the handler and pull outputs in the passive workflow.
- Multi-step processing is best split into separate patterns so AE2 can organize the production.
- Handling small patterns frequently consumes a lot of I/O time, so for high-frequency patterns it is best to multiply both inputs and outputs to raise parallel throughput.
- Use `require_recipes(spec)` to request recipe batches. General JSON data may be imported with a relative default import, such as `import data from "./data.json"`; importing TypeScript/JavaScript modules is not supported.
- If baked recipes are needed, leave a reusable baking script — for example a small generator that reproduces the imported JSON — so the data can be regenerated after the modpack updates.
- Most machines only output products, which means you can extract directly without filters and will not pull out inputs that are being processed; those inputs can still be inspected through `storage()`.
- All valid values of `ResourceChannel` can be processed by pattern handlers, because some addons have registered them as available channels.
- Empty resources and empty ResourceArrays can be transferred safely (no-op), so no extra checks are needed.
- Networks containing lots of ingredients are likely ordering networks; those with only machines are likely production networks.
- The ordering network may contain many kinds of resources, so try not to list them all.
- Modpacks may modify recipes; trust the exported data, not your memory.
- Different controllers and network structures basically mean multiple production lines; do not treat them as one and merge the logic.
- Information outside the folder is not needed if the user does not mention it. If there is a situation of insufficient decision information / unclear implementation path / missing crafting prerequisites, **do not expand your checks on your own — ask the user**, who knows the game and the process better than you.
- Situations worth asking the user about include but are not limited to: 1) how crafting is triggered (automatic, redstone, etc.); 2) whether passive resources are needed (such as energy, or a fluid or item consumed at a fixed rate); 3) whether machines only output products (which decides whether filtering is needed); 4) whether to do a trial run, and what to produce; 5) whether patterns should be multiplied.
