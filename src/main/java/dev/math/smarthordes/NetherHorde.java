package dev.math.smarthordes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Horda do Nether: na PRIMEIRA vez que alguem entra no nether de um mundo, nasce uma horda de zumbificados
 * (mesmo cerebro da horda zumbi) + piglins de besta, todos com armadura de ouro. Se o jogador volta para o
 * overworld, a horda passa pelo portal atras dele. Acaba quando alguem morre, a horda morre ou em 10 min.
 */
final class NetherHorde implements Listener {

    private final SmartHordes plugin;
    private final SmartHorde horde;
    private final List<UUID> pigs = new ArrayList<>(), piglins = new ArrayList<>();
    private UUID target;
    private long startedAt;
    private BukkitTask task;

    NetherHorde(SmartHordes plugin) {
        this.plugin = plugin;
        this.horde = new SmartHorde(plugin);
        var c = plugin.getConfig();
        c.addDefault("nether-horde.enabled", true);
        c.addDefault("nether-horde.max-minutes", 10);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private boolean active() { return target != null; }

    private static boolean real(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !p.getName().matches("^(Zumbi|Pirata|Guilda)_\\d{1,2}$");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("nether-horde.enabled", true) || !real(p)) return;
        World to = p.getWorld();
        // voltou para o overworld com a horda atras: ela passa pelo portal tambem
        if (active() && p.getUniqueId().equals(target) && to.getEnvironment() == World.Environment.NORMAL) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> follow(p), 60L);
            return;
        }
        if (to.getEnvironment() != World.Environment.NETHER || active()) return;
        String key = "nether-horde.visited";
        List<String> visited = new ArrayList<>(plugin.getConfig().getStringList(key));
        String id = SmartHordes.baseOf(to.getName());
        if (visited.contains(id)) return; // so na primeira vez de cada mundo
        visited.add(id);
        plugin.getConfig().set(key, visited);
        plugin.saveConfig();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && p.getWorld() == to) start(p); }, 200L);
    }

    void start(Player p) {
        World w = p.getWorld();
        int inNether = (int) w.getPlayers().stream().filter(NetherHorde::real).count();
        double hours = plugin.runAliveSeconds() / 3600.0;
        int total = (int) Math.max(6, Math.min(18, 6 + inNether * 3 + hours * 1.5));
        int nPiglins = Math.max(2, total / 3), nPigs = total - nPiglins;
        List<Location> spots = spots(p.getLocation(), 24, 36, 12);
        if (spots.isEmpty()) { plugin.getLogger().info("Horda do Nether: sem lugar para nascer perto de " + p.getName()); return; }
        target = p.getUniqueId();
        startedAt = System.currentTimeMillis();
        pigs.clear(); piglins.clear();
        pigs.addAll(horde.spawnPigs(w, spots, nPigs, p));
        for (int i = 0; i < nPiglins; i++) {
            Piglin pg = spawnPiglin(spots.get(i % spots.size()), p);
            horde.emerge(pg, spots.get(i % spots.size()), java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 50));
            piglins.add(pg.getUniqueId());
        }
        Bukkit.broadcast(Component.text("🔥 Os piglins sentiram o cheiro de ouro... a horda do Nether vem atras de " + p.getName() + ".",
                NamedTextColor.GOLD, TextDecoration.ITALIC));
        for (Player o : w.getPlayers()) o.playSound(o.getLocation(), Sound.ENTITY_PIGLIN_ANGRY, 1f, 0.8f);
        plugin.getLogger().info("Horda do Nether: " + nPigs + " zumbificados + " + nPiglins + " piglins de besta atras de " + p.getName());
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    private Piglin spawnPiglin(Location l, Player t) {
        return l.getWorld().spawn(l, Piglin.class, pg -> {
            pg.setAdult();
            pg.setImmuneToZombification(true); // no overworld nao vira zumbi
            pg.setPersistent(true);
            pg.setRemoveWhenFarAway(false);
            var eq = pg.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(new ItemStack(Material.CROSSBOW));
                eq.setItemInMainHandDropChance(0.05f);
                SmartHorde.goldArmor(eq);
            }
            pg.setTarget(t);
        });
    }

    /** Lugares de pe (chao solido + 2 de ar) num anel em volta, perto da altura do jogador. */
    private static List<Location> spots(Location c, int rmin, int rmax, int want) {
        List<Location> out = new ArrayList<>();
        World w = c.getWorld();
        var rnd = ThreadLocalRandom.current();
        for (int tries = 0; tries < 400 && out.size() < want; tries++) {
            double ang = rnd.nextDouble() * Math.PI * 2, r = rmin + rnd.nextDouble() * (rmax - rmin);
            int x = (int) Math.round(c.getX() + Math.cos(ang) * r), z = (int) Math.round(c.getZ() + Math.sin(ang) * r);
            for (int dy = -10; dy <= 10; dy++) {
                Block b = w.getBlockAt(x, c.getBlockY() + dy, z);
                if (b.isPassable() && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, -1, 0).getType().isSolid()
                        && b.getRelative(0, -1, 0).getType() != Material.LAVA && b.getType() != Material.LAVA) {
                    out.add(b.getLocation().add(0.5, 0, 0.5));
                    break;
                }
            }
        }
        return out;
    }

    /** Jogador voltou pelo portal: quem estava vivo aparece perto dele no overworld. */
    private void follow(Player p) {
        if (!active() || !p.isOnline() || p.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        List<Location> around = spots(p.getLocation(), 10, 18, 16);
        if (around.isEmpty()) around = List.of(p.getLocation());
        int i = 0;
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (Zombie z : horde.members()) {
            Location l = around.get(i++ % around.size());
            z.teleport(l);
            horde.emerge(z, l, r.nextInt(0, 50)); // saem da terra em volta do jogador
        }
        for (UUID id : piglins) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Piglin pg && pg.isValid()) {
                Location l = around.get(i++ % around.size());
                pg.teleport(l); pg.setTarget(p);
                horde.emerge(pg, l, r.nextInt(0, 50));
            }
        }
        Bukkit.broadcast(Component.text("🔥 A horda do Nether atravessou o portal atras de " + p.getName() + "!", NamedTextColor.RED, TextDecoration.ITALIC));
    }

    private void tick() {
        if (!active()) { if (task != null) { task.cancel(); task = null; } return; }
        Player t = Bukkit.getPlayer(target);
        int alivePiglins = 0;
        for (UUID id : piglins) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Piglin pg && pg.isValid() && !pg.isDead()) {
                alivePiglins++;
                if (t != null && pg.getWorld() == t.getWorld() && pg.getTarget() != t) pg.setTarget(t); // o cerebro do piglin larga o alvo
            }
        }
        boolean timeUp = System.currentTimeMillis() - startedAt > plugin.getConfig().getInt("nether-horde.max-minutes", 10) * 60_000L;
        if (horde.members().isEmpty() && alivePiglins == 0) finish(Component.text("✔ A horda do Nether foi derrotada!", NamedTextColor.GREEN, TextDecoration.BOLD));
        else if (timeUp || t == null) finish(Component.text("A horda do Nether recua para as sombras.", NamedTextColor.GRAY));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (active() && real(e.getEntity()))
            Bukkit.getScheduler().runTask(plugin, () -> finish(Component.text("☠ A horda do Nether se dispersa.", NamedTextColor.GRAY)));
    }

    void finish(Component msg) {
        if (!active()) return;
        Bukkit.broadcast(msg);
        horde.despawn(true);
        for (UUID id : piglins) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        piglins.clear(); pigs.clear();
        target = null;
    }

    /** Comando de teste: /invasao nether [alvo]. */
    void forceStart(Player p) { if (!active()) start(p); }
}
