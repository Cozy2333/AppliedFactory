package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.fulent.appliedfactory.mcp.McpBinding;
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
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side, GUI-independent auto-reload session. It watches the workspace backup
 * behind the bound controller's live program and re-uploads it when the file changes,
 * so edits made outside the game keep applying even after the editor is closed. The
 * on/off toggle is a persistent client setting.
 */
public final class ClientProgramWatcher {
    private static final ClientProgramWatcher INSTANCE = new ClientProgramWatcher();
    private static final long DEBOUNCE_MILLIS = 300L;

    private boolean settingLoaded;
    private boolean autoReload;
    private String trackedPath = "";
    private String resolvedPath = "";
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

    /** Sets the workspace backup the watcher follows; blank disables watching. */
    public void track(String relativePath) {
        trackedPath = relativePath == null ? "" : relativePath;
        resync();
    }

    public void reset() {
        trackedPath = "";
        resolvedPath = "";
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
        var binding = McpClientManager.get().binding();
        if (minecraft.level == null || minecraft.player == null || binding == null) {
            return;
        }
        if (pendingRequestId != null || resolvedPath.isBlank()
                || !ScriptWorkspaceFiles.exists(resolvedPath)) {
            return;
        }
        if (!binding.dimension().equals(minecraft.level.dimension().location().toString())) {
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
            upload(minecraft, binding);
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
        notify(minecraft, "Auto-reloaded " + uploadedPath + " onto the bound controller");
    }

    private void upload(Minecraft minecraft, McpBinding binding) {
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
                requestId, binding.pos(), source, compiled, resolvedPath));
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
