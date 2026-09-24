package com.fulent.appliedfactory.factory;

import java.util.Objects;

/** Post-break handles; the tool and collected drops may have different storage origins. */
public record FactoryBreakResult(
        FactoryResourceRef tool,
        FactoryResourceRef drops) {

    public FactoryBreakResult {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(drops, "drops");
    }
}
