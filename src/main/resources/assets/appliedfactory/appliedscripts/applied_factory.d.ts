/**
 * Applied Factory scripting API — authoritative type and signature reference.
 *
 * Types and signatures are defined by this file; appliedscripts/SCRIPT_API.md
 * only describes the runtime model and behavioral semantics. Controller
 * scripts are transpiled to ES2022 JavaScript on the client, then evaluated
 * once by GraalJS when the controller loads: scripts obtain handles through
 * global functions, register processing patterns, and start passive generator
 * workflows through `go`.
 */

type Direction = "up" | "down" | "north" | "south" | "west" | "east";
type NetworkDirection = "top" | "bottom" | "north" | "south" | "west" | "east";
type RelativeDirection = "front" | "back" | "left" | "right";
type NetworkSide = NetworkDirection | RelativeDirection;
type NbtValue =
  | string
  | number
  | boolean
  | readonly NbtValue[]
  | Readonly<Record<string, NbtValue | undefined>>
  | null;
type NbtCompound = Readonly<Record<string, NbtValue>>;

/** Registry ID of an AEKeyType, e.g. "ae2:i" for items or "ae2:f" for fluids. */
type ResourceChannel = string;

type Action = SleepAction | TransferAction<unknown> | CraftingAction;

/** Exact flat AE key used by patterns and crafting orders; also valid as an extract query. */
interface ResourceSpec {
  /** See channels.json for valid values. */
  readonly channel: ResourceChannel;
  /** Exact positive amount used by patterns and crafting orders. */
  readonly amount: number;
  /** All other fields are decoded directly by the selected AEKey codec. */
  readonly [field: string]: NbtValue | undefined;
}

/**
 * Flat partial-key query for extract(). Missing key fields are ignored. String
 * fields support '*' and '?' globs; channel and $tag are always exact. amount
 * caps every matched exact resource independently.
 */
interface ResourceQuery {
  readonly channel?: ResourceChannel;
  readonly amount?: number;
  readonly $tag?: string;
  readonly [field: string]: NbtValue | undefined;
}

interface Resource {
  /** Raw AEKeyType ID; unknown extension channels need no extra interface or adapter. */
  readonly channel: string;
  /** The channel's own codec NBT; spread beside channel to build a flat query. */
  readonly key: NbtCompound;
  /** AEKey ID for display and filtering; not used to rebuild unknown keys. */
  readonly id: string;
  readonly amount: number;
  readonly origin: ResourceOrigin;

  /** Partial transfer: moves whatever is currently possible until the resource is gone. */
  to(target: ResourceTarget): TransferAction<Resource | null>;
  /** Exact transfer: waits until source and target can handle the complete amount at once. */
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

/** A real JS array with bulk transfer methods for the whole snapshot. */
interface ResourceArray extends ReadonlyArray<Resource> {
  /**
   * Bulk partial transfer: equivalent to calling to() on every resource in the
   * array, independently moving min(source available, target capacity). A
   * resource that is short or cannot fit does not block the others.
   * now() returns the ResourceArray that is still untransferred, or null when all moved.
   */
  to(target: ResourceTarget): TransferAction<ResourceArray | null>;
  /**
   * Bulk exact transfer: moves only when every resource can be transferred
   * completely at once (source has the full amount and the target can accept it).
   * If any is unsatisfied the whole batch waits and nothing moves; on success the
   * whole batch moves in one step. now() returns whether all succeeded.
   */
  pushExactlyInto(target: ResourceTarget): TransferAction<boolean>;
}

/** Current item handle followed by whether the use happened. */
type ItemUseResult = readonly [current: Resource | null, success: boolean];

/** Current tool, collected drops, and whether the block was broken. */
type BlockBreakResult = readonly [tool: Resource | null, drops: ResourceArray, success: boolean];

interface ResourceOrigin {
  readonly kind: "network" | "bus" | "slot" | "escrow";
  readonly endpoint: Network | Bus | Slot | null;
}

interface TransferAction<TResult> {
  now(): TResult;
}

interface SleepAction {}

/** Network crafting order; yield-only, and the yield expression returns the finished Resource. */
interface CraftingAction {}

interface BlockView {
  readonly id: string;
  /** Full block state string, e.g. "minecraft:furnace[facing=north,lit=false]". */
  readonly state: string;
  /** Target block coordinates. */
  readonly x: number;
  readonly y: number;
  readonly z: number;
  /** Block state property table (name → value); booleans/integers keep their type, others become strings. */
  readonly properties: Readonly<Record<string, boolean | number | string>>;
  /** Block entity type id; null when there is no block entity. */
  readonly blockEntityType: string | null;
  /** Read-only NBT snapshot of the block entity itself; null when there is none. */
  readonly nbt: Readonly<Record<string, NbtValue>> | null;

  /** Whether this BlockView points at the same block as another (compared by coordinates). */
  isSameBlock(other: BlockView): boolean;
}

interface Bus {
  /** The bus itself currently resolves on its grid. */
  readonly exists: boolean;
  /** Direction of the bus on its host (pointing toward the target block). */
  readonly targetFace: Direction;
  /** Block the bus faces; air (minecraft:air) when absent or unloaded, never null. */
  readonly target: BlockView;
  /** Resource channel IDs the target face currently supports for input/output (e.g. "ae2:i", "ae2:f"), independent of contents. */
  readonly channels: readonly string[];

  /** Flat partial-key query; an omitted channel locks to the first matching channel instead of mixing channels. */
  extract(): ResourceArray;
  extract(query: ResourceQuery): ResourceArray;
  /**
   * Read-only inventory query: prefers the target's unsided view, falling back
   * to the bus face when unavailable. Includes non-extractable contents of the
   * selected view. Always a ResourceArray, empty when there is nothing. Accepts
   * a channel filter. The result can be turned into an Action, but entries that
   * cannot be extracted share the semantics of "absent" at execution time (the
   * transfer waits).
   */
  storage(): ResourceArray;
  storage(channel: ResourceChannel): ResourceArray;
  /** Immediately extracts the exact items and throws them out from the bus facing; not scheduled. */
  drop(item: Resource): boolean;
  /** Immediately uses the target block empty-handed; shift means sneak-use; false when it did not succeed. */
  use(): boolean;
  use(shift: boolean): boolean;
  /**
   * Immediately uses one item from its source. Returns [current, success]: on
   * success current is the remainder written back to the source, or null when
   * fully consumed; on failure current is the unchanged input handle.
   */
  use(item: Resource, shift?: boolean): ItemUseResult;
  /** Immediately places one BlockItem as a block; the remainder is written back to its source. */
  place(block: Resource, shift?: boolean): boolean;
  /** Reads the redstone level the target block emits toward the bus face (0-15); 0 when bus or target cannot resolve. */
  redstone(): number;
  /** Sets the redstone level the bus emits outward from its physical cable face (0-15); false when the bus cannot resolve. */
  redstone(level: number): boolean;
  /**
   * Immediately breaks one block. The full drop bundle goes to dropTarget if it fits,
   * otherwise to the tool's source; if neither accepts it, the items drop into the world.
   * drops contains only items stored in a target and is empty when they drop into the world.
   * The tool remainder returns to its source or drops into the world if it cannot fit.
   * Failure returns the unchanged input tool, an empty array, and false.
   */
  break(tool: Resource, dropTarget?: ResourceTarget): BlockBreakResult;
  /**
   * Gets a slot numbered within this bus face's item handler. Changing the face
   * can change which slot an index selects. When the face has no item handler,
   * an unsided capability or complete reflected inventory supplies the slots.
   * When the index is out of range the handle's exists is false and related
   * operations keep waiting.
   */
  slot(index: number): Slot;
}

/** An item slot selected by its index in the current bus-face handler. */
interface Slot {
  /** Slot number in the selected handler, 0-based. */
  readonly index: number;
  /** The bus resolves and its selected item handler has this slot. */
  readonly exists: boolean;

  /** Queries only the amount this ae2:i slot can actually extract. */
  extract(): ResourceArray;
  extract(query: ResourceQuery): ResourceArray;
  /** Read-only query of this slot's current contents; always a ResourceArray. */
  storage(): ResourceArray;
  storage(channel: ResourceChannel): ResourceArray;
}

interface Network {
  /** Resolved absolute controller face; vertical faces use top/bottom. */
  readonly side: NetworkDirection;
  readonly online: boolean;
  /** Current topology snapshot, re-enumerated on every read. */
  readonly buses: readonly Bus[];

  /** Runs on this face's node or its grid's Factory Bus node events; a shared callback runs once per step. */
  onChange(callback: () => void): void;
  /** Live check whether two controller faces join the same AE grid; disconnected faces return false. */
  isSameNetwork(other: Network): boolean;
  /** Flat partial-key query; an omitted channel locks to the first matching channel instead of mixing channels. */
  extract(): ResourceArray;
  extract(query: ResourceQuery): ResourceArray;
  /**
   * Read-only inventory query: everything on the network is extractable, so
   * this equals extract(); always a ResourceArray. Accepts a channel filter.
   */
  storage(): ResourceArray;
  storage(channel: ResourceChannel): ResourceArray;
  /** Whether the network currently has an autocraftable pattern for this key. */
  canOrder(resource: ResourceSpec): boolean;
  /** Submits a craft to the network; yield waits for ingredients, CPU and output, returning the managed finished resource. */
  order(resource: ResourceSpec): CraftingAction;
}

type ResourceTarget = Network | Bus | Slot;

interface PatternDefinition {
  readonly orderNetwork: NetworkSide;
  /** Flat specs from item()/fluid() or Recipe.inputs/Recipe.outputs. */
  readonly inputs: readonly ResourceSpec[];
  readonly outputs: readonly ResourceSpec[];
}

/** Gets the network on the controller side; left/right use the view of a player facing the controller's front. */
declare function network(side: NetworkSide): Network;
/** Returns a SleepAction that can be yielded to wait some ticks. */
declare function sleep(ticks: number): SleepAction;

/** Starts a passive production line. A passive line does not return; once it
 * returns it is not restarted automatically. Combined with active network
 * orders it can uniformly push energy or pull returns. */
declare function go(factory: () => Generator<Action, unknown, any>): void;

interface Order {
  /** Input resources for this order, preserving the pattern's input slot order; duplicate inputs are not merged and can be routed by index. */
  readonly input: ResourceArray;
  /** Ordering network. */
  readonly network: Network;
  /** Cancels the parent AE crafting request and returns inputs still held in controller escrow to the ordering network. */
  cancel(): boolean;
}
/** Registers processing patterns; handler receives an Order. */
declare function registerProcessingPattern(
  patterns: readonly PatternDefinition[],
  handler: (order: Order) => Generator<Action, unknown, any>,
): void;

/** Sends a value to controller log subscribers and the server log. Strings are
 * printed unchanged; other values are formatted as indented JSON. MCP execution
 * captures logs as its return. */
declare function log(value: unknown): void;

/** Builds a flat item query/spec without parsing or validating its fields. */
declare function item(id: string): ResourceQuery;
declare function item(
  id: string,
  amount: undefined,
  components?: NbtCompound,
): ResourceQuery;
/** Adds an optional amount and item components to the flat query/spec. */
declare function item(
  id: string,
  amount: number,
  components?: NbtCompound,
): ResourceSpec;
/** Builds a flat fluid query/spec without parsing or validating its fields. */
declare function fluid(id: string): ResourceQuery;
declare function fluid(
  id: string,
  amount: undefined,
  components?: NbtCompound,
): ResourceQuery;
declare function fluid(
  id: string,
  amount: number,
  components?: NbtCompound,
): ResourceSpec;
/** Immediately renames in place; throws at runtime when not ae2:i, returns null when resources are insufficient. */
declare function rename(item: Resource, name: string): Resource | null;
/** Reads the full ItemStack save NBT; throws at runtime when not ae2:i. */
declare function itemNbt(item: Resource): NbtCompound;

/** One input slot: key is the slot's representative (safe to register) and options lists every accepted alternative. */
interface RecipeInput extends ResourceSpec {
  /**
   * Every alternative accepted by this slot (full ResourceSpec, including
   * channel); present for tag/multi-choice ingredient slots, omitted when there
   * is only one option. A recipe needs any one of them, not all.
   */
  readonly options?: readonly ResourceSpec[];
}

/** Processing recipes (crafting/stonecutting/smithing already excluded). inputs/outputs are normalized resource declarations, safe to pass to registerProcessingPattern. */
interface Recipe {
  readonly id: string;
  /** Recipe type id, e.g. "minecraft:smelting". */
  readonly type: string;
  /** Normalized inputs (the server-side generic extraction covers items only; fluids/chemicals/input counts follow the json). A slot with options means any one of them. */
  readonly inputs: readonly RecipeInput[];
  /** Normalized primary output, same flat shape as item()/fluid() specs. */
  readonly outputs: readonly ResourceSpec[];
  /** Raw recipe JSON for read-only reference (complex recipes such as fluids/chemicals/energy/multi-output); null when it cannot be re-encoded. */
  readonly json: Record<string, NbtValue> | null;
}

/** require_recipes filter: multiple fields are ANDed, multiple values within one field are any-of; omitted fields are unconstrained. */
interface RecipeFilter {
  /** Exact recipe id match. */
  readonly id?: string | readonly string[];
  /** Exact recipe type id match, e.g. "minecraft:smelting". */
  readonly type?: string | readonly string[];
  /** Machine block id that can process the recipe (its type resolved through recipe_types.json), e.g. "minecraft:furnace". */
  readonly machine?: string | readonly string[];
  /** Exact flat id match of any input slot's representative (or any option of that slot). */
  readonly input?: string | readonly string[];
  /** Exact flat id match of any output resource. */
  readonly output?: string | readonly string[];
}

/**
 * Client-side precompile macro: before MCP's appliedfactory_execute /
 * appliedfactory_upload are sent, and before the controller GUI saves, the
 * client reads appliedscripts/processing_recipes.json (and recipe_types.json),
 * selects recipes by the filter, and replaces the whole call with a recipe
 * array literal. This function does not exist at controller runtime; a filter
 * that matches nothing expands to []; a missing processing_recipes.json or an
 * invalid filter fails the bundle.
 */
declare function require_recipes(filter?: RecipeFilter): readonly Recipe[];
