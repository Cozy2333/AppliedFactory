package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.mcp.ScriptBundler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Builds the coding-agent workspace entirely on the physical client. */
public final class ClientWorkspaceCommand {
    private static final String WORKSPACE_RESOURCE = "assets/appliedfactory/appliedscripts";
    private static final String GUIDE_RESOURCE = "assets/appliedfactory/ae2guide";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().create();

    private ClientWorkspaceCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("appliedfactory")
                .then(Commands.literal("setupworkspace")
                        .executes(context -> build())));
    }

    private static int build() {
        var root = ScriptBundler.workspaceDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            var language = normalizeLanguage(Minecraft.getInstance().options.languageCode);
            var docs = copyPackagedDocs(root, language);
            Files.writeString(root.resolve("processing_recipes.json"),
                    GSON.toJson(ClientRecipeDumpPayloadHandler.buildEntries()), StandardCharsets.UTF_8);
            writeChannels(root);
            Files.writeString(root.resolve("recipe_types.json"),
                    GSON.toJson(ClientMachineIconsPayloadHandler.buildIcons()), StandardCharsets.UTF_8);
            AppliedFactory.LOGGER.info("appliedfactory: client workspace ready at {} ({} doc files)",
                    root, docs);
            Minecraft.getInstance().gui.getChat().addMessage(
                    Component.literal("Applied Factory workspace ready at " + root));
        } catch (IOException | RuntimeException exception) {
            AppliedFactory.LOGGER.error("appliedfactory: client workspace build failed", exception);
            Minecraft.getInstance().gui.getChat().addMessage(Component.literal(
                    "Applied Factory workspace build failed: " + exception.getMessage()));
        }
        return 1;
    }

    private static void writeChannels(Path root) throws IOException {
        var types = new java.util.ArrayList<AEKeyType>(AEKeyTypes.getAll());
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

    private static int copyPackagedDocs(Path root, String language) throws IOException {
        var copied = copyLayer(root, WORKSPACE_RESOURCE, true);
        if (!"en_us".equals(language)) {
            copied += copyLayer(root, WORKSPACE_RESOURCE + "/_" + language, false);
        }
        var fallback = modResource(GUIDE_RESOURCE + "/applied_factory/script_api.md");
        var localized = modResource(GUIDE_RESOURCE + "/_" + language + "/applied_factory/script_api.md");
        var selected = !"en_us".equals(language) && Files.isRegularFile(localized) ? localized : fallback;
        var markdown = Files.readString(selected, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("SCRIPT_API.md"), stripFrontmatter(markdown), StandardCharsets.UTF_8);
        return copied + 1;
    }

    private static int copyLayer(Path root, String resourceRoot, boolean required) throws IOException {
        var sourceRoot = modResource(resourceRoot);
        if (!Files.isDirectory(sourceRoot)) {
            if (required) {
                throw new IOException("Missing bundled workspace directory: " + resourceRoot);
            }
            return 0;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            var sources = files.filter(Files::isRegularFile)
                    .filter(path -> !required || !isLanguageOverlay(sourceRoot, path))
                    .sorted().toList();
            for (var source : sources) {
                var output = root.resolve(sourceRoot.relativize(source).toString());
                Files.createDirectories(output.getParent());
                Files.copy(source, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return sources.size();
        }
    }

    private static boolean isLanguageOverlay(Path root, Path file) {
        var relative = root.relativize(file);
        return relative.getNameCount() > 1
                && relative.getName(0).toString().startsWith("_");
    }

    private static Path modResource(String resource) throws IOException {
        var modFile = ModList.get().getModFileById(AppliedFactory.MOD_ID);
        if (modFile == null) {
            throw new IOException("Applied Factory mod file is unavailable");
        }
        return modFile.getFile().findResource(resource.split("/"));
    }

    private static String normalizeLanguage(String language) {
        return language == null || language.isBlank()
                ? "en_us" : language.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String stripFrontmatter(String markdown) {
        var normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return markdown;
        }
        var end = normalized.indexOf("\n---\n", 4);
        return end < 0 ? markdown : normalized.substring(end + 5);
    }
}
