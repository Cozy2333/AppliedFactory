# Applied Factory

This is a mod that provides a script-based approach to controlling processing and passive work for AE2.

Main features:

- High flexibility: with our powerful APIs, you can program any complex processing flow.
- High accessibility: using any agent scaffold that supports MCP, you can easily customize and modify your own processing line.

## Getting started

1. Place a **Factory Controller** on an AE2 network and attach **Factory Buses** to the machines it should operate.
2. Open the controller and click **Export Workspace** in the top-right toolbar; this builds `appliedscripts/` in the game directory from the bundled documentation and the local JEI recipe data. Resource packs may replace those documentation files.
3. Choose or create a `.ts` file in the file browser, edit it, then click **↑** to precompile and upload it. The same screen can open `appliedscripts/` in Visual Studio Code or the system file explorer, and manage files with New/Delete/Rename/Paging.
4. For MCP-driven work, bind the controller with the **M** button and open `appliedscripts/` as a trusted project in your agent; see `appliedscripts/agents.md` inside the generated workspace.

Full in-game documentation is available in the AE2 guide (Script API and Factory Controller pages), in English and Chinese.
