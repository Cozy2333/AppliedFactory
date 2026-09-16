package com.fulent.appliedfactory.client;

import com.fulent.appliedfactory.mcp.McpClientManager;
import com.fulent.appliedfactory.network.ControllerProgramSaveResultPayload;
import com.fulent.appliedfactory.network.ControllerProgramContentPayload;

import net.minecraft.client.Minecraft;

/** Client-only bridge keeping client GUI references out of server-side controller logic. */
public final class ClientControllerProgramPayloadHandler {
    private ClientControllerProgramPayloadHandler() {
    }

    public static void handleSaveResult(ControllerProgramSaveResultPayload payload) {
        ClientProgramWatcher.get().onSaveResult(payload);
        if (Minecraft.getInstance().screen instanceof FactoryControllerProgramScreen editor) {
            editor.showSaveResult(payload);
        }
    }

    public static void handleProgramContent(ControllerProgramContentPayload payload) {
        var binding = McpClientManager.get().binding();
        if (binding != null && binding.pos().equals(payload.pos())) {
            McpClientManager.get().updateProgramPath(payload.workspacePath());
        }
        if (Minecraft.getInstance().screen instanceof FactoryControllerProgramScreen editor) {
            editor.showProgramContent(payload);
        }
    }
}
