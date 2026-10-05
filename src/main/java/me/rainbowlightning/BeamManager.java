package me.rainbowlightning;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects "holding right-click" and drives the charge + beam.
 *
 * Minecraft has no "right-click released" packet for normal items, but while you HOLD
 * right-click the client re-sends the click every 4 ticks. So we treat the player as
 * holding as long as clicks keep arriving, and stop a few ticks after they stop.
 */
public final class BeamManager implements Listener {

    private static final int RELEASE_GRACE_TICKS = 7;

    private final RainbowLightningPlugin plugin;
    private final RodItem rodItem;
    private final ScorchManager scorch;
    private final Map<UUID, BeamState> states = new HashMap<>();

    private BukkitTask task;
    private long tick;

    // settings
    private int chargeTicks;
    private double range;
    private double hitRadius;
    private double damage;
    private int damageInterval;
    private boolean setOnFire;
    private boolean slowTargets;
    private boolean strikeEffect;
    private int strikeInterval;
    private double rainbowSpeed;

    private static final class BeamState {
        final long startTick;
        long lastClickTick;
        boolean firing;
        long firingTicks;

        BeamState(long now) {
            this.startTick = now;
            this.lastClickTick = now;
        }
    }

    public BeamManager(RainbowLightningPlugin plugin, RodItem rodItem, ScorchManager scorch) {
        this.plugin = plugin;
        this.rodItem = rodItem;
        this.scorch = scorch;
    }

    public void loadSettings(FileConfiguration c) {
        chargeTicks = Math.max(1, c.getInt("charge-ticks", 20));
        range = c.getDouble("beam.range", 48);
        hitRadius = c.getDouble("beam.hit-radius", 1.0);
        damage = c.getDouble("beam.damage", 2.5);
        damageInterval = Math.max(1, c.getInt("beam.damage-interval-ticks", 4));
        setOnFire = c.getBoolean("beam.set-on-fire", true);
        slowTargets = c.getBoolean("beam.slow-targets", true);
        strikeEffect = c.getBoolean("beam.lightning-strike-effect", true);
        strikeInterval = Math.max(1, c.getInt("beam.lightning-strike-interval-ticks", 15));
        rainbowSpeed = c.getDouble("beam.rainbow-speed", 0.02);
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void stopAll() {
        if (task != null) task.cancel();
        states.clear();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!rodItem.isRod(event.getItem())) return;

        // never place the rod / open doors etc. while using it
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        if (player.hasPermission("rainbowlightning.use")) heartbeat(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!rodItem.isRod(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (player.hasPermission("rainbowlightning.use")) heartbeat(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (rodItem.isRod(event.getItemInHand())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private void heartbeat(Player player) {
        BeamState state = states.get(player.getUniqueId());
        if (state == null) {
            state = new BeamState(tick);
            states.put(player.getUniqueId(), state);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
        }
        state.lastClickTick = tick;
    }

    // ------------------------------------------------------------------ main loop

    private void tickAll() {
        tick++;
        for (UUID id : new ArrayList<>(states.keySet())) {
            BeamState state = states.get(id);
            if (state == null) continue;
            Player player = Bukkit.getPlayer(id);

            boolean stillHolding = player != null
                    && player.isOnline()
                    && !player.isDead()
                    && rodItem.isRod(player.getInventory().getItemInMainHand())
                    && tick - state.lastClickTick <= RELEASE_GRACE_TICKS;

            if (!stillHolding) {
                if (player != null && state.firing) {
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);
                }
                if (player != null) player.sendActionBar(Component.empty());
                states.remove(id);
                continue;
            }

            long held = tick - state.startTick;
            if (held < chargeTicks) {
                charge(player, held);
            } else {
                if (!state.firing) {
                    state.firing = true;
                    World w = player.getWorld();
                    w.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.3f);
                    w.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.8f);
                    player.sendActionBar(MiniMessage.miniMessage().deserialize("<bold><rainbow>⚡ UNLEASHED ⚡</rainbow></bold>"));
                }
                state.firingTicks++;
                fire(player, state);
            }
        }
    }

    // ------------------------------------------------------------------ charging

    private void charge(Player player, long held) {
        double progress = held / (double) chargeTicks;
        World w = player.getWorld();
        Location hand = handLocation(player);
        double baseHue = tick * rainbowSpeed;

        // rainbow ring that shrinks into the rod
        double radius = 1.8 * (1 - progress) + 0.15;
        int points = 12;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2 * i / points) + held * 0.35;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = Math.sin(angle * 3 + held * 0.5) * 0.25 * (1 - progress);
            dust(w, hand.clone().add(x, y, z), rainbow(baseHue + (double) i / points), 1.3f, 1, 0);
        }
        if (progress > 0.3) {
            w.spawnParticle(Particle.ELECTRIC_SPARK, hand, (int) (progress * 6), 0.15, 0.15, 0.15, 0.05, null, true);
        }

        if (held % 4 == 0) {
            float pitch = (float) Math.min(2.0, 0.6 + progress * 1.4);
            w.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, pitch);
        }

        int filled = (int) Math.round(progress * 10);
        String bar = "<rainbow>" + "▮".repeat(filled) + "</rainbow><dark_gray>" + "▮".repeat(10 - filled) + "</dark_gray>";
        player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Charging </gray>" + bar));
    }

    // ------------------------------------------------------------------ the beam

    private void fire(Player player, BeamState state) {
        World w = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // where the cursor is pointing
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        Location end;
        Block hitBlock = null;
        if (hit != null) {
            end = hit.getHitPosition().toLocation(w);
            hitBlock = hit.getHitBlock();
        } else {
            end = eye.clone().add(dir.clone().multiply(range));
        }

        Location start = handLocation(player);
        Vector startV = start.toVector();
        Vector axis = end.toVector().subtract(startV);
        double len = axis.length();
        if (len < 0.5) return;
        Vector f = axis.clone().multiply(1.0 / len);

        // two vectors perpendicular to the beam, used to make it jagged / spiral
        Vector ref = Math.abs(f.getY()) < 0.95 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector u = f.getCrossProduct(ref).normalize();
        Vector v = f.getCrossProduct(u).normalize();

        double baseHue = tick * rainbowSpeed;

        drawBolt(w, startV, f, u, v, len, baseHue, 0.85, 2.2f, 3, 0.35);   // thick main bolt
        drawBolt(w, startV, f, u, v, len, baseHue + 0.33, 1.1, 1.1f, 1, 0.5); // second crackling strand
        drawSpiral(w, startV, f, u, v, len, baseHue);
        drawBranches(w, startV, f, u, v, len, baseHue);

        // random sparks along the beam
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int sparks = (int) (len / 4);
        for (int i = 0; i < sparks; i++) {
            Vector p = startV.clone().add(f.clone().multiply(r.nextDouble() * len));
            w.spawnParticle(Particle.ELECTRIC_SPARK, p.getX(), p.getY(), p.getZ(), 1, 0.3, 0.3, 0.3, 0.05, null, true);
        }

        impactEffects(w, end, hitBlock, state, baseHue);

        // looping beam sound
        if (state.firingTicks % 8 == 1) {
            w.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1f, 2f);
        }

        // hurt everything the beam touches
        if (state.firingTicks % damageInterval == 1 || damageInterval == 1) {
            BoundingBox area = BoundingBox.of(start, end).expand(hitRadius + 1.0);
            for (Entity e : w.getNearbyEntities(area)) {
                if (!(e instanceof LivingEntity target) || e.equals(player) || target.isDead()) continue;
                if (e instanceof Player tp && (tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) continue;
                if (target.getBoundingBox().expand(hitRadius).rayTrace(startV, f, len) == null) continue;
                zap(player, target, baseHue);
            }
        }
    }

    /** A jagged lightning bolt from start to end that re-randomises every tick. */
    private void drawBolt(World w, Vector start, Vector f, Vector u, Vector v, double len,
                          double hue, double jitter, float size, int count, double step) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int nodes = Math.max(3, (int) (len / 2.0));
        Vector[] pts = new Vector[nodes + 1];
        for (int i = 0; i <= nodes; i++) {
            double t = (double) i / nodes;
            Vector p = start.clone().add(f.clone().multiply(len * t));
            if (i != 0 && i != nodes) {
                double scale = jitter * Math.min(1.0, Math.sin(Math.PI * t) * 2.5); // pinned at both ends
                p.add(u.clone().multiply((r.nextDouble() * 2 - 1) * scale));
                p.add(v.clone().multiply((r.nextDouble() * 2 - 1) * scale));
            }
            pts[i] = p;
        }
        for (int i = 0; i < nodes; i++) {
            Vector a = pts[i];
            Vector b = pts[i + 1];
            Vector seg = b.clone().subtract(a);
            double segLen = seg.length();
            if (segLen <= 0) continue;
            seg.multiply(1.0 / segLen);
            for (double d = 0; d < segLen; d += step) {
                Vector p = a.clone().add(seg.clone().multiply(d));
                double along = ((double) i + d / segLen) / nodes;
                dust(w, p, rainbow(hue + along * 0.6), size, count, 0.08);
            }
        }
    }

    /** A rainbow spiral wrapping around the beam so it looks big and glowy. */
    private void drawSpiral(World w, Vector start, Vector f, Vector u, Vector v, double len, double hue) {
        double radius = 0.5;
        for (double d = 0.5; d < len; d += 0.55) {
            for (int arm = 0; arm < 2; arm++) {
                double angle = d * 1.3 - tick * 0.6 + arm * Math.PI;
                Vector p = start.clone().add(f.clone().multiply(d))
                        .add(u.clone().multiply(Math.cos(angle) * radius))
                        .add(v.clone().multiply(Math.sin(angle) * radius));
                dust(w, p, rainbow(hue + 0.5 + d / len * 0.6 + arm * 0.25), 1.0f, 1, 0);
            }
        }
    }

    /** A few short forks shooting off the main bolt, like real lightning. */
    private void drawBranches(World w, Vector start, Vector f, Vector u, Vector v, double len, double hue) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int branches = 1 + (int) (len / 15);
        for (int b = 0; b < branches; b++) {
            if (r.nextDouble() < 0.4) continue;
            double at = r.nextDouble() * len;
            Vector p = start.clone().add(f.clone().multiply(at));
            Vector dir = f.clone().multiply(0.6)
                    .add(u.clone().multiply(r.nextDouble() * 2 - 1))
                    .add(v.clone().multiply(r.nextDouble() * 2 - 1))
                    .normalize();
            double branchLen = 1.5 + r.nextDouble() * 3;
            for (double d = 0; d < branchLen; d += 0.3) {
                if (r.nextDouble() < 0.25) {
                    // little kink in the fork
                    dir.add(new Vector(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).multiply(0.6)).normalize();
                }
                p.add(dir.clone().multiply(0.3));
                dust(w, p, rainbow(hue + at / len * 0.6), 0.9f, 1, 0);
            }
        }
    }

    private void impactEffects(World w, Location end, Block hitBlock, BeamState state, double hue) {
        dust(w, end.toVector(), rainbow(hue + 0.6), 2.5f, 8, 0.45);
        w.spawnParticle(Particle.ELECTRIC_SPARK, end, 6, 0.5, 0.5, 0.5, 0.25, null, true);
        if (state.firingTicks % 2 == 0) {
            w.spawnParticle(Particle.LARGE_SMOKE, end, 2, 0.4, 0.3, 0.4, 0.02, null, true);
        }
        if (state.firingTicks % 4 == 0) {
            w.spawnParticle(Particle.LAVA, end, 1, 0.3, 0.1, 0.3, 0, null, true);
        }
        if (state.firingTicks % 6 == 0) {
            w.playSound(end, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.6f);
        }

        if (hitBlock == null) return;

        if (state.firingTicks % 3 == 0) {
            scorch.scorch(hitBlock);
        }
        if (strikeEffect && state.firingTicks % strikeInterval == 0) {
            w.strikeLightningEffect(end); // visual only
        }
    }

    private void zap(Player shooter, LivingEntity target, double hue) {
        Vector velocity = target.getVelocity();

        // ignore the normal half-second "invincibility" after getting hit,
        // so the beam keeps chewing through health (and totems)
        target.setNoDamageTicks(0);
        DamageSource source = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(shooter)
                .withDirectEntity(shooter)
                .build();
        target.damage(damage, source);

        // cancel the knockback so the target stays stuck in the beam
        if (!target.isDead()) target.setVelocity(velocity);

        if (setOnFire) target.setFireTicks(Math.max(target.getFireTicks(), 60));
        if (slowTargets) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 2, true, false, false));
        }

        Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
        World w = target.getWorld();
        w.spawnParticle(Particle.ELECTRIC_SPARK, c, 10, target.getWidth() / 2, target.getHeight() / 2, target.getWidth() / 2, 0.3, null, true);
        dust(w, c.toVector(), rainbow(hue + 0.15), 1.6f, 6, target.getWidth() / 2 + 0.1);
    }

    // ------------------------------------------------------------------ helpers

    /** Roughly where the tip of the rod is in the player's right hand. */
    private Location handLocation(Player player) {
        Location eye = player.getEyeLocation();
        double yaw = Math.toRadians(eye.getYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        return eye.clone()
                .add(eye.getDirection().multiply(0.6))
                .add(right.multiply(0.35))
                .add(0, -0.3, 0);
    }

    private static void dust(World w, Location loc, Color color, float size, int count, double spread) {
        w.spawnParticle(Particle.DUST, loc.getX(), loc.getY(), loc.getZ(), count, spread, spread, spread, 0,
                new Particle.DustOptions(color, size), true);
    }

    private static void dust(World w, Vector p, Color color, float size, int count, double spread) {
        w.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), count, spread, spread, spread, 0,
                new Particle.DustOptions(color, size), true);
    }

    private static Color rainbow(double hue) {
        float h = (float) (hue - Math.floor(hue));
        int rgb = java.awt.Color.HSBtoRGB(h, 1f, 1f);
        return Color.fromRGB(rgb & 0xFFFFFF);
    }
}
