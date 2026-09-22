package com.fulent.appliedfactory.client;

import java.util.Locale;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

/** Central policy for recipe families that are noise in processing-pattern exports. */
final class RecipeExportBlacklist {
    private static final Set<String> TYPES = Set.of(
            "minecraft:crafting",
            "minecraft:stonecutting",
            "minecraft:smithing",
            "create:automatic_shaped",
            "create:automatic_shapeless",
            "create:automatic_packing",
            "create:mixing",
            "create_dragons_plus:coloring",
            "mekanism:painting",
            "mekanism:pigment_extracting",
            "mekanism:pigment_mixing");

    private static final Set<String> CAMOUFLAGE_MARKERS = Set.of(
            "copycat", "facade", "camo", "mimic");

    private RecipeExportBlacklist() {
    }

    static boolean excludesType(String typeId) {
        return TYPES.contains(typeId) || containsCamouflageMarker(typeId);
    }

    static boolean excludes(String typeId, String recipeId) {
        if (excludesType(typeId)) {
            return true;
        }
        var id = ResourceLocation.tryParse(recipeId);
        if (id == null) {
            return false;
        }
        var path = id.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith("_as_coloring") || containsCamouflageMarker(path);
    }

    private static boolean containsCamouflageMarker(String value) {
        var normalized = value.toLowerCase(Locale.ROOT);
        return CAMOUFLAGE_MARKERS.stream().anyMatch(normalized::contains);
    }
}
