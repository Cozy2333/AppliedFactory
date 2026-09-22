---
navigation:
  parent: applied_factory/applied_factory-index.md
  title: 脚本 API
  icon: appliedfactory:factory_controller
---

# Applied Factory 脚本 API

> 本文解释控制器的编程模型、运行语义与常见工作流。完整类型和函数签名以工作区中的 `applied_factory.d.ts` 为准，可运行示例见 `demo.ts`。

## 1. 从一个可运行程序开始

控制器脚本使用 TypeScript。下面的程序在控制器正面寻找熔炉，为 AE2 注册一条烧铁加工样板；收到订单后，它把输入完整推入熔炉，等待，再把可提取产物送回发起订单的网络。

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

推荐的使用顺序：

1. 在 `appliedscripts/` 中新建或选择 `.ts` 文件；
2. 用 IDE 和 `applied_factory.d.ts` 完成类型检查；
3. 用 GUI 上传，或先通过 MCP 的 `appliedfactory_execute` 运行探针（内联 `code` 会被当作 `appliedscripts/` 根目录下的虚拟文件预编译，无需写入磁盘）；
4. 确认网络、总线、机器与资源后，再上传生产程序。

## 2. 编译、上传与运行生命周期

上传不是直接执行 TypeScript，而是依次进行：

1. 保存工作区中的原始 `.ts` 文件；
2. 展开相对 JSON 默认导入与 `require_recipes()` 宏；
3. 转译为 ES2022 JavaScript；
4. 把可编辑 TypeScript、可执行 JavaScript和工作区相对路径一同保存到世界级数据；
5. GraalJS 对 JavaScript 求值一次，注册样板并启动 workflow。

上传阶段只保证语法可转译，完整类型检查由 IDE 负责。源码或展开后的执行代码超过 128k 字符会被拒绝。没有对应本地备份的远端源码必须先拉取，不能直接覆盖上传。

## 3. 核心概念与全局入口

| 概念 | 用途 |
| --- | --- |
| `Network` | 控制器某一面的 AE 网络，既可查询也可作为资源目标 |
| `Bus` | 网络中的工厂总线；负责访问它面对的机器或世界方块 |
| `Resource` | 带来源、channel、key 与数量的不可变资源句柄 |
| `ResourceArray` | 保留资源槽位顺序的只读资源数组，并提供批量转移 |
| `Action` | 可 `yield` 等待的动作；资源转移动作还可用 `.now()` 单次尝试 |
| `Order` | AE2 加工订单，包含输入资源与发起订单的网络 |

常用全局入口：

- `network(side)`：取得控制器某一面的网络；
- `item(...)` / `stack(...)`：声明资源规格；
- `registerProcessingPattern(...)`：注册加工样板与订单处理器；
- `go(...)`：启动独立的 generator workflow；
- `sleep(ticks)`：创建等待动作；
- `log(message)`：写入控制器日志；
- `rename(...)` / `itemNbt(...)`：物品专用辅助函数。

## 4. Network 与 Bus

### 4.1 面与网络

`network(side)` 接受世界绝对方向 `up/down/north/south/west/east`，也接受相对控制器正面的 `front/back/left/right`。其中左右采用玩家站在正面、看向控制器时的视角。相对方向在创建句柄时解析为绝对面，因此 `network("front").side` 返回实际世界方向。`PatternDefinition.orderNetwork` 使用相同规则。

不要用 `===` 比较两个 `Network` 包装对象。要比较它们当前是否属于同一 AE 网格，请使用：

```ts
if (network("left").isSameNetwork(network("right"))) {
  log("两面当前连接到同一 AE 网格");
}
```

任一面离线时 `isSameNetwork` 返回 `false`；两个未连接面不会被视为相同。网格合并或拆分后，结果会随实时拓扑变化。

### 4.2 总线发现与稳定句柄

`network.buses` 每次读取都返回当前拓扑快照。`network.onChange(callback)` 只在工厂总线加入或离开 AE 网络时同步调用，普通机器方块更新不会触发；回调可以重建缓存的句柄数组，但不能 `yield`。

### 3.1 主动网络订单

`network.canOrder(resourceSpec)` 同步查询网络当前是否公开了该 key 的合成样板。它不进行完整合成模拟，因此不保证材料充足或存在合适的空闲 CPU。

`network.order(resourceSpec)` 返回只能 `yield` 的合成 Action。调度器异步计算 AE 合成计划；材料或 CPU 暂不可用时每秒重试，订单完成后 `yield` 表达式返回一个来源为 `escrow` 的 `Resource`，可继续转移到网络或总线：

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

脚本 workflow 与主动订单都不跨控制器卸载或脚本重载持久化；此时仍在运行的 AE 订单会被取消，已经收到控制器托管区的部分成品按资源回收规则返还。

`Network`、`Bus`、存储端点和资源来源保存的是稳定地址，每次查询或执行动作时都会重新解析：

- 原位替换机器后，旧 `Bus` 句柄会操作当前面对的新机器；
- 机器、网络或区块暂时不可用时，可等待动作保持等待；
- 资源不足或目标已满时，可等待动作保持等待；
- 总线被拆除后，句柄无法解析，可等待动作保持等待。

如果脚本依赖目标机器类型，可重新上传脚本，或在 `onChange` 中重新枚举总线。

### 4.3 能力与方块快照

`bus.channels` 返回目标面当前支持输入或输出的 AE channel ID，如 `"ae2:i"`、`"ae2:f"`。它反映能力而非当前库存，覆盖所有已注册的扩展 channel；出现在数组中只代表至少支持输入、输出之一，不保证两者都支持。

`bus.target` 是 `BlockView` 快照，包含方块 ID、状态、坐标、属性、方块实体类型与 NBT。使用 `isSameBlock()` 比较两个方块位置，不要比较 JavaScript 包装对象身份。

## 5. Resource 与库存查询

### 5.1 资源身份

`Resource` 是不可变的精确来源句柄 `(origin, channel, key, amount)`。创建句柄不会立即提取或锁定库存。执行前若资源被其他设备消耗，可等待动作会等待相同 AE key 再次满足数量；句柄不追踪某一个槽位或实体，也不提供独占锁。

`channel` 是 `AEKeyType#getId()` 的注册表 ID，`key` 是该类型 codec 对应的 NBT。内置物品和流体分别使用 `"ae2:i"` 与 `"ae2:f"`，扩展 channel 不需要新的脚本资源类型。

`ResourceArray` 继承 `ReadonlyArray<Resource>`，可用索引、`find()`、`filter()`、`map()` 和 `for...of`。订单输入保留样板的槽位顺序：重复资源不会合并，可分别路由；批量转移时仍按总量处理。

### 5.2 `extract()`：可从指定面取出的资源

`Network`、`Bus` 与 `Slot` 统一使用扁平的 `extract(query?)` 查询，并始终返回 `ResourceArray`：

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

- `channel` 可省略，但填写时只允许精确值。省略后由首个匹配资源确定 channel，其他 channel 的资源会被静默忽略，因此一次结果绝不跨 channel；
- `$tag` 是可选的精确标签 ID，不支持通配符；
- 其他字段会与 AE key 编码后的字段递归匹配，缺失字段不参与匹配，因此 `{ id: "minecraft:paper" }` 也能找到被改名的纸；
- 字符串字段支持 `*`（任意长度）和 `?`（单个字符）通配符，不支持正则表达式；
- `amount` 必须是正整数，并对每种精确资源独立限额：每个返回项的数量均为 `min(可用量, amount)`；省略时保留每种资源的全部可提取数量；
- 无匹配资源时返回空数组；
- channel 未注册、标签 ID 或 amount 无效、使用未知 `$` 操作符时抛出运行时错误。

### 5.3 `storage()`：目标整体库存快照

`storage(channel?)` 是只读库存查询。对 `Bus` 调用时，它以“无面”方式查看目标方块全部非空槽位，不受总线所贴面的输入输出限制；例如熔炉可以一次看到输入、燃料与输出槽。

```ts
const contents = furnace.storage();
const itemContents = furnace.storage("ae2:i");
```

`extract()` 适合获取该面实际可取出的资源；`storage()` 适合排查机器卡料或观察在途输入。`storage()` 返回的仍是来源句柄，可以 `.to()`，但不建议对其中可能无法从该面取出的资源调用 `pushExactlyInto()`，否则可能一直等待。

### 5.4 单槽句柄 `bus.slot(n)`

`bus.slot(n)` 返回目标容器第 `n` 个物品槽（从 0 开始）的句柄，可绕过所贴面的输入输出限制、直接操作某个槽位：

```ts
const output = furnaceBus.slot(2);        // 熔炉输出槽
const ingots = output.extract();          // 只取该槽内的物品
yield ingots.to(order.network);
yield someResource.pushExactlyInto(output);
```

- 索引按目标方块的完整物品栏（`ae2:i`）解析，与总线所贴面无关；熔炉的输入/燃料槽等通常无法从该面取出，但可以通过 `slot()` 直接访问；
- 槽位句柄只覆盖物品，传入其他 channel 的查询返回空数组；
- `exists` 表示总线可解析且槽位编号有效；越界或总线被拆除后，相关转移动作会像资源不存在一样保持等待；
- 作为转移目标时只向该槽位插入；目标槽已满或物品不兼容时保持等待；
- 由 `slot.extract()` 得到的资源 `origin.kind` 为 `"slot"`，`origin.endpoint` 是该槽位句柄；
- 游戏中把准星指向工厂总线时，Jade 会以物品图标逐槽列出该容器全部物品槽位（空槽显示为空），WTHIT / The One Probe 则以文本列出；编号与 `slot(n)` 一致。

## 6. Action 与资源转移

generator 中的 `yield action` 会在当前 tick 立即尝试一次。成功时继续执行；失败时每 tick 重试，直到成功。`action.now()` 只立即尝试一次并返回该动作的结果，不进入等待调度。

### 6.1 可分转移 `to(target)`

`resource.to(target)` 每次移动当前可行的部分。来源缺货或目标无容量时等待；产生部分进度后只保留尚未移动的 `remaining`。

```ts
yield products.to(order.network);
const remaining = products.to(order.network).now();
```

对 `ResourceArray` 调用时，各资源独立推进，一个资源被阻塞不会妨碍其他资源。空数组会立即成功，是安全 no-op。`.now()` 返回仍未移动的资源句柄或数组；全部完成时返回 `null`。

### 6.2 原子转移 `pushExactlyInto(target)`

`pushExactlyInto` 要求来源拥有完整数量，且目标能一次接收完整数量；任一条件不满足都不移动。

```ts
yield order.input.pushExactlyInto(machine);
const inserted = resource.pushExactlyInto(machine).now();
```

对数组调用时，整个数组是一个不可分批次：所有资源必须同时满足条件。空数组立即成功。`.now()` 返回布尔值。

### 6.3 睡眠与并发

`yield sleep(ticks)` 等待指定 tick。多个 workflow 和订单处理器都在服务器主线程推进；多个动作竞争同一库存时，先执行者先取得资源，后执行者在不足时等待。

## 7. 加工样板与 workflow

`registerProcessingPattern(definitions, handler)` 为每个 definition 注册 AE2 加工样板。`orderNetwork` 决定从哪一面接收订单；`inputs` 和 `outputs` 是资源规格。handler 收到：

- `order.input`：来自本次订单、保留输入槽位顺序的 `ResourceArray`；
- `order.network`：发起本次订单的实时网络句柄。

`order.cancel()` 返回是否成功发出取消。成功后，同一父级 AE 请求产生的处理器会停止，控制器托管区内尚未消耗的 `order.input` 会返还下单网络，AE CPU 内尚未使用的材料也由 AE2 返库。已经转移进外部机器、掉落到世界或被同步操作消耗的材料不再受控制器托管，无法自动取回。

AE2 在样板的全部输出回到订单网络后认定加工完成，输出来自哪台机器并不重要。通常让订单处理器只负责可靠地推送输入，再由独立 `go(function* () { ... })` workflow 持续拉取产物，结构更简单。多步加工通常应拆成多个样板，让 AE2 负责编排；高频小订单会增加 I/O 开销，可按机器吞吐同比放大输入与输出数量。

## 8. 物品与世界交互

`rename`、`use`、`place`、`drop`、`break` 与 `redstone` 都是一次性同步操作。

### 8.1 NBT 与改名

```ts
const sword = network("north")
  .extract({ channel: "ae2:i", id: "minecraft:diamond_sword", amount: 1 })[0];

if (sword !== undefined) {
  const data = itemNbt(sword); // { id, count, components }
  const named = rename(sword, "Factory Pickaxe");
}
```

`stack(channel, key, amount)` 使用对应 AEKeyType codec 解码 key，并返回扁平的 `{ channel, ...keyFields, amount }` 规格；`item(id, amount, components?)` 是物品便利形式。返回值可原样复用于 `extract()`、加工样板、`canOrder()` 与 `order()`。`channel`、`amount`、`options` 和 `$` 开头的名称是保留元数据字段，不能同时作为 codec key 字段。`itemNbt()` 只接受 `ae2:i` 资源。

`rename()` 会立即在原来源中以新 key 替换旧 key；资源不足返回 `null`，成功时返回新的来源句柄。

### 8.2 使用、放置、丢弃与采掘

```ts
go(function* () {
  const storage = network("north");
  const bus = storage.buses[0];
  if (bus === undefined) return;

  bus.use(true); // 潜行空手使用目标

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

- `use(item?, shift?)` 先尝试右键目标，再回退到物品的空中使用。持物调用返回 `[current, success]`；成功时 `current` 是写回来源的剩余物（例如受损的工具），完全消耗时为 `null`；失败时返回原句柄与 `false`；
- `place(block, shift?)` 要求 `amount === 1` 且资源是 BlockItem；
- `drop(item)` 精确扣除资源并沿总线朝向生成物品实体；
- `break(tool)` 返回 `[tool, drops, success]`；成功时 `tool` 是写回来源的受损工具（损毁时为 `null`），`drops` 是掉落 `ResourceArray`；失败时返回原工具、空数组与 `false`。

持物操作会从句柄来源精确取出旧 key，再把剩余物、容器物品、受损工具与掉落写回同一来源。第三方存储若拒绝写回，结果进入 recovery escrow，并使 workflow 失败以避免复制或删除物品。

### 8.3 红石

- `bus.redstone()`：读取目标方块朝总线一面的强信号，范围 0–15；目标不可用时为 0；
- `bus.redstone(level)`：让总线向外输出 0–15 的红石等级，成功返回 `true`，总线不可用返回 `false`，非法等级抛错。

输出等级随总线 NBT 持久化。读取使用强信号语义，目标方块上的红石粉等弱信号源可能读不到。

## 9. 方块 NBT 快照

`BlockView.nbt` 是方块实体 NBT 的只读快照，普通方块和空气为 `null`；没有方块实体时 `blockEntityType` 也为 `null`。

```ts
const target = bus.target;
if (target.blockEntityType === "minecraft:chest") {
  log(JSON.stringify(target.nbt?.Items));
}
```

NBT 转换为 JavaScript 对象、数组、字符串和数字。超过 JavaScript 安全整数范围的 long 转为字符串。快照与世界状态完全分离，修改对象不会写回世界。单次转换限制为 24 层和 4096 个节点。

## 10. 预编译数据：配方与 JSON

### 10.1 `require_recipes(filter)`

`require_recipes()` 是客户端预编译宏，不是运行时函数。上传前，客户端读取 `processing_recipes.json`；使用 `machine` 过滤时还读取 `recipe_types.json`，然后把调用替换为配方数组字面量。

同样的预编译逻辑也用于 MCP 探测：内联 `code` 被视为 `appliedscripts/` 根目录下的虚拟文件。因此如果确实需要烘培配方，请留下可复用的烘培脚本，方便整合包更新后重新生成数据。

每条配方是 `{ id, type, inputs, outputs, json }`。输入输出使用与 `stack()` 相同的扁平 `{ channel, ...keyFields, amount }` 结构，可直接传给 `extract()` 或 `registerProcessingPattern`。多选 ingredient 槽以自身扁平字段作为代表物，并在 `options` 中保存全部候选。

工作区导出会跳过不适合作为加工样板的配方族：原版合成、切石与锻造，机械动力自动有序/无序合成、自动打包及全部搅拌，Create Dragons Plus 染色，Mekanism 喷涂与颜料处理，以 `_as_coloring` 结尾的配方，以及配方/类型 ID 中含 `copycat`、`facade`、`camo` 或 `mimic` 的伪装配方。

过滤器字段值可以是字符串或字符串数组（any-of）：

| 字段 | 匹配规则 |
| --- | --- |
| `id` | 配方 ID 精确匹配 |
| `type` | 配方类型 ID 精确匹配 |
| `machine` | 经 `recipe_types.json` 反查，可处理该类型的机器方块 ID |
| `input` | 任一输入代表物或 option 的扁平 `id` |
| `output` | 任一输出资源的扁平 `id` |

多个字段之间是 AND。零结果展开为 `[]`；数据文件缺失、未知字段或非字面量参数会使预编译失败。

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

展开结果是真实只读数组，可继续 `filter()`、`map()` 或 `find()`。应尽量缩小过滤范围，避免展开后超过 128k 限制。

### 10.2 相对 JSON 默认导入

```ts
import recipes from "./data/recipes.json";
```

只支持相对于入口 `.ts` 文件的 JSON 默认导入。IDE 通过 `resolveJsonModule` 提供字段补全；上传时导入会被替换为内嵌常量，服务器不需要 JSON 文件。路径不能离开 `appliedscripts/`。

不支持命名导入、动态 `import()`、绝对路径以及 TypeScript/JavaScript 模块导入，这些形式会在预编译阶段报错。

## 11. 常见故障定位

- `network.online === false`：检查对应控制器面是否连接 AE 网格、区块是否加载；
- `network.buses` 为空：检查工厂总线是否实际加入该面的网络；
- `bus.channels` 有通道但 `extract()` 为空：能力存在，但当前没有可从该面取出的资源；
- `storage()` 有资源而 `extract()` 没有：资源位于机器整体库存中，但该面不允许提取；
- workflow 一直等待：检查完整输入数量、目标容量、总线是否仍存在，以及是否误用了原子批量转移；
- 配方宏失败：在控制器界面点击 **导出工作区** 生成最新配方数据；
