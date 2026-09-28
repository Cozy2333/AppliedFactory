# Changelog

## [0.3.1] - 2026-09-28

- 自动重载不再依赖 MCP 绑定；同一维度内即使距离较远，也能监视并重载选中的脚本。
- 脚本 workflow 意外终止或程序被替换时，取消对应的 AE CPU 请求，并回收 escrow 中剩余材料。
- 新增 Draconic Evolution 聚合合成和 Hephaestus Forge 自动化示例脚本。
- 移除 ICU4J 并禁用脚本运行时的 `Intl` API，JAR 体积减少约 16.7 MB。
- 更新控制器按钮说明，并澄清资源查询空结果及转移行为。

- Decoupled auto-reload from MCP binding. Selected scripts can now be watched and reloaded anywhere in the same dimension.
- Cancel the corresponding AE CPU request and recover remaining escrowed materials when a script workflow terminates unexpectedly or its program is replaced.
- Added sample scripts for Draconic Evolution fusion crafting and Hephaestus Forge automation.
- Removed ICU4J and disabled the script runtime's `Intl` API, reducing the JAR size by about 16.7 MB.
- Updated controller button documentation and clarified empty resource-query and transfer behavior.

## [0.3.0] - 2026-09-24

- 全面改进控制器界面和脚本编辑器；可在游戏内创建、保存、重命名和管理脚本，并快捷打开工作区、VS Code 或系统文件管理器。
- 更新 MCP 与自动重载支持：工作区路径和重载设置可供编码助手使用，自动重载设置会在游戏重启后保留。
- 新增 `bus.slot(n)`，可按机器面向总线开放的槽位读取、提取或放入物品；Jade 会显示槽位图标，WTHIT 和 The One Probe 显示槽位信息。
- 更新脚本资源 API：查询改用扁平字段，新增流体资源支持；`extract()`、`use()` 和 `break()` 等调用方式有调整，`stack()` 已移除。
- 可导出当前整合包的 JEI 配方，并通过 `require_recipes()` 按配方、机器、输入或输出筛选；同时过滤了不适合作为加工样板的大部分配方。
- 改进订单取消、控制器断电处理和能源桥接；`break()` 可指定掉落目标，目标和工具来源都放不下时物品会掉落在世界中。
- 更新控制器模型、纹理、日志显示和中英文文档。

- Redesigned the controller screen and script editor. Create, save, rename, and manage scripts in game, and quickly open the workspace in VS Code or the system file manager.
- Improved MCP and auto-reload support. Coding assistants can access the workspace path and reload settings, and auto-reload preferences persist across restarts.
- Added `bus.slot(n)` to inspect, extract from, and insert items into slots exposed on a bus face. Jade shows slot icons; WTHIT and The One Probe show slot details.
- Updated the script resource API with flat query fields and fluid support. Calls such as `extract()`, `use()`, and `break()` have changed, and `stack()` was removed.
- Export JEI recipes from the current modpack and filter them with `require_recipes()` by recipe, machine, input, or output. Most recipes unsuitable for processing patterns are filtered out.
- Improved order cancellation, controller shutdown on power loss, and energy bridging. `break()` accepts a drop target; drops fall into the world if neither the target nor the tool's source can hold them.
- Updated controller models, textures, log display, and English and Chinese documentation.
