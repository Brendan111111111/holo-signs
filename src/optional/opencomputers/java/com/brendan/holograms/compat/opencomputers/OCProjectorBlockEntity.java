package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import li.cil.oc.api.Network;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projector block entity exposed to OpenComputers as the {@code hologram_projector} component.
 * <br>
 * OC discovers block entities implementing {@link Environment} through its network scan and calls
 * {@link li.cil.oc.api.machine.Callback} methods on this class directly.
 */
public final class OCProjectorBlockEntity extends ProjectorBlockEntity implements Environment {
    private static final String COMPONENT_NAME = "hologram_projector";

    private Component node;

    public OCProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(HoloSigns.PROJECTOR_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel)) return;
        if (node == null) {
            node = Network.newNode(this, Visibility.Network)
                    .withComponent(COMPONENT_NAME)
                    .create();
        }
        if (node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }
    }

    @Override
    public void setRemoved() {
        if (node != null) {
            node.remove();
            node = null;
        }
        super.setRemoved();
    }

    @Override
    public Node node() {
        return node;
    }

    @Override
    public void onConnect(Node node) {
    }

    @Override
    public void onDisconnect(Node node) {
    }

    @Override
    public void onMessage(Message message) {
    }

    @Callback(doc = "function():table -- Returns the current hologram lines, indexed from 1.")
    public Object[] getLines(Context context, Arguments arguments) {
        return new Object[]{super.getLines()};
    }

    @Callback(doc = "function(line:number, text:string):boolean -- Sets a single 1-based hologram line.")
    public Object[] setLine(Context context, Arguments arguments) {
        int line = arguments.checkInteger(0);
        String text = arguments.checkString(1);
        if (line < 1 || line > MAX_LINES) {
            return new Object[]{false, "line must be between 1 and " + MAX_LINES};
        }
        if (text.codePointCount(0, text.length()) > MAX_LINE_LENGTH) {
            return new Object[]{false, "text must be at most " + MAX_LINE_LENGTH + " characters"};
        }
        return new Object[]{super.setLine(line - 1, text)};
    }

    @Callback(doc = "function(lines:table):boolean -- Replaces all hologram lines; an empty table clears them.")
    @SuppressWarnings("rawtypes")
    public Object[] setLines(Context context, Arguments arguments) {
        Map input = arguments.checkTable(0);
        if (input.size() > MAX_LINES) {
            return new Object[]{false, "at most " + MAX_LINES + " lines are supported"};
        }

        String[] indexed = new String[MAX_LINES];
        int highestIndex = 0;
        for (Object entryObject : input.entrySet()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
            if (!(entry.getKey() instanceof Number number)) {
                return new Object[]{false, "lines must be a 1-based numeric array"};
            }
            double numericIndex = number.doubleValue();
            int index = (int) numericIndex;
            if (!Double.isFinite(numericIndex) || numericIndex != index || index < 1 || index > MAX_LINES) {
                return new Object[]{false, "line indexes must be between 1 and " + MAX_LINES};
            }
            if (indexed[index - 1] != null) {
                return new Object[]{false, "line indexes must be unique"};
            }
            if (!(entry.getValue() instanceof String text)) {
                return new Object[]{false, "all lines must be strings"};
            }
            if (text.codePointCount(0, text.length()) > MAX_LINE_LENGTH) {
                return new Object[]{false, "each line must be at most " + MAX_LINE_LENGTH + " characters"};
            }
            indexed[index - 1] = text;
            highestIndex = Math.max(highestIndex, index);
        }

        List<String> lines = new ArrayList<>(highestIndex);
        for (int i = 0; i < highestIndex; i++) {
            if (indexed[i] == null) {
                return new Object[]{false, "lines must be a contiguous 1-based array"};
            }
            lines.add(indexed[i]);
        }
        super.setLines(lines);
        return new Object[]{true};
    }

    @Callback(doc = "function():boolean -- Clears all hologram lines.")
    public Object[] clearLines(Context context, Arguments arguments) {
        super.clearLines();
        return new Object[]{true};
    }
}
