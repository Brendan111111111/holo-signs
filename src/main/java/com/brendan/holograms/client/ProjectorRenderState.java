package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlockEntity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

import java.util.List;

public final class ProjectorRenderState extends BlockEntityRenderState {
    public List<String> lines = List.of();
    public Direction facing = Direction.UP;
    public int distance = ProjectorBlockEntity.DEFAULT_DISTANCE;
    public boolean monitor;
    public int columns;
    public int rows;
    public List<String> monitorRows = List.of();
    public int[] monitorForeground = new int[0];
}
