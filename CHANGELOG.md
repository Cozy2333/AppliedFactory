# Changelog

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
