package com.fulent.appliedfactory.script;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Program snapshot carried on the dropped/picked-up controller item and on the block
 * entity, so a replaced controller restores its program instead of starting over.
 */
public record ControllerProgramComponent(
        UUID programId,
        String source,
        String compiledSource,
        String workspacePath,
        long updatedAt) {

    public static final Codec<ControllerProgramComponent> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("program_id")
                            .forGetter(ControllerProgramComponent::programId),
                    Codec.STRING.fieldOf("source")
                            .forGetter(ControllerProgramComponent::source),
                    Codec.STRING.fieldOf("compiled_source")
                            .forGetter(ControllerProgramComponent::compiledSource),
                    Codec.STRING.optionalFieldOf("workspace_path", "")
                            .forGetter(ControllerProgramComponent::workspacePath),
                    Codec.LONG.optionalFieldOf("updated_at", 0L)
                            .forGetter(ControllerProgramComponent::updatedAt))
                    .apply(instance, ControllerProgramComponent::new));

    public static final StreamCodec<ByteBuf, ControllerProgramComponent> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ControllerProgramComponent::programId,
                    ByteBufCodecs.STRING_UTF8, ControllerProgramComponent::source,
                    ByteBufCodecs.STRING_UTF8, ControllerProgramComponent::compiledSource,
                    ByteBufCodecs.STRING_UTF8, ControllerProgramComponent::workspacePath,
                    ByteBufCodecs.VAR_LONG, ControllerProgramComponent::updatedAt,
                    ControllerProgramComponent::new);
}
