const energyNetwork = network("top");

const Sides = ["back", "left", "right"] as const;

let injectors: Bus[];
let core: Bus | undefined;

function init() {
  injectors = Sides.flatMap((side) =>
    network(side).buses.filter((bus) =>
      bus.target.id.endsWith("_crafting_injector"),
    ),
  );
  core = network("front").buses.find(
    (bus) => bus.target.id === "draconicevolution:crafting_core",
  );
}
for (const side of Sides) network(side).onChange(init);
network("front").onChange(init);
init();

const fusionRecipes = require_recipes({
  type: "draconicevolution:fusion_crafting",
});

for (const recipe of fusionRecipes) {
  registerProcessingPattern(
    [
      {
        orderNetwork: "top",
        inputs: recipe.inputs,
        outputs: recipe.outputs,
      },
    ],
    function* (order) {
      if (core === undefined || injectors.length < order.input.length - 1) {
        log({
          event: "fusion_unavailable",
          core: core !== undefined,
          injectors: injectors.length,
        });
        return;
      }

      while (core.storage().length > 0) {
        yield sleep(5);
      }

      const catalyst = order.input[0];
      yield catalyst.pushExactlyInto(core);

      // 其余材料没有固定的注入器位置。
      for (let i = 1; i < order.input.length; i++) {
        const injector = injectors[(i - 1) % injectors.length];
        yield order.input[i].pushExactlyInto(injector);
      }

      core.redstone(15);
      yield sleep(1);
      core.redstone(0);
    },
  );
}

// 持续为所有注入器充能，不会因某个已满的注入器而阻塞。
go(function* () {
  while (true) {
    let energy = energyNetwork.extract({ channel: "appflux:flux" });
    for (const injector of injectors) {
      energy.to(injector).now();
    }
    yield sleep(1);

    core?.extract().to(energyNetwork).now();
  }
});

log({
  event: "dragon_fusion_patterns_registered",
  count: fusionRecipes.length,
});
