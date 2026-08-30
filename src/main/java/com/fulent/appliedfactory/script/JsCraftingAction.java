package com.fulent.appliedfactory.script;

import com.fulent.appliedfactory.factory.FactoryAction;
import com.fulent.appliedfactory.factory.FactoryCraftingAction;

/** Opaque JavaScript facade for a yielded AE network crafting request. */
@JsBridge
final class JsCraftingAction {
    private final FactoryCraftingAction action;

    JsCraftingAction(FactoryCraftingAction action) {
        this.action = action;
    }

    FactoryAction action() {
        return action;
    }
}
