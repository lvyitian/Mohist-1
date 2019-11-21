package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/**
 * Represents events with a source block and a destination block, currently
 * only applies to liquid (lava and water) and teleporting dragon eggs.
 * <p>
 * If a Block From To event is cancelled, the block will not move (the liquid
 * will not flow).
 */
public class BlockFromToEvent extends BlockEvent implements Cancellable {
    private static final HandlerList handlers = new HandlerList();
    protected Block to;
    protected BlockFace face;
    protected boolean cancel;

    public BlockFromToEvent(final Block block, final BlockFace face) {
        super(block);
        this.face = face;
        // do not flow into unloaded chunks
        // https://github.com/aikar/EMC-Bukkit/commit/71da77292535c31073a06e2c68aa93cbe5db408e
        int x = block.getX() + face.getModX();
        int z = block.getZ() + face.getModZ();
        this.cancel = !block.getWorld().isChunkLoaded(x >> 4, z >> 4);
        if (this.cancel) {
            this.face = BlockFace.SELF;
        }
    }

    public BlockFromToEvent(final Block block, final Block toBlock) {
        super(block);
        this.to = toBlock;
        this.face = BlockFace.SELF;
        this.cancel = false;
    }

    /**
     * Gets the BlockFace that the block is moving to.
     *
     * @return The BlockFace that the block is moving to
     */
    public BlockFace getFace() {
        return face;
    }

    /**
     * Convenience method for getting the faced Block.
     *
     * @return The faced Block
     */
    public Block getToBlock() {
        if (to == null) {
            to = block.getRelative(face);
        }
        return to;
    }

    public boolean isCancelled() {
        return cancel;
    }

    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}