---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: 工厂控制器
  icon: appliedfactory:factory_controller
categories:
- applied factory devices
item_ids:
- appliedfactory:factory_controller
---

# 工厂控制器

<BlockImage id="appliedfactory:factory_controller" scale="8" />

工厂控制器通过工厂总线操作机器，并向 AE2 提供由脚本定义的加工样板。你可以用它为熔炉、粉碎机、灌装机等设备编写自动化流程。

## 搭建

控制器的每个面都可以连接 AE2 线缆。脚本可以按方向选择这些连接：

- `north/south/east/west/top/bottom`：控制器绝对面；
- `front/back/left/right`：以控制器正面为基准的方向。

把工厂总线安装在 AE2 线缆上，并保证它贴在机器上。控制器可检测到一个面上连接的所有工厂总线。

最简单的搭法是让控制器、存储和所有工厂总线共用一个 AE2 网络。需要分开原料网络与机器网络时，再把它们分别接到控制器的不同面。

控制器像玻璃纤维一样通过 AE2 的能源叠加网络共享各面的能源，各面的物品和频道网络仍然独立。每个连接到 AE 网络的控制器面消耗 1 AE/t。所有面断电时，控制器暂停脚本、被动流程及已有订单的推进，也不接收新订单；恢复供电后继续。暂停期间 `sleep` 的计时不前进。

**注意，控制器每个面都是单独的部件，可独立提供子网环境，每面都可在无控制器的情况下提供7个频道，最多提供42频道。然而，如果两个面被连接在一起，会互相占用一个频道，导致最终一共只能提供 6 个频道。**

## 准备脚本工作区

打开控制器后，点击右上角工具栏中的 **导出工作区** 按钮。该按钮会用模组内置文档与本地 JEI 配方数据重新生成游戏目录下的 `appliedscripts/`，其中包含示例、`SCRIPT_API.md`、类型声明以及当前整合包导出的配方数据。

文档通过资源管理器读取，因此资源包（或其他语言覆盖层）可以替换内置文件。如果配方发生变化，请重新点击该按钮。

## 编辑与上传

打开控制器后：

1. 在左侧文件列表选择一个 `.ts` 文件；
2. 在右侧编辑代码；
3. 点击右上角的**预编译并上传**；
4. 打开日志订阅，检查脚本是否成功加载；
5. 在 AE2 终端中查看脚本注册的加工样板。

如果控制器中已有程序，但本地没有对应文件，先点击**拉取到本地**，再继续编辑。

### 按钮

工具栏按钮（将鼠标悬停在图标上可查看名称）：

- **导出工作区**：根据内置文档与本地 JEI 数据重新生成 `appliedscripts/`；
- **启用/关闭自动重载**（刷新图标）：选中的本地脚本文件变化时，自动预编译并上传；
- **订阅/取消订阅日志**（日志图标）：订阅或停止接收此控制器的日志；
- **绑定 MCP / 解除绑定**（链接图标）：将此控制器绑定到 MCP，或解除绑定；
- **拉取到本地**（下载图标）：将控制器中的程序复制到 `appliedscripts/`；覆盖不同的本地文件前会先询问；
- **预编译并上传**（上传图标）：保存选中的本地文件、预编译 TypeScript，再将程序上传到控制器；
- **保存**（`Ctrl+S`）：只把当前脚本写入选中的本地文件，不联系控制器；

文件浏览器底栏：**新建**、**删除**、**重命名**、**刷新**、**打开文件夹**和**用 VSCode 打开**。右侧的箭头用于翻页。

## 编写控制器程序

一个加工程序通常需要完成以下事情：

1. 用 `network(...)` 选择 AE2 网络；
2. 从 `network.buses` 中找到目标机器的工厂总线；
3. 用 `registerProcessingPattern(...)` 注册输入和输出；
4. 收到订单后，把输入送入机器；
5. 把加工产物送回订单网络。

下面的示例注册一条铁矿石烧炼样板：

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

所有函数、对象和更多示例见[脚本 API](script_api.md)。

## 使用导出的配方

`require_recipes()` 可以从导出的整合包配方中筛选内容。例如：

```ts
const recipes = require_recipes({
  machine: "minecraft:furnace",
  output: "minecraft:iron_ingot"
});
```

可以按配方 ID、配方类型、机器、输入或输出筛选。整合包可能修改配方，刷新导出后应以工作区中的数据为准。

脚本也可以导入同一工作区内的 JSON：

```ts
import settings from "./settings.json";
```

## 使用 MCP

MCP 可以让编码助手查看控制器状态、运行临时脚本并上传程序。

1. 用编码工具打开 `appliedscripts/` 文件夹；
2. 进入世界并打开目标控制器；
3. 点击控制器工具栏中的**绑定 MCP**；
4. 先运行临时脚本检查网络、总线和机器；
5. 确认结果后再上传正式程序。

## 常见问题

### 文件没有出现在左侧

确认文件位于 `appliedscripts/` 中，然后点击刷新。控制器程序应使用 `.ts` 扩展名。

### 无法编辑或上传现有程序

点击**拉取到本地**将控制器中的程序复制到本地文件，然后选择该文件。

### AE2 中没有出现加工样板

开启日志并重新上传脚本，检查方向、输入输出和脚本错误。还要确认 `orderNetwork` 指向需要使用这些样板的 AE2 网络。

### MCP 无法连接

确认游戏仍在运行、控制器所在区块已加载，并在控制器界面重新点击**绑定 MCP**，或重启 agent 工具。
