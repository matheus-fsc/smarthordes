package dev.math.smarthordes;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/**
 * Grava uma onda para o simulador (horde-sim): terreno em volta do alvo no inicio, e a cada tick do cerebro
 * (0,5 s) posicao/papel/vida/inventario/acao de cada zumbi, posicao/vida dos jogadores, blocos que mudaram
 * e danos. Sai em plugins/SmartHordes/waves/&lt;data&gt;/ (meta.json, terrain.bin.gz, frames.jsonl.gz).
 */
final class WaveRecorder implements Listener {

    static final int HALF = 48, BELOW = 32, ABOVE = 40;

    private final SmartHordes plugin;
    private final World world;
    private final File dir;
    private final int x0, y0, z0, sx, sy, sz;
    private final long start = SmartHorde.now();
    private final Map<Material, Integer> palette = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> blocks = new ArrayList<>();
    private BufferedWriter out;
    private int frames;

    WaveRecorder(SmartHordes plugin, World w, Location center, String faction, int lowestY) {
        this.plugin = plugin;
        this.world = w;
        x0 = center.getBlockX() - HALF;
        z0 = center.getBlockZ() - HALF;
        // cobre do ponto mais baixo do spawn (com folga) ate acima do alvo
        y0 = Math.max(w.getMinHeight(), Math.min(center.getBlockY() - BELOW, lowestY - 12));
        int y1 = Math.min(w.getMaxHeight(), Math.max(center.getBlockY(), lowestY) + ABOVE);
        sx = HALF * 2;
        sz = HALF * 2;
        sy = y1 - y0;
        dir = new File(plugin.getDataFolder(), "waves/" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        dir.mkdirs();
        try {
            writeTerrain();
            out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                    new FileOutputStream(new File(dir, "frames.jsonl.gz")), true), StandardCharsets.UTF_8));
            writeMeta(faction, center);
        } catch (IOException e) {
            plugin.getLogger().warning("gravador da onda: " + e.getMessage());
            out = null;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Gravando onda em " + dir.getName());
    }

    private int pal(Material m) {
        return palette.computeIfAbsent(m, k -> palette.size());
    }

    /** Terreno via ChunkSnapshot (rapido; nao trava o tick). Ordem x, z, y como no simulador. */
    private void writeTerrain() throws IOException {
        byte[] data = new byte[sx * sz * sy];
        Map<Long, ChunkSnapshot> snaps = new LinkedHashMap<>();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                int wx = x0 + x, wz = z0 + z;
                long key = (((long) (wx >> 4)) << 32) ^ ((wz >> 4) & 0xffffffffL);
                ChunkSnapshot cs = snaps.computeIfAbsent(key, k -> world.getChunkAt(wx >> 4, wz >> 4).getChunkSnapshot(false, false, false));
                for (int y = 0; y < sy; y++) {
                    int id = pal(cs.getBlockType(wx & 15, y0 + y, wz & 15));
                    data[(x * sz + z) * sy + y] = (byte) Math.min(255, id);
                }
            }
        }
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(new File(dir, "terrain.bin.gz")))) {
            gz.write(data);
        }
    }

    private void writeMeta(String faction, Location c) throws IOException {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"world\":\"").append(world.getName()).append("\",\"faction\":\"").append(faction).append('"');
        sb.append(",\"origin\":[").append(x0).append(',').append(y0).append(',').append(z0).append(']');
        sb.append(",\"size\":[").append(sx).append(',').append(sy).append(',').append(sz).append(']');
        sb.append(",\"center\":[").append(c.getBlockX()).append(',').append(c.getBlockY()).append(',').append(c.getBlockZ()).append(']');
        sb.append(",\"tick_ms\":500,\"palette\":[");
        boolean first = true;
        for (Material m : palette.keySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(m.name().toLowerCase()).append('"');
            first = false;
        }
        sb.append("]}");
        try (var w = new OutputStreamWriter(new FileOutputStream(new File(dir, "meta.json")), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    // ---------------------------------------------------------------- eventos
    void block(Block b, Material m) {
        blocks.add("[" + b.getX() + "," + b.getY() + "," + b.getZ() + ",\"" + m.name().toLowerCase() + "\",\"z\"]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld() != world) return;
        Block b = e.getBlock();
        blocks.add("[" + b.getX() + "," + b.getY() + "," + b.getZ() + ",\"" + b.getType().name().toLowerCase() + "\",\"p\"]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld() != world) return;
        Block b = e.getBlock();
        blocks.add("[" + b.getX() + "," + b.getY() + "," + b.getZ() + ",\"air\",\"p\"]");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getWorld() != world) return;
        String by = e instanceof EntityDamageByEntityEvent d ? who(d.getDamager()) : e.getCause().name().toLowerCase();
        events.add("[\"dmg\",\"" + who(e.getEntity()) + "\",\"" + by + "\"," + Math.round(e.getFinalDamage() * 10) / 10.0 + "]");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity().getWorld() == world) events.add("[\"death\",\"" + e.getEntity().getName() + "\"]");
    }

    private static String who(Entity e) {
        if (e instanceof Player p) return p.getName();
        if (e instanceof Zombie z) return "z" + z.getEntityId();
        return e.getType().name().toLowerCase();
    }

    // ---------------------------------------------------------------- frames
    /** zs: id, papel, x, y, z, vida, inventario, coletando, acao */
    <S> void frame(Map<java.util.UUID, S> horde, Function<S, Object[]> info) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":").append(SmartHorde.now() - start).append(",\"z\":[");
        boolean first = true;
        for (var en : horde.entrySet()) {
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Zombie z) || z.getWorld() != world) continue;
            Object[] i = info.apply(en.getValue());
            Location l = z.getLocation();
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(z.getEntityId()).append(',').append(i[0]).append(',')
                    .append(r2(l.getX())).append(',').append(r2(l.getY())).append(',').append(r2(l.getZ())).append(',')
                    .append(Math.round(z.getHealth())).append(',').append(i[1]).append(',').append(i[2]).append(",\"")
                    .append(String.valueOf(i[3]).replace("\"", "'")).append("\"]");
        }
        sb.append("],\"p\":[");
        first = true;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue; // so quem joga de verdade
            Location l = p.getLocation();
            if (!first) sb.append(',');
            first = false;
            sb.append("[\"").append(p.getName()).append("\",").append(r2(l.getX())).append(',').append(r2(l.getY())).append(',')
                    .append(r2(l.getZ())).append(',').append(Math.round(p.getHealth() * 10) / 10.0).append(',')
                    .append(Math.round(l.getYaw())).append(",\"").append(p.getGameMode().name().toLowerCase()).append("\",")
                    .append(p.isSprinting() ? 1 : 0).append(',').append(p.isSneaking() ? 1 : 0).append(',')
                    .append(r2(p.getVelocity().getY())).append(",\"").append(p.getInventory().getItemInMainHand().getType().name().toLowerCase())
                    .append("\",").append(p.isOnGround() ? 1 : 0).append(']');
        }
        for (java.util.UUID id : SmartHorde.EXTRA_TARGETS) { // arena: o substituto entra como jogador "dummy"
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || e.getWorld() != world) continue;
            Location l = le.getLocation();
            if (!first) sb.append(',');
            first = false;
            double hp = Arena.hp(id);
            sb.append("[\"dummy\",").append(r2(l.getX())).append(',').append(r2(l.getY())).append(',').append(r2(l.getZ()))
                    .append(',').append(Math.round(hp * 10) / 10.0).append(",0,\"survival\",0,0,0,\"iron_sword\",1]");
        }
        sb.append("],\"b\":[").append(String.join(",", blocks)).append("],\"e\":[").append(String.join(",", events)).append("]}\n");
        blocks.clear();
        events.clear();
        try {
            out.write(sb.toString());
            if (++frames % 20 == 0) out.flush();
        } catch (IOException ex) {
            out = null;
        }
    }

    String name() { return dir.getName(); }

    void close() {
        HandlerList.unregisterAll(this);
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        out = null;
        plugin.getLogger().info("Onda gravada: " + dir.getName() + " (" + frames + " frames)");
    }

    private static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
