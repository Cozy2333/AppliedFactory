package com.fulent.appliedfactory.script;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import com.fulent.appliedfactory.factory.FactoryBusAddress;
import com.fulent.appliedfactory.factory.FactoryCraftingAction;
import com.fulent.appliedfactory.factory.FactoryEndpoint;
import com.fulent.appliedfactory.factory.FactoryProgram;
import com.fulent.appliedfactory.factory.FactoryResource;
import com.fulent.appliedfactory.factory.FactoryResourceOrigin;
import com.fulent.appliedfactory.factory.FactoryResourceRef;
import com.fulent.appliedfactory.factory.FactorySleepAction;
import com.fulent.appliedfactory.factory.FactoryTransferAction;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** State shared by the Java facade graph installed into one GraalJS context. */
final class ScriptApi {
    private final FactoryProgram.Host host;
    private final Registration registration;
    private final JsBridgeBinder binder;
    private final EnumMap<Direction, List<Value>> topologyListeners = new EnumMap<>(Direction.class);
    private ScriptExecutionContext activeContext;

    ScriptApi(
            FactoryProgram.Host host,
            Registration registration,
            Context context) {
        this.host = host;
        this.registration = registration;
        binder = new JsBridgeBinder(context);
    }

    void install() {
        binder.installGlobals(new JsGlobals(this));
        binder.installGlobal("console", new JsConsole(host));
    }

    void bind(ScriptExecutionContext context) {
        if (activeContext != null) {
            throw new IllegalStateException("Factory script execution is already active");
        }
        activeContext = context;
    }

    void unbind() {
        activeContext = null;
    }

    Object wrap(Object value) {
        if (value instanceof FactoryResourceRef resource) {
            return binder.wrap(resourceArray(resource.origin(), resource.bundle()));
        }
        return binder.wrap(value);
    }

    Object delegate(Object value) {
        return binder.delegate(value);
    }

    FactoryProgram.Host host() {
        return host;
    }

    Registration registration() {
        return registration;
    }

    void addTopologyListener(Direction side, Value listener) {
        topologyListeners.computeIfAbsent(side, ignored -> new ArrayList<>()).add(listener);
    }

    void fireTopologyListeners(Set<Direction> affectedSides) {
        var listeners = new LinkedHashSet<Value>();
        for (var side : Direction.values()) {
            if (affectedSides.contains(side)) {
                listeners.addAll(topologyListeners.getOrDefault(side, List.of()));
            }
        }
        for (var listener : listeners) {
            listener.execute();
        }
    }

    Object performNow(FactoryTransferAction action, boolean arrayResult) {
        if (activeContext == null) {
            throw JsValues.error("Action.now() may only run inside a workflow");
        }
        var result = host.performTransfer(activeContext.workflowId(), action);
        if (action.mode() == FactoryTransferAction.Mode.EXACT) {
            return result.completed();
        }
        return result.remaining().isEmpty()
                ? null
                : arrayResult
                        ? resourceArray(action.source(), result.remaining())
                        : resourceValue(new FactoryResourceRef(
                                action.source(), result.remaining()));
    }

    /** Flat partial-key query. Matches never span more than one AE key channel. */
    Object extractResources(FactoryEndpoint endpoint, Object rawQuery) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        var query = resourceQuery(rawQuery);
        var snapshot = query.channel() == null
                ? host.availableResources(endpoint)
                : host.availableResources(endpoint, query.channel());
        AEKeyType selectedChannel = query.channel();
        var matches = new ArrayList<FactoryResource>();
        for (var resource : snapshot) {
            var key = resource.key();
            if (selectedChannel != null && !selectedChannel.equals(key.getType())) {
                continue;
            }
            if (!query.fields().isEmpty()
                    && !nbtMatches(key.toTag(host.registries()), query.fields())) {
                continue;
            }
            if (query.tag() != null && !isTagged(key, query.tag())) {
                continue;
            }
            if (selectedChannel == null) {
                selectedChannel = key.getType();
            }
            matches.add(new FactoryResource(
                    key, Math.min(resource.amount(), query.amountLimit())));
        }
        return resourceArray(origin, matches);
    }

    private ResourceQuery resourceQuery(Object raw) {
        if (JsValues.isNullish(raw)) {
            return ResourceQuery.ALL;
        }
        var object = JsValues.object(raw, "extract query");
        AEKeyType channel = null;
        ResourceLocation tag = null;
        var amountLimit = Long.MAX_VALUE;
        var fields = new CompoundTag();
        for (var field : object.getMemberKeys()) {
            var value = object.getMember(field);
            if (value == null || value.isNull()) {
                continue;
            }
            switch (field) {
                case "channel" -> channel = resolveChannel(
                        JsValues.string(value, "extract query.channel"));
                case "amount" -> amountLimit = queryAmount(value);
                case "$tag" -> {
                    tag = ResourceLocation.tryParse(
                            JsValues.string(value, "extract query.$tag"));
                    if (tag == null) {
                        throw JsValues.error("extract query.$tag must be a valid resource id");
                    }
                }
                case "options" -> {
                    // Recipe input metadata, not part of the encoded AE key. Keeping
                    // it out lets an exported RecipeInput double as an extract query.
                }
                default -> {
                    if (field.startsWith("$")) {
                        throw JsValues.error("Unknown extract query operator: " + field);
                    }
                    fields.put(field, NbtJs.fromValue(value, "extract query." + field));
                }
            }
        }
        return new ResourceQuery(channel, tag, amountLimit, fields);
    }

    private static long queryAmount(Value value) {
        var number = JsValues.number(value, "extract query.amount");
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number <= 0 || number > 9_007_199_254_740_991D) {
            throw JsValues.error("extract query.amount must be a positive integer");
        }
        return (long) number;
    }

    private static boolean isTagged(AEKey key, ResourceLocation tagId) {
        return key.getType().getTagNames()
                .filter(tag -> tag.location().equals(tagId))
                .anyMatch(key::isTagged);
    }

    private static boolean nbtMatches(Tag candidate, Tag query) {
        if (candidate instanceof CompoundTag candidateCompound
                && query instanceof CompoundTag queryCompound) {
            for (var field : queryCompound.getAllKeys()) {
                var candidateValue = candidateCompound.get(field);
                if (candidateValue == null
                        || !nbtMatches(candidateValue, queryCompound.get(field))) {
                    return false;
                }
            }
            return true;
        }
        if (candidate instanceof CollectionTag<?> candidateList
                && query instanceof CollectionTag<?> queryList) {
            if (candidateList.size() != queryList.size()) {
                return false;
            }
            for (int index = 0; index < queryList.size(); index++) {
                if (!nbtMatches(candidateList.get(index), queryList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (candidate instanceof StringTag candidateString
                && query instanceof StringTag queryString) {
            return globMatches(candidateString.getAsString(), queryString.getAsString());
        }
        if (candidate instanceof NumericTag candidateNumber
                && query instanceof NumericTag queryNumber) {
            return Double.compare(
                    candidateNumber.getAsDouble(), queryNumber.getAsDouble()) == 0;
        }
        return candidate.equals(query);
    }

    /** Linear-time glob matcher supporting '*' and '?' without regular expressions. */
    private static boolean globMatches(String value, String pattern) {
        int valueIndex = 0;
        int patternIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '?'
                            || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                valueIndex++;
                patternIndex++;
            } else if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                starValueIndex = valueIndex;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++starValueIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private record ResourceQuery(
            AEKeyType channel,
            ResourceLocation tag,
            long amountLimit,
            CompoundTag fields) {
        private static final ResourceQuery ALL = new ResourceQuery(
                null, null, Long.MAX_VALUE, new CompoundTag());
    }

    /**
     * Read-only view of an endpoint's full contents, including slots that reject
     * extraction from the accessed face. Actions created from such entries wait
     * exactly like entries that do not exist; {@code channel} is optional.
     */
    Object storage(FactoryEndpoint endpoint, Object rawChannel) {
        var origin = FactoryResourceOrigin.endpoint(endpoint);
        if (JsValues.isNullish(rawChannel)) {
            return resourceArray(origin, host.storageContents(endpoint));
        }
        var channel = resolveChannel(JsValues.string(rawChannel));
        return resourceArray(origin, host.storageContents(endpoint, channel));
    }

    /** Accepts a live resource or an exact flat ResourceSpec. */
    FactoryResource resourceSpec(Object raw, String name) {
        var delegate = binder.delegate(raw);
        if (delegate instanceof JsResource resource) {
            var bundle = resource.resource().bundle();
            if (bundle.size() == 1) {
                return bundle.getFirst();
            }
        }
        if (raw instanceof Value object && object.hasMembers() && object.hasMember("channel")) {
            var rawChannel = object.getMember("channel");
            var rawAmount = object.getMember("amount");
            if (rawAmount != null && !rawAmount.isNull()) {
                var channel = resolveChannel(JsValues.string(rawChannel));
                var amount = JsValues.number(rawAmount, name + ".amount");
                if (!Double.isFinite(amount) || amount != Math.rint(amount)
                        || amount <= 0 || amount > 9_007_199_254_740_991D) {
                    throw JsValues.error(name + " requires an exact positive resource amount");
                }
                var keyTag = new CompoundTag();
                for (var field : object.getMemberKeys()) {
                    if (field.equals("channel") || field.equals("amount")
                            || field.equals("options")) {
                        continue;
                    }
                    if (field.startsWith("$")) {
                        throw JsValues.error(name + " cannot use query operator " + field);
                    }
                    var value = object.getMember(field);
                    if (value != null && !value.isNull()) {
                        keyTag.put(field, NbtJs.fromValue(value, name + "." + field));
                    }
                }
                var key = channel.loadKeyFromTag(host.registries(), keyTag);
                if (key == null) {
                    throw JsValues.error(
                            name + " has invalid flat key fields for channel " + channel.getId());
                }
                return new FactoryResource(key, (long) amount);
            }
        }
        throw JsValues.error(name + " requires a ResourceSpec");
    }

    List<String> channels(FactoryBusAddress bus) {
        return host.channels(bus);
    }

    JsOrder order(ScriptExecutionContext context) {
        var origin = FactoryResourceOrigin.escrow(context.workflowId());
        return new JsOrder(
                this,
                orderedResourceArray(origin, context.inputs()),
                new JsNetwork(this, context.orderNetwork()),
                context.craftingRequestId());
    }

    boolean cancelProcessingOrder(java.util.UUID craftingRequestId) {
        if (craftingRequestId == null) {
            throw JsValues.error("order.cancel() is only available in a processing handler");
        }
        return host.cancelCraftingRequest(craftingRequestId);
    }

    FactoryEndpoint requireEndpoint(Object value) {
        var delegate = binder.delegate(value);
        if (delegate instanceof JsNetwork network) {
            return FactoryEndpoint.network(network.side());
        }
        if (delegate instanceof JsBus bus) {
            return FactoryEndpoint.bus(bus.address());
        }
        if (delegate instanceof JsSlot slot) {
            return FactoryEndpoint.itemSlot(slot.address(), slot.index());
        }
        throw JsValues.error("target must be a Network, Bus or Bus slot");
    }

    FactoryResourceRef requireResource(Object value) {
        var delegate = binder.delegate(value);
        var resource = delegate instanceof JsResource handle ? handle.resource() : null;
        if (resource == null) {
            throw JsValues.error("resource must be a factory Resource handle");
        }
        return requireResource(resource);
    }

    FactoryResourceRef requireResource(FactoryResourceRef resource) {
        if (resource.origin().kind() == FactoryResourceOrigin.Kind.ESCROW
                && (activeContext == null
                        || !resource.origin().escrowId().equals(activeContext.workflowId()))) {
            throw JsValues.error(
                    "An escrow Resource can only be used by the workflow that owns it");
        }
        return resource;
    }

    FactoryResourceRef requireItemResource(Object value) {
        var resource = requireResource(value);
        if (resource.bundle().size() != 1
                || !(resource.bundle().getFirst().key() instanceof AEItemKey)) {
            throw JsValues.error("operation requires an ae2:i resource");
        }
        return resource;
    }

    JsResource resourceFacade(FactoryResourceRef resource) {
        if (resource.bundle().size() != 1) {
            throw new IllegalArgumentException("A script Resource must contain one exact AE key");
        }
        return new JsResource(this, resource);
    }

    Object resourceValue(FactoryResourceRef resource) {
        return resource.bundle().size() == 1
                ? resourceFacade(resource)
                : resourceArray(resource.origin(), resource.bundle());
    }

    Object resourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources) {
        return resourceArray(origin, resources, false);
    }

    /**
     * Creates the order-input view. Repeated AE keys remain distinct visible
     * entries so a processing script can route each encoded input slot, while
     * the array's bulk actions still operate on the normalized total bundle.
     */
    private Object orderedResourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources) {
        return resourceArray(origin, resources, true);
    }

    private Object resourceArray(
            FactoryResourceOrigin origin, List<FactoryResource> resources, boolean preserveEntries) {
        var normalized = FactoryResourceRef.normalize(resources);
        var visibleResources = preserveEntries
                ? resources.stream().filter(resource -> resource.amount() > 0).toList()
                : normalized;
        var values = visibleResources.stream()
                .map(resource -> binder.wrap(resourceFacade(new FactoryResourceRef(
                        origin, List.of(resource)))))
                .toArray();
        return binder.arrayWithMethods(values, new JsResourceArray(
                this, new FactoryResourceRef(origin, normalized)));
    }

    JsTransferAction transfer(
            FactoryResourceRef resource,
            Object target,
            FactoryTransferAction.Mode mode,
            boolean arrayResult) {
        var usable = requireResource(resource);
        return new JsTransferAction(
                this,
                new FactoryTransferAction(
                        usable.origin(), requireEndpoint(target), usable.bundle(), mode),
                arrayResult);
    }

    Object renameItem(Object item, String name) {
        requireActiveContext("rename(item, name)");
        return host.renameItem(activeContext.workflowId(), requireItemResource(item), name)
                .map(this::resourceValue)
                .orElse(null);
    }

    Object itemNbt(Object item) {
        var resource = requireItemResource(item);
        var key = (AEItemKey) resource.bundle().getFirst().key();
        return NbtJs.toJs(key.toStack(1).save(host.registries()));
    }

    boolean dropItem(com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item) {
        requireActiveContext("bus.drop(item)");
        return host.dropItem(activeContext.workflowId(), bus, requireItemResource(item));
    }

    Object useItem(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object item, Object rawShift) {
        requireActiveContext("bus.use(item)");
        var emptyHand = JsValues.isNullish(item) || item instanceof Boolean;
        var shift = optionalBoolean(item instanceof Boolean ? item : rawShift, "bus.use shift");
        if (emptyHand) {
            return host.use(activeContext.workflowId(), bus, shift);
        }
        var input = requireItemResource(item);
        var outcome = host.use(activeContext.workflowId(), bus, input, shift);
        if (outcome.isEmpty()) {
            return binder.wrap(new Object[] { item, false });
        }
        return binder.wrap(new Object[] { singleResourceOrNull(outcome.get()), true });
    }

    private Object singleResourceOrNull(FactoryResourceRef resource) {
        if (resource.isEmpty()) {
            return null;
        }
        return resourceFacade(resource);
    }

    boolean placeItem(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus,
            Object item,
            Object rawShift) {
        requireActiveContext("bus.place(item)");
        return host.place(
                activeContext.workflowId(), bus, requireItemResource(item),
                optionalBoolean(rawShift, "bus.place shift"));
    }

    private static boolean optionalBoolean(Object value, String name) {
        if (JsValues.isNullish(value)) {
            return false;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw JsValues.error(name + " must be a boolean");
        }
        return booleanValue;
    }

    Object breakBlock(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object tool,
            Object rawDropTarget) {
        requireActiveContext("bus.break(tool)");
        var input = requireItemResource(tool);
        var dropTarget = JsValues.isNullish(rawDropTarget) ? null : requireEndpoint(rawDropTarget);
        var outcome = host.breakBlock(activeContext.workflowId(), bus, input, dropTarget);
        if (outcome.isEmpty()) {
            return binder.wrap(new Object[] {
                    tool, resourceArray(input.origin(), List.of()), false
            });
        }
        var result = outcome.get();
        return binder.wrap(new Object[] {
                singleResourceOrNull(result.tool()),
                resourceArray(result.drops().origin(), result.drops().bundle()),
                true
        });
    }

    Object busRedstone(
            com.fulent.appliedfactory.factory.FactoryBusAddress bus, Object level) {
        if (JsValues.isNullish(level)) {
            return host.busRedstoneLevel(bus);
        }
        var number = JsValues.number(level, "bus.redstone level");
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > 15) {
            throw JsValues.error(
                    "bus.redstone(level) requires an integer level between 0 and 15");
        }
        return host.setBusRedstoneOutput(bus, (int) number);
    }

    private void requireActiveContext(String operation) {
        if (activeContext == null) {
            throw JsValues.error(operation + " may only run inside a workflow");
        }
    }

    static String channel(AEKey key) {
        return key.getType().getId().toString();
    }

    static AEKeyType resolveChannel(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw JsValues.error("Invalid AE resource channel id: " + value);
        }
        try {
            return AEKeyTypes.get(id);
        } catch (IllegalArgumentException exception) {
            throw JsValues.error("Unknown AE resource channel: " + value);
        }
    }

    CompoundTag optionalNbt(Object value, String name) {
        if (JsValues.isNullish(value)) {
            return null;
        }
        return NbtJs.fromObject(JsValues.object(value, name), name);
    }

    static Direction direction(String value) {
        var side = Direction.byName(value);
        if (side == null) {
            throw JsValues.error("Invalid direction: " + value);
        }
        return side;
    }

    Direction networkSide(String value) {
        var absolute = Direction.byName(value);
        if (absolute != null) {
            return absolute;
        }
        var front = host.controllerFacing();
        return switch (value) {
            case "front" -> front;
            case "back" -> front.getOpposite();
            // Left/right follow a player standing in front of and looking at
            // the controller, rather than looking outward from inside it.
            case "left" -> front.getClockWise();
            case "right" -> front.getCounterClockWise();
            default -> throw JsValues.error("Invalid network side: " + value);
        };
    }

    static Object required(Value object, String name) {
        return JsValues.required(object, name);
    }
}

@JsBridge
final class JsGlobals {
    private final ScriptApi api;

    JsGlobals(ScriptApi api) {
        this.api = api;
    }

    public JsNetwork network(String side) {
        return new JsNetwork(api, api.networkSide(side));
    }

    public JsSleepAction sleep(int ticks) {
        return new JsSleepAction(new FactorySleepAction(ticks));
    }

    public Object go(Value factory) {
        api.registration().requireOpen();
        api.registration().passiveHandlers.add(factory);
        return null;
    }

    public Object registerProcessingPattern(Object definitions, Value handler) {
        api.registration().requireOpen();
        var array = JsValues.array(definitions, "registerProcessingPattern definitions");
        var handlerIndex = api.registration().patternHandlers.size();
        api.registration().patternHandlers.add(handler);
        for (long index = 0; index < array.getArraySize(); index++) {
            var definition = JsValues.object(array.getArrayElement(index), "Pattern definition");
            var side = api.networkSide(JsValues.string(
                    ScriptApi.required(definition, "orderNetwork")));
            var inputs = specs(ScriptApi.required(definition, "inputs"), "inputs");
            var outputs = specs(ScriptApi.required(definition, "outputs"), "outputs");
            if (inputs.isEmpty() || outputs.isEmpty()) {
                throw JsValues.error("Processing patterns require inputs and outputs");
            }
            var encoded = PatternDetailsHelper.encodeProcessingPattern(
                    genericStacks(inputs), genericStacks(outputs));
            api.registration().patterns.add(new CompiledControllerProgram.ScriptPattern(
                    side, encoded, handlerIndex));
        }
        return null;
    }

    public Object rename(Object item, String name) {
        return api.renameItem(item, name);
    }

    /**
     * Prints a message to this controller's log subscribers (chat) and the server
     * log.
     */
    public Object log(String message) {
        api.host().log(message);
        return null;
    }

    public Object itemNbt(Object item) {
        return api.itemNbt(item);
    }

    public Object item(String id, Object rawAmount, Object components) {
        return flatQuery("ae2:i", id, rawAmount, components);
    }

    public Object fluid(String id, Object rawAmount, Object components) {
        return flatQuery("ae2:f", id, rawAmount, components);
    }

    private static Map<String, Object> flatQuery(
            String channel, String id, Object amount, Object components) {
        var result = new LinkedHashMap<String, Object>();
        result.put("channel", channel);
        result.put("id", id);
        if (!JsValues.isNullish(components)) {
            result.put("components", components);
        }
        if (!JsValues.isNullish(amount)) {
            result.put("amount", amount);
        }
        return result;
    }

    private List<FactoryResource> specs(Object value, String name) {
        var array = JsValues.array(value, name);
        var result = new ArrayList<FactoryResource>();
        for (long index = 0; index < array.getArraySize(); index++) {
            result.add(spec(JsValues.toHost(array.getArrayElement(index)), name));
        }
        return List.copyOf(result);
    }

    /**
     * Accepts a flat {@code {channel, ...keyFields, amount}} object (the same
     * shape returned by {@code item()}/{@code fluid()} and exported recipes).
     */
    private FactoryResource spec(Object raw, String name) {
        return api.resourceSpec(raw, name);
    }

    private static List<GenericStack> genericStacks(List<FactoryResource> resources) {
        return resources.stream()
                .map(resource -> new GenericStack(resource.key(), resource.amount()))
                .toList();
    }
}

@JsBridge
final class JsNetwork {
    private final ScriptApi api;
    private final Direction side;

    JsNetwork(ScriptApi api, Direction side) {
        this.api = api;
        this.side = side;
    }

    Direction side() {
        return side;
    }

    @JsProperty
    public String getSide() {
        return side.getName();
    }

    @JsProperty
    public boolean isOnline() {
        return api.host().onlineNetworks().contains(side);
    }

    @JsProperty
    public List<JsBus> getBuses() {
        return api.host().busAddressesByNetwork().getOrDefault(side, List.of()).stream()
                .map(address -> new JsBus(api, address))
                .toList();
    }

    public Object onChange(Value callback) {
        api.addTopologyListener(side, callback);
        return null;
    }

    /**
     * Compares the live AE grid objects; disconnected sides never compare equal.
     */
    public boolean isSameNetwork(JsNetwork other) {
        return api.host().isSameNetwork(side, other.side);
    }

    public Object extract(Object query) {
        return api.extractResources(FactoryEndpoint.network(side), query);
    }

    public Object storage(Object channel) {
        return api.storage(FactoryEndpoint.network(side), channel);
    }

    public boolean canOrder(Object resource) {
        return api.host().canOrder(side, api.resourceSpec(resource, "network.canOrder"));
    }

    public JsCraftingAction order(Object resource) {
        return new JsCraftingAction(new FactoryCraftingAction(
                side, api.resourceSpec(resource, "network.order")));
    }
}

@JsBridge
final class JsBus {
    private final ScriptApi api;
    private final com.fulent.appliedfactory.factory.FactoryBusAddress address;

    JsBus(
            ScriptApi api,
            com.fulent.appliedfactory.factory.FactoryBusAddress address) {
        this.api = api;
        this.address = address;
    }

    com.fulent.appliedfactory.factory.FactoryBusAddress address() {
        return address;
    }

    @JsProperty
    public boolean isExists() {
        return api.host().busTarget(address).isPresent();
    }

    @JsProperty
    public String getTargetFace() {
        return address.side().getOpposite().getName();
    }

    @JsProperty
    public List<String> getChannels() {
        return api.channels(address);
    }

    @JsProperty
    public JsBlockView getTarget() {
        var target = api.host().busTarget(address).orElse(null);
        var position = address.hostPosition().relative(address.side());
        if (target == null || !target.isLoaded()) {
            return new JsBlockView(
                    "minecraft:air", "minecraft:air",
                    position.getX(), position.getY(), position.getZ(), Map.of(), null, null);
        }
        var state = target.blockState();
        var blockEntityType = target.blockEntityTypeId();
        return new JsBlockView(
                target.blockId().toString(), state.toString(),
                position.getX(), position.getY(), position.getZ(),
                blockStateProperties(state),
                blockEntityType == null ? null : blockEntityType.toString(),
                target.blockEntityNbt());
    }

    private static Map<String, Object> blockStateProperties(BlockState state) {
        var result = new LinkedHashMap<String, Object>();
        state.getValues().forEach((property, value) -> result.put(property.getName(), exposePropertyValue(value)));
        return result;
    }

    private static Object exposePropertyValue(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value.toString();
    }

    public Object extract(Object query) {
        return api.extractResources(FactoryEndpoint.bus(address), query);
    }

    public Object storage(Object channel) {
        return api.storage(FactoryEndpoint.bus(address), channel);
    }

    public boolean drop(Object item) {
        return api.dropItem(address, item);
    }

    public Object use(Object resource, Object shift) {
        return api.useItem(address, resource, shift);
    }

    public boolean place(Object resource, Object shift) {
        return api.placeItem(address, resource, shift);
    }

    /**
     * JS name is {@code break}; the Java name stays breakBlock because break is a
     * keyword.
     */
    @JsName("break")
    public Object breakBlock(Object tool, Object dropTarget) {
        return api.breakBlock(address, tool, dropTarget);
    }

    /** Reads (no args) or sets (with a 0-15 level) this bus's redstone. */
    public Object redstone(Object level) {
        return api.busRedstone(address, level);
    }

    /**
     * A handle to one exact item slot of this bus's target container. The slot
     * can be used for {@code extract()}, as a transfer target and directly,
     * bypassing the accessed face's input/output capability filters.
     */
    public JsSlot slot(Object rawIndex) {
        var number = JsValues.number(rawIndex, "bus.slot index");
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < 0 || number > Integer.MAX_VALUE) {
            throw JsValues.error("bus.slot(index) requires a non-negative integer");
        }
        return new JsSlot(api, address, (int) number);
    }
}

@JsBridge
final class JsSlot {
    private final ScriptApi api;
    private final com.fulent.appliedfactory.factory.FactoryBusAddress address;
    private final int index;

    JsSlot(
            ScriptApi api,
            com.fulent.appliedfactory.factory.FactoryBusAddress address,
            int index) {
        this.api = api;
        this.address = address;
        this.index = index;
    }

    com.fulent.appliedfactory.factory.FactoryBusAddress address() {
        return address;
    }

    int index() {
        return index;
    }

    @JsProperty
    public int getIndex() {
        return index;
    }

    /** Whether the bus resolves and the target container actually has this slot. */
    @JsProperty
    public boolean isExists() {
        return api.host().busTarget(address)
                .map(target -> index < target.itemSlotCount())
                .orElse(false);
    }

    public Object extract(Object query) {
        return api.extractResources(FactoryEndpoint.itemSlot(address, index), query);
    }

    public Object storage(Object channel) {
        return api.storage(FactoryEndpoint.itemSlot(address, index), channel);
    }
}

@JsBridge
final class JsBlockView {
    private final String id;
    private final String state;
    private final int x;
    private final int y;
    private final int z;
    private final Map<String, Object> properties;
    private final String blockEntityType;
    private final CompoundTag nbt;

    JsBlockView(
            String id,
            String state,
            int x,
            int y,
            int z,
            Map<String, Object> properties,
            String blockEntityType,
            CompoundTag nbt) {
        this.id = id;
        this.state = state;
        this.x = x;
        this.y = y;
        this.z = z;
        this.properties = Map.copyOf(properties);
        this.blockEntityType = blockEntityType;
        this.nbt = nbt == null ? null : nbt.copy();
    }

    @JsProperty
    public String getId() {
        return id;
    }

    @JsProperty
    public String getState() {
        return state;
    }

    @JsProperty
    public int getX() {
        return x;
    }

    @JsProperty
    public int getY() {
        return y;
    }

    @JsProperty
    public int getZ() {
        return z;
    }

    @JsProperty
    public Map<String, Object> getProperties() {
        return properties;
    }

    @JsProperty
    public String getBlockEntityType() {
        return blockEntityType;
    }

    @JsProperty
    public Object getNbt() {
        return nbt == null
                ? null
                : NbtJs.toJs(nbt);
    }

    public boolean isSameBlock(JsBlockView other) {
        return x == other.x && y == other.y && z == other.z;
    }
}

@JsBridge
final class JsResource {
    private final ScriptApi api;
    private final FactoryResourceRef resource;

    JsResource(ScriptApi api, FactoryResourceRef resource) {
        this.api = api;
        this.resource = resource;
    }

    FactoryResourceRef resource() {
        return resource;
    }

    @JsProperty
    public JsResourceOrigin getOrigin() {
        return new JsResourceOrigin(api, resource.origin());
    }

    @JsProperty
    public String getChannel() {
        return ScriptApi.channel(resource.bundle().getFirst().key());
    }

    @JsProperty
    public String getId() {
        return resource.bundle().getFirst().id().toString();
    }

    @JsProperty
    public double getAmount() {
        return resource.bundle().getFirst().amount();
    }

    @JsProperty
    public Object getKey() {
        return NbtJs.toJs(resource.bundle().getFirst().key().toTag(api.host().registries()));
    }

    public JsTransferAction to(Object target) {
        return api.transfer(resource, target, FactoryTransferAction.Mode.PARTIAL, false);
    }

    public JsTransferAction pushExactlyInto(Object target) {
        return api.transfer(resource, target, FactoryTransferAction.Mode.EXACT, false);
    }
}

@JsBridge
final class JsResourceArray {
    private final ScriptApi api;
    private final FactoryResourceRef resources;

    JsResourceArray(ScriptApi api, FactoryResourceRef resources) {
        this.api = api;
        this.resources = resources;
    }

    public JsTransferAction to(Object target) {
        return api.transfer(resources, target, FactoryTransferAction.Mode.PARTIAL, true);
    }

    public JsTransferAction pushExactlyInto(Object target) {
        return api.transfer(resources, target, FactoryTransferAction.Mode.EXACT, true);
    }
}

@JsBridge
final class JsResourceOrigin {
    private final ScriptApi api;
    private final FactoryResourceOrigin origin;

    JsResourceOrigin(ScriptApi api, FactoryResourceOrigin origin) {
        this.api = api;
        this.origin = origin;
    }

    @JsProperty
    public String getKind() {
        if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
            return "escrow";
        }
        return switch (origin.endpoint().kind()) {
            case NETWORK -> "network";
            case BUS -> "bus";
            case SLOT -> "slot";
        };
    }

    @JsProperty
    public Object getEndpoint() {
        if (origin.kind() == FactoryResourceOrigin.Kind.ESCROW) {
            return null;
        }
        var endpoint = origin.endpoint();
        return switch (endpoint.kind()) {
            case NETWORK -> new JsNetwork(api, endpoint.networkSide());
            case BUS -> new JsBus(api, endpoint.bus());
            case SLOT -> new JsSlot(api, endpoint.bus(), endpoint.slotIndex());
        };
    }
}

@JsBridge
final class JsOrder {
    private final ScriptApi api;
    private final Object input;
    private final JsNetwork network;
    private final java.util.UUID craftingRequestId;

    JsOrder(
            ScriptApi api,
            Object input,
            JsNetwork network,
            java.util.UUID craftingRequestId) {
        this.api = api;
        this.input = input;
        this.network = network;
        this.craftingRequestId = craftingRequestId;
    }

    @JsProperty
    public Object getInput() {
        return input;
    }

    @JsProperty
    public JsNetwork getNetwork() {
        return network;
    }

    public boolean cancel() {
        return api.cancelProcessingOrder(craftingRequestId);
    }
}

/**
 * {@code console.log/warn/error} convenience mirror of the {@code log()}
 * global.
 */
@JsBridge
final class JsConsole {
    private final FactoryProgram.Host host;

    JsConsole(FactoryProgram.Host host) {
        this.host = host;
    }

    public Object log(String message) {
        host.log(message);
        return null;
    }

    public Object warn(String message) {
        host.log(message);
        return null;
    }

    public Object error(String message) {
        host.log(message);
        return null;
    }
}
