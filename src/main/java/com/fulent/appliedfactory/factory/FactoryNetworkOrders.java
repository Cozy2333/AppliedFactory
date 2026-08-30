package com.fulent.appliedfactory.factory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Owns the asynchronous AE calculations and requester links created by script
 * {@code network.order(...)} actions. Script workflows themselves are intentionally
 * non-persistent, so every link is canceled when its owning workflow or program ends.
 */
public final class FactoryNetworkOrders {
    private static final long RETRY_DELAY_TICKS = 20;

    @FunctionalInterface
    public interface OutputSink {
        void accept(UUID workflowId, Direction recoverySide, FactoryResource resource);
    }

    public record Endpoint(
            Level level,
            ICraftingService crafting,
            ICraftingRequester requester,
            IActionSource source) {
    }

    private final Function<Direction, Optional<Endpoint>> endpoints;
    private final OutputSink outputSink;
    private final Map<UUID, ActiveOrder> byWorkflow = new HashMap<>();
    private final Map<UUID, ActiveOrder> byCraftingId = new HashMap<>();

    public FactoryNetworkOrders(
            Function<Direction, Optional<Endpoint>> endpoints,
            OutputSink outputSink) {
        this.endpoints = endpoints;
        this.outputSink = outputSink;
    }

    /** Only checks that the live network currently exposes at least one pattern for the key. */
    public boolean canOrder(Direction side, FactoryResource requested) {
        return endpoints.apply(side)
                .map(endpoint -> endpoint.crafting().isCraftable(requested.key()))
                .orElse(false);
    }

    public FactoryCraftingResult advance(
            UUID workflowId,
            FactoryCraftingAction action,
            long tick) {
        var endpoint = endpoints.apply(action.networkSide()).orElse(null);
        var active = byWorkflow.get(workflowId);
        if (active != null && !active.matches(action)) {
            return FactoryCraftingResult.failed(
                    "A workflow cannot wait for multiple network orders at once");
        }
        if (active == null) {
            if (endpoint == null) {
                return FactoryCraftingResult.waiting();
            }
            active = new ActiveOrder(workflowId, action);
            byWorkflow.put(workflowId, active);
        }

        if (active.link != null) {
            if (active.link.isCanceled()) {
                remove(active, false);
                return FactoryCraftingResult.failed("AE crafting order was canceled");
            }
            if (active.link.isDone()) {
                if (active.received != action.requested().amount()) {
                    remove(active, false);
                    return FactoryCraftingResult.failed(
                            "AE crafting order completed without delivering its full output");
                }
                remove(active, false);
                return FactoryCraftingResult.completed(action.requested());
            }
            if (endpoint == null || endpoint.crafting() != active.craftingService) {
                remove(active, true);
                return FactoryCraftingResult.failed(
                        "AE network changed while the crafting order was running");
            }
            return FactoryCraftingResult.waiting();
        }

        if (endpoint == null || tick < active.retryAtTick) {
            return FactoryCraftingResult.waiting();
        }
        if (active.plan == null) {
            active.startCalculation(endpoint);
            return FactoryCraftingResult.waiting();
        }
        if (endpoint.crafting() != active.craftingService) {
            active.resetForRetry(tick);
            return FactoryCraftingResult.waiting();
        }
        if (!active.plan.isDone()) {
            return FactoryCraftingResult.waiting();
        }

        final ICraftingPlan plan;
        try {
            plan = active.plan.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            remove(active, true);
            return FactoryCraftingResult.failed("AE crafting calculation was interrupted");
        } catch (CancellationException exception) {
            active.resetForRetry(tick);
            return FactoryCraftingResult.waiting();
        } catch (ExecutionException exception) {
            remove(active, true);
            return FactoryCraftingResult.failed(
                    "AE crafting calculation failed: " + messageOf(exception.getCause()));
        }

        if (plan.simulation()) {
            active.resetForRetry(tick);
            return FactoryCraftingResult.waiting();
        }
        var submitted = endpoint.crafting().submitJob(
                plan, endpoint.requester(), null, false, endpoint.source());
        if (!submitted.successful() || submitted.link() == null) {
            active.resetForRetry(tick);
            return FactoryCraftingResult.waiting();
        }
        active.plan = null;
        active.link = submitted.link();
        byCraftingId.put(active.link.getCraftingID(), active);
        return FactoryCraftingResult.waiting();
    }

    public Set<ICraftingLink> links(Direction side) {
        return byWorkflow.values().stream()
                .filter(active -> active.action.networkSide() == side)
                .map(active -> active.link)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public long insertCraftedItems(
            Direction side,
            ICraftingLink link,
            AEKey key,
            long amount,
            Actionable mode) {
        var active = byCraftingId.get(link.getCraftingID());
        if (active == null || active.action.networkSide() != side
                || !active.action.requested().key().equals(key) || amount <= 0) {
            return 0;
        }
        var accepted = Math.min(amount, active.action.requested().amount() - active.received);
        if (accepted <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            outputSink.accept(
                    active.workflowId,
                    active.action.networkSide(),
                    new FactoryResource(key, accepted));
            active.received += accepted;
        }
        return accepted;
    }

    public void jobStateChange(ICraftingLink link) {
        // Link state is polled by advance(). Keeping the entry until then lets the
        // scheduler return the completed Resource to the suspended generator.
    }

    public void cancel(UUID workflowId) {
        var active = byWorkflow.get(workflowId);
        if (active != null) {
            remove(active, true);
        }
    }

    public void cancelAll() {
        for (var active : new ArrayList<>(byWorkflow.values())) {
            remove(active, true);
        }
    }

    private void remove(ActiveOrder active, boolean cancelLink) {
        byWorkflow.remove(active.workflowId, active);
        if (active.plan != null) {
            active.plan.cancel(true);
            active.plan = null;
        }
        if (active.link != null) {
            byCraftingId.remove(active.link.getCraftingID(), active);
            if (cancelLink && !active.link.isDone() && !active.link.isCanceled()) {
                active.link.cancel();
            }
            active.link = null;
        }
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static final class ActiveOrder {
        private final UUID workflowId;
        private final FactoryCraftingAction action;
        private ICraftingService craftingService;
        private Future<ICraftingPlan> plan;
        private ICraftingLink link;
        private long retryAtTick;
        private long received;

        private ActiveOrder(UUID workflowId, FactoryCraftingAction action) {
            this.workflowId = workflowId;
            this.action = action;
        }

        private boolean matches(FactoryCraftingAction other) {
            return action.equals(other);
        }

        private void startCalculation(Endpoint endpoint) {
            craftingService = endpoint.crafting();
            plan = endpoint.crafting().beginCraftingCalculation(
                    endpoint.level(),
                    () -> endpoint.source(),
                    action.requested().key(),
                    action.requested().amount(),
                    CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        private void resetForRetry(long tick) {
            if (plan != null) {
                plan.cancel(true);
            }
            plan = null;
            craftingService = null;
            retryAtTick = tick + RETRY_DELAY_TICKS;
        }
    }
}
