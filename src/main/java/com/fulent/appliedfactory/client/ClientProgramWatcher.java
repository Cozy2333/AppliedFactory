package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.fulent.appliedfactory.mcp.McpClientManager;
import com.fulent.appliedfactory.mcp.McpToolException;
import com.fulent.appliedfactory.mcp.ScriptBundler;
import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;
import com.fulent.appliedfactory.network.SaveControllerProgramPayload;
import com.fulent.appliedfactory.script.ControllerProgram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side, GUI-independent auto-reload session. It watches the workspace backup
 * behind a controller's live program and re-uploads it when the file changes, so edits
 * made outside the game keep applying even after the editor is closed. The target
 * controller is remembered from either the editor GUI or an MCP binding, so the feature
 * works both for manual editing and for coding agents. The on/off toggle is a persistent
 * client setting.
 */
public final class ClientProgramWatcher {
    private static final ClientProgramWatcher INSTANCE = new ClientProgramWatcher();
    private static final long DEBOUNCE_MILLIS = 300L;

    private boolean settingLoaded;
    private boolean autoReload;
    private BlockPos controllerPos;
    private String controllerDimension = "";
    private String trackedPath = "";
    private String resolvedPath = "";
    private String syncedProgramPath = "";
    private long watchedModifiedAt = -1L;
    private long dueAt = -1L;
    private UUID pendingRequestId;
    private String pendingPath = "";

    private ClientProgramWatcher() {
    }

    public static ClientProgramWatcher get() {
        return INSTANCE;
    }

    public boolean isAutoReload() {
        loadSetting();
        return autoReload;
    }

    public void setAutoReload(boolean value) {
        loadSetting();
        if (autoReload == value) {
            return;
        }
        autoReload = value;
        saveSetting();
        resync();
    }

    /** The workspace file currently being watched, or blank when none. */
    public String watchedPath() {
        return resolvedPath;
    }

    /**
     * Points the watcher at a controller and the workspace file backing its live
     * program. Called from the editor GUI (manual use) and from the MCP binding
     * (agent use); the last caller wins.
     */
    public void watchTarget(BlockPos pos, String dimension, String programPath) {
        if (pos == null || dimension == null || dimension.isBlank()) {
            return;
        }
        controllerPos = pos;
        controllerDimension = dimension;
        var program = programPath == null ? "" : programPath;
        var programChanged = !program.equals(syncedProgramPath);
        syncedProgramPath = program;
        if (!program.isBlank() && (programChanged || resolvedPath.isBlank())) {
            track(program);
        }
    }

    /** Sets the workspace backup the watcher follows; blank disables watching. */
    public void track(String relativePath) {
        trackedPath = relativePath == null ? "" : relativePath;
        resync();
    }

    public void reset() {
        controllerPos = null;
        controllerDimension = "";
        trackedPath = "";
        resolvedPath = "";
        syncedProgramPath = "";
        watchedModifiedAt = -1L;
        dueAt = -1L;
        pendingRequestId = null;
        pendingPath = "";
    }

    public void tick() {
        if (!isAutoReload()) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        syncFromBinding();
        if (controllerPos == null || controllerDimension.isBlank()) {
            return;
        }
        if (!controllerDimension.equals(minecraft.level.dimension().location().toString())) {
            return;
        }
        if (resolvedPath.isBlank() && !syncedProgramPath.isBlank()) {
            track(syncedProgramPath);
        }
        if (pendingRequestId != null || resolvedPath.isBlank()
                || !ScriptWorkspaceFiles.exists(resolvedPath)) {
            return;
        }
        try {
            var modifiedAt = ScriptWorkspaceFiles.lastModifiedMillis(resolvedPath);
            if (modifiedAt != watchedModifiedAt) {
                watchedModifiedAt = modifiedAt;
                dueAt = Util.getMillis() + DEBOUNCE_MILLIS;
                return;
            }
            if (dueAt < 0L || Util.getMillis() < dueAt) {
                return;
            }
            dueAt = -1L;
            upload(minecraft);
        } catch (IOException | IllegalArgumentException exception) {
            dueAt = -1L;
        }
    }

    public void onSaveResult(ControllerProgramSaveResultPayload payload) {
        if (pendingRequestId == null || !pendingRequestId.equals(payload.requestId())) {
            return;
        }
        pendingRequestId = null;
        var uploadedPath = pendingPath;
        pendingPath = "";
        var minecraft = Minecraft.getInstance();
        if (!payload.saved()) {
            notify(minecraft, "Auto-reload rejected: " + payload.message());
            return;
        }
        McpClientManager.get().updateProgramPath(uploadedPath);
        notify(minecraft, "Auto-reloaded " + uploadedPath + " onto the watched controller");
    }

    private void upload(Minecraft minecraft) {
        String source;
        String compiled;
        try {
            source = ScriptWorkspaceFiles.read(resolvedPath);
            compiled = ScriptBundler.bundle(
                    source, ScriptWorkspaceFiles.absolute(resolvedPath).getParent());
        } catch (IOException | IllegalArgumentException | McpToolException exception) {
            notify(minecraft, "Auto-reload failed: " + exception.getMessage());
            return;
        }
        if (!ControllerProgram.isWithinLimit(source) || !ControllerProgram.isWithinLimit(compiled)) {
            notify(minecraft, "Auto-reload failed: program exceeds the "
                    + ControllerProgram.MAX_SOURCE_LENGTH + " character limit");
            return;
        }
        var requestId = UUID.randomUUID();
        pendingRequestId = requestId;
        pendingPath = resolvedPath;
        PacketDistributor.sendToServer(new SaveControllerProgramPayload(
                requestId, controllerPos, source, compiled, resolvedPath));
    }

    private void syncFromBinding() {
        var binding = McpClientManager.get().binding();
        if (binding == null) {
            return;
        }
        watchTarget(binding.pos(), binding.dimension(),
                binding.programPath() == null ? "" : binding.programPath());
    }

    private void resync() {
        resolvedPath = trackedPath;
        watchedModifiedAt = -1L;
        dueAt = -1L;
        if (resolvedPath.isBlank() || !ScriptWorkspaceFiles.exists(resolvedPath)) {
            return;
        }
        try {
            watchedModifiedAt = ScriptWorkspaceFiles.lastModifiedMillis(resolvedPath);
        } catch (IOException | IllegalArgumentException ignored) {
            watchedModifiedAt = -1L;
        }
    }

    private static void notify(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal(message));
        }
    }

    private void loadSetting() {
        if (settingLoaded) {
            return;
        }
        settingLoaded = true;
        try {
            var file = configFile();
            if (!Files.isRegularFile(file)) {
                return;
            }
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("autoReload")) {
                autoReload = root.getAsJsonObject().get("autoReload").getAsBoolean();
            }
        } catch (IOException | RuntimeException ignored) {
            // Fall back to the default (off) when the setting cannot be read.
        }
    }

    private void saveSetting() {
        try {
            var object = new JsonObject();
            object.addProperty("autoReload", autoReload);
            var file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, object.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // A failed write only means the toggle is forgotten next launch.
        }
    }

    private static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("appliedfactory-client.json");
    }
}
