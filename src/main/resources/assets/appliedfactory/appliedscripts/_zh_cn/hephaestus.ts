const 启用加速 = true;

const orderNet = network("top");

const ACCELERATOR = "gag:time_sand_pouch";
const SIDES = ["bottom", "north", "south", "west", "east"] as const;

function findBuses(sides: readonly NetworkSide[], query: string) {
  let re = [];
  for (const side of sides)
    for (const b of network(side).buses)
      if (b.target.id.includes(query)) re.push(b);
  return re;
}

let forge: Bus | undefined;
let pedestals: Bus[] | undefined;

function init() {
  log("锻炉初始化中……");
  forge = findBuses(SIDES, "forge")[0];
  pedestals = findBuses(SIDES, "pedestal");
  if (!forge || pedestals.length < 8) log("需要的方块不足！");
  if (!forge?.target.properties["activated"]) log("结构未成型！");
}

for (const net of SIDES) network(net).onChange(init);
init();

// 将升级配方里的物品形式锻炉从配料表剔除
function forgeTierOf(id: string): number {
  const m =
    /hephaestus_forge_tier_(\d+)/.exec(id) ?? /upgrade_tier_(\d+)/.exec(id);
  return m === null ? -1 : parseInt(m[1], 10);
}

function isForgeTierInput(spec: ResourceSpec): boolean {
  return forgeTierOf(String(spec.id ?? "")) >= 0;
}

function* ritual(
  order: Order,
  expectedTier?: number,
): Generator<Action, unknown, any> {
  if (!forge || !pedestals || pedestals.length < order.input.length - 1) {
    log("需要的方块不足！");
    order.cancel();
    return;
  }
  if (expectedTier && forgeTierOf(forge.target.id) !== expectedTier) {
    log(`升级失败, 需要 ${expectedTier} 级锻炉`);
    order.cancel();
    return;
  }

  const forgeId0 = forge.target.id;
  const main = order.input[0];
  // 用中央槽当串行锁
  let slot4 = forge.slot(4).storage();
  while (slot4.length !== 0) {
    yield sleep(5);
    slot4 = forge.slot(4).storage();
  }
  main.pushExactlyInto(forge.slot(4)).now();

  for (let i = 1; i < order.input.length; i++) {
    const pedestal = pedestals[i - 1];
    pedestal.use(order.input[i]);
  }

  if (启用加速) {
    let pouch = orderNet.extract(item(ACCELERATOR))[0];
    if (pouch)
      for (let k = 0; k < 4; k++) {
        const result = forge.use(pouch, true);
        pouch = result[0]!;
      }
  }

  let gavel: Resource | null = orderNet.extract({
    id: "forbidden_arcanus:*gavel",
  })[0];
  if (!gavel) {
    log("未找到锻锤！");
    order.cancel();
    return;
  }

  function get_gavel() {
    return (gavel =
      gavel ?? orderNet.extract({ id: "forbidden_arcanus:*gavel" })[0]);
  }

  yield sleep(2); //等待2tick，避免同步问题
  [gavel] = forge.use(get_gavel());
  yield sleep(1);
  let waited = 0;
  for (;;) {
    if (forge.slot(4).storage()[0]?.id !== main.id) break;
    if (forge.target.id !== forgeId0) break;
    if (waited >= 1200 && waited % 1200 === 0) {
      log(`卡料了！ 合成耗时超过 ${waited / 20} 秒`);
      [gavel] = forge.use(get_gavel()); //重新激活一下试试
    }
    yield sleep(20);
    waited += 20;
  }

  if (expectedTier) {
    order.cancel();
  } else {
    forge.slot(4).extract().to(order.network).now();
  }
}

const all = require_recipes({
  type: [
    "forbidden_arcanus:hephaestus_smithing",
    "forbidden_arcanus:hephaestus_forge_upgrading",
  ],
});
const smithing = all.filter(
  (r) => r.type === "forbidden_arcanus:hephaestus_smithing",
);
const upgrading = all.filter(
  (r) => r.type === "forbidden_arcanus:hephaestus_forge_upgrading",
);

// 注册普通物品样板
registerProcessingPattern(
  smithing.map((r) => ({
    orderNetwork: orderNet.side,
    inputs: r.inputs.filter((i) => !isForgeTierInput(i)),
    outputs: r.outputs,
  })),
  ritual,
);

// 注册无输出升级样板
for (const r of upgrading) {
  const outId = String(r.outputs[0]?.id ?? "");
  const targetTier =
    forgeTierOf(outId) >= 0 ? forgeTierOf(outId) : forgeTierOf(r.id);
  const expectedTier = targetTier - 1;
  registerProcessingPattern(
    [
      {
        orderNetwork: orderNet.side,
        inputs: r.inputs.filter((i) => !isForgeTierInput(i)),
        outputs: r.outputs,
      },
    ],
    function* (order) {
      yield* ritual(order, expectedTier);
    },
  );
}

// 被动：灵魂 / 附魔瓶 尽量填满
go(function* () {
  for (;;) {
    yield sleep(20);
    if (!forge) continue;
    orderNet.extract(item("forbidden_arcanus:soul")).to(forge.slot(6)).now();
    orderNet
      .extract(item("minecraft:experience_bottle"))
      .to(forge.slot(8))
      .now();
  }
});
