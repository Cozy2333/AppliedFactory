package com.fulent.appliedfactory.factory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Best-effort item-handler lookup for block entities whose owner never
 * registered the NeoForge {@code Capabilities.ItemHandler.BLOCK} capability.
 * This is what makes deliberately hard-to-automate machines usable, such as
 * Forbidden Arcanus' Hephaestus Forge: its block entity is a Valhelsia
 * {@code ValhelsiaContainerBlockEntity} that only exposes a public
 * {@code getItemStackHandler()}.
 *
 * <p>Tries, in order: the block entity itself as an {@link IItemHandler}, a
 * vanilla {@link Container}, and a public no-argument getter returning an
 * {@link IItemHandler}.</p>
 */
final class FactoryItemHandlerResolver {
    /** Getters seen in the wild, tried before an unconstrained scan. */
    private static final List<String> PREFERRED_GETTERS = List.of(
            "getItemStackHandler", "getItemHandler", "getHandler", "getInventory");
    private static final Map<Class<?>, Optional<Method>> GETTER_CACHE = new ConcurrentHashMap<>();

    private FactoryItemHandlerResolver() {
    }

    @Nullable
    static IItemHandler resolve(@Nullable BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        if (blockEntity instanceof IItemHandler handler) {
            return handler;
        }
        if (blockEntity instanceof Container container) {
            return new InvWrapper(container);
        }
        var getter = getterFor(blockEntity.getClass()).orElse(null);
        if (getter == null) {
            return null;
        }
        try {
            return getter.invoke(blockEntity) instanceof IItemHandler handler ? handler : null;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            return null;
        }
    }

    private static Optional<Method> getterFor(Class<?> type) {
        return GETTER_CACHE.computeIfAbsent(type, FactoryItemHandlerResolver::findGetter);
    }

    private static Optional<Method> findGetter(Class<?> type) {
        for (var name : PREFERRED_GETTERS) {
            var method = publicGetMethod(type, name);
            if (method != null) {
                return Optional.of(method);
            }
        }
        // Fall back to a single unambiguous public no-arg IItemHandler getter.
        Method found = null;
        for (var method : type.getMethods()) {
            if (!isCandidate(method) || !IItemHandler.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (found != null) {
                return Optional.empty();
            }
            found = method;
        }
        return Optional.ofNullable(found);
    }

    @Nullable
    private static Method publicGetMethod(Class<?> type, String name) {
        try {
            var method = type.getMethod(name);
            if (isCandidate(method) && IItemHandler.class.isAssignableFrom(method.getReturnType())) {
                method.trySetAccessible();
                return method;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static boolean isCandidate(Method method) {
        return Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0
                && method.getReturnType() != void.class;
    }
}
