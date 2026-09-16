package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.fulent.appliedfactory.mcp.McpClientManager;
import com.fulent.appliedfactory.mcp.McpToolException;
import com.fulent.appliedfactory.mcp.ScriptBundler;
import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;
import com.fulent.appliedfactory.network.ControllerProgramContentPayload;
import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;
import com.fulent.appliedfactory.network.RequestControllerProgramPayload;
import com.fulent.appliedfactory.network.SaveControllerProgramPayload;
import com.fulent.appliedfactory.network.SetControllerLogSubscriptionPayload;
import com.fulent.appliedfactory.script.ControllerProgram;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Workspace-backed controller editor with a compact file browser. */
public final class FactoryControllerProgramScreen
        extends AbstractContainerScreen<FactoryControllerProgramMenu> {
    private static final int MAX_WIDTH = 920;
    private static final int MAX_HEIGHT = 560;
    private static final int MARGIN = 10;
    private static final int HEADER_HEIGHT = 28;
    private static final int FILES_WIDTH = 190;
    private static final int FILE_ROW_HEIGHT = 19;
    private static final int EDITOR_DECORATION_RIGHT = 10;
    private static final int EDITOR_DECORATION_BOTTOM = 15;
    private static final long AUTO_RELOAD_DEBOUNCE_MILLIS = 300L;

    private final List<Button> fileButtons = new ArrayList<>();
    private ScriptEditBox scriptBox;
    private Button logButton;
    private Button uploadButton;
    private Button pullButton;
    private Button mcpButton;
    private Button autoReloadButton;
    private Button exportWorkspaceButton;
    private Button openFolderButton;
    private Button openVscodeButton;
    private Button newFileButton;
    private Button deleteFileButton;
    private Button renameFileButton;
    private Button pageUpButton;
    private Button pageDownButton;
    private Button refreshFilesButton;
    private List<WorkspaceEntry> workspaceEntries = List.of();
    private String selectedPath;
    private String remotePath = "";
    private String remoteSource = ControllerProgram.DEFAULT_SOURCE;
    private long remoteUpdatedAt;
    private boolean sourceLoaded;
    private boolean logSubscribed;
    private boolean autoReload;
    private boolean uploadPending;
    private String pendingUploadSource;
    private String pendingUploadPath;
    private String watchedPath;
    private long watchedModifiedAt = -1L;
    private long autoReloadDueAt = -1L;
    private int filePage;
    private Component saveStatus = Component.empty();
    private int saveStatusColor = 0xffa9d8e9;

    public FactoryControllerProgramScreen(
            FactoryControllerProgramMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        logSubscribed = menu.isLogSubscribed(inventory.player.getUUID());
    }

    @Override
    protected void init() {
        var currentSource = scriptBox == null ? remoteSource : scriptBox.getValue();
        imageWidth = Math.min(MAX_WIDTH, width - 20);
        imageHeight = Math.min(MAX_HEIGHT, height - 20);
        super.init();

        var editorX = leftPos + FILES_WIDTH + MARGIN * 2;
        var editorY = topPos + HEADER_HEIGHT + MARGIN;
        var editorAreaWidth = imageWidth - FILES_WIDTH - MARGIN * 3;
        var editorAreaHeight = imageHeight - HEADER_HEIGHT - MARGIN * 2;
        // MultiLineEditBox renders its scrollbar to the right of its bounds and
        // its character count below them. Reserve both areas inside our panel.
        var editorWidth = editorAreaWidth - EDITOR_DECORATION_RIGHT;
        var editorHeight = editorAreaHeight - EDITOR_DECORATION_BOTTOM;
        scriptBox = new ScriptEditBox(
                font, editorX, editorY, editorWidth, editorHeight,
                Component.translatable("gui.appliedfactory.script"),
                Component.literal(ControllerProgram.DEFAULT_SOURCE));
        scriptBox.setCharacterLimit(ControllerProgram.MAX_SOURCE_LENGTH);
        scriptBox.setValue(currentSource);
        scriptBox.setEditable(selectedPath != null);
        addRenderableWidget(scriptBox);

        uploadButton = addRenderableWidget(Button.builder(
                Component.literal("↑"),
                ignored -> uploadProgram())
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.appliedfactory.upload_precompiled")))
                .bounds(leftPos + imageWidth - 25, topPos + 5, 19, 18).build());
        mcpButton = addRenderableWidget(Button.builder(
                Component.literal("M"), ignored -> onMcpButton())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.bind_mcp")))
                .bounds(leftPos + imageWidth - 46, topPos + 5, 19, 18).build());
        pullButton = addRenderableWidget(Button.builder(
                Component.literal("↓"),
                ignored -> pullRemoteProgram())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.pull_local")))
                .bounds(leftPos + imageWidth - 67, topPos + 5, 19, 18).build());
        logButton = addRenderableWidget(Button.builder(
                Component.literal(logSubscribed ? "●" : "○"), ignored -> toggleLogSubscription())
                .bounds(leftPos + imageWidth - 88, topPos + 5, 19, 18).build());
        autoReloadButton = addRenderableWidget(Button.builder(
                Component.literal("↻"), ignored -> toggleAutoReload())
                .bounds(leftPos + imageWidth - 109, topPos + 5, 19, 18).build());
        updateLogButton();
        updateAutoReloadButton();

        exportWorkspaceButton = addRenderableWidget(Button.builder(
                Component.literal("W"), ignored -> exportWorkspace())
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.appliedfactory.export_workspace_tooltip")))
                .bounds(leftPos + imageWidth - 130, topPos + 5, 19, 18).build());
        openVscodeButton = addRenderableWidget(Button.builder(
                Component.literal("V"), ignored -> openWorkspaceWith(true))
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.appliedfactory.open_vscode_tooltip")))
                .bounds(leftPos + imageWidth - 151, topPos + 5, 19, 18).build());
        openFolderButton = addRenderableWidget(Button.builder(
                Component.literal("F"), ignored -> openWorkspaceWith(false))
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.appliedfactory.open_folder_tooltip")))
                .bounds(leftPos + imageWidth - 172, topPos + 5, 19, 18).build());

        reloadWorkspaceFiles();
        if (!sourceLoaded) {
            PacketDistributor.sendToServer(new RequestControllerProgramPayload(menu.getBlockPos()));
            setStatus("gui.appliedfactory.loading_program", 0xffffd37a);
        }
        updateButtonStates();
    }

    private void reloadWorkspaceFiles() {
        try {
            var files = ScriptWorkspaceFiles.list();
            var entries = new ArrayList<WorkspaceEntry>(files.size() + 1);
            for (var path : files) {
                entries.add(new WorkspaceEntry(path, false));
                if (isRemoteConflict(path)) {
                    entries.add(new WorkspaceEntry(path, true));
                }
            }
            workspaceEntries = List.copyOf(entries);
            var pages = Math.max(1, (workspaceEntries.size() + rowsPerPage() - 1) / rowsPerPage());
            filePage = Math.min(filePage, pages - 1);
            rebuildFileButtons();
        } catch (IOException exception) {
            setLiteralStatus("Unable to list appliedscripts: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void rebuildFileButtons() {
        fileButtons.forEach(this::removeWidget);
        fileButtons.clear();
        var first = filePage * rowsPerPage();
        var last = Math.min(workspaceEntries.size(), first + rowsPerPage());
        for (int index = first; index < last; index++) {
            var entry = workspaceEntries.get(index);
            var labelWidth = FILES_WIDTH - 20;
            var label = font.plainSubstrByWidth(entry.path(), labelWidth);
            if (entry.remote()) {
                var suffix = Component.translatable(
                        "gui.appliedfactory.remote_file_suffix").getString();
                label = font.plainSubstrByWidth(
                        entry.path(), Math.max(0, labelWidth - font.width(suffix))) + suffix;
            }
            var button = Button.builder(Component.literal(label), ignored -> selectEntry(entry))
                    .bounds(leftPos + MARGIN, topPos + HEADER_HEIGHT + MARGIN
                            + (index - first) * FILE_ROW_HEIGHT,
                            FILES_WIDTH - MARGIN, 18).build();
            fileButtons.add(addRenderableWidget(button));
        }
        var navY = topPos + imageHeight - 25;
        var navLeft = leftPos + MARGIN;
        var navWidth = (FILES_WIDTH - MARGIN * 2 - 5 * 2) / 6;
        int slot = 0;
        newFileButton = addRenderableWidget(Button.builder(
                Component.literal("+"), ignored -> createFile())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.file_new")))
                .bounds(navLeft + slot++ * (navWidth + 2), navY, navWidth, 18).build());
        deleteFileButton = addRenderableWidget(Button.builder(
                Component.literal("×"), ignored -> deleteFile())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.file_delete")))
                .bounds(navLeft + slot++ * (navWidth + 2), navY, navWidth, 18).build());
        renameFileButton = addRenderableWidget(Button.builder(
                Component.literal("✎"), ignored -> renameFile())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.file_rename")))
                .bounds(navLeft + slot++ * (navWidth + 2), navY, navWidth, 18).build());
        pageUpButton = addRenderableWidget(Button.builder(
                Component.literal("▲"), ignored -> {
                    if (filePage > 0) {
                        filePage--;
                        rebuildFileButtons();
                    }
                }).tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.file_page_up")))
                .bounds(navLeft + slot++ * (navWidth + 2), navY, navWidth, 18).build());
        pageDownButton = addRenderableWidget(Button.builder(
                Component.literal("▼"), ignored -> {
                    if ((filePage + 1) * rowsPerPage() < workspaceEntries.size()) {
                        filePage++;
                        rebuildFileButtons();
                    }
                }).tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.file_page_down")))
                .bounds(navLeft + slot++ * (navWidth + 2), navY, navWidth, 18).build());
        refreshFilesButton = addRenderableWidget(Button.builder(
                Component.literal("⟳"), ignored -> reloadWorkspaceFiles())
                .tooltip(Tooltip.create(Component.translatable("gui.appliedfactory.refresh_files")))
                .bounds(navLeft + slot * (navWidth + 2), navY, navWidth, 18).build());
        fileButtons.add(newFileButton);
        fileButtons.add(deleteFileButton);
        fileButtons.add(renameFileButton);
        fileButtons.add(pageUpButton);
        fileButtons.add(pageDownButton);
        fileButtons.add(refreshFilesButton);
        updateButtonStates();
    }

    private int rowsPerPage() {
        return Math.max(1, (imageHeight - HEADER_HEIGHT - MARGIN - 25) / FILE_ROW_HEIGHT);
    }

    private void selectEntry(WorkspaceEntry entry) {
        if (entry.remote()) {
            showRemoteSource();
            setStatus("gui.appliedfactory.remote_snapshot", 0xffffd37a);
        } else {
            selectFile(entry.path());
        }
    }

    private void selectFile(String path) {
        try {
            var source = ScriptWorkspaceFiles.read(path);
            if (!ControllerProgram.isWithinLimit(source)) {
                setStatus("gui.appliedfactory.local_source_too_long", 0xffff7d7d);
                return;
            }
            selectedPath = path;
            scriptBox.setValue(source);
            scriptBox.setEditable(true);
            armFileWatcher(path);
            setLiteralStatus("", 0xffa9d8e9);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Unable to read " + path + ": " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void uploadProgram() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.local_backup_required", 0xffff7d7d);
            return;
        }
        uploadProgram(selectedPath, scriptBox.getValue(), true);
    }

    private void uploadProgram(String path, String source, boolean writeLocal) {
        if (uploadPending) {
            return;
        }
        source = ControllerProgram.normalizeLineEndings(source);
        scriptBox.setValue(source);
        final String compiled;
        try {
            ScriptBundler.requireTypeScriptEntry(path);
            if (writeLocal) {
                ScriptWorkspaceFiles.write(path, source);
                armFileWatcher(path);
            }
            compiled = ScriptBundler.bundle(source, ScriptWorkspaceFiles.absolute(path).getParent());
        } catch (IOException | IllegalArgumentException | McpToolException exception) {
            setLiteralStatus("Precompile failed: " + exception.getMessage(), 0xffff7d7d);
            return;
        }
        if (!ControllerProgram.isWithinLimit(source)
                || !ControllerProgram.isWithinLimit(compiled)) {
            setStatus("gui.appliedfactory.source_too_long", 0xffff7d7d,
                    ControllerProgram.MAX_SOURCE_LENGTH);
            return;
        }
        uploadPending = true;
        pendingUploadSource = source;
        pendingUploadPath = path;
        setStatus("gui.appliedfactory.saving", 0xffffd37a);
        PacketDistributor.sendToServer(new SaveControllerProgramPayload(
                menu.getBlockPos(), source, compiled, path));
        updateButtonStates();
    }

    private void pullRemoteProgram() {
        if (!sourceLoaded) {
            return;
        }
        try {
            if (!remotePath.isBlank() && ScriptWorkspaceFiles.exists(remotePath)
                    && !ScriptWorkspaceFiles.read(remotePath).equals(remoteSource)) {
                var path = remotePath;
                minecraft.setScreen(new ConfirmScreen(confirmed -> {
                    minecraft.setScreen(this);
                    if (confirmed) {
                        writeRemoteFile(path);
                    }
                }, Component.translatable("gui.appliedfactory.confirm_pull_title"),
                        Component.translatable("gui.appliedfactory.confirm_pull", path)));
                return;
            }
            var path = !remotePath.isBlank() && ScriptWorkspaceFiles.exists(remotePath)
                    ? remotePath : ScriptWorkspaceFiles.availableDownloadPath(remotePath);
            writeRemoteFile(path);
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Pull failed: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void writeRemoteFile(String path) {
        try {
            if (!ScriptWorkspaceFiles.exists(path)
                    || !ScriptWorkspaceFiles.read(path).equals(remoteSource)) {
                ScriptWorkspaceFiles.write(path, remoteSource);
            }
            selectedPath = path;
            scriptBox.setValue(remoteSource);
            scriptBox.setEditable(true);
            armFileWatcher(path);
            reloadWorkspaceFiles();
            setStatus("gui.appliedfactory.pull_success", 0xff8fe3a1);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Pull failed: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void exportWorkspace() {
        var ok = ClientWorkspaceBuilder.build();
        reloadWorkspaceFiles();
        if (ok) {
            setStatus("gui.appliedfactory.export_workspace_done", 0xff8fe3a1);
        } else {
            setStatus("gui.appliedfactory.export_workspace_failed", 0xffff7d7d);
        }
    }

    private void openWorkspaceWith(boolean vscode) {
        var dir = ScriptBundler.workspaceDir().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            var path = dir.toString();
            var command = switch (Util.getPlatform()) {
                case WINDOWS -> vscode
                        ? List.of("cmd.exe", "/c", "code", path)
                        : List.of("explorer.exe", path);
                case OSX -> vscode
                        ? List.of("open", "-a", "Visual Studio Code", path)
                        : List.of("open", path);
                default -> vscode
                        ? List.of("code", path)
                        : List.of("xdg-open", path);
            };
            new ProcessBuilder(command).start();
            setStatus(vscode
                    ? "gui.appliedfactory.opened_vscode"
                    : "gui.appliedfactory.opened_folder", 0xffa9d8e9);
        } catch (IOException | RuntimeException exception) {
            setLiteralStatus("Open failed: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private void createFile() {
        openNamePrompt("gui.appliedfactory.new_file_title", "gui.appliedfactory.file_new", "",
                name -> {
                    var path = normalizeNewFilePath(name);
                    if (path == null) {
                        setStatus("gui.appliedfactory.invalid_file_name", 0xffff7d7d);
                        return;
                    }
                    try {
                        if (ScriptWorkspaceFiles.exists(path)) {
                            setStatus("gui.appliedfactory.file_exists", 0xffff7d7d, path);
                            return;
                        }
                        ScriptWorkspaceFiles.write(path, "");
                        reloadWorkspaceFiles();
                        selectFile(path);
                        setStatus("gui.appliedfactory.file_created", 0xff8fe3a1, path);
                    } catch (IOException | IllegalArgumentException exception) {
                        setLiteralStatus("Create failed: " + exception.getMessage(), 0xffff7d7d);
                    }
                });
    }

    private void deleteFile() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.no_local_file", 0xffff7d7d);
            return;
        }
        var path = selectedPath;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (!confirmed) {
                return;
            }
            try {
                ScriptWorkspaceFiles.delete(path);
                if (path.equals(selectedPath)) {
                    selectedPath = null;
                    watchedPath = null;
                    watchedModifiedAt = -1L;
                    autoReloadDueAt = -1L;
                }
                reloadWorkspaceFiles();
                updateButtonStates();
                setStatus("gui.appliedfactory.file_deleted", 0xff8fe3a1, path);
            } catch (IOException | IllegalArgumentException exception) {
                setLiteralStatus("Delete failed: " + exception.getMessage(), 0xffff7d7d);
            }
        }, Component.translatable("gui.appliedfactory.confirm_delete_title"),
                Component.translatable("gui.appliedfactory.confirm_delete", path)));
    }

    private void renameFile() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.no_local_file", 0xffff7d7d);
            return;
        }
        var oldPath = selectedPath;
        openNamePrompt("gui.appliedfactory.rename_file_title",
                "gui.appliedfactory.file_rename", oldPath, name -> {
                    var path = normalizeNewFilePath(name);
                    if (path == null) {
                        setStatus("gui.appliedfactory.invalid_file_name", 0xffff7d7d);
                        return;
                    }
                    if (path.equals(oldPath)) {
                        return;
                    }
                    try {
                        if (ScriptWorkspaceFiles.exists(path)) {
                            setStatus("gui.appliedfactory.file_exists", 0xffff7d7d, path);
                            return;
                        }
                        ScriptWorkspaceFiles.rename(oldPath, path);
                        selectedPath = path;
                        reloadWorkspaceFiles();
                        armFileWatcher(path);
                        setStatus("gui.appliedfactory.file_renamed", 0xff8fe3a1, path);
                    } catch (IOException | IllegalArgumentException exception) {
                        setLiteralStatus("Rename failed: " + exception.getMessage(), 0xffff7d7d);
                    }
                });
    }

    private void openNamePrompt(
            String titleKey, String confirmKey, String initial, Consumer<String> onSubmit) {
        minecraft.setScreen(new WorkspaceStringPromptScreen(
                Component.translatable(titleKey),
                Component.translatable(confirmKey),
                initial,
                200,
                value -> {
                    minecraft.setScreen(this);
                    if (value != null && !value.isBlank()) {
                        onSubmit.accept(value);
                    }
                }));
    }

    private static String normalizeNewFilePath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var trimmed = name.trim().replace('\\', '/');
        if (trimmed.startsWith("/") || trimmed.contains("..")) {
            return null;
        }
        var slash = trimmed.lastIndexOf('/');
        var base = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        if (!base.contains(".")) {
            trimmed = trimmed + ".ts";
        }
        return trimmed;
    }

    private boolean boundHere() {
        var binding = McpClientManager.get().binding();
        return binding != null && binding.pos().equals(menu.getBlockPos());
    }

    private void onMcpButton() {
        if (boundHere()) {
            McpClientManager.get().unbind();
        } else {
            McpClientManager.get().requestBind(menu.getBlockPos());
        }
    }

    private void toggleLogSubscription() {
        logSubscribed = !logSubscribed;
        PacketDistributor.sendToServer(new SetControllerLogSubscriptionPayload(
                menu.getBlockPos(), logSubscribed));
        updateLogButton();
    }

    private void updateLogButton() {
        if (logButton == null) {
            return;
        }
        logButton.setMessage(Component.literal(logSubscribed ? "●" : "○"));
        logButton.setTooltip(Tooltip.create(Component.translatable(logSubscribed
                ? "gui.appliedfactory.unsubscribe_logs"
                : "gui.appliedfactory.subscribe_logs")));
    }

    public void showSaveResult(ControllerProgramSaveResultPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos())) {
            return;
        }
        uploadPending = false;
        if (payload.saved() && pendingUploadSource != null && pendingUploadPath != null) {
            remoteSource = pendingUploadSource;
            remotePath = pendingUploadPath;
            remoteUpdatedAt = payload.updatedAt();
            setStatus("gui.appliedfactory.save_success", 0xff8fe3a1);
        } else {
            setStatus("gui.appliedfactory.syntax_error", 0xffff7d7d, payload.message());
        }
        pendingUploadSource = null;
        pendingUploadPath = null;
        reloadWorkspaceFiles();
        updateButtonStates();
    }

    public void showProgramContent(ControllerProgramContentPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos()) || scriptBox == null) {
            return;
        }
        sourceLoaded = true;
        remoteSource = payload.source();
        remotePath = payload.workspacePath();
        remoteUpdatedAt = payload.updatedAt();
        if (selectedPath != null) {
            reloadWorkspaceFiles();
            updateButtonStates();
            return;
        }
        try {
            if (!remotePath.isBlank() && ScriptWorkspaceFiles.exists(remotePath)) {
                var localSource = ScriptWorkspaceFiles.read(remotePath);
                if (localSource.equals(remoteSource)) {
                    selectFile(remotePath);
                    setStatus("gui.appliedfactory.local_file_matched", 0xffa9d8e9);
                } else if (ScriptWorkspaceFiles.lastModifiedMillis(remotePath) > remoteUpdatedAt) {
                    selectFile(remotePath);
                    setStatus("gui.appliedfactory.local_file_newer", 0xffffd37a);
                } else {
                    showRemoteSource();
                    setStatus("gui.appliedfactory.remote_file_newer", 0xffffd37a);
                }
                reloadWorkspaceFiles();
                updateButtonStates();
                return;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Fall through to the unbacked remote view.
        }
        showRemoteSource();
        setStatus("gui.appliedfactory.remote_unbacked", 0xffa9d8e9);
        reloadWorkspaceFiles();
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (uploadButton != null) {
            uploadButton.active = sourceLoaded && selectedPath != null && !uploadPending;
        }
        if (pullButton != null) {
            pullButton.active = sourceLoaded && selectedPath == null && !uploadPending;
        }
        if (deleteFileButton != null) {
            deleteFileButton.active = selectedPath != null;
        }
        if (renameFileButton != null) {
            renameFileButton.active = selectedPath != null;
        }
        if (pageUpButton != null) {
            pageUpButton.active = filePage > 0;
        }
        if (pageDownButton != null) {
            pageDownButton.active =
                    (filePage + 1) * rowsPerPage() < workspaceEntries.size();
        }
    }

    private void setStatus(String key, int color, Object... args) {
        saveStatus = Component.translatable(key, args);
        saveStatusColor = color;
    }

    private void setLiteralStatus(String value, int color) {
        saveStatus = Component.literal(value);
        saveStatusColor = color;
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        return scriptBox.mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return scriptBox.mouseReleased(mouseX, mouseY, button)
                || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= leftPos + MARGIN && mouseX < leftPos + FILES_WIDTH
                && mouseY >= topPos + HEADER_HEIGHT
                && mouseY < topPos + imageHeight) {
            var nextPage = filePage + (verticalAmount > 0 ? -1 : verticalAmount < 0 ? 1 : 0);
            var lastPage = Math.max(0, (workspaceEntries.size() - 1) / rowsPerPage());
            nextPage = Math.max(0, Math.min(lastPage, nextPage));
            if (nextPage != filePage) {
                filePage = nextPage;
                rebuildFileButtons();
            }
            return true;
        }
        if (scriptBox.isMouseOver(mouseX, mouseY)
                && scriptBox.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        mcpButton.setTooltip(Tooltip.create(Component.translatable(
                boundHere() ? "gui.appliedfactory.unbind_mcp" : "gui.appliedfactory.bind_mcp")));
        pollAutoReload();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight, 0xff17232e, 0xff5d95ad);
        drawPanel(graphics, leftPos + 4, topPos + 4, imageWidth - 8, HEADER_HEIGHT - 4,
                0xff263b49, 0xff72b6d1);
        drawPanel(graphics, leftPos + MARGIN - 2, topPos + HEADER_HEIGHT + MARGIN - 2,
                FILES_WIDTH - MARGIN + 4, imageHeight - HEADER_HEIGHT - MARGIN * 2 + 4,
                0xff0e171e, 0xff3f6d81);
        drawPanel(graphics, leftPos + FILES_WIDTH + MARGIN * 2 - 2,
                topPos + HEADER_HEIGHT + MARGIN - 2,
                imageWidth - FILES_WIDTH - MARGIN * 3 + 4,
                imageHeight - HEADER_HEIGHT - MARGIN * 2 + 4,
                0xff0e171e, 0xff3f6d81);
    }

    private static void drawPanel(
            GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        var fileName = currentFileName();
        var status = saveStatus.getString();
        var caption = status.isEmpty() ? fileName : fileName + ":" + status;
        graphics.drawString(font, font.plainSubstrByWidth(caption, imageWidth - 192),
                10, 10, saveStatusColor);
    }

    private String currentFileName() {
        var path = selectedPath;
        if (path == null || path.isBlank()) {
            return Component.translatable("gui.appliedfactory.no_local_file").getString();
        }
        var slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private boolean isRemoteConflict(String path) {
        if (!sourceLoaded || remotePath.isBlank() || !remotePath.equals(path)) {
            return false;
        }
        try {
            return !ScriptWorkspaceFiles.read(path).equals(remoteSource);
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private void showRemoteSource() {
        selectedPath = null;
        watchedPath = null;
        watchedModifiedAt = -1L;
        autoReloadDueAt = -1L;
        scriptBox.setValue(remoteSource);
        scriptBox.setEditable(false);
        setFocused(null);
        updateButtonStates();
    }

    private void toggleAutoReload() {
        autoReload = !autoReload;
        autoReloadDueAt = -1L;
        if (autoReload && selectedPath != null) {
            armFileWatcher(selectedPath);
        }
        setStatus(autoReload
                ? "gui.appliedfactory.auto_reload_enabled"
                : "gui.appliedfactory.auto_reload_disabled", 0xffa9d8e9);
        updateAutoReloadButton();
    }

    private void updateAutoReloadButton() {
        if (autoReloadButton == null) {
            return;
        }
        autoReloadButton.setMessage(Component.literal(autoReload ? "⟳" : "↻"));
        autoReloadButton.setTooltip(Tooltip.create(Component.translatable(autoReload
                ? "gui.appliedfactory.disable_auto_reload"
                : "gui.appliedfactory.enable_auto_reload")));
    }

    private void armFileWatcher(String path) {
        watchedPath = path;
        autoReloadDueAt = -1L;
        try {
            watchedModifiedAt = ScriptWorkspaceFiles.lastModifiedMillis(path);
        } catch (IOException | IllegalArgumentException ignored) {
            watchedModifiedAt = -1L;
        }
    }

    private void pollAutoReload() {
        if (!autoReload || selectedPath == null) {
            return;
        }
        if (!selectedPath.equals(watchedPath)) {
            armFileWatcher(selectedPath);
            return;
        }
        try {
            var modifiedAt = ScriptWorkspaceFiles.lastModifiedMillis(selectedPath);
            if (modifiedAt != watchedModifiedAt) {
                watchedModifiedAt = modifiedAt;
                autoReloadDueAt = Util.getMillis() + AUTO_RELOAD_DEBOUNCE_MILLIS;
                return;
            }
            if (autoReloadDueAt < 0L || Util.getMillis() < autoReloadDueAt || uploadPending) {
                return;
            }
            autoReloadDueAt = -1L;
            var source = ScriptWorkspaceFiles.read(selectedPath);
            scriptBox.setValue(source);
            uploadProgram(selectedPath, source, false);
        } catch (IOException | IllegalArgumentException exception) {
            autoReloadDueAt = -1L;
            setLiteralStatus("Auto reload failed: " + exception.getMessage(), 0xffff7d7d);
        }
    }

    private record WorkspaceEntry(String path, boolean remote) {
    }
}
