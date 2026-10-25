package com.brendan.holograms.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

import java.util.List;

public final class ProjectorRenderState extends BlockEntityRenderState {
    public List<String> lines = List.of();
    public Direction facing = Direction.NORTH;
}
