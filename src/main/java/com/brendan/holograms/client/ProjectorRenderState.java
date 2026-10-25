package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlockEntity;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ProjectorRenderState extends BlockEntityRenderState {
    public List<String> lines = List.of();
    public Direction facing = Direction.UP;
    public int distance = ProjectorBlockEntity.DEFAULT_DISTANCE;
    public int sizePercent = ProjectorBlockEntity.DEFAULT_SIZE_PERCENT;
    public int backgroundColor = ProjectorBlockEntity.DEFAULT_BACKGROUND_COLOR;
    public @Nullable BlockState camouflage;
    public final BlockModelRenderState camouflageModel = new BlockModelRenderState();
    public int blockLightCoords;
    public boolean hasCamouflageModel;
    public boolean hologramVisible = true;
    public boolean monitor;
    public boolean obstructed;
    public int columns;
    public int rows;
    public List<String> monitorRows = List.of();
    public int[] monitorForeground = new int[0];
    public int textVersion = -1;
    public int terminalVersion = -1;
}
