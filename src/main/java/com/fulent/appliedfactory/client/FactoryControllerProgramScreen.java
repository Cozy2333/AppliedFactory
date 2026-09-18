package com.fulent.appliedfactory.client;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

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
import com.fulent.appliedfactory.client.FactoryIconButton.Symbol;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
    private static final int HEADER_HEIGHT = 36;
    private static final int MAX_FILES_WIDTH = 190;
    private static final int FILE_ROW_HEIGHT = 19;
    private static final int FILES_FOOTER_HEIGHT = 30;
    private static final int TOOLBAR_STEP = 21;
    private static final int HEADER_ACTIONS = 7;

    private final List<Button> fileButtons = new ArrayList<>();
    private ScriptEditBox scriptBox;
    private FactoryIconButton logButton;
    private FactoryIconButton saveButton;
    private FactoryIconButton uploadButton;
    private FactoryIconButton pullButton;
    private FactoryIconButton mcpButton;
    private FactoryIconButton autoReloadButton;
    private FactoryIconButton deleteFileButton;
    private FactoryIconButton renameFileButton;
    private FactoryIconButton pageUpButton;
    private FactoryIconButton pageDownButton;
    private int filesWidth;
    private List<WorkspaceEntry> workspaceEntries = List.of();
    private String selectedPath;
    private String remotePath = "";
    private String remoteSource = ControllerProgram.DEFAULT_SOURCE;
    private long remoteUpdatedAt;
    private boolean sourceLoaded;
    private boolean logSubscribed;
    private boolean uploadPending;
    private UUID pendingSaveRequestId;
    private String pendingUploadSource;
    private String pendingUploadPath;
    private int filePage;
    private Component saveStatus = Component.empty();
    private int saveStatusColor = FactoryGuiTheme.TEXT;

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

        filesWidth = Math.min(MAX_FILES_WIDTH, Math.max(156, imageWidth / 4 + 10));
        var editorX = leftPos + filesWidth + MARGIN * 2;
        var editorY = topPos + HEADER_HEIGHT;
        var editorWidth = imageWidth - filesWidth - MARGIN * 3;
        var editorHeight = imageHeight - HEADER_HEIGHT - MARGIN;
        scriptBox = new ScriptEditBox(
                font, editorX, editorY, editorWidth, editorHeight,
                Component.empty(), Component.translatable("gui.appliedfactory.script"));
        scriptBox.setCharacterLimit(ControllerProgram.MAX_SOURCE_LENGTH);
        scriptBox.setValue(currentSource);
        scriptBox.setEditable(selectedPath != null);
        addRenderableWidget(scriptBox);

        var toolsX = headerActionsX();
        var toolsY = topPos + 8;
        addTool(toolsX, toolsY, Symbol.WORKSPACE, "gui.appliedfactory.export_workspace_tooltip",
                ignored -> exportWorkspace());
        autoReloadButton = addTool(toolsX + TOOLBAR_STEP, toolsY, Symbol.REFRESH,
                "gui.appliedfactory.enable_auto_reload", ignored -> toggleAutoReload());
        logButton = addTool(toolsX + TOOLBAR_STEP * 2, toolsY, Symbol.LOG,
                "gui.appliedfactory.subscribe_logs", ignored -> toggleLogSubscription());
        mcpButton = addTool(toolsX + TOOLBAR_STEP * 3, toolsY, Symbol.LINK,
                "gui.appliedfactory.bind_mcp", ignored -> onMcpButton());
        pullButton = addTool(toolsX + TOOLBAR_STEP * 4, toolsY, Symbol.DOWNLOAD,
                "gui.appliedfactory.pull_local", ignored -> pullRemoteProgram());
        uploadButton = addTool(toolsX + TOOLBAR_STEP * 5, toolsY, Symbol.UPLOAD,
                "gui.appliedfactory.upload_precompiled", ignored -> uploadProgram());
        saveButton = addTool(toolsX + TOOLBAR_STEP * 6, toolsY, Symbol.SAVE,
                "gui.appliedfactory.save_tooltip", ignored -> saveProgram());
        updateLogButton();
        updateAutoReloadButton();
        initFileTools();
        reloadWorkspaceFiles();
        if (!sourceLoaded) {
            PacketDistributor.sendToServer(new RequestControllerProgramPayload(menu.getBlockPos()));
            setStatus("gui.appliedfactory.loading_program", FactoryGuiTheme.WARNING);
        }
        updateButtonStates();
    }

    private int headerActionsX() {
        return leftPos + imageWidth - MARGIN - FactoryIconButton.WIDTH - (HEADER_ACTIONS - 1) * TOOLBAR_STEP;
    }

    private int fileFooterTop() {
        return topPos + imageHeight - MARGIN - FILES_FOOTER_HEIGHT;
    }

    private FactoryIconButton addTool(int x, int y, Symbol symbol, String tooltipKey, Button.OnPress onPress) {
        return addRenderableWidget(new FactoryIconButton(x, y, symbol, tooltipKey, onPress));
    }

    private void initFileTools() {
        var x = leftPos + MARGIN + 6;
        var y = fileFooterTop() + 6;
        addTool(x, y, Symbol.NEW, "gui.appliedfactory.file_new", ignored -> createFile());
        deleteFileButton = addTool(x + TOOLBAR_STEP, y, Symbol.DELETE,
                "gui.appliedfactory.file_delete", ignored -> deleteFile());
        renameFileButton = addTool(x + TOOLBAR_STEP * 2, y, Symbol.RENAME,
                "gui.appliedfactory.file_rename", ignored -> renameFile());
        addTool(x + TOOLBAR_STEP * 3, y, Symbol.REFRESH,
                "gui.appliedfactory.refresh_files", ignored -> reloadWorkspaceFiles());
        addTool(x + TOOLBAR_STEP * 4, y, Symbol.FOLDER,
                "gui.appliedfactory.open_folder_tooltip", ignored -> openWorkspaceWith(false));
        addTool(x + TOOLBAR_STEP * 5, y, Symbol.CODE,
                "gui.appliedfactory.open_vscode_tooltip", ignored -> openWorkspaceWith(true));
        var pageX = leftPos + MARGIN + filesWidth - 16;
        pageUpButton = addRenderableWidget(new FactoryIconButton(pageX, y, 10, 10,
                Symbol.PAGE_UP, "gui.appliedfactory.file_page_up", ignored -> changeFilePage(-1)));
        pageDownButton = addRenderableWidget(new FactoryIconButton(pageX, y + 10, 10, 10,
                Symbol.PAGE_DOWN, "gui.appliedfactory.file_page_down", ignored -> changeFilePage(1)));
    }

    private void changeFilePage(int delta) {
        var lastPage = Math.max(0, (workspaceEntries.size() - 1) / rowsPerPage());
        var nextPage = Math.max(0, Math.min(lastPage, filePage + delta));
        if (nextPage != filePage) {
            filePage = nextPage;
            rebuildFileButtons();
        }
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
            setLiteralStatus("Unable to list appliedscripts: " + exception.getMessage(), FactoryGuiTheme.ERROR);
        }
    }

    private void rebuildFileButtons() {
        fileButtons.forEach(this::removeWidget);
        fileButtons.clear();
        var first = filePage * rowsPerPage();
        var last = Math.min(workspaceEntries.size(), first + rowsPerPage());
        for (int index = first; index < last; index++) {
            var entry = workspaceEntries.get(index);
            var label = Component.literal(entry.path());
            if (entry.remote()) {
                label.append(Component.translatable("gui.appliedfactory.remote_file_suffix"));
            }
            var button = new FactoryFileRowButton(leftPos + MARGIN + 2,
                    topPos + HEADER_HEIGHT + 4 + (index - first) * FILE_ROW_HEIGHT,
                    filesWidth - 4, FILE_ROW_HEIGHT, label,
                    () -> entry.remote() ? selectedPath == null && entry.path().equals(remotePath)
                            : entry.path().equals(selectedPath),
                    ignored -> selectEntry(entry));
            fileButtons.add(addRenderableWidget(button));
        }
        updateButtonStates();
    }

    private int rowsPerPage() {
        return Math.max(1, (fileFooterTop() - topPos - HEADER_HEIGHT - 8) / FILE_ROW_HEIGHT);
    }

    private void selectEntry(WorkspaceEntry entry) {
        if (entry.remote()) {
            showRemoteSource();
            setStatus("gui.appliedfactory.remote_snapshot", FactoryGuiTheme.WARNING);
        } else {
            selectFile(entry.path());
        }
    }

    private void selectFile(String path) {
        try {
            var source = ScriptWorkspaceFiles.read(path);
            if (!ControllerProgram.isWithinLimit(source)) {
                setStatus("gui.appliedfactory.local_source_too_long", FactoryGuiTheme.ERROR);
                return;
            }
            selectedPath = path;
            scriptBox.setValue(source);
            scriptBox.setEditable(true);
            ClientProgramWatcher.get().track(path);
            setLiteralStatus("", FactoryGuiTheme.TEXT);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Unable to read " + path + ": " + exception.getMessage(), FactoryGuiTheme.ERROR);
        }
    }

    private void uploadProgram() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.local_backup_required", FactoryGuiTheme.ERROR);
            return;
        }
        var path = selectedPath;
        uploadProgram(path, scriptBox.getValue());
    }

    /**
     * Writes the editor contents to the selected local file without contacting
     * the controller; uploading stays on the upload action.
     */
    private void saveProgram() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.local_backup_required", FactoryGuiTheme.ERROR);
            return;
        }
        var source = ControllerProgram.normalizeLineEndings(scriptBox.getValue());
        scriptBox.setValue(source);
        if (!ControllerProgram.isWithinLimit(source)) {
            setStatus("gui.appliedfactory.local_source_too_long", FactoryGuiTheme.ERROR);
            return;
        }
        try {
            ScriptWorkspaceFiles.write(selectedPath, source);
            ClientProgramWatcher.get().track(selectedPath);
            setStatus("gui.appliedfactory.save_local_success", FactoryGuiTheme.SUCCESS, selectedPath);
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Save failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
        }
    }

    private void uploadProgram(String path, String rawSource) {
        if (uploadPending) {
            return;
        }
        var source = ControllerProgram.normalizeLineEndings(rawSource);
        scriptBox.setValue(source);
        final String compiled;
        try {
            ScriptBundler.requireTypeScriptEntry(path);
            ScriptWorkspaceFiles.write(path, source);
            // Keep the background watcher in step so our own write does not
            // immediately trigger a redundant auto-reload upload.
            ClientProgramWatcher.get().track(path);
            compiled = ScriptBundler.bundle(source, ScriptWorkspaceFiles.absolute(path).getParent());
        } catch (IOException | IllegalArgumentException | McpToolException exception) {
            setLiteralStatus("Precompile failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
            return;
        }
        if (!ControllerProgram.isWithinLimit(source)
                || !ControllerProgram.isWithinLimit(compiled)) {
            setStatus("gui.appliedfactory.source_too_long", FactoryGuiTheme.ERROR,
                    ControllerProgram.MAX_SOURCE_LENGTH);
            return;
        }
        uploadPending = true;
        pendingSaveRequestId = UUID.randomUUID();
        pendingUploadSource = source;
        pendingUploadPath = path;
        setStatus("gui.appliedfactory.saving", FactoryGuiTheme.WARNING);
        PacketDistributor.sendToServer(new SaveControllerProgramPayload(
                pendingSaveRequestId, menu.getBlockPos(), source, compiled, path));
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
            setLiteralStatus("Pull failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
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
            ClientProgramWatcher.get().track(path);
            reloadWorkspaceFiles();
            setStatus("gui.appliedfactory.pull_success", FactoryGuiTheme.SUCCESS);
            updateButtonStates();
        } catch (IOException | IllegalArgumentException exception) {
            setLiteralStatus("Pull failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
        }
    }

    private void exportWorkspace() {
        var ok = ClientWorkspaceBuilder.build();
        reloadWorkspaceFiles();
        if (ok) {
            setStatus("gui.appliedfactory.export_workspace_done", FactoryGuiTheme.SUCCESS);
        } else {
            setStatus("gui.appliedfactory.export_workspace_failed", FactoryGuiTheme.ERROR);
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
                    : "gui.appliedfactory.opened_folder", FactoryGuiTheme.TEXT);
        } catch (IOException | RuntimeException exception) {
            setLiteralStatus("Open failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
        }
    }

    private void createFile() {
        openNamePrompt("gui.appliedfactory.new_file_title", "gui.appliedfactory.file_new", "",
                name -> {
                    var path = normalizeNewFilePath(name);
                    if (path == null) {
                        setStatus("gui.appliedfactory.invalid_file_name", FactoryGuiTheme.ERROR);
                        return;
                    }
                    try {
                        if (ScriptWorkspaceFiles.exists(path)) {
                            setStatus("gui.appliedfactory.file_exists", FactoryGuiTheme.ERROR, path);
                            return;
                        }
                        ScriptWorkspaceFiles.write(path, "");
                        reloadWorkspaceFiles();
                        selectFile(path);
                        setStatus("gui.appliedfactory.file_created", FactoryGuiTheme.SUCCESS, path);
                    } catch (IOException | IllegalArgumentException exception) {
                        setLiteralStatus("Create failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
                    }
                });
    }

    private void deleteFile() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.no_local_file", FactoryGuiTheme.ERROR);
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
                    ClientProgramWatcher.get().track("");
                }
                reloadWorkspaceFiles();
                updateButtonStates();
                setStatus("gui.appliedfactory.file_deleted", FactoryGuiTheme.SUCCESS, path);
            } catch (IOException | IllegalArgumentException exception) {
                setLiteralStatus("Delete failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
            }
        }, Component.translatable("gui.appliedfactory.confirm_delete_title"),
                Component.translatable("gui.appliedfactory.confirm_delete", path)));
    }

    private void renameFile() {
        if (selectedPath == null) {
            setStatus("gui.appliedfactory.no_local_file", FactoryGuiTheme.ERROR);
            return;
        }
        var oldPath = selectedPath;
        openNamePrompt("gui.appliedfactory.rename_file_title",
                "gui.appliedfactory.file_rename", oldPath, name -> {
                    var path = normalizeNewFilePath(name);
                    if (path == null) {
                        setStatus("gui.appliedfactory.invalid_file_name", FactoryGuiTheme.ERROR);
                        return;
                    }
                    if (path.equals(oldPath)) {
                        return;
                    }
                    try {
                        if (ScriptWorkspaceFiles.exists(path)) {
                            setStatus("gui.appliedfactory.file_exists", FactoryGuiTheme.ERROR, path);
                            return;
                        }
                        ScriptWorkspaceFiles.rename(oldPath, path);
                        selectedPath = path;
                        reloadWorkspaceFiles();
                        ClientProgramWatcher.get().track(path);
                        setStatus("gui.appliedfactory.file_renamed", FactoryGuiTheme.SUCCESS, path);
                    } catch (IOException | IllegalArgumentException exception) {
                        setLiteralStatus("Rename failed: " + exception.getMessage(), FactoryGuiTheme.ERROR);
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
        logButton.setSelected(logSubscribed);
        logButton.setActionLabel(logSubscribed
                ? "gui.appliedfactory.unsubscribe_logs"
                : "gui.appliedfactory.subscribe_logs");
    }

    public void showSaveResult(ControllerProgramSaveResultPayload payload) {
        if (!menu.getBlockPos().equals(payload.pos())
                || pendingSaveRequestId == null
                || !pendingSaveRequestId.equals(payload.requestId())) {
            return;
        }
        pendingSaveRequestId = null;
        uploadPending = false;
        if (payload.saved() && pendingUploadSource != null && pendingUploadPath != null) {
            remoteSource = pendingUploadSource;
            remotePath = pendingUploadPath;
            remoteUpdatedAt = payload.updatedAt();
            McpClientManager.get().updateProgramPath(pendingUploadPath);
            setStatus("gui.appliedfactory.save_success", FactoryGuiTheme.SUCCESS);
        } else {
            setStatus("gui.appliedfactory.syntax_error", FactoryGuiTheme.ERROR, payload.message());
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
                    setStatus("gui.appliedfactory.local_file_matched", FactoryGuiTheme.TEXT);
                } else if (ScriptWorkspaceFiles.lastModifiedMillis(remotePath) > remoteUpdatedAt) {
                    selectFile(remotePath);
                    setStatus("gui.appliedfactory.local_file_newer", FactoryGuiTheme.WARNING);
                } else {
                    showRemoteSource();
                    setStatus("gui.appliedfactory.remote_file_newer", FactoryGuiTheme.WARNING);
                }
                reloadWorkspaceFiles();
                updateButtonStates();
                return;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Fall through to the unbacked remote view.
        }
        showRemoteSource();
        setStatus("gui.appliedfactory.remote_unbacked", FactoryGuiTheme.TEXT);
        reloadWorkspaceFiles();
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (scriptBox != null) {
            scriptBox.setEditable(selectedPath != null);
        }
        if (uploadButton != null) {
            uploadButton.active = sourceLoaded && selectedPath != null && !uploadPending;
        }
        if (saveButton != null) {
            saveButton.active = selectedPath != null;
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_S && hasControlDown()) {
            saveProgram();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        if (mouseX >= leftPos + MARGIN && mouseX < leftPos + MARGIN + filesWidth
                && mouseY >= topPos + HEADER_HEIGHT
                && mouseY < fileFooterTop()) {
            changeFilePage(verticalAmount > 0 ? -1 : verticalAmount < 0 ? 1 : 0);
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
        mcpButton.setSelected(boundHere());
        mcpButton.setActionLabel(boundHere() ? "gui.appliedfactory.unbind_mcp" : "gui.appliedfactory.bind_mcp");
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FactoryGuiTheme.window(graphics, leftPos, topPos, imageWidth, imageHeight);
        FactoryGuiTheme.panel(graphics, leftPos + MARGIN, topPos + HEADER_HEIGHT,
                filesWidth, imageHeight - HEADER_HEIGHT - MARGIN,
                FactoryGuiTheme.BORDER, FactoryGuiTheme.FILES, 1);
        graphics.fill(leftPos + MARGIN + 6, fileFooterTop(), leftPos + MARGIN + filesWidth - 6,
                fileFooterTop() + 1, FactoryGuiTheme.BORDER);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        var captionWidth = Math.max(0, headerActionsX() - leftPos - MARGIN - 8);
        graphics.drawString(font, font.plainSubstrByWidth("Applied Factory / " + currentFileName(), captionWidth),
                MARGIN, 8, FactoryGuiTheme.TEXT, false);
        if (!saveStatus.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(saveStatus.getString(), captionWidth),
                    MARGIN, 22, saveStatusColor, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mouseX >= leftPos + MARGIN && mouseX < headerActionsX() - 8
                && mouseY >= topPos + 6 && mouseY < topPos + HEADER_HEIGHT - 2) {
            var tooltip = mouseY >= topPos + 20 && !saveStatus.getString().isEmpty()
                    ? saveStatus : Component.literal(selectedPath == null ? remotePath : selectedPath);
            if (!tooltip.getString().isEmpty()) {
                graphics.renderTooltip(font, tooltip, mouseX, mouseY);
            }
        }
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
        ClientProgramWatcher.get().track("");
        scriptBox.setValue(remoteSource);
        scriptBox.setEditable(false);
        setFocused(null);
        updateButtonStates();
    }

    private void toggleAutoReload() {
        var watcher = ClientProgramWatcher.get();
        watcher.setAutoReload(!watcher.isAutoReload());
        if (watcher.isAutoReload()) {
            watcher.track(selectedPath);
        }
        setStatus(watcher.isAutoReload()
                ? "gui.appliedfactory.auto_reload_enabled"
                : "gui.appliedfactory.auto_reload_disabled", FactoryGuiTheme.TEXT);
        updateAutoReloadButton();
    }

    private void updateAutoReloadButton() {
        if (autoReloadButton == null) {
            return;
        }
        var autoReload = ClientProgramWatcher.get().isAutoReload();
        autoReloadButton.setSelected(autoReload);
        autoReloadButton.setActionLabel(autoReload
                ? "gui.appliedfactory.disable_auto_reload"
                : "gui.appliedfactory.enable_auto_reload");
    }

    private record WorkspaceEntry(String path, boolean remote) {
    }
}
