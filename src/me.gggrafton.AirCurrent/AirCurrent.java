package me.gggrafton.AirCurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.command.Commands;

public class AirCurrent extends AirAbility implements AddonAbility {

    public static final String NAME = "AirCurrent";
    private static final String PATH = "ExtraAbilities.Lyss.Air." + NAME + ".";

    // Cluster Variables
    private static final int     DEFAULT_MAX_RANGE = 18;
    private static final int     DEFAULT_MIN_RANGE = 4;
    private static final long    DEFAULT_DURATION  = 10000L;
    private static final long    DEFAULT_COOLDOWN  = 10000L;
    private static final boolean DEFAULT_PLAYER_RIDE_OWN_FLOW = true;
    private static final int     DEFAULT_SIZE = 2;
    private static final boolean DEFAULT_REMOVE_ON_ANY_DAMAGE = false;
    private static final double  DEFAULT_BASE_PULL  = 0.7D;
    private static final double  DEFAULT_HEAD_STEP  = 0.6D;
    private static final int     DEFAULT_LAUNCH_TICKS = 30;
    private static final int     DEFAULT_SELECT_RANGE = 10;

    // Elytra boost variables
    private static final long    DEFAULT_BOOST_COOLDOWN = 3000L;
    private static final long    DEFAULT_BOOST_DURATION = 10000L;
    private static final double  DEFAULT_BOOST_SPEED    = 1.0D;

    private static final int     TRAIL = 1;
    private static final String  FLY_SOUND_NAME = "ENTITY_BREEZE_IDLE_GROUND";
    private static final int     SELECT_PARTICLES = 6;

    private static final Map<Player, Location> ORIGINS = new ConcurrentHashMap<>();
    private static int originTaskId = -1;

    private static final String BOOST_COOLDOWN_KEY = "AirCurrent-Boost";

    @Attribute(Attribute.RANGE)
    private int maxRange;
    private int minRange;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;

    @Attribute(Attribute.DURATION)
    private long duration;

    private boolean playerRideOwnFlow;
    private boolean removeOnAnyDamage;
    private double basePull;
    private double headStep;
    private int selectRange;

    private long boostCooldown;
    private long boostDuration;
    private double boostSpeed;

    private final Random rand = new Random();
    private Location head;
    private int range;
    private int headSize;
    private long startTime;
    private double lastHealth;

    private boolean launched = false;
    private Vector launchDir = new Vector(0, 0, 0);
    private int launchedTicks = 0;
    private int launchMaxTicks;

    private boolean boostActive = false;
    private boolean boostOnly = false;
    private long boostStartTime = 0L;

    public AirCurrent(Player player) {
        this(player, false);
    }

    public AirCurrent(Player player, boolean boostOnly) {
        super(player);

        if (!bPlayer.canBend(this)) {
            remove();
            return;
        }

        if (CoreAbility.hasAbility(player, AirCurrent.class)) {
            CoreAbility.getAbility(player, AirCurrent.class).remove();
        }

        loadConfig();

        this.boostOnly = boostOnly;

        if (!boostOnly) {
            // Use selected origin if present
            Location pre = ORIGINS.remove(player);
            if (pre != null) {
                this.head = pre.clone();
                int initialRange = (int) Math.round(player.getLocation().distance(pre));
                this.range = Math.min(maxRange, Math.max(minRange, initialRange));
            } else {
                this.head = player.getLocation().clone();
                this.range = maxRange;
            }

            this.headSize = DEFAULT_SIZE;
            this.startTime = System.currentTimeMillis();
            this.lastHealth = player.getHealth();
        }

        start();

        // If this is a boost-only activation, start the boost immediately.
        if (boostOnly) {
            startBoost();
        }
    }

    private void loadConfig() {
        ConfigManager.defaultConfig.reload();
        FileConfiguration c = ConfigManager.defaultConfig.get();

        // Cluster settings
        this.maxRange = c.getInt(PATH + "MaxRange", DEFAULT_MAX_RANGE);
        this.minRange = c.getInt(PATH + "MinRange", DEFAULT_MIN_RANGE);
        this.duration = c.getLong(PATH + "Duration", DEFAULT_DURATION);
        this.cooldown = c.getLong(PATH + "Cooldown", DEFAULT_COOLDOWN);

        this.playerRideOwnFlow = c.getBoolean(PATH + "PlayerRideOwnFlow", DEFAULT_PLAYER_RIDE_OWN_FLOW);
        this.headSize = c.getInt(PATH + "Size.Start", DEFAULT_SIZE);
        this.removeOnAnyDamage = c.getBoolean(PATH + "RemoveOnAnyDamage", DEFAULT_REMOVE_ON_ANY_DAMAGE);

        this.basePull = c.getDouble(PATH + "BasePull", DEFAULT_BASE_PULL);
        this.headStep = c.getDouble(PATH + "HeadStep", DEFAULT_HEAD_STEP);

        this.launchMaxTicks = c.getInt(PATH + "LaunchTicks", DEFAULT_LAUNCH_TICKS);
        this.selectRange = c.getInt(PATH + "SelectRange", DEFAULT_SELECT_RANGE);

        // Elytra boost settings
        this.boostCooldown = c.getLong(PATH + "Boost.Cooldown", DEFAULT_BOOST_COOLDOWN);
        this.boostDuration = c.getLong(PATH + "Boost.Duration", DEFAULT_BOOST_DURATION);
        this.boostSpeed    = c.getDouble(PATH + "Boost.Speed", DEFAULT_BOOST_SPEED);
    }

    // Left-click again to launch the cluster head
    public void launch() {
        if (!bPlayer.canBend(this) || boostOnly) return;
        if (launched) return;

        launched = true;
        launchedTicks = 0;
        launchDir = player.getEyeLocation().getDirection().normalize();

        try {
            Sound s = Sound.valueOf(FLY_SOUND_NAME);
            player.getWorld().playSound(player.getLocation(), s, 0.4f, 1.6f);
        } catch (IllegalArgumentException ignored) {}
    }

    // Start the Elytra boost
    public void startBoost() {
        if (!player.isGliding()) { if (boostOnly) remove(); return; }

        if (bPlayer.isOnCooldown(BOOST_COOLDOWN_KEY)) {
            if (boostOnly) remove();
            return;
        }

        // Ensures the cluster can't run at the same time
        if (!boostOnly) {
            boostOnly = true;
            launched = false;
        }

        this.boostActive = true;
        this.boostStartTime = System.currentTimeMillis();

        try {
            Sound s = Sound.valueOf(FLY_SOUND_NAME);
            player.getWorld().playSound(player.getLocation(), s, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {}
    }


    @Override
    public void progress() {
        if (player == null || !player.isOnline() || player.isDead()) { remove(); return; }
        if (!bPlayer.canBend(this)) { remove(); return; }

        // Elytra boost path
        if (boostActive) {
            if (System.currentTimeMillis() > boostStartTime + boostDuration ||
                    !player.isGliding() || player.isOnGround() ||
                    player.getLocation().getBlock().isLiquid()) {

                bPlayer.addCooldown(this, DEFAULT_BOOST_COOLDOWN);
                boostActive = false;
                if (boostOnly) { remove(); return; }
            } else {
                Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(boostSpeed);
                player.setVelocity(velocity);

                ParticleEffect.CLOUD.display(GeneralMethods.getRightSide(player.getLocation(), 1.0), 5, 0.1, 0.2, 0.1, 0.0);
                ParticleEffect.CLOUD.display(GeneralMethods.getLeftSide(player.getLocation(), 1.0), 5, 0.1, 0.2, 0.1, 0.0);

                if (((System.currentTimeMillis() - boostStartTime) / 50) % 20 == 0) {
                    try {
                        Sound s = Sound.valueOf(FLY_SOUND_NAME);
                        player.getWorld().playSound(player.getLocation(), s, 1.0f, 1.0f);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        // Cluster path
        if (boostOnly) return;

        if (duration > 0 && System.currentTimeMillis() > startTime + duration) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (removeOnAnyDamage && player.getHealth() < lastHealth) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        lastHealth = player.getHealth();

        if (head.getY() > head.getWorld().getMaxHeight() || head.getY() < head.getWorld().getMinHeight()) { bPlayer.addCooldown(this); remove(); return; }
        if (RegionProtection.isRegionProtected(player, head, this)) { bPlayer.addCooldown(this); remove(); return; }
        if (AirAbility.isWithinAirShield(head)) { bPlayer.addCooldown(this); remove(); return; }

        if (launched) {
            moveHeadLaunched();
            launchedTicks++;
            if (launchedTicks >= launchMaxTicks) {
                bPlayer.addCooldown(this);
                remove();
                return;
            }
        } else {
            if (player.isSneaking()) {
                if (range > minRange) range -= 1;
            } else {
                if (range < maxRange) range += 1;
            }
            moveHeadTowardLook();
        }

        renderParticles();
        pullEntities();
    }

    private void moveHeadTowardLook() {
        if (!isTransparent(head.getBlock()) || RegionProtection.isRegionProtected(player, head, this)) {
            range = Math.max(minRange, range - 1);
        }
        Location target = GeneralMethods.getTargetedLocation(player, range, Material.AIR);
        Vector dir = GeneralMethods.getDirection(head, target).normalize();

        Vector step = dir.clone().multiply(headStep);
        head.add(step);
        head.setDirection(dir);

        try {
            Sound s = Sound.valueOf(FLY_SOUND_NAME);
            head.getWorld().playSound(head, s, 0.15f, 1.8f);
        } catch (IllegalArgumentException ignored) {}
    }

    private void moveHeadLaunched() {
        Vector step = launchDir.clone().multiply(Math.max(0.8D, headStep * 1.4D));
        head.add(step);
        head.setDirection(launchDir);
    }

    private void renderParticles() {
        for (Block block : GeneralMethods.getBlocksAroundPoint(head, headSize)) {
            if (rand.nextInt(3) != 0) continue;
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            for (int i = 0; i < TRAIL; i++) {
                double ox = (rand.nextDouble() - 0.5) * 0.6;
                double oy = (rand.nextDouble() - 0.5) * 0.6;
                double oz = (rand.nextDouble() - 0.5) * 0.6;
                ParticleEffect.CLOUD.display(center.clone().add(ox, oy, oz), 1, 0.0, 0.0, 0.0);
            }
        }
    }

    private void pullEntities() {
        double radius = 1.5 + (headSize * 0.6);
        List<Entity> nearby = GeneralMethods.getEntitiesAroundPoint(head, radius);
        for (Entity e : new ArrayList<>(nearby)) {
            if (!(e instanceof LivingEntity)) continue;

            if (e.getEntityId() == player.getEntityId() && !playerRideOwnFlow) continue;
            if (e instanceof Player) {
                Player p = (Player) e;
                if (Commands.invincible.contains(p.getName())) continue;
            }
            if (RegionProtection.isRegionProtected(this, e.getLocation())) continue;

            Vector toHead = GeneralMethods.getDirection(e.getLocation(), head);

            double strength = basePull + (headSize * 0.10);
            if (player.isSprinting()) strength += 0.15;

            Vector vel = toHead.normalize().multiply(strength);
            vel.setY(Math.min(0.65, Math.max(vel.getY() + 0.15, 0.15)));

            GeneralMethods.setVelocity(this, e, vel);
            e.setFallDistance(0f);
        }
    }

    // Origin helpers
    public static void setOrigin(final Player player) {
        final Location loc = GeneralMethods.getTargetedLocation(player, getSelectRange(), getTransparentMaterials());
        if (loc == null) return;
        if (loc.getBlock().isLiquid() || GeneralMethods.isSolid(loc.getBlock())) return;
        if (RegionProtection.isRegionProtected(player, loc, NAME)) return;

        ORIGINS.put(player, loc);
    }

    private static void playOriginEffect(final Player player) {
        if (!ORIGINS.containsKey(player)) return;

        final Location origin = ORIGINS.get(player);
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null || player.isDead() || !player.isOnline()) return;
        if (!origin.getWorld().equals(player.getWorld())) { ORIGINS.remove(player); return; }
        if (!bPlayer.canBendIgnoreCooldowns(CoreAbility.getAbility(NAME))) { ORIGINS.remove(player); return; }
        if (origin.distanceSquared(player.getEyeLocation()) > getSelectRange() * getSelectRange()) { ORIGINS.remove(player); return; }

        playAirbendingParticles(origin, SELECT_PARTICLES);
    }

    public static void progressOrigins() {
        for (final Player p : ORIGINS.keySet()) {
            playOriginEffect(p);
        }
    }

    private static int getSelectRange() {
        return ConfigManager.defaultConfig.get().getInt(PATH + "SelectRange", DEFAULT_SELECT_RANGE);
    }

    // Metadata
    @Override public long getCooldown() { return cooldown; }
    @Override public Location getLocation() { return head != null ? head.clone() : null; }
    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Compress and guide a cluster of air that lifts and draws entities along your aim. You can also use it as a boost during Elytra flight!";
    }
    @Override public String getInstructions() {
        return "Usage: Bind " + NAME + ". Tap sneak while looking at a block to set the origin, then Left-click in the air to activate.\n" +
                "Sneak to pull it closer, release to push back. Left-click again to launch.\n" +
                "Left-click during Elytra flight for a boost."
    }
    @Override public boolean isHarmlessAbility() { return false; }
    @Override public boolean isSneakAbility() { return false; }

    @Override
    public void remove() {
        super.remove();
    }

    @Override public String getAuthor() { return "Lyssterine & gggrafton"; }
    @Override public String getVersion() { return "1.1.0"; }

    @Override
    public void load() {
        // Register listener
        ProjectKorra.plugin.getServer().getPluginManager()
                .registerEvents(new AirCurrentListener(), ProjectKorra.plugin);

        // Load config defaults
        FileConfiguration c = ConfigManager.defaultConfig.get();
        applyDefaults(c);
        ConfigManager.defaultConfig.save();

        // Start origin task if not running
        if (originTaskId == -1) {
            originTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    ProjectKorra.plugin,
                    AirCurrent::progressOrigins,
                    1L,
                    2L
            );
        }

        ProjectKorra.log.info(getName() + " v" + getVersion() + " by " + getAuthor() + " loaded.");
    }

    @Override
    public void stop() {
        if (originTaskId != -1) {
            Bukkit.getScheduler().cancelTask(originTaskId);
            originTaskId = -1;
        }
        ProjectKorra.log.info(getName() + " v" + getVersion() + " by " + getAuthor() + " unloaded.");
    }

    private static void applyDefaults(FileConfiguration c) {
        String base = "ExtraAbilities.Lyss.Air." + NAME + ".";

        // Enable this ability (prevents PK from auto-creating another section)
        c.addDefault(base + "Enabled", true);

        // Cluster settings
        c.addDefault(base + "MaxRange", DEFAULT_MAX_RANGE);
        c.addDefault(base + "MinRange", DEFAULT_MIN_RANGE);
        c.addDefault(base + "Duration", DEFAULT_DURATION);
        c.addDefault(base + "Cooldown", DEFAULT_COOLDOWN);
        c.addDefault(base + "PlayerRideOwnFlow", DEFAULT_PLAYER_RIDE_OWN_FLOW);
        c.addDefault(base + "Size.Start", DEFAULT_SIZE);
        c.addDefault(base + "RemoveOnAnyDamage", DEFAULT_REMOVE_ON_ANY_DAMAGE);
        c.addDefault(base + "BasePull", DEFAULT_BASE_PULL);
        c.addDefault(base + "HeadStep", DEFAULT_HEAD_STEP);
        c.addDefault(base + "LaunchTicks", DEFAULT_LAUNCH_TICKS);
        c.addDefault(base + "SelectRange", DEFAULT_SELECT_RANGE);

        // Elytra boost
        c.addDefault(base + "Boost.Cooldown", DEFAULT_BOOST_COOLDOWN);
        c.addDefault(base + "Boost.Duration", DEFAULT_BOOST_DURATION);
        c.addDefault(base + "Boost.Speed", DEFAULT_BOOST_SPEED);

        // Only write defaults that don’t exist yet
        c.options().copyDefaults(true);
    }
}
