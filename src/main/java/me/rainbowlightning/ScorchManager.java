package me.rainbowlightning;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Turns the ground where the beam hits into burned, charred blocks. */
public final class ScorchManager {

    // Center of the burn: the darkest, most "charcoal" blocks (with a few glowing embers)
    private static final Material[] INNER = {
            Material.COAL_BLOCK, Material.COAL_BLOCK, Material.COAL_BLOCK,
            Material.BLACKSTONE, Material.BLACKSTONE,
            Material.BLACK_CONCRETE, Material.BLACK_CONCRETE,
            Material.BASALT,
            Material.MAGMA_BLOCK
    };

    // Outer ring: ashy, dark gray, partly burned
    private static final Material[] OUTER = {
            Material.BLACKSTONE, Material.BLACKSTONE,
            Material.SMOOTH_BASALT, Material.SMOOTH_BASALT,
            Material.BASALT,
            Material.BLACK_TERRACOTTA,
            Material.COARSE_DIRT, Material.COARSE_DIRT,
            Material.TUFF
    };

    private static final Set<Material> SCORCH_BLOCKS = EnumSet.noneOf(Material.class);
    static {
        for (Material m : INNER) SCORCH_BLOCKS.add(m);
        for (Material m : OUTER) SCORCH_BLOCKS.add(m);
    }

    private static final Set<Material> NEVER_REPLACE = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.END_GATEWAY,
            Material.REINFORCED_DEEPSLATE, Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.RESPAWN_ANCHOR, Material.SPAWNER, Material.TRIAL_SPAWNER, Material.VAULT,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW
    );

    private record Burned(BlockData original, long restoreAt, boolean wasPlant) {}

    private final RainbowLightningPlugin plugin;
    private final Map<Location, Burned> burned = new HashMap<>();

    private boolean enabled;
    private double radius;
    private double fireChance;
    private long restoreMillis;

    public ScorchManager(RainbowLightningPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadSettings(FileConfiguration c) {
        enabled = c.getBoolean("scorch.enabled", true);
        radius = Math.max(0.5, c.getDouble("scorch.radius", 2.5));
        fireChance = c.getDouble("scorch.fire-chance", 0.06);
        restoreMillis = (long) (c.getDouble("scorch.restore-after-seconds", 0) * 1000);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::restoreDue, 20L, 20L);
    }

    public void scorch(Block center) {
        if (!enabled) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = center.getWorld();
        int ri = (int) Math.ceil(radius + 0.5);

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    // ragged, natural-looking edge
                    if (dist > radius + (r.nextDouble() * 0.9 - 0.45)) continue;

                    Block b = center.getRelative(dx, dy, dz);
                    if (!canScorch(b) || !isExposed(b)) continue;

                    double norm = dist / radius;
                    Material mat = norm < 0.55
                            ? INNER[r.nextInt(INNER.length)]
                            : OUTER[r.nextInt(OUTER.length)];

                    remember(b, false);
                    b.setType(mat, false);

                    // burn away grass / flowers sitting on top
                    Block above = b.getRelative(BlockFace.UP);
                    if (isBurnablePlant(above)) {
                        remember(above, true);
                        above.setType(Material.AIR, false);
                    }

                    // a little fire and smoke (never on magma - fire there never goes out)
                    if (above.getType().isAir()) {
                        if (mat != Material.MAGMA_BLOCK && r.nextDouble() < fireChance) {
                            above.setType(Material.FIRE, true);
                        }
                        if (r.nextDouble() < 0.3) {
                            Location smoke = above.getLocation().add(0.5, 0.1, 0.5);
                            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, smoke, 1, 0.2, 0, 0.2, 0.01, null, true);
                        }
                    }
                }
            }
        }
    }

    private boolean canScorch(Block b) {
        Material t = b.getType();
        if (t.isAir() || !t.isSolid() || !t.isOccluding()) return false;
        if (SCORCH_BLOCKS.contains(t) || NEVER_REPLACE.contains(t)) return false;
        if (t.getHardness() < 0) return false;
        // never touch chests, furnaces, signs, etc.
        return !(b.getState(false) instanceof TileState);
    }

    /** Only burn the surface: the block must touch air or a plant on some side. */
    private boolean isExposed(Block b) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(face);
            if (n.getType().isAir() || (n.isPassable() && !n.isLiquid())) return true;
        }
        return false;
    }

    private boolean isBurnablePlant(Block b) {
        Material t = b.getType();
        if (t.isAir() || b.isLiquid() || t == Material.FIRE || !b.isPassable()) return false;
        if (b.getBlockData() instanceof Bisected) return false; // tall plants / doors - leave them
        if (b.getState(false) instanceof TileState) return false;
        return Tag.REPLACEABLE.isTagged(t) || Tag.FLOWERS.isTagged(t) || Tag.SAPLINGS.isTagged(t);
    }

    private void remember(Block b, boolean plant) {
        if (restoreMillis <= 0) return;
        long jitter = ThreadLocalRandom.current().nextLong(0, 3000); // regrow a bit unevenly
        burned.putIfAbsent(b.getLocation(),
                new Burned(b.getBlockData().clone(), System.currentTimeMillis() + restoreMillis + jitter, plant));
    }

    private void restoreDue() {
        if (burned.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Location, Burned>> it = burned.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, Burned> e = it.next();
            if (now < e.getValue().restoreAt()) continue;
            Location loc = e.getKey();
            World w = loc.getWorld();
            if (w == null) { it.remove(); continue; }
            if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue; // wait until loaded
            restore(loc, e.getValue());
            it.remove();
        }
    }

    /** Called when the server stops so nothing is left half-burned if restoring is on. */
    public void restoreAll() {
        for (Map.Entry<Location, Burned> e : burned.entrySet()) {
            if (e.getKey().getWorld() != null) restore(e.getKey(), e.getValue());
        }
        burned.clear();
    }

    private void restore(Location loc, Burned data) {
        Block b = loc.getBlock();
        Material current = b.getType();
        if (data.wasPlant()) {
            if (current.isAir() || current == Material.FIRE) b.setBlockData(data.original(), false);
        } else if (SCORCH_BLOCKS.contains(current)) {
            // only restore if nobody mined/replaced it (prevents duping)
            Block above = b.getRelative(BlockFace.UP);
            if (above.getType() == Material.FIRE) above.setType(Material.AIR, false);
            b.setBlockData(data.original(), false);
        }
    }
}
