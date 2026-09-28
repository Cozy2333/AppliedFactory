const enableAcceleration = true;

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
  log("Initializing Hephaestus forge...");
  forge = findBuses(SIDES, "forge")[0];
  pedestals = findBuses(SIDES, "pedestal");
  if (!forge || pedestals.length < 8) log("Missing required blocks!");
  if (!forge?.target.properties["activated"]) log("Structure is not formed!");
}

for (const net of SIDES) network(net).onChange(init);
init();

// Remove item-form forge tiers from upgrade recipe ingredients.
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
    log("Missing required blocks!");
    order.cancel();
    return;
  }
  if (expectedTier && forgeTierOf(forge.target.id) !== expectedTier) {
    log(`Upgrade failed: a tier ${expectedTier} forge is required.`);
    order.cancel();
    return;
  }

  const forgeId0 = forge.target.id;
  const main = order.input[0];
  // Use the center slot as a serial lock.
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

  if (enableAcceleration) {
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
    log("No gavel found!");
    order.cancel();
    return;
  }

  function get_gavel() {
    return (gavel =
      gavel ?? orderNet.extract({ id: "forbidden_arcanus:*gavel" })[0]);
  }

  yield sleep(2); // Wait 2 ticks to avoid synchronization issues.
  [gavel] = forge.use(get_gavel());
  yield sleep(1);
  let waited = 0;
  for (;;) {
    if (forge.slot(4).storage()[0]?.id !== main.id) break;
    if (forge.target.id !== forgeId0) break;
    if (waited >= 1200 && waited % 1200 === 0) {
      log(`Items stuck! Crafting has taken over ${waited / 20} seconds.`);
      [gavel] = forge.use(get_gavel()); // Try activating it again.
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

// Register regular item patterns.
registerProcessingPattern(
  smithing.map((r) => ({
    orderNetwork: orderNet.side,
    inputs: r.inputs.filter((i) => !isForgeTierInput(i)),
    outputs: r.outputs,
  })),
  ritual,
);

// Register upgrade patterns with no output.
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

// Passively keep soul and experience bottles stocked.
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
