package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

import org.jetbrains.annotations.Nullable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.FactoryRecipes;
import com.fulent.appliedfactory.mcp.ScriptBundler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import tamaized.ae2jeiintegration.api.integrations.jei.IngredientConverters;

import appeng.api.stacks.GenericStack;

/**
 * Builds the coding-agent workspace entirely on the physical client. Unlike the
 * former setup command, documentation is read through the live
 * {@link ResourceManager} so resource packs (and additional language overlays)
 * can replace the bundled {@code assets/appliedfactory} files, and the dynamic
 * data (recipes, machine icons, channels) is produced directly from the local
 * JEI runtime without a server round-trip.
 */
public final class ClientWorkspaceBuilder {
    private static final String WORKSPACE_RESOURCE = "appliedscripts";
    private static final String GUIDE_RESOURCE = "ae2guide";
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();

    private ClientWorkspaceBuilder() {
    }

    /**
     * Regenerates {@code appliedscripts/} and reports the result in chat.
     *
     * @return whether the workspace was written successfully
     */
    public static boolean build() {
        var root = ScriptBundler.workspaceDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            var resources = Minecraft.getInstance().getResourceManager();
            var language = normalizeLanguage(Minecraft.getInstance().options.languageCode);
            var docs = copyPackagedDocs(root, language, resources);
            Files.writeString(root.resolve("processing_recipes.json"),
                    GSON.toJson(buildRecipes()), StandardCharsets.UTF_8);
            writeChannels(root);
            Files.writeString(root.resolve("recipe_types.json"),
                    GSON.toJson(buildIcons()), StandardCharsets.UTF_8);
            patchChannelDeclaration(root);
            AppliedFactory.LOGGER.info(
                    "appliedfactory: client workspace ready at {} ({} doc files)", root, docs);
            message("Applied Factory workspace ready at " + root);
            return true;
        } catch (IOException | RuntimeException exception) {
            AppliedFactory.LOGGER.error("appliedfactory: client workspace build failed", exception);
            message("Applied Factory workspace build failed: " + exception.getMessage());
            return false;
        }
    }

    private static void message(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(text));
    }

    private static void writeChannels(Path root) throws IOException {
        var types = new ArrayList<AEKeyType>(AEKeyTypes.getAll());
        types.sort(Comparator.comparing(type -> type.getId().toString()));
        var array = new JsonArray();
        for (var type : types) {
            var entry = new JsonObject();
            entry.addProperty("id", type.getId().toString());
            entry.addProperty("name", type.getDescription().getString());
            array.add(entry);
        }
        Files.writeString(root.resolve("channels.json"), GSON.toJson(array), StandardCharsets.UTF_8);
    }

    /**
     * Restores the workspace assets from the active resource stack, then applies
     * the player's language overlay. Reading through the {@link ResourceManager}
     * means a resource pack that provides the same {@code ResourceLocation}
     * overrides the bundled file, including the {@code _<language>} overlays.
     *
     * @return the number of doc files written
     */
    private static int copyPackagedDocs(
            Path root, String language, ResourceManager resources) throws IOException {
        var copied = copyResourceLayer(root, resources, WORKSPACE_RESOURCE, true);
        if (!DEFAULT_LANGUAGE.equals(language)) {
            copied += copyResourceLayer(
                    root, resources, WORKSPACE_RESOURCE + "/_" + language, false);
        }
        copyApiReference(root.resolve("SCRIPT_API.md"), language, resources);
        return copied + 1;
    }

    private static int copyResourceLayer(
            Path root, ResourceManager resources, String prefix, boolean baseLayer)
            throws IOException {
        var locations = resources.listResources(prefix,
                id -> id.getNamespace().equals(AppliedFactory.MOD_ID)
                        && id.getPath().startsWith(prefix + "/"));
        var copied = 0;
        for (var entry : locations.entrySet()) {
            var relative = entry.getKey().getPath().substring(prefix.length() + 1);
            if (baseLayer && isLanguageOverlay(relative)) {
                continue;
            }
            var output = root.resolve(relative);
            Files.createDirectories(output.getParent());
            try (InputStream stream = entry.getValue().open()) {
                Files.copy(stream, output, StandardCopyOption.REPLACE_EXISTING);
            }
            copied++;
        }
        return copied;
    }

    private static void copyApiReference(
            Path output, String language, ResourceManager resources) throws IOException {
        Optional<String> selected =
                resource(GUIDE_RESOURCE + "/applied_factory/script_api.md", resources);
        if (!DEFAULT_LANGUAGE.equals(language)) {
            var localized = resource(GUIDE_RESOURCE + "/_" + language
                    + "/applied_factory/script_api.md", resources);
            if (localized.isPresent()) {
                selected = localized;
            }
        }
        var markdown = selected.orElseThrow(
                () -> new IOException("Missing bundled script API reference"));
        Files.writeString(output, stripFrontmatter(markdown), StandardCharsets.UTF_8);
    }

    private static Optional<String> resource(String path, ResourceManager resources)
            throws IOException {
        var id = ResourceLocation.fromNamespaceAndPath(AppliedFactory.MOD_ID, path);
        var resource = resources.getResource(id);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream stream = resource.get().open()) {
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static boolean isLanguageOverlay(String relative) {
        var slash = relative.indexOf('/');
        return slash > 0 && relative.startsWith("_");
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank()
                ? DEFAULT_LANGUAGE : language.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String stripFrontmatter(String markdown) {
        var normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return markdown;
        }
        var end = normalized.indexOf("\n---\n", 4);
        return end < 0 ? markdown : normalized.substring(end + 5);
    }

    /**
     * Best-effort update of the {@code applied_factory.d.ts}
     * {@code type ResourceChannel} union so the IDE offers autocomplete.
     */
    private static void patchChannelDeclaration(Path root) throws IOException {
        var candidate = root.resolve("applied_factory.d.ts");
        if (!Files.isRegularFile(candidate)) {
            return;
        }
        var union = AEKeyTypes.getAll().stream()
                .map(type -> "\"" + type.getId() + "\"")
                .sorted()
                .collect(java.util.stream.Collectors.joining(" | "));
        var line = "type ResourceChannel = " + union + ";";
        var content = Files.readString(candidate, StandardCharsets.UTF_8);
        String patched;
        if (content.contains("type ResourceChannel")) {
            patched = content.replaceFirst(
                    "type ResourceChannel = .*;", Matcher.quoteReplacement(line));
        } else {
            patched = content + (content.isBlank() ? "" : "\n") + line + "\n";
        }
        Files.writeString(candidate, patched, StandardCharsets.UTF_8);
    }

    /** Builds the JEI-normalized recipe export written to {@code processing_recipes.json}. */
    static JsonArray buildRecipes() {
        var entries = new JsonArray();
        var runtime = FactoryJeiPlugin.runtime();
        if (runtime == null) {
            return entries;
        }
        var level = Minecraft.getInstance().level;
        var registries = level == null ? null : level.registryAccess();
        var ids = recipeIds(level);
        var focusGroup = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        for (var category : runtime.getRecipeManager().createRecipeCategoryLookup().get().toList()) {
            appendCategoryEntries(entries, runtime, category, focusGroup, registries, ids);
        }
        return entries;
    }

    /** Keeps the category's recipe type paired with each recipe without raw generic casts. */
    private static <T> void appendCategoryEntries(
            JsonArray entries,
            IJeiRuntime runtime,
            IRecipeCategory<T> category,
            IFocusGroup focusGroup,
            HolderLookup.Provider registries,
            Map<Recipe<?>, ResourceLocation> ids) {
        var typeId = category.getRecipeType().getUid().toString();
        if (FactoryRecipes.isCraftingType(typeId)) {
            return;
        }
        var recipeManager = runtime.getRecipeManager();
        for (var recipe : recipeManager.createRecipeLookup(category.getRecipeType()).get().toList()) {
            var id = recipeId(category, recipe, ids);
            if (id == null) {
                continue;
            }
            List<JsonObject> inputs;
            List<JsonObject> outputs;
            try {
                var drawable = recipeManager
                        .createRecipeLayoutDrawable(category, recipe, focusGroup)
                        .orElse(null);
                if (drawable != null) {
                    var slots = drawable.getRecipeSlotsView();
                    inputs = resources(slots.getSlotViews(RecipeIngredientRole.INPUT), registries);
                    outputs = resources(slots.getSlotViews(RecipeIngredientRole.OUTPUT), registries);
                } else {
                    var supplier = recipeManager.getRecipeIngredients(category, recipe);
                    inputs = flatResources(
                            supplier.getIngredients(RecipeIngredientRole.INPUT), registries);
                    outputs = flatResources(
                            supplier.getIngredients(RecipeIngredientRole.OUTPUT), registries);
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            if (inputs.isEmpty() || outputs.isEmpty()) {
                continue;
            }
            var entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("type", typeId);
            entry.add("inputs", toArray(inputs));
            entry.add("outputs", toArray(outputs));
            entries.add(entry);
        }
    }

    /**
     * One {@code {channel, key, amount}} entry per slot, with an
     * {@code options} array of every alternative the slot accepts.
     */
    private static List<JsonObject> resources(
            List<IRecipeSlotView> slots, HolderLookup.Provider registries) {
        var result = new ArrayList<JsonObject>();
        for (var slot : slots) {
            var options = new ArrayList<JsonObject>();
            var seen = new LinkedHashSet<String>();
            for (var typed : slot.getAllIngredientsList()) {
                var obj = resource(typed, registries);
                if (obj == null || !seen.add(obj.get("key").toString())) {
                    continue;
                }
                options.add(obj);
            }
            if (options.isEmpty()) {
                continue;
            }
            var representative = options.getFirst().deepCopy();
            if (options.size() > 1) {
                var array = new JsonArray();
                options.forEach(array::add);
                representative.add("options", array);
            }
            result.add(representative);
        }
        return result;
    }

    /** Fallback for recipes whose layout cannot be built: flat per-ingredient entries. */
    private static List<JsonObject> flatResources(
            List<ITypedIngredient<?>> ingredients, HolderLookup.Provider registries) {
        var result = new ArrayList<JsonObject>();
        var seen = new LinkedHashSet<String>();
        for (var typed : ingredients) {
            var obj = resource(typed, registries);
            if (obj == null || !seen.add(obj.get("key").toString())) {
                continue;
            }
            result.add(obj);
        }
        return result;
    }

    @Nullable
    private static <T> JsonObject resource(
            ITypedIngredient<T> typed, HolderLookup.Provider registries) {
        var converter = IngredientConverters.getConverter(typed.getType());
        if (converter == null) {
            return null;
        }
        GenericStack stack;
        try {
            stack = converter.getStackFromIngredient(typed.getIngredient());
        } catch (RuntimeException ignored) {
            return null;
        }
        if (stack == null || stack.amount() <= 0) {
            return null;
        }
        var obj = new JsonObject();
        obj.addProperty("channel", stack.what().getType().getId().toString());
        obj.add("key", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, stack.what().toTag(registries)));
        obj.addProperty("amount", stack.amount());
        return obj;
    }

    /**
     * Maps each recipe value of the client's own recipe manager to its id by
     * identity so JEI recipes that are raw {@code Recipe} instances can still be
     * keyed to the server's recipe ids.
     */
    private static Map<Recipe<?>, ResourceLocation> recipeIds(ClientLevel level) {
        var result = new IdentityHashMap<Recipe<?>, ResourceLocation>();
        if (level == null) {
            return result;
        }
        for (var holder : level.getRecipeManager().getRecipes()) {
            result.put(holder.value(), holder.id());
        }
        return result;
    }

    @Nullable
    private static <T> String recipeId(
            IRecipeCategory<T> category, T recipe, Map<Recipe<?>, ResourceLocation> ids) {
        try {
            var id = category.getRegistryName(recipe);
            if (id != null) {
                return id.toString();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the identity lookup.
        }
        if (recipe instanceof RecipeHolder<?> holder) {
            return holder.id().toString();
        }
        if (recipe instanceof Recipe<?> value) {
            var id = ids.get(value);
            if (id != null) {
                return id.toString();
            }
        }
        return null;
    }

    private static JsonArray toArray(List<JsonObject> values) {
        var array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /** Builds the JEI machine map written to {@code recipe_types.json}. */
    static JsonObject buildIcons() {
        var runtime = FactoryJeiPlugin.runtime();
        var icons = new JsonObject();
        if (runtime == null) {
            return icons;
        }
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
        return icons;
    }
}
