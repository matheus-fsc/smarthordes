package dev.math.smarthordes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Arena de teste (servidor separado com arena-mode: true): o proprio jogo como simulador.
 * Varias rodadas ao mesmo tempo no mesmo servidor (arena-slots na config): cada uma num "slot" a 1000 blocos
 * das outras, com o terreno de uma onda gravada, a horda no lugar/papel de onde comecou e um substituto do
 * jogador (aldeao sem IA) seguindo o trajeto gravado ou uma estrategia. Roda acelerado com /tick sprint enquanto
 * houver rodada; cada rodada imprime "ARENA_RESULT {json}" com a sua tag.
 *
 * /arena params k=v ...                        parametros da horda para as proximas rodadas (horde.p.k / opt:k)
 * /arena run <onda> <estrategia> [seg] [tag]   estrategias: replay afk pillar fighter smart
 * /arena stop | watch on|off
 */
final class Arena implements Listener {

    static final Map<UUID, Double> HP = new HashMap<>();

    static double hp(UUID id) { return HP.getOrDefault(id, 20.0); }

    private static final double ARMOR = 0.4;
    private static final int SLOT_SPACING = 1000;

    private final SmartHordes plugin;
    private final SmartHorde horde;
    private final World world;
    private final int slots;
    private final Map<Integer, Round> rounds = new HashMap<>();
    private final Map<UUID, Round> byDummy = new HashMap<>();
    private final java.util.Set<Integer> pasting = new java.util.HashSet<>();
    private final int[][] boxes; // ultima colagem por slot (x0 y0 z0 sx sy sz)
    private BukkitTask task;
    private boolean sprinting, watch;

    /** Uma rodada (um slot). */
    private final class Round {
        int slot;
        Villager dummy;
        String strategy, tag, wave;
        /** Estrategia base do boneco ("replay", "pillar"...) e defesas da rodada: "replay+g2+t3" = 2 golens e 3 torres. */
        String base;
        final List<org.bukkit.entity.IronGolem> golems = new ArrayList<>();
        final List<Block> turrets = new ArrayList<>();
        final List<double[]> path = new ArrayList<>(); // t(ms), x, y, z (ja no slot)
        final List<UUID> zombies = new ArrayList<>();
        int startTick, maxTicks, lastHurt = -9999, hits, built, broke, swings, firstHit = -1, pathIdx, turretsLeft;
        /** Rodada de demonstracao (tag "demo..."): o alvo nao morre e a gravacao cobre tudo; solo = sem horda da IA. */
        boolean demo, solo;
        Location spawn;
        double dmg, minDist = 1e9;
    }

    Arena(SmartHordes plugin) {
        this.plugin = plugin;
        this.horde = new SmartHorde(plugin);
        this.slots = Math.max(1, plugin.getConfig().getInt("arena-slots", 1));
        this.boxes = new int[slots][];
        Bukkit.getPluginManager().registerEvents(this, plugin);
        World w = Bukkit.getWorld("arena");
        if (w == null) w = new WorldCreator("arena").generator(new ChunkGenerator() { }).createWorld(); // vazio
        world = w;
        rule(false, "advance_time", "doDaylightCycle");
        rule(false, "spawn_mobs", "doMobSpawning");
        rule(false, "advance_weather", "doWeatherCycle");
        rule(0, "random_tick_speed", "randomTickSpeed");
        world.setDifficulty(org.bukkit.Difficulty.HARD); // mundo novo nasce pacifico: zumbi sumia na hora
        world.setSpawnFlags(false, false);
        world.setTime(6000);
        world.setStorm(false);
        plugin.getLogger().info("ARENA pronta (mundo 'arena', " + slots + " slots)");
    }

    /** Gamerule pelo nome (os nomes mudaram no 26.x: tenta os dois). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rule(Object value, String... names) {
        for (GameRule<?> g : GameRule.values()) {
            for (String n : names) {
                if (g.getName().equalsIgnoreCase(n) || g.getName().equalsIgnoreCase("minecraft:" + n)) {
                    try { world.setGameRule((GameRule) g, value); } catch (Exception ignored) { }
                    return;
                }
            }
        }
    }

    private File waveDir(String name) {
        File f = new File(name);
        if (f.isDirectory()) return f;
        return new File(plugin.getDataFolder(), "waves/" + name);
    }

    void command(CommandSender sender, String[] args) {
        try {
            if (args.length == 0) { sender.sendMessage("/arena params k=v .. | run <onda> <estrategia> [seg] [tag] | stop | watch on|off | zumbi [minerador|lenhador|cavador|guerreiro|sair]"); return; }
            switch (args[0]) {
                case "params" -> {
                    setParams(args, 1);
                    plugin.getLogger().info("ARENA_PARAMS_OK");
                }
                case "run" -> {
                    // compat: tokens k=v no proprio run (1 slot) continuam funcionando
                    List<String> pos = new ArrayList<>();
                    boolean hasParams = false;
                    for (int i = 1; i < args.length; i++) { if (args[i].indexOf('=') > 0) hasParams = true; else pos.add(args[i]); }
                    if (hasParams) setParams(args, 1);
                    for (org.bukkit.entity.Monster m : world.getEntitiesByClass(org.bukkit.entity.Monster.class))
                        if (!horde.isHorde(m)) m.remove();
                    String wave = pos.get(0);
                    String strat = pos.size() > 1 ? pos.get(1) : "replay";
                    int secs = pos.size() > 2 ? Integer.parseInt(pos.get(2)) : 300;
                    String tg = pos.size() > 3 ? pos.get(3) : "-";
                    boolean solo = pos.size() > 4 && pos.get(4).equalsIgnoreCase("solo");
                    int slot = -1;
                    for (int s = 0; s < slots; s++) if (!rounds.containsKey(s) && !pasting.contains(s)) { slot = s; break; }
                    if (slot < 0) { plugin.getLogger().warning("ARENA_ERROR ocupado " + tg); return; }
                    final int sl = slot;
                    pasting.add(sl);
                    paste(wave, sl, () -> {
                        pasting.remove(sl);
                        try { start(wave, sl, strat, secs, tg, solo); }
                        catch (Exception ex) { plugin.getLogger().warning("ARENA_ERROR " + tg + " " + ex); }
                    });
                }
                case "stop" -> { for (Round r : new ArrayList<>(rounds.values())) finish(r, "parado"); }
                case "zumbi" -> humanZombie(sender, args);
                case "watch" -> {
                    watch = args.length < 2 || args[1].equalsIgnoreCase("on");
                    if (watch && sprinting) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop"); sprinting = false; }
                    sender.sendMessage("modo assistir: " + (watch ? "velocidade normal" : "acelerado (tick sprint)"));
                }
                default -> sender.sendMessage("subcomando desconhecido");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("ARENA_ERROR " + ex);
        }
    }

    /** Parametros da horda so em memoria (horde.p.k ou opt:k = horde.k). Zera os anteriores. */
    private void setParams(String[] args, int from) {
        var cfg = plugin.getConfig();
        for (String key : new ArrayList<>(cfg.getKeys(true))) if (key.startsWith("horde.p.")) cfg.set(key, null);
        for (int i = from; i < args.length; i++) {
            String tok = args[i];
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq), v = tok.substring(eq + 1);
            String path = k.startsWith("opt:") ? "horde." + k.substring(4) : "horde.p." + k;
            if (v.equals("true") || v.equals("false")) cfg.set(path, Boolean.parseBoolean(v));
            else try { cfg.set(path, Double.parseDouble(v)); } catch (NumberFormatException nf) { cfg.set(path, v); }
        }
    }

    // ------------------------------------------------------------------ terreno
    private int baseX(int slot) { return slot * SLOT_SPACING; }

    /** Cola (ou restaura) o terreno da onda no slot, em lotes por tick; chama done no fim. */
    private void paste(String name, int slot, Runnable done) throws Exception {
        File dir = waveDir(name);
        JsonObject meta = JsonParser.parseString(Files.readString(new File(dir, "meta.json").toPath())).getAsJsonObject();
        JsonArray sz = meta.getAsJsonArray("size"), pal = meta.getAsJsonArray("palette"), o = meta.getAsJsonArray("origin");
        int sx = sz.get(0).getAsInt(), sy = sz.get(1).getAsInt(), szz = sz.get(2).getAsInt();
        int x0 = baseX(slot), y0 = o.get(1).getAsInt(), z0 = 0;
        Material[] mats = new Material[pal.size()];
        for (int i = 0; i < mats.length; i++) {
            Material m = Material.matchMaterial(pal.get(i).getAsString());
            mats[i] = m == null || !m.isBlock() ? Material.STONE : m;
        }
        byte[] data;
        try (GZIPInputStream in = new GZIPInputStream(new FileInputStream(new File(dir, "terrain.bin.gz")))) { data = in.readAllBytes(); }
        int[] oldBox = boxes[slot];
        int[] box = new int[]{x0, y0, z0, sx, sy, szz};
        boxes[slot] = box;
        for (int cx = (x0 >> 4) - 1; cx <= ((x0 + sx) >> 4) + 1; cx++)
            for (int cz = (z0 >> 4) - 1; cz <= ((z0 + szz) >> 4) + 1; cz++) world.setChunkForceLoaded(cx, cz, true);
        final byte[] d = data;
        final int total = sx * szz * sy;
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0, clearI = 0;
            final int clearTotal = oldBox == null || java.util.Arrays.equals(oldBox, box) ? 0 : oldBox[3] * oldBox[4] * oldBox[5];
            @Override public void run() {
                int budget = 60_000;
                while (clearI < clearTotal && budget-- > 0) { // limpa a colagem anterior do slot (outra onda)
                    int k = clearI++;
                    int y = k % oldBox[4], z = (k / oldBox[4]) % oldBox[5], x = k / (oldBox[4] * oldBox[5]);
                    world.getBlockAt(oldBox[0] + x, oldBox[1] + y, oldBox[2] + z).setType(Material.AIR, false);
                }
                while (i < total && budget-- > 0) {
                    int k = i++;
                    int y = k % sy, z = (k / sy) % szz, x = k / (sy * szz);
                    Material m = mats[d[k] & 0xff];
                    Block b = world.getBlockAt(x0 + x, y0 + y, z0 + z);
                    if (b.getType() != m) b.setType(m, false);
                }
                if (i >= total && clearI >= clearTotal) {
                    cancel();
                    // so os blocos de horda deste slot saem da lista (os outros slots seguem rodando)
                    SmartHorde.ZPLACED.removeIf(b -> b.getWorld() == world && b.getX() >= x0 - 50 && b.getX() < x0 + SLOT_SPACING - 50);
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ rodada
    private void start(String name, int slot, String strat, int secs, String tg, boolean solo) throws Exception {
        File dir = waveDir(name);
        JsonObject meta = JsonParser.parseString(Files.readString(new File(dir, "meta.json").toPath())).getAsJsonObject();
        JsonArray o = meta.getAsJsonArray("origin");
        double dx = baseX(slot) - o.get(0).getAsInt(), dz = -o.get(2).getAsInt(); // onda -> slot
        Round r = new Round();
        r.slot = slot;
        List<Location> spots = new ArrayList<>();
        List<Integer> roles = new ArrayList<>();
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(new File(dir, "frames.jsonl.gz"))), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while (true) {
                try { line = rd.readLine(); } catch (java.io.EOFException eof) { break; }
                if (line == null) break;
                if (line.isBlank()) continue;
                JsonObject f;
                try { f = JsonParser.parseString(line).getAsJsonObject(); } catch (Exception bad) { break; }
                if (first) {
                    for (JsonElement ze : f.getAsJsonArray("z")) {
                        JsonArray z = ze.getAsJsonArray();
                        spots.add(new Location(world, z.get(2).getAsDouble() + dx, z.get(3).getAsDouble(), z.get(4).getAsDouble() + dz));
                        roles.add(z.get(1).getAsInt());
                    }
                    first = false;
                }
                JsonArray ps = f.getAsJsonArray("p");
                if (ps.size() > 0) {
                    JsonArray p = ps.get(0).getAsJsonArray();
                    r.path.add(new double[]{f.get("t").getAsDouble(), p.get(1).getAsDouble() + dx, p.get(2).getAsDouble(), p.get(3).getAsDouble() + dz});
                }
            }
        }
        if (spots.isEmpty() || r.path.isEmpty()) { plugin.getLogger().warning("ARENA_ERROR " + tg + " onda sem zumbis ou sem jogador"); return; }
        double[] p0 = r.path.get(0);
        r.dummy = world.spawn(new Location(world, p0[1], p0[2], p0[3]), Villager.class, v -> {
            v.setAI(false); v.setSilent(true); v.setPersistent(true); v.setRemoveWhenFarAway(false);
            v.setGravity(false); v.customName(net.kyori.adventure.text.Component.text("alvo"));
        });
        SmartHorde.EXTRA_TARGETS.add(r.dummy.getUniqueId());
        HP.put(r.dummy.getUniqueId(), 20.0);
        r.strategy = strat; r.tag = tg; r.wave = name;
        String[] mods = strat.split("\\+");
        r.base = mods[0];
        int golems = 0, turrets = 0;
        for (int k = 1; k < mods.length; k++) {
            try {
                if (mods[k].startsWith("g")) golems = Integer.parseInt(mods[k].substring(1));
                if (mods[k].startsWith("t")) turrets = Integer.parseInt(mods[k].substring(1));
            } catch (NumberFormatException ignored) { }
        }
        Location home = r.dummy.getLocation();
        for (int k = 0; k < golems; k++) {
            double a = Math.PI * 2 * k / Math.max(1, golems);
            Location gl = home.clone().add(Math.cos(a) * 2.5, 0, Math.sin(a) * 2.5);
            gl.setY(world.getHighestBlockYAt(gl) + 1);
            r.golems.add(world.spawn(gl, org.bukkit.entity.IronGolem.class, g -> { g.setPlayerCreated(true); g.setPersistent(true); }));
        }
        for (int k = 0; k < turrets; k++) {
            double a = Math.PI * 2 * k / Math.max(1, turrets) + 0.4;
            Block tb = world.getBlockAt((int) Math.floor(home.getX() + Math.cos(a) * 4), 0, (int) Math.floor(home.getZ() + Math.sin(a) * 4));
            tb = world.getHighestBlockAt(tb.getLocation()).getRelative(BlockFace.UP);
            tb.setType(Material.DISPENSER);
            r.turrets.add(tb);
        }
        r.startTick = Bukkit.getCurrentTick(); r.maxTicks = secs * 20;
        r.demo = tg.startsWith("demo");
        r.solo = solo;
        r.spawn = spots.get(0).clone();
        if (!solo) r.zombies.addAll(horde.spawnExact(world, spots, roles, r.dummy));
        if (r.demo) {
            int low = (int) r.dummy.getLocation().getY();
            for (Location l : spots) low = Math.min(low, l.getBlockY());
            horde.startDemoRecording(world, r.dummy.getLocation(), low);
            if (!watch) { watch = true; if (sprinting) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop"); sprinting = false; } }
        }
        rounds.put(slot, r);
        byDummy.put(r.dummy.getUniqueId(), r);
        if (!watch && !sprinting) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint 20000000"); sprinting = true; }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (org.bukkit.entity.Player pl : Bukkit.getOnlinePlayers()) if (rounds.size() == 1 && !humanZombies.containsKey(pl.getUniqueId())) watchPlayer(pl);
        plugin.getLogger().info("ARENA_START " + name + " " + strat + " " + secs + "s slot " + slot + " (" + spots.size() + " zumbis) " + tg);
    }

    private void tick() {
        for (Round r : new ArrayList<>(rounds.values())) tickRound(r);
        if (rounds.isEmpty() && pasting.isEmpty()) {
            if (sprinting) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop"); sprinting = false; }
            if (task != null) { task.cancel(); task = null; }
        }
        if (!Bukkit.getOnlinePlayers().isEmpty() && Bukkit.getCurrentTick() % 10 == 0) labels();
        if (Bukkit.getCurrentTick() % 100 == 0) {
            for (org.bukkit.entity.Monster m : world.getEntitiesByClass(org.bukkit.entity.Monster.class))
                if (!horde.isHorde(m)) m.remove(); // restos de rodadas antigas (versao anterior) e qualquer monstro solto
        }
    }

    private void tickRound(Round r) {
        if (r.dummy == null || !r.dummy.isValid()) { finish(r, "alvo sumiu"); return; }
        int t = Bukkit.getCurrentTick() - r.startTick;
        UUID id = r.dummy.getUniqueId();
        double hp = hp(id);
        if (hp < 20 && t - r.lastHurt > 80 && t % 80 == 0) HP.put(id, Math.min(20, hp + 1)); // regeneracao natural
        if (!r.turrets.isEmpty() && t % 20 == 0) turretShots(r);
        switch (r.base == null ? r.strategy : r.base) {
            case "replay" -> replay(r, t);
            case "pillar" -> { pillar(r, t); breakStruct(r, t); }
            case "fighter" -> fight(r, t);
            case "smart" -> { pillar(r, t); breakStruct(r, t); fight(r, t); }
            default -> { }
        }
        if (t % 10 == 0) { Zombie nz = nearest(r, 64); if (nz != null) r.minDist = Math.min(r.minDist, nz.getLocation().distance(r.dummy.getLocation())); }
        if (hp(id) <= 0 && !r.demo) { finish(r, "morreu"); return; }
        if (t >= r.maxTicks) { finish(r, "tempo"); return; }
        if (t % 20 == 0 && horde.statsFor(r.zombies)[0] == 0) finish(r, "horda morta");
    }

    /** Nome + acao em cima de cada zumbi (so quando tem alguem assistindo). */
    private void labels() {
        horde.forEachState((z, role, action, inv) -> {
            z.customName(net.kyori.adventure.text.Component.text(role + " [" + inv + "] " + action));
            z.setCustomNameVisible(true);
        });
        for (Round r : rounds.values()) {
            r.dummy.customName(net.kyori.adventure.text.Component.text("ALVO " + Math.round(hp(r.dummy.getUniqueId()) * 10) / 10.0 + " vida"));
            r.dummy.setCustomNameVisible(true);
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> watchPlayer(e.getPlayer()), 5L);
    }

    /** Auto_Math entra criativo (voa e pode mexer); outros como espectador. A horda ignora os dois. */
    private void watchPlayer(org.bukkit.entity.Player p) {
        p.setGameMode(p.getName().equals("Auto_Math") ? org.bukkit.GameMode.CREATIVE : org.bukkit.GameMode.SPECTATOR);
        p.setAllowFlight(true);
        p.setFlying(true);
        Round any = rounds.values().stream().findFirst().orElse(null);
        Location to = any != null ? any.dummy.getLocation().add(8, 6, 8) : world.getSpawnLocation();
        if (any != null) to.setDirection(any.dummy.getLocation().toVector().subtract(to.toVector()));
        p.teleport(to);
        p.sendMessage("Arena (" + slots + " slots, " + rounds.size() + " rodando). Slots ficam a cada " + SLOT_SPACING + " blocos no eixo X.");
    }

    private void replay(Round r, int t) {
        double ms = t * 50.0;
        while (r.pathIdx + 1 < r.path.size() && r.path.get(r.pathIdx + 1)[0] <= ms) r.pathIdx++;
        double[] p = r.path.get(r.pathIdx);
        Location cur = r.dummy.getLocation();
        if (Math.abs(cur.getX() - p[1]) + Math.abs(cur.getY() - p[2]) + Math.abs(cur.getZ() - p[3]) > 0.05)
            r.dummy.teleport(new Location(world, p[1], p[2], p[3], cur.getYaw(), cur.getPitch()));
    }

    private Zombie nearest(Round r, double max) {
        Zombie best = null; double bd = max * max;
        for (Entity e : r.dummy.getNearbyEntities(max, max, max)) {
            if (!(e instanceof Zombie z) || !horde.isHorde(z)) continue;
            double d = z.getLocation().distanceSquared(r.dummy.getLocation());
            if (d < bd) { bd = d; best = z; }
        }
        return best;
    }

    /** Sobe em pilar (ate 10) quando a horda chega a 30 blocos: 1 bloco a cada 0,5 s. */
    private void pillar(Round r, int t) {
        if (t % 10 != 0 || r.built >= 10 || nearest(r, 30) == null) return;
        Block feet = r.dummy.getLocation().getBlock();
        if (!feet.getRelative(0, 2, 0).isPassable()) return;
        r.dummy.teleport(r.dummy.getLocation().add(0, 1, 0));
        feet.setType(Material.COBBLESTONE, false);
        r.built++;
    }

    /** Derruba o bloco colocado pela horda embaixo do zumbi mais alto ao alcance (4,5 blocos). */
    private void breakStruct(Round r, int t) {
        if (t % 8 != 0) return;
        Location eye = r.dummy.getEyeLocation();
        Block best = null; double by = -1e9;
        for (Entity e : r.dummy.getNearbyEntities(5, 6, 5)) {
            if (!(e instanceof Zombie z) || !horde.isHorde(z)) continue;
            Block under = z.getLocation().getBlock().getRelative(0, -1, 0);
            if (!SmartHorde.ZPLACED.contains(under)) continue;
            if (under.getLocation().add(0.5, 0.5, 0.5).distance(eye) > 4.5) continue;
            if (z.getLocation().getY() > by) { by = z.getLocation().getY(); best = under; }
        }
        if (best != null) { best.setType(Material.AIR); SmartHorde.ZPLACED.remove(best); r.broke++; }
    }

    /** Espada: 6 de dano a cada 0,6 s no zumbi mais perto (alcance 3), com empurrao. */
    private void fight(Round r, int t) {
        if (t % 12 != 0) return;
        Zombie z = nearest(r, 3);
        if (z == null) return;
        z.damage(6, r.dummy);
        Vector kb = z.getLocation().toVector().subtract(r.dummy.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 0.01) z.setVelocity(kb.normalize().multiply(0.4).setY(0.3));
        r.swings++;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        Round r = byDummy.get(e.getEntity().getUniqueId());
        if (r == null) return;
        e.setCancelled(true); // a vida do substituto e nossa (com armadura), nao a do aldeao
        if (!(e instanceof EntityDamageByEntityEvent by)) return;
        boolean human = by.getDamager() instanceof org.bukkit.entity.Player hp && humanZombies.containsKey(hp.getUniqueId());
        if (!(by.getDamager() instanceof Zombie) && !human) return;
        double d = e.getDamage() * (1 - ARMOR);
        HP.merge(r.dummy.getUniqueId(), -d, Double::sum);
        if (r.demo && hp(r.dummy.getUniqueId()) < 1) HP.put(r.dummy.getUniqueId(), 1.0); // demo: o alvo aguenta (golpes contam)
        r.dmg += d;
        r.hits++;
        r.lastHurt = Bukkit.getCurrentTick() - r.startTick;
        if (r.firstHit < 0) r.firstHit = r.lastHurt;
    }

    // ------------------------------------------------------------------ jogador como zumbi (demonstracao)

    /** Jogador -> papel (0 minerador, 1 lenhador, 2 brutamontes, 4 cavador). Mesmas regras de quebra e de inventario. */
    private final Map<UUID, Integer> humanZombies = new HashMap<>();

    /** /arena zumbi [minerador|lenhador|cavador|guerreiro|sair] */
    private void humanZombie(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player p)) { sender.sendMessage("so jogador"); return; }
        String which = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "minerador";
        if (which.equals("sair")) {
            humanZombies.remove(p.getUniqueId());
            SmartHorde.ALLY_PLAYERS.remove(p.getUniqueId());
            p.setInvulnerable(false);
            p.getInventory().clear();
            watchPlayer(p);
            p.sendMessage("Voce saiu da horda.");
            return;
        }
        int role = switch (which) {
            case "lenhador" -> SmartHorde.LUMBER;
            case "cavador" -> SmartHorde.DIGGER;
            case "guerreiro" -> SmartHorde.BRUTE;
            default -> SmartHorde.MINER;
        };
        Round r = rounds.values().stream().findFirst().orElse(null);
        if (r == null) { p.sendMessage("Nenhuma rodada rodando. Comece uma com /arena run <onda> replay 600 demo"); return; }
        Location at = null;
        for (UUID id : r.zombies) { Entity e = Bukkit.getEntity(id); if (e != null && !e.isDead()) { at = e.getLocation(); break; } }
        if (at == null) at = r.spawn != null ? r.spawn : r.dummy.getLocation().add(15, 0, 0);
        if (!watch) { watch = true; if (sprinting) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop"); sprinting = false; } }
        humanZombies.put(p.getUniqueId(), role);
        SmartHorde.ALLY_PLAYERS.add(p.getUniqueId());
        p.getInventory().clear();
        p.getInventory().addItem(new org.bukkit.inventory.ItemStack(switch (role) {
            case SmartHorde.LUMBER -> Material.WOODEN_AXE;
            case SmartHorde.DIGGER -> Material.WOODEN_SHOVEL;
            case SmartHorde.BRUTE -> Material.STONE_SWORD;
            default -> Material.WOODEN_PICKAXE;
        }));
        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setInvulnerable(true); // golem e torre nao te matam: o foco e o caminho ate o alvo
        p.setFoodLevel(20);
        p.teleport(at);
        p.sendMessage("Voce e um zumbi da horda (" + which + "). Mesmas regras: so quebra o que o papel quebra, carrega ate "
                + SmartHorde.capacityOf(plugin) + " blocos e a horda nao te ataca. Chegue no alvo e bata nele. /arena zumbi sair para sair.");
        plugin.getLogger().info("DEMO " + p.getName() + " jogando como " + which + " na rodada " + r.tag);
    }

    private int blockCount(org.bukkit.entity.Player p) {
        int n = 0;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType().isBlock() && it.getType().isSolid()) n += it.getAmount();
        return n;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHumanBreak(org.bukkit.event.block.BlockBreakEvent e) {
        Integer role = humanZombies.get(e.getPlayer().getUniqueId());
        if (role == null) return;
        if (!SmartHorde.roleCanBreak(role, e.getBlock())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("Esse papel nao quebra " + e.getBlock().getType().name().toLowerCase()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHumanPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (humanZombies.containsKey(e.getPlayer().getUniqueId())) SmartHorde.ZPLACED.add(e.getBlockPlaced()); // vira estrutura da horda
    }

    @EventHandler(ignoreCancelled = true)
    public void onHumanPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Player p) || !humanZombies.containsKey(p.getUniqueId())) return;
        Material m = e.getItem().getItemStack().getType();
        if (m.isBlock() && m.isSolid() && blockCount(p) >= SmartHorde.capacityOf(plugin)) e.setCancelled(true);
    }

    @EventHandler
    public void onTransform(EntityTransformEvent e) {
        if (byDummy.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /**
     * Torre da arena com os numeros da Torre Sentinela do Classforge (nivel 1): 1 tiro/s, alcance 12, so em
     * mobs da horda com linha de visao. A flecha leva a marca de origem, como a torre de verdade.
     */
    private void turretShots(Round r) {
        for (Block tb : r.turrets) {
            if (tb.getType() != Material.DISPENSER) continue;
            Location muzzle = tb.getLocation().add(0.5, 1.2, 0.5);
            org.bukkit.entity.LivingEntity best = null;
            double bd = 144;
            for (UUID id : r.zombies) {
                Entity e = Bukkit.getEntity(id);
                if (!(e instanceof org.bukkit.entity.LivingEntity le) || le.isDead()) continue;
                double d = le.getLocation().distanceSquared(muzzle);
                if (d >= bd) continue;
                org.bukkit.util.Vector to = le.getEyeLocation().toVector().subtract(muzzle.toVector());
                if (world.rayTraceBlocks(muzzle, to.clone().normalize(), Math.max(0.1, to.length() - 0.6)) != null) continue; // sem visao
                bd = d; best = le;
            }
            if (best == null) continue;
            org.bukkit.util.Vector dir = best.getEyeLocation().toVector().subtract(muzzle.toVector()).normalize();
            org.bukkit.entity.Arrow ar = world.spawnArrow(muzzle, dir, 2.6f, 2f);
            ar.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            ar.getPersistentDataContainer().set(SmartHorde.TURRET_ORIGIN, org.bukkit.persistence.PersistentDataType.STRING,
                    tb.getX() + "," + tb.getY() + "," + tb.getZ());
        }
    }

    private void finish(Round r, String why) {
        if (rounds.get(r.slot) != r) return;
        rounds.remove(r.slot);
        for (org.bukkit.entity.IronGolem g : r.golems) if (g.isValid()) g.remove();
        if (r.demo) horde.stopDemoRecording();
        int turretsLeft = 0;
        for (Block tb : r.turrets) { if (tb.getType() == Material.DISPENSER) { turretsLeft++; tb.setType(Material.AIR); } }
        r.turretsLeft = turretsLeft;
        int t = Bukkit.getCurrentTick() - r.startTick;
        int[] st = horde.statsFor(r.zombies);
        horde.removeAll(r.zombies);
        boolean died = r.dummy != null && hp(r.dummy.getUniqueId()) <= 0;
        if (r.dummy != null) {
            UUID id = r.dummy.getUniqueId();
            SmartHorde.EXTRA_TARGETS.remove(id); HP.remove(id); byDummy.remove(id); r.dummy.remove();
        }
        String json = String.format(java.util.Locale.ROOT,
                "{\"wave\":\"%s\",\"strategy\":\"%s\",\"tag\":\"%s\",\"end\":\"%s\",\"survival\":%.1f,\"died\":%b,\"dmg\":%.1f,"
                        + "\"hits\":%d,\"zombies_alive\":%d,\"zombie_blocks_left\":%d,\"player_built\":%d,\"player_broke\":%d,\"swings\":%d,\"min_dist\":%.1f,\"first_hit\":%.1f,\"slot\":%d}",
                r.wave, r.strategy, r.tag, why, t / 20.0, died, r.dmg, r.hits, st[0], st[1], r.built, r.broke, r.swings,
                r.minDist > 1e8 ? 99.0 : r.minDist, r.firstHit < 0 ? -1.0 : r.firstHit / 20.0, r.slot);
        plugin.getLogger().info("ARENA_RESULT " + json);
    }
}
