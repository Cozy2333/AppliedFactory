package com.fulent.appliedfactory.factory;

import java.util.Objects;

/** Immutable post-break tool and drop handles written back to the same source. */
public record FactoryBreakResult(
        FactoryResourceRef tool,
        FactoryResourceRef drops) {

    public FactoryBreakResult {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(drops, "drops");
        if (!tool.origin().equals(drops.origin())) {
            throw new IllegalArgumentException("Break results must share one origin");
        }
    }
}
