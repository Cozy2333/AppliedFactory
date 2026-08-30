package com.fulent.appliedfactory.client;

import java.util.UUID;

import com.fulent.appliedfactory.factory.FactoryRecipes;
import com.fulent.appliedfactory.network.MachineIconsPayload;
import com.fulent.appliedfactory.network.RequestMachineIconsPayload;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only answer to the server's machine-icon request: iterates every JEI
 * recipe category and resolves each category's catalysts — the machines JEI
 * shows next to its recipes — through {@code IRecipeManager
 * .createRecipeCatalystLookup(...).getItemStack()}. The resulting
 * {@code {recipeTypeId: [machineId, ...]}} map is sent back as one
 * {@link MachineIconsPayload}, so the server can replace its recipe-type machine
 * map with JEI's complete snapshot.
 */
public final class ClientMachineIconsPayloadHandler {
    private static final Gson GSON = new Gson();

    private ClientMachineIconsPayloadHandler() {
    }

    public static void handleRequest(RequestMachineIconsPayload payload) {
        send(payload.requestId());
    }

    private static void send(UUID requestId) {
        var icons = buildIcons();
        var runtime = FactoryJeiPlugin.runtime();
        PacketDistributor.sendToServer(new MachineIconsPayload(
                requestId, runtime != null, GSON.toJson(icons)));
    }

    /** Builds the JEI machine map without sending it over the network. */
    public static JsonObject buildIcons() {
        var runtime = FactoryJeiPlugin.runtime();
        var icons = new JsonObject();
        if (runtime != null) {
            var recipeManager = runtime.getRecipeManager();
            for (var category : recipeManager.createRecipeCategoryLookup().get().toList()) {
                var typeId = category.getRecipeType().getUid().toString();
                if (FactoryRecipes.isCraftingType(typeId)) {
                    continue;
                }
                var machines = recipeManager.createRecipeCatalystLookup(category.getRecipeType())
                        .getItemStack()
                        .filter(stack -> !stack.isEmpty())
                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .distinct()
                        .sorted()
                        .toList();
                if (!machines.isEmpty()) {
                    var array = new JsonArray();
                    machines.forEach(array::add);
                    icons.add(typeId, array);
                }
            }
        }
        return icons;
    }
}
