package dev.math.smarthordes;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Horda zumbi com mobs de verdade (sem bots): rastreio de longo alcance, quebra de blocos com animacao,
 * pilares e pontes. Mesma ideia dos mods de apocalipse (Zombie Break & Build etc.), via API do Paper.
 */
final class SmartHorde implements org.bukkit.event.Listener {

    private static final Set<Material> PROTECTED = Set.of(
            Material.BEDROCK, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.BARRIER, Material.REINFORCED_DEEPSLATE,
            Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.NETHER_PORTAL, Material.END_GATEWAY,
            Material.RESPAWN_ANCHOR, Material.SPAWNER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.LIGHT);
    private static final class State {
        UUID target;
        int role;            // 0 minerador (picareta), 1 lenhador (machado), 2 brutamontes, 3 tanque, 4 cavador (pa)
        Location lastPos;
        int stuckTicks;
        boolean pathOk = true;
        Block breaking;
        long breakStart;
        long lastPlace;
        int tick;
        /** Na agua: para onde nadar (margem ou o proprio alvo); null fora da agua. */
        Location swimTo;
        /** Ultimo bloco que este zumbi quebrou: a coleta continua colada nele (um buraco so, nao furos espalhados). */
        Block lastMined;
        /** Horda de caverna: so mineradores, que cavam qualquer bloco natural da caverna (e so eles). */
        boolean cave;
        /** Ticks seguidos na agua e se ja sorteou virar afogado nesta vida. */
        int waterTicks;
        boolean drownRolled;
        /** Escolhido para mergulhar de proposito quando o alvo se defende com agua (sorteado uma vez). */
        Boolean diver;
        Location diveTo;
        /** Inventario de blocos: so constroi com o que carrega (e o que carrega veio do que quebrou). */
        final java.util.EnumMap<Material, Integer> inv = new java.util.EnumMap<>(Material.class);
        int invTotal;
        int ladders;         // escadas craftadas (lenhador)
        boolean hasTable;    // bancada guardada no inventario
        Block table;         // bancada colocada no chao
        long craftUntil;     // usando a bancada ate
        Block tree;          // tora que o lenhador esta indo cortar
        long lastTreeScan;
        // fase de coleta
        boolean gathering;
        boolean hand; // sem recurso do papel: cava terra/areia na mao (mais lento, como no vanilla)
        int need;            // material alvo (blocos; para lenhador, unidades de tabua)
        long gatherStart, gatherCooldownUntil, lastScan, resourceSince;
        Block resource;
        final java.util.Set<Block> bad = new java.util.HashSet<>(); // recursos que nao conseguiu alcancar
        String action = "-";
        long lastDebug;
        boolean assigned;    // ja passou pela distribuicao de alvos
        boolean rampMode;    // trabalhando na rampa: sem alvo de ataque (o goal vanilla puxava para fora da borda)
        int rampStuck;       // ticks parado com a rampa 'pronta' sem chegar
    }

    private boolean opt(String k) { return plugin.getConfig().getBoolean("horde." + k, true); }

    /** Relogio do jogo em ms (tick * 50): acelera junto com /tick sprint na arena. */
    static long now() { return Bukkit.getCurrentTick() * 50L; }

    /** Alvos que nao sao jogadores (arena: substituto do jogador). */
    static final java.util.Set<UUID> EXTRA_TARGETS = new java.util.HashSet<>();

    /** Parametro ajustavel (horde.p.<nome> na config; a arena troca por rodada). Padrao = valor de hoje. */
    private double P(String k, double def) { return plugin.getConfig().getDouble("horde.p." + k, def); }
    private int Pi(String k, int def) { return plugin.getConfig().getInt("horde.p." + k, def); }

    private int capacity() { return Math.max(0, plugin.getConfig().getInt("horde.block-capacity", 32)); }

    /** Bloco que da para usar em pilar/ponte: cubo solido comum (nada de flor, vidro fino, bau etc.). */
    private static boolean buildable(Material m) {
        return m.isBlock() && m.isSolid() && m.isOccluding() && !m.hasGravity() && !PROTECTED.contains(m)
                && m != Material.TNT && !m.name().contains("ORE") && !m.name().contains("SHULKER");
    }

    private boolean addBlock(State s, Material m) {
        if (!buildable(m) || s.invTotal >= capacity()) return false;
        if (Tag.LOGS.isTagged(m) && opt("log-to-planks")) { // receita 2x2 no inventario: 1 tora = 4 tabuas
            Material pl = planksOf(m);
            int n = Math.min(4, capacity() - s.invTotal);
            s.inv.merge(pl, n, Integer::sum);
            s.invTotal += n;
            return true;
        }
        s.inv.merge(m, 1, Integer::sum);
        s.invTotal++;
        return true;
    }

    /** Tira um bloco do inventario (o tipo que tem mais). null = sem blocos. */
    private static Material takeBlock(State s) {
        Material best = null; int n = 0;
        for (var e : s.inv.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        if (best == null) return null;
        if (n == 1) s.inv.remove(best); else s.inv.put(best, n - 1);
        s.invTotal--;
        return best;
    }

    private final SmartHordes plugin;
    private final NamespacedKey key;
    private final Map<UUID, State> horde = new HashMap<>();
    /** Gravador da onda atual (para o simulador); null = nao gravando. */
    static WaveRecorder REC;
    private final Map<UUID, Ramp> ramps = new HashMap<>(); // uma rampa por jogador-alvo
    private BukkitTask task;

    SmartHorde(SmartHordes plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "horde");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::swimTick, 2L, 2L);
        plugin.getConfig().addDefault("horde.block-capacity", 32);
        plugin.getConfig().addDefault("horde.debug", false);
        // vindos do simulador (horde-sim): cada um ligavel
        plugin.getConfig().addDefault("horde.log-to-planks", true);
        plugin.getConfig().addDefault("horde.record", true);
        plugin.getConfig().addDefault("horde.emerge", true);
        plugin.getConfig().addDefault("horde.swim", true);
        plugin.getConfig().addDefault("horde.cave-miners", true);
        plugin.getConfig().addDefault("horde.ramp-repair", true);
        plugin.getConfig().addDefault("horde.fell-whole-tree", true);
        plugin.getConfig().addDefault("horde.tidy-gather", true);
        plugin.getConfig().addDefault("horde.drowned", true);
        plugin.getConfig().addDefault("horde.no-self-push", true);
        plugin.getConfig().addDefault("horde.ramp-wide-floating", true);
        plugin.getConfig().addDefault("horde.ramp-shapes", "straight,zigzag,spiral");
        plugin.getConfig().addDefault("horde.dig-stairs", true);
        plugin.getConfig().addDefault("horde.warrior-escort", true);
        plugin.getConfig().addDefault("horde.ramp", true);
        plugin.getConfig().addDefault("horde.surround", true);
        plugin.getConfig().addDefault("horde.escape-ceiling", true);
        // restos de uma horda anterior (crash/restart) somem ao carregar
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Zombie z : w.getEntitiesByClass(Zombie.class))
                if (z.getPersistentDataContainer().has(key, PersistentDataType.BYTE) && !horde.containsKey(z.getUniqueId())) z.remove();
        }, 40L);
    }

    boolean isHorde(Entity e) { return e != null && horde.containsKey(e.getUniqueId()); }

    int alive() {
        horde.keySet().removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead() || !e.isValid(); });
        return horde.size();
    }

    // ---------------------------------------------------------------- saindo da terra

    /** Mobs no meio da animacao de sair do chao (a IA da horda espera). */
    static final Set<UUID> EMERGING = new java.util.HashSet<>();

    /**
     * Animacao: o mob nasce enterrado e sobe ate {@code dest} em ~1,5 s, com particulas do bloco do chao
     * e som de terra. Comeca depois de {@code delay} ticks (escalonado para nao subirem todos juntos).
     * Parado, sem gravidade e invulneravel enquanto sobe; no fim volta ao normal.
     */
    void emerge(org.bukkit.entity.Mob m, Location dest, int delay) {
        if (!plugin.getConfig().getBoolean("horde.emerge", true)) return;
        Block ground = dest.clone().add(0, -0.5, 0).getBlock();
        if (!ground.getType().isSolid() || ground.isLiquid() || dest.getBlock().isLiquid()) return; // sem chao: nasce normal
        final int rise = 30;
        final double depth = 2.2;
        org.bukkit.block.data.BlockData dust = ground.getBlockData();
        UUID id = m.getUniqueId();
        EMERGING.add(id);
        m.setAware(false);
        m.setGravity(false);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.teleport(dest.clone().add(0, -depth, 0));
        int[] t = {-Math.max(0, delay)};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            t[0]++;
            if (!m.isValid() || m.isDead()) { EMERGING.remove(id); task.cancel(); return; }
            World w = dest.getWorld();
            if (t[0] <= 0) {
                if (t[0] % 10 == 0) w.spawnParticle(Particle.BLOCK, dest.clone().add(0, 0.1, 0), 6, 0.3, 0.05, 0.3, dust);
                return;
            }
            if (t[0] < rise) {
                m.teleport(dest.clone().add(0, -depth + depth * t[0] / rise, 0));
                w.spawnParticle(Particle.BLOCK, dest.clone().add(0, 0.1, 0), 8, 0.35, 0.1, 0.35, dust);
                if (t[0] % 6 == 1) w.playSound(dest, dust.getSoundGroup().getBreakSound(), 0.8f, 0.6f);
                return;
            }
            m.teleport(dest);
            m.setGravity(true);
            m.setInvulnerable(false);
            m.setSilent(false);
            m.setAware(true);
            EMERGING.remove(id);
            w.spawnParticle(Particle.BLOCK, dest.clone().add(0, 0.3, 0), 25, 0.4, 0.3, 0.4, dust);
            w.playSound(dest, m instanceof org.bukkit.entity.PigZombie ? Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY
                    : m instanceof org.bukkit.entity.Piglin ? Sound.ENTITY_PIGLIN_ANGRY : Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 0.7f);
            task.cancel();
        }, 1L, 1L);
    }

    // ---------------------------------------------------------------- agua

    private final Map<UUID, long[]> waterDefCache = new HashMap<>();

    /**
     * O alvo esta usando agua como defesa: nadando, de barco, ou cercado de agua (ilha, fosso):
     * metade ou mais dos pontos num anel de raio 2 a 4 em volta dele sao agua. Cache de 2 s.
     */
    private boolean waterDefense(LivingEntity t) {
        long now = now();
        long[] c = waterDefCache.get(t.getUniqueId());
        if (c != null && now - c[0] < 2000) return c[1] == 1;
        boolean def = t.isInWater() || t.getVehicle() instanceof org.bukkit.entity.Boat;
        if (!def) {
            Location l = t.getLocation();
            World w = l.getWorld();
            int water = 0, total = 0;
            for (int r = 2; r <= 4; r++) {
                for (int k = 0; k < 12; k++) {
                    double a = Math.PI * 2 * k / 12;
                    int x = (int) Math.floor(l.getX() + Math.cos(a) * r), z = (int) Math.floor(l.getZ() + Math.sin(a) * r);
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    total++;
                    for (int dy = -2; dy <= 0; dy++) if (w.getBlockAt(x, l.getBlockY() + dy, z).getType() == Material.WATER) { water++; break; }
                }
            }
            def = total > 0 && water * 2 >= total;
        }
        waterDefCache.put(t.getUniqueId(), new long[]{now, def ? 1 : 0});
        return def;
    }

    /** Bloco de agua mais util para mergulhar: perto do zumbi e na direcao do alvo. */
    private Location nearestWater(Location from, Location toward, int R) {
        World w = from.getWorld();
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > R * R) continue;
                int x = from.getBlockX() + dx, z = from.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int dy = 2; dy >= -4; dy--) {
                    Block b = w.getBlockAt(x, from.getBlockY() + dy, z);
                    if (b.getType() != Material.WATER) continue;
                    if (!b.getRelative(BlockFace.UP).isPassable()) break;
                    Location l = b.getLocation().add(0.5, 0.2, 0.5);
                    double score = Math.sqrt(dx * dx + dz * dz) + 0.5 * l.distance(toward);
                    if (score < bestScore) { bestScore = score; best = l; }
                    break;
                }
            }
        }
        return best;
    }

    /** Escolhe para onde nadar: o alvo, se ele tambem esta na agua e perto; senao a margem seca mais util. */
    private void planSwim(Zombie z, State s, LivingEntity t) {
        s.action = "nadando";
        if (t != null && t.isInWater() && z.getLocation().distanceSquared(t.getLocation()) < 144) {
            s.swimTo = t.getLocation();
            return;
        }
        if (s.swimTo != null && s.tick % 4 != 0) return; // recalcula a margem a cada 2 s
        Location zl = z.getLocation();
        World w = zl.getWorld();
        int R = (int) P("swim_shore_r", 10);
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz > R * R) continue;
                int x = zl.getBlockX() + dx, zz = zl.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, zz >> 4)) continue;
                for (int dy = -2; dy <= 3; dy++) {
                    int y = zl.getBlockY() + dy;
                    Block ground = w.getBlockAt(x, y - 1, zz), feet = w.getBlockAt(x, y, zz), head = w.getBlockAt(x, y + 1, zz);
                    if (!ground.getType().isSolid() || ground.isLiquid() || feet.isLiquid() || head.isLiquid()
                            || !feet.isPassable() || !head.isPassable()) continue;
                    double score = Math.sqrt(dx * dx + dz * dz) + Math.max(0, dy) * 1.5;
                    if (t != null) score += 0.6 * Math.sqrt(t.getLocation().distanceSquared(new Location(w, x + 0.5, y, zz + 0.5)));
                    if (score < bestScore) { bestScore = score; best = new Location(w, x + 0.5, y, zz + 0.5); }
                    break;
                }
            }
        }
        // sem margem por perto: nada na direcao do alvo pela superficie
        s.swimTo = best != null ? best : t != null ? t.getLocation() : null;
    }

    /** A cada 2 ticks: boia na superficie e nada ate swimTo; perto da margem, pula para fora. */
    private void swimTick() {
        for (Map.Entry<UUID, State> en : horde.entrySet()) {
            State s = en.getValue();
            if (s.swimTo == null) continue;
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Zombie z) || !z.isValid() || !z.isInWater() || s.swimTo.getWorld() != z.getWorld()) continue;
            Location l = z.getLocation();
            boolean headUnder = l.clone().add(0, z.getHeight() * 0.85, 0).getBlock().isLiquid();
            Vector dir = s.swimTo.toVector().subtract(l.toVector()).setY(0);
            double horiz = dir.length();
            if (horiz > 0.05) dir.multiply(P("swim_speed", 0.11) / horiz);
            double vy = headUnder ? 0.09 : Math.max(0.0, z.getVelocity().getY());
            // chegando na margem (mais alta que a agua): pulo para sair
            if (horiz < 1.6 && s.swimTo.getY() > l.getY() - 0.2 && !s.swimTo.getBlock().isLiquid()) vy = 0.42;
            z.setVelocity(new Vector(dir.getX(), vy, dir.getZ()));
            if (horiz > 0.05) z.setRotation((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())), 0f);
        }
    }

    /**
     * Troca o zumbi por um afogado da horda no mesmo lugar: mesmo papel, ferramenta, armadura,
     * blocos carregados, vida e alvo. O afogado nada rapido e a IA da horda continua mandando nele.
     */
    private void toDrowned(Zombie z, State s, LivingEntity t) {
        Location l = z.getLocation();
        EntityEquipment old = z.getEquipment();
        double hp = z.getHealth();
        org.bukkit.entity.Drowned d = l.getWorld().spawn(l, org.bukkit.entity.Drowned.class, dd -> {
            setup(dd, s.role, t);
            EntityEquipment eq = dd.getEquipment();
            if (old != null && eq != null) {
                eq.setArmorContents(old.getArmorContents());
                eq.setItemInMainHand(old.getItemInMainHand());
                eq.setItemInOffHand(old.getItemInOffHand());
            }
            attr(dd, Attribute.MOVEMENT_SPEED, s.role == 3 ? 0.24 : 0.28);
        });
        d.setHealth(Math.min(hp, d.getMaxHealth()));
        s.swimTo = null;
        horde.put(d.getUniqueId(), s);
        horde.remove(z.getUniqueId());
        z.remove();
        l.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, l.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
        l.getWorld().playSound(l, Sound.ENTITY_ZOMBIE_CONVERTED_TO_DROWNED, 1f, 1f);
        s.action = "virou afogado";
    }

    /** Zumbi da horda nunca vira afogado pelo jeito vanilla (afundar travava a horda; a troca e por toDrowned). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onDrownConvert(org.bukkit.event.entity.EntityTransformEvent e) {
        if (e.getTransformReason() == org.bukkit.event.entity.EntityTransformEvent.TransformReason.DROWNED
                && horde.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    private static int stagger() { return ThreadLocalRandom.current().nextInt(0, 50); }

    // ---------------------------------------------------------------- spawn / remover

    void spawn(World w, List<Location> spots, int count, LivingEntity target) { spawn(w, spots, count, target, false); }

    /** buildersOnly: so minerador/cavador/lenhador (onda de construtores). */
    void spawn(World w, List<Location> spots, int count, LivingEntity target, boolean buildersOnly) {
        spawn(w, spots, count, target, buildersOnly ? new int[]{MINER, DIGGER, LUMBER} : new int[]{MINER, DIGGER, LUMBER, BRUTE, TANK});
    }

    /** cycle: ordem dos papeis (define a mistura da horda). */
    void spawn(World w, List<Location> spots, int count, LivingEntity target, int[] cycle) {
        // alvo no subsolo: horda so de mineradores, que cavam os blocos naturais da caverna ate ele
        boolean cave = opt("cave-miners") && underground(target.getLocation());
        if (cave) {
            cycle = new int[]{MINER};
            plugin.getLogger().info("Horda de caverna: " + count + " mineradores atras de " + target.getName());
        }
        for (int i = 0; i < count; i++) {
            Location base = spots.get(i % spots.size()).clone();
            Biome b = w.getBiome(base);
            Class<? extends Zombie> type = b.getKey().getKey().contains("desert") || b.getKey().getKey().contains("badlands") ? Husk.class : Zombie.class;
            final int role = cycle[i % cycle.length];
            Zombie z = w.spawn(base, type, zz -> setup(zz, role, target));
            State s = new State();
            s.role = role;
            s.cave = cave;
            s.target = target.getUniqueId();
            // inventario inicial por papel; o resto ele junta quebrando blocos no caminho
            // comecam sem blocos (vanilla): so colocam o que mineraram no caminho / antes de subir
            horde.put(z.getUniqueId(), s);
            emerge(z, base, stagger());
        }
        if (REC == null && plugin.getConfig().getBoolean("horde.record", true)) {
            int low = target.getLocation().getBlockY();
            for (Location l : spots) low = Math.min(low, l.getBlockY());
            try { REC = new WaveRecorder(plugin, w, target.getLocation(), "zumbis", low); }
            catch (Exception ex) { plugin.getLogger().warning("gravador: " + ex); }
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /**
     * Horda do Nether: zumbificados com o MESMO cerebro da horda (papeis, rampa, escada cavada, cerco),
     * armadura de ouro completa e sempre bravos. Nao grava (a gravacao e das ondas de zumbi).
     */
    List<UUID> spawnPigs(World w, List<Location> spots, int count, LivingEntity target) {
        int[] cycle = {MINER, DIGGER, LUMBER, BRUTE, TANK};
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location base = spots.get(i % spots.size()).clone();
            final int role = cycle[i % cycle.length];
            org.bukkit.entity.PigZombie z = w.spawn(base, org.bukkit.entity.PigZombie.class, zz -> {
                setup(zz, role, target);
                zz.setAngry(true);
                zz.setAnger(Integer.MAX_VALUE);
                goldArmor(zz.getEquipment());
                if (role == BRUTE && zz.getEquipment() != null) zz.getEquipment().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
            });
            State s = new State();
            s.role = role;
            s.target = target.getUniqueId();
            horde.put(z.getUniqueId(), s);
            ids.add(z.getUniqueId());
            w.spawnParticle(Particle.FLAME, base.clone().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.01);
            emerge(z, base, stagger());
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        return ids;
    }

    static void goldArmor(EntityEquipment eq) {
        if (eq == null) return;
        eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
        eq.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.GOLDEN_LEGGINGS));
        eq.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
        eq.setHelmetDropChance(0.05f); eq.setChestplateDropChance(0.05f);
        eq.setLeggingsDropChance(0.05f); eq.setBootsDropChance(0.05f);
    }

    /** Membros vivos (para seguir o jogador pelo portal). */
    List<Zombie> members() {
        List<Zombie> out = new ArrayList<>();
        for (UUID id : horde.keySet()) { Entity e = Bukkit.getEntity(id); if (e instanceof Zombie z && z.isValid() && !z.isDead()) out.add(z); }
        return out;
    }

    /** Arena: recria uma onda gravada (cada zumbi no lugar e papel de onde comecou). */
    List<UUID> spawnExact(World w, List<Location> spots, List<Integer> roles, LivingEntity target) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            Location base = spots.get(i);
            final int role = roles.get(i);
            Zombie z = w.spawn(base, Zombie.class, zz -> setup(zz, role, target));
            State s = new State();
            s.role = role;
            s.target = target.getUniqueId();
            horde.put(z.getUniqueId(), s);
            ids.add(z.getUniqueId());
        }
        if (REC == null && plugin.getConfig().getBoolean("horde.record", true) && plugin.getConfig().getInt("arena-slots", 1) <= 1) {
            int low = target.getLocation().getBlockY();
            for (Location l : spots) low = Math.min(low, l.getBlockY());
            try { REC = new WaveRecorder(plugin, w, target.getLocation(), "arena", low); }
            catch (Exception ex) { plugin.getLogger().warning("gravador: " + ex); }
        }
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        return ids;
    }

    /** Arena: vivos e blocos carregados so dos zumbis desta rodada. */
    int[] statsFor(java.util.Collection<UUID> ids) {
        int alive = 0, blocks = 0;
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            State st = horde.get(id);
            if (e == null || e.isDead() || !e.isValid() || st == null) continue;
            alive++;
            blocks += st.invTotal;
        }
        return new int[]{alive, blocks};
    }

    /** Arena: tira so os zumbis desta rodada (as outras seguem). */
    void removeAll(java.util.Collection<UUID> ids) {
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
            horde.remove(id);
        }
    }

    /** Arena: estatisticas do fim da rodada. */
    int[] stats() {
        int blocks = 0;
        for (State st : horde.values()) blocks += st.invTotal;
        return new int[]{alive(), blocks};
    }

    String recordingName() { return REC == null ? null : REC.name(); }

    interface StateView { void accept(Zombie z, String role, String action, int inv); }

    /** Arena: para os rotulos em cima de cada zumbi. */
    void forEachState(StateView v) {
        String[] roles = {"minerador", "lenhador", "bruto", "tanque", "cavador"};
        for (var en : horde.entrySet()) {
            Entity e = Bukkit.getEntity(en.getKey());
            if (e instanceof Zombie z && z.isValid()) v.accept(z, roles[en.getValue().role], en.getValue().action, en.getValue().invTotal);
        }
    }

    /** Time da horda: zumbis nao se empurram entre si (pushOtherTeams), mas colidem com jogadores normalmente. */
    private static org.bukkit.scoreboard.Team hordeTeam() {
        var sb = Bukkit.getScoreboardManager().getMainScoreboard();
        var t = sb.getTeam("hc_horda");
        if (t == null) t = sb.registerNewTeam("hc_horda");
        t.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.FOR_OTHER_TEAMS);
        return t;
    }

    private void setup(Zombie z, int role, LivingEntity target) {
        if (plugin.getConfig().getBoolean("horde.no-self-push", true)) {
            try { hordeTeam().addEntity(z); } catch (Exception ignored) { }
        }
        z.setAdult();
        z.setShouldBurnInDay(false);
        z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false));
        z.setPersistent(true);
        z.setRemoveWhenFarAway(false);
        z.setCanPickupItems(false);
        z.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        attr(z, Attribute.FOLLOW_RANGE, 128);
        attr(z, Attribute.MOVEMENT_SPEED, role == 3 ? 0.24 : 0.28);
        attr(z, Attribute.KNOCKBACK_RESISTANCE, role == 3 ? 0.6 : 0.15);
        if (role == 3) attr(z, Attribute.MAX_HEALTH, 34);
        EntityEquipment eq = z.getEquipment();
        if (eq != null) {
            switch (role) {
                case MINER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_PICKAXE));
                case LUMBER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_AXE));
                case DIGGER -> eq.setItemInMainHand(new ItemStack(Material.WOODEN_SHOVEL));
                case 2 -> eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                default -> eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
            }
            eq.setItemInMainHandDropChance(0.08f);
            eq.setHelmetDropChance(0.08f);
        }
        if (role == 3) z.setHealth(34);
        z.setTarget(target);
    }

    private static void attr(Zombie z, Attribute a, double v) {
        AttributeInstance i = z.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    void despawn(boolean effect) {
        for (UUID id : new ArrayList<>(horde.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) continue;
            if (effect) e.getWorld().spawnParticle(Particle.LARGE_SMOKE, e.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
            e.remove();
        }
        horde.clear();
        ramps.clear();
        rampHistory.clear();
        stopRecording();
        if (task != null) { task.cancel(); task = null; }
    }

    // ---------------------------------------------------------------- cerebro (2x por segundo)

    /**
     * Distribui a horda entre os jogadores proximos: cada zumbi vai no jogador com MENOS zumbis em cima,
     * ponderado pela distancia (nao foca todo mundo em 1 enquanto o outro limpa). Rebalanceia a cada ~10s.
     */
    private static LivingEntity entityTarget(UUID id) {
        if (id == null) return null;
        Entity e = Bukkit.getEntity(id);
        return e instanceof LivingEntity le ? le : null;
    }

    private LivingEntity pickTarget(Zombie z, State s) {
        LivingEntity t = entityTarget(s.target);
        boolean rebalance = s.tick % 20 == 0 || !s.assigned;
        if (valid(t, z) && !rebalance) return t;
        Map<UUID, Integer> load = new HashMap<>();
        for (var en : horde.entrySet()) {
            if (en.getValue() == s || en.getValue().target == null) continue;
            load.merge(en.getValue().target, 1, Integer::sum);
        }
        LivingEntity best = null; double bestScore = Double.MAX_VALUE;
        List<LivingEntity> cands = new ArrayList<>(z.getWorld().getPlayers());
        for (UUID id : EXTRA_TARGETS) { LivingEntity le = entityTarget(id); if (le != null) cands.add(le); }
        for (LivingEntity p : cands) {
            if (!valid(p, z)) continue;
            double d = p.getLocation().distance(z.getLocation());
            if (d > 96) continue; // so jogadores proximos
            double score = load.getOrDefault(p.getUniqueId(), 0) * 24 + d; // 1 zumbi a mais ~ 24 blocos a mais
            if (p.getUniqueId().equals(s.target)) score -= 6;               // leve preferencia em manter o alvo
            if (score < bestScore) { bestScore = score; best = p; }
        }
        if (best == null && valid(t, z)) best = t;
        if (best != null && !best.getUniqueId().equals(s.target)) { s.resource = null; s.action = "novo alvo: " + best.getName(); }
        s.target = best == null ? null : best.getUniqueId();
        s.assigned = true;
        return best;
    }

    private static boolean valid(LivingEntity e, Zombie z) {
        if (e == null || e.isDead() || !e.isValid() || e.getWorld() != z.getWorld()) return false;
        if (!(e instanceof Player p)) return EXTRA_TARGETS.contains(e.getUniqueId());
        return p.isOnline() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !p.getName().matches("^(Zumbi|Pirata|Guilda)_\\d{1,2}$");
    }

    private void tick() {
        Iterator<Map.Entry<UUID, State>> it = horde.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, State> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Zombie z) || z.isDead() || !z.isValid()) { it.remove(); continue; }
            if (EMERGING.contains(z.getUniqueId())) continue; // ainda saindo da terra
            State s = en.getValue();
            s.tick++;
            LivingEntity t = pickTarget(z, s);
            // NA AGUA: zumbi afunda e fica parado ate virar afogado. Nada para a margem (ou ate o alvo).
            // afogado da horda nada sozinho (IA vanilla e rapida na agua): segue o resto da logica normal
            if (z.isInWater() && !(z instanceof org.bukkit.entity.Drowned) && opt("swim")
                    && (t == null || z.getLocation().distanceSquared(t.getLocation()) > 6.25)) {
                s.waterTicks++;
                // alguns viram AFOGADOS a favor da horda: nadam rapido e alcancam quem esta na agua
                boolean vsWater = t != null && waterDefense(t);
                int after = vsWater || Boolean.TRUE.equals(s.diver) ? 1 : (int) P("drowned_after", 6);
                double chance = Boolean.TRUE.equals(s.diver) ? 1.0 : vsWater ? P("drowned_chance_defense", 0.8) : P("drowned_chance", 0.4);
                if (!s.drownRolled && s.waterTicks >= after && !(z instanceof org.bukkit.entity.PigZombie)) {
                    s.drownRolled = true;
                    if (opt("drowned") && ThreadLocalRandom.current().nextDouble() < chance) {
                        final Zombie zz = z; final LivingEntity tt = t;
                        // fora do laco: trocar a entidade mexe no mapa da horda
                        Bukkit.getScheduler().runTask(plugin, () -> { if (zz.isValid()) toDrowned(zz, s, tt); });
                        continue;
                    }
                }
                planSwim(z, s, t);
                continue;
            }
            s.swimTo = null;
            s.waterTicks = 0;
            if (t == null) continue;
            // ALVO SE DEFENDE COM AGUA (nadando, de barco, ilha ou fosso): parte da horda mergulha de proposito
            // para virar afogado; os construtores seguem tentando ponte/rampa
            if (opt("drowned") && !(z instanceof org.bukkit.entity.Drowned) && !(z instanceof org.bukkit.entity.PigZombie)
                    && !s.drownRolled && waterDefense(t)) {
                if (s.diver == null) s.diver = tool(s) == null ? ThreadLocalRandom.current().nextDouble() < P("diver_share", 0.6)
                        : ThreadLocalRandom.current().nextDouble() < P("diver_share_builders", 0.2);
                if (s.diver) {
                    if (s.diveTo == null || s.tick % 6 == 0) s.diveTo = nearestWater(z.getLocation(), t.getLocation(), (int) P("dive_search_r", 14));
                    if (s.diveTo != null) {
                        if (z.getTarget() != null) z.setTarget(null);
                        z.getPathfinder().moveTo(s.diveTo, 1.15);
                        s.action = "mergulhando para virar afogado";
                        continue;
                    }
                }
            }
            if (!s.rampMode && z.getTarget() != t) z.setTarget(t);

            Location zl = z.getLocation(), tl = t.getLocation();
            double dist = zl.distance(tl);
            long now = now();

            // GUERREIROS (bruto/tanque): nao constroem. Com rampa em obra, esperam em formacao longe dela
            // (antes ficavam colados nos construtores: 'lugar ocupado', empurrao e queda da ponta da rampa)
            if (tool(s) == null && opt("warrior-escort")) {
                Ramp wr = ramps.get(t.getUniqueId());
                if (wr != null && !wr.broken && wr.next() >= 0 && tl.getY() - zl.getY() >= 2) {
                    List<UUID> ws = new ArrayList<>();
                    for (var en2 : horde.entrySet()) if (tool(en2.getValue()) == null && t.getUniqueId().equals(en2.getValue().target)) ws.add(en2.getKey());
                    ws.sort(null);
                    int k = Math.max(0, ws.indexOf(z.getUniqueId()));
                    double ang = 2 * Math.PI * k / Math.max(1, ws.size());
                    double rr = P("warrior_ring", 6);
                    Block c = wr.waitCenter;
                    int wx = (int) Math.round(c.getX() + Math.cos(ang) * rr), wz = (int) Math.round(c.getZ() + Math.sin(ang) * rr);
                    Location spot = new Location(c.getWorld(), wx + 0.5, groundY(c.getWorld(), wx, wz) + 1, wz + 0.5);
                    if (z.getTarget() != null) z.setTarget(null);
                    s.rampMode = true;
                    if (zl.distanceSquared(spot) > 4) z.getPathfinder().moveTo(spot, 1.0); else z.getPathfinder().stopPathfinding();
                    s.action = "guerreiro aguardando a rampa";
                    debug(z, s, dist, tl.getY() - zl.getY());
                    continue;
                }
                if (s.rampMode) { s.rampMode = false; z.setTarget(t); s.action = "rampa pronta: ataque"; }
            }
            // RAMPA DA HORDA: alvo bem acima -> todos constroem a mesma escadaria ate ele
            // histerese: entra na rampa com o alvo 3+ acima; so sai se ficar menos de 1 acima
            double dyR = tl.getY() - zl.getY();
            boolean rampOn = ramps.containsKey(t.getUniqueId()) ? dyR >= 1 : dyR >= P("ramp_min_dy", 3);
            boolean wasRamp = s.rampMode;
            s.rampMode = false;
            if (tool(s) != null && opt("ramp") && !s.gathering && rampOn) {
                if (rampTick(z, s, t, now)) {
                    s.rampMode = true;
                    if (z.getTarget() != null) z.setTarget(null); // sem puxao do ataque vanilla na borda
                    debug(z, s, dist, tl.getY() - zl.getY());
                    continue;
                }
            }
            if (wasRamp && !s.rampMode) z.setTarget(t);
            // FASE 1 (COLETA): alvo no alto e pouco material -> junta recurso primeiro, ataca depois
            if (tool(s) != null) {
                double dyT = tl.getY() - zl.getY();
                if (!s.gathering && dyT >= 2 && now > s.gatherCooldownUntil && z.isOnGround()
                        && zl.getBlock().getType() != Material.LADDER && material(s) < needFor(s, dyT)) {
                    startGather(s, needFor(s, dyT), now);
                }
                if (s.gathering) {
                    int needNow = Math.min(s.need, needFor(s, dyT)); // o alvo pode ter descido/chegado perto
                    long took = now - s.gatherStart;
                    if (material(s) >= needNow || s.invTotal >= capacity() || (took > Pi("partial_after", 25_000) && material(s) >= needNow * P("partial_frac", 0.6))) {
                        s.gathering = false; s.resource = null; s.action = "material ok, atacando";
                        s.gatherCooldownUntil = now + Pi("cd_ok", 20_000); // usa o que tem antes de voltar a coletar
                    }
                    else if (took > Pi("gather_timeout", 45_000)) { s.gathering = false; s.gatherCooldownUntil = now + Pi("cd_timeout", 20_000); s.action = "coleta demorou, ataca com o que tem"; }
                    else { gatherTick(z, s, now); debug(z, s, dist, tl.getY() - zl.getY()); continue; }
                }
            }
            // FASE 2 (ATAQUE)
            if (s.tick % 2 == 0 || !s.pathOk) {
                double dyAbs = Math.abs(tl.getY() - zl.getY());
                Location flank = opt("surround") && dist > P("surround_until", 6) && dyAbs <= 2 ? flankGoal(z, t) : null;
                s.pathOk = flank != null ? z.getPathfinder().moveTo(flank, 1.15) : z.getPathfinder().moveTo(t, 1.15);
            }
            debug(z, s, dist, tl.getY() - zl.getY());

            // travado: quase nao andou e o alvo nao esta colado
            if (s.lastPos != null && zl.getWorld() == s.lastPos.getWorld() && zl.distanceSquared(s.lastPos) < 0.05 && dist > 1.8) s.stuckTicks++;
            else { s.stuckTicks = 0; if (s.breaking != null) cancelBreak(z, s); }
            s.lastPos = zl.clone();
            // na escada com o alvo acima: continua subindo (teleporte por degrau zera o "travado")
            if (zl.getBlock().getType() == Material.LADDER && tl.getY() - zl.getY() >= 1) { act(z, s, zl, tl, dist); continue; }
            if (s.stuckTicks < Pi("stuck_ticks", 3) && s.pathOk) continue; // andando normalmente

            act(z, s, zl, tl, dist);
        }
        if (REC != null) REC.frame(horde, s -> new Object[]{s.role, s.invTotal, s.gathering ? 1 : 0, s.action});
        if (horde.isEmpty() && task != null) { task.cancel(); task = null; stopRecording(); }
    }

    // ---------------------------------------------------------------- fase de coleta

    /** Quanto material a subida pede: blocos (dy + margem) ou, para o lenhador, madeira para escadas + bancada. */
    private int needFor(State s, double dy) {
        if (opt("ramp") && s.target != null && ramps.containsKey(s.target)) {
            Ramp r = ramps.get(s.target);
            int builders = 0;
            for (State o : horde.values()) if (s.target.equals(o.target) && tool(o) != null) builders++;
            int share = (int) Math.ceil(r.remaining() / (double) Math.max(1, builders)) + 3;
            return Math.min(capacity(), Math.min(r.remaining() + 2, Math.max(Pi("ramp_min_share", 8), share))); // >= N por viagem: menos vai-e-volta
        }
        if (s.role == LUMBER && !s.hand) {
            int ladders = (int) Math.ceil(dy);
            return Math.min(capacity(), (int) Math.ceil(ladders / 3.0) * 4 + 4); // ~4 tabuas por 3 escadas + bancada
        }
        return Math.min(capacity(), (int) Math.ceil(dy) + Pi("need_margin", 1)); // 1 bloco por degrau + folga
    }

    /** Material atual na mesma unidade do needFor. */
    private int material(State s) {
        if (s.role == LUMBER && !s.hand) return woodUnits(s) + (int) Math.ceil(s.ladders * 4 / 3.0) + (s.hasTable ? 4 : 0);
        return s.invTotal;
    }

    private void startGather(State s, int need, long now) {
        s.gathering = true;
        s.hand = false;
        s.need = need;
        s.gatherStart = now;
        s.resource = null;
        s.action = "coletando (precisa " + need + ")";
    }

    /** Vai ate o recurso mais proximo (superficie primeiro), quebra e guarda; repete ate ter o necessario. */
    private void gatherTick(Zombie z, State s, long now) {
        if (s.resource != null && (!breakable(s, s.resource) || s.bad.contains(s.resource))) s.resource = null;
        if (s.resource == null) {
            if (now - s.lastScan < 1000) return;
            s.lastScan = now;
            s.resource = scanResource(z, s);
            s.resourceSince = now;
            if (s.resource == null) { // nada no raio: ataca com o que tem e tenta de novo daqui a pouco
                s.gathering = false;
                s.gatherCooldownUntil = now + Pi("cd_none", 15_000);
                s.action = "sem recurso no raio";
                return;
            }
        }
        Block r = s.resource;
        // alguem em cima desse bloco: quebrar derruba o colega -> procura outro
        if (occupied(r.getRelative(BlockFace.UP), z)) { s.bad.add(r); s.resource = null; s.action = "recurso com zumbi em cima, outro"; return; }
        Location c = r.getLocation().add(0.5, 0.5, 0.5);
        if (z.getEyeLocation().distance(c) <= 4.2) {
            z.lookAt(c);
            s.action = (s.hand ? "cavando na mao " : "quebrando ") + r.getType().name().toLowerCase();
            progressBreak(z, s, r);
            if (!breakable(s, r)) s.resource = null; // quebrou: proximo
            return;
        }
        if (now - s.resourceSince > Pi("res_giveup", 8000)) { s.bad.add(r); s.resource = null; s.action = "nao alcanca recurso, outro"; return; }
        z.getPathfinder().moveTo(r.getLocation().add(0.5, 1, 0.5), 1.15);
        s.action = "indo ate " + r.getType().name().toLowerCase();
    }

    /**
     * Recurso mais proximo e exposto que vira material util. Primeiro o do papel (pedra para o minerador,
     * tora para o lenhador, num raio maior); sem nada, cai para terra/areia cavada na mao.
     */
    private Block scanResource(Zombie z, State s) {
        boolean wasHand = s.hand;
        s.hand = false;
        Block b = s.role == LUMBER ? scan(z, s, Pi("scan_r_lumber", 28), -4, 6)
                : scan(z, s, s.role == MINER ? Pi("scan_r_miner", 16) : Pi("scan_r_digger", 12), -5, 4);
        if (b == null && s.role != DIGGER) {
            s.hand = true;
            b = scan(z, s, Pi("scan_r_hand", 12), -4, 4);
            if (b != null && s.role == LUMBER && !wasHand) s.need = Math.min(capacity(), Math.max(6, s.need));
        }
        if (b == null) s.hand = wasHand;
        return b;
    }

    /** Recurso a ate 2 blocos do que outro zumbi da horda ja esta indo pegar. */
    private boolean claimedNear(State me, Block b) {
        for (State o : horde.values()) {
            if (o == me || o.resource == null || o.resource.getWorld() != b.getWorld()) continue;
            Block r = o.resource;
            if (Math.abs(r.getX() - b.getX()) <= 2 && Math.abs(r.getZ() - b.getZ()) <= 2 && Math.abs(r.getY() - b.getY()) <= 2) return true;
        }
        return false;
    }

    private Block scan(Zombie z, State s, int r, int ylo, int yhi) {
        Block feet = z.getLocation().getBlock();
        Block best = null; double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dyb = ylo; dyb <= yhi; dyb++) {
            Block b = feet.getRelative(dx, dyb, dz);
            Material m = b.getType();
            if (m.isAir() || b.isPassable()) continue;
            if (s.role == LUMBER && !s.hand ? !Tag.LOGS.isTagged(m) : !breakable(s, b)) continue;
            if (s.bad.contains(b) || (dx == 0 && dz == 0 && dyb < 0)) continue; // nunca o chao embaixo dele
            if (claimedNear(s, b)) continue; // outro zumbi ja esta nesse ponto: espalha a coleta
            boolean exposed = false;
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (b.getRelative(f).isPassable()) { exposed = true; break; }
            }
            if (!exposed) continue;
            boolean useful = false;
            for (ItemStack d : drops(s, b)) if (buildable(d.getType()) || Tag.LOGS.isTagged(d.getType())) { useful = true; break; }
            if (!useful) continue; // ex.: minerio de carvao nao serve
            double score = Math.sqrt(dx * dx + dz * dz + dyb * dyb * 1.5) + m.getHardness() * 1.5;
            if (opt("tidy-gather") && s.role != LUMBER) {
                // escava organizado: continua no buraco que ja abriu (vizinho do ultimo bloco) e de cima para baixo
                Block lm = s.lastMined;
                if (lm != null && lm.getWorld() == b.getWorld()) {
                    int md = Math.abs(lm.getX() - b.getX()) + Math.abs(lm.getY() - b.getY()) + Math.abs(lm.getZ() - b.getZ());
                    if (md <= 2) score -= P("tidy_bonus", 6) - md;
                }
                score -= dyb * 0.4; // mais alto primeiro: tira a camada de cima antes de afundar
            }
            if (score < bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    /** Cavar escada: o que a ferramenta do papel quebra, ou terra/areia/cascalho na mao (nunca bloco da horda). */
    private static boolean canDig(State s, Block b) {
        if (ZPLACED.contains(b)) return false;
        if (canBreak(s, b)) return true;
        Material m = b.getType();
        return !b.isPassable() && !PROTECTED.contains(m) && Tag.MINEABLE_SHOVEL.isTagged(m) && m.getHardness() >= 0 && m.getHardness() <= 0.6f;
    }

    /** Quebravel pelo papel, ou na mao (terra, grama, areia, cascalho) quando em modo mao. */
    private static boolean breakable(State s, Block b) {
        if (canBreak(s, b)) return true;
        if (!s.hand || ZPLACED.contains(b)) return false;
        Material m = b.getType();
        return !b.isPassable() && !PROTECTED.contains(m) && Tag.MINEABLE_SHOVEL.isTagged(m) && m.getHardness() >= 0 && m.getHardness() <= 0.6f;
    }

    private static java.util.Collection<ItemStack> drops(State s, Block b) {
        ItemStack t = tool(s);
        return t == null || !canBreak(s, b) ? b.getDrops() : b.getDrops(t);
    }

    private void debug(Zombie z, State s, double dist, double dy) {
        if (!plugin.getConfig().getBoolean("horde.debug", false)) return;
        long now = now();
        if (now - s.lastDebug < 5000) return;
        s.lastDebug = now;
        String[] roles = {"minerador", "lenhador", "bruto", "tanque", "cavador"};
        plugin.getLogger().info(String.format("[horda] %s#%04d %s inv=%d/%d escadas=%d dist=%.0f dy=%+.0f | %s",
                roles[s.role], z.getEntityId() % 10000, s.gathering ? "COLETA" : "ATAQUE",
                material(s), s.gathering ? s.need : capacity(), s.ladders, dist, dy, s.action));
    }

    /** Sem caminho ou travado: pilar, quebrar o obstaculo, ponte ou pulo. */
    private void act(Zombie z, State s, Location zl, Location tl, double dist) {
        Vector dir = tl.toVector().subtract(zl.toVector());
        double dy = dir.getY();
        dir.setY(0);
        double horiz = dir.length();
        if (horiz < 0.01) dir = new Vector(1, 0, 0); else dir.normalize();
        Block feet = zl.getBlock();
        Block front = zl.clone().add(dir.clone().multiply(0.9)).getBlock();
        Block frontHead = front.getRelative(BlockFace.UP);
        long now = now();

        // 0) escada: qualquer zumbi da horda sobe (a do lenhador ou de jogador); o lenhador estende a escada
        if (dy >= 1 && climbLadder(z, s, zl, feet, dir)) return;
        if (s.role == LUMBER && dy >= 2 && horiz < P("ladder_max_horiz", 6) && buildLadder(z, s, feet, dir, dy)) return;

        // brutamontes/tanque nao mexem em blocos: so tentam destravar e seguem os outros
        if (tool(s) == null) {
            safeNudge(z, dir, 0.22);
            return;
        }

        // 1a) alvo acima mas longe na horizontal: escadaria de blocos na direcao dele (sobe 1 por bloco andado)
        boolean wall = !front.isPassable() || !frontHead.isPassable();
        if (dy >= 2 && horiz >= P("stair_min_horiz", 3) && !wall) {
            Block feetUp2 = feet.getRelative(BlockFace.UP, 2), frontUp2 = front.getRelative(BlockFace.UP, 2);
            if (feetUp2.isPassable() && frontUp2.isPassable()) {
                if (now - s.lastPlace < Pi("place_ms", 650)) return;
                if (s.invTotal == 0) {
                    if (z.isOnGround() && now > s.gatherCooldownUntil) startGather(s, needFor(s, dy), now);
                    else gather(z, s, feet, dir, true);
                    return;
                }
                Block below = front.getRelative(BlockFace.DOWN);
                if (replaceable(below)) { // buraco na frente: tapa antes do degrau
                    if (occupied(below, z)) { s.action = "lugar ocupado"; return; }
                    below.setType(takeBlock(s)); placeFx(below); s.lastPlace = now; s.action = "ponte"; return;
                }
                if (occupied(front, z)) { s.action = "lugar ocupado"; return; }
                front.setType(takeBlock(s));
                placeFx(front);
                Location step = front.getLocation().add(0.5, 1.05, 0.5);
                step.setYaw(zl.getYaw()); step.setPitch(zl.getPitch());
                z.teleport(step);
                s.lastPlace = now;
                s.action = "degrau (" + s.invTotal + " blocos)";
                return;
            }
        }
        // 1c) parede de terra/pedra na frente e alvo acima: cava uma escadaria na parede (nao gasta bloco)
        if (opt("dig-stairs") && dy >= 2 && wall && front.getType().isSolid() && !ZPLACED.contains(front)) {
            Block[] clear = {frontHead, front.getRelative(BlockFace.UP, 2), feet.getRelative(BlockFace.UP, 2)};
            boolean possible = true;
            for (Block c : clear) if (!c.isPassable() && !canDig(s, c)) { possible = false; break; }
            if (possible) {
                for (Block c : clear) {
                    if (!c.isPassable()) { progressBreak(z, s, c); s.action = "cavando escada na parede"; return; }
                }
                Location step = front.getLocation().add(0.5, 1.05, 0.5);
                step.setYaw(zl.getYaw()); step.setPitch(zl.getPitch());
                z.teleport(step); // sobe no degrau que ficou
                z.setVelocity(new Vector(0, 0, 0));
                s.action = "subiu degrau cavado";
                return;
            }
        }
        // 1b) alvo acima e perto (ou parede na frente): sobe em pilar.
        //    Prioridade sobre cavar para o lado: cavar a parede nao ajuda a subir.
        if (dy >= 2 && (horiz < 3 || wall)) {
            Block ceiling = feet.getRelative(BlockFace.UP, 2);
            if (!ceiling.isPassable()) { // teto/saliencia: quebra primeiro
                if (canBreak(s, ceiling)) progressBreak(z, s, ceiling);
                else if (opt("escape-ceiling") && escapeCeiling(z, s, feet, tl)) s.action = "saindo de baixo do teto";
                else safeNudge(z, dir, 0.3); // nao quebravel: tenta sair de baixo (so se tiver chao do lado)
                return;
            }
            if (now - s.lastPlace > Pi("place_ms", 650) && replaceable(feet) && feet.getRelative(BlockFace.DOWN).isSolid()) {
                // Junta material ANTES de subir: em cima do pilar so tem ar, nao da para reabastecer.
                if (s.invTotal == 0) { // acabou: volta a coletar (no chao) ou espera
                    if (z.isOnGround() && now > s.gatherCooldownUntil) startGather(s, needFor(s, dy), now);
                    else gather(z, s, feet, dir, true);
                    return;
                }
                if (occupied(feet, z)) { s.action = "lugar ocupado"; return; }
                s.action = "pilar (" + s.invTotal + " blocos)";
                Material m = takeBlock(s);
                z.teleport(zl.clone().add(0, 1.05, 0));
                feet.setType(m);
                placeFx(feet);
                s.lastPlace = now;
            }
            return;
        }
        // 2a) alvo bem abaixo e longe na horizontal: escadaria cavada para baixo (diagonal), em vez de poco
        if (opt("dig-stairs") && dy <= -2 && horiz >= 2) {
            Block down = front.getRelative(BlockFace.DOWN);
            Block[] clear = {frontHead, front, down};
            boolean possible = down.getRelative(BlockFace.DOWN).getType().isSolid() || down.getRelative(0, -2, 0).getType().isSolid();
            for (Block c : clear) if (possible && !c.isPassable() && !canDig(s, c)) possible = false;
            if (possible) {
                for (Block c : clear) {
                    if (!c.isPassable() && c != frontHead) { progressBreak(z, s, c); s.action = "cavando escada para baixo"; return; }
                }
                Location step = down.getLocation().add(0.5, 0.05, 0.5);
                step.setYaw(zl.getYaw()); step.setPitch(zl.getPitch());
                z.teleport(step);
                z.setVelocity(new Vector(0, 0, 0));
                s.action = "desceu degrau cavado";
                return;
            }
        }
        // 2) obstaculo na frente (cabeca primeiro) ou cavar para baixo se o alvo esta embaixo
        Block obstacle = null;
        if (dy <= -2 && horiz < 2) obstacle = feet.getRelative(BlockFace.DOWN);
        else if (!frontHead.isPassable()) obstacle = frontHead;
        else if (!front.isPassable()) obstacle = front;
        else if (dy >= 1 && !feet.getRelative(BlockFace.UP, 2).isPassable()) obstacle = feet.getRelative(BlockFace.UP, 2); // teto
        if (obstacle != null && canBreak(s, obstacle)) { progressBreak(z, s, obstacle); s.action = "abrindo caminho"; return; }

        // 3) buraco/agua na frente e alvo nao muito abaixo: ponte
        Block frontBelow = front.getRelative(BlockFace.DOWN);
        if (front.isPassable() && replaceable(frontBelow) && dy > -2 && now - s.lastPlace > 500) {
            if (s.invTotal == 0) { // sem blocos para a ponte: coleta
                if (now > s.gatherCooldownUntil) startGather(s, Math.min(capacity(), 8), now); else gather(z, s, feet, dir, true);
                return;
            }
            s.action = "ponte";
            if (occupied(frontBelow, z)) { s.action = "lugar ocupado"; return; }
            frontBelow.setType(takeBlock(s));
            placeFx(frontBelow);
            s.lastPlace = now;
            return;
        }
        // 4) empurrao + pulo para destravar
        safeNudge(z, dir, 0.22);
    }

    /** Teto que nao quebra: vai para a celula vizinha (raio 3) com a coluna livre ate a altura do alvo. */
    private boolean escapeCeiling(Zombie z, State s, Block feet, Location tl) {
        int top = tl.getBlockY() + 1;
        Block best = null; double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int dyb : new int[]{0, 1, -1, -2}) {
                Block b = feet.getRelative(dx, dyb, dz);
                if (!b.isPassable() || !b.getRelative(BlockFace.UP).isPassable() || !b.getRelative(BlockFace.DOWN).isSolid()) continue;
                boolean clear = true;
                for (int y = b.getY() + 2; y <= top && clear; y++) clear = b.getWorld().getBlockAt(b.getX(), y, b.getZ()).isPassable();
                if (clear) {
                    double d = Math.abs(dx) + Math.abs(dz) + 0.3 * Math.hypot(b.getX() - tl.getX(), b.getZ() - tl.getZ());
                    if (d < bd) { bd = d; best = b; }
                }
                break;
            }
        }
        if (best == null) return false;
        z.getPathfinder().moveTo(best.getLocation().add(0.5, 0, 0.5), 1.15);
        s.stuckTicks = 0;
        return true;
    }

    /**
     * Empurrao para destravar, mas como o pathfinder do vanilla: so se o bloco da frente tem chao
     * a no maximo 3 de queda (senao o zumbi se joga do pilar/rampa). Tambem nao pula se a cabeca bate.
     */
    private static void safeNudge(Zombie z, Vector dir, double speed) {
        if (!z.isOnGround()) return;
        Location l = z.getLocation();
        Block feet = l.getBlock();
        Block ahead = l.clone().add(dir.getX(), 0, dir.getZ()).getBlock();
        if (ahead.equals(feet)) ahead = feet.getRelative(Math.abs(dir.getX()) >= Math.abs(dir.getZ())
                ? (dir.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST) : (dir.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH));
        boolean ground = false;
        if (!ahead.isPassable()) ground = ahead.getRelative(BlockFace.UP).isPassable() && ahead.getRelative(0, 2, 0).isPassable(); // degrau de 1
        else for (int k = 1; k <= 3 && !ground; k++) ground = ahead.getRelative(0, -k, 0).getType().isSolid();
        if (!ground) return;
        z.setVelocity(dir.clone().setY(0).normalize().multiply(speed).setY(0.42));
    }

    private static boolean replaceable(Block b) {
        Material m = b.getType();
        return b.isPassable() || b.isLiquid() || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.SNOW;
    }

    static final int MINER = 0, LUMBER = 1, BRUTE = 2, TANK = 3, DIGGER = 4;

    /** Ferramenta do papel (define o que quebra e o drop). null = nao mexe em blocos. */
    private static ItemStack tool(State s) {
        return switch (s.role) {
            case MINER -> new ItemStack(Material.WOODEN_PICKAXE);
            case LUMBER -> new ItemStack(Material.WOODEN_AXE);
            case DIGGER -> new ItemStack(Material.WOODEN_SHOVEL);
            default -> null;
        };
    }

    /**
     * Regras de ferramenta como no vanilla: minerador so quebra o que picareta de madeira minera
     * (pedra sim; ferro/diamante/obsidiana nao); lenhador so blocos de madeira (tag de machado).
     */
    /** Blocos que a horda colocou (rampa, pilar, degrau, ponte, escada): ninguem da horda desmonta. */
    static final java.util.Set<Block> ZPLACED = new java.util.HashSet<>();

    /**
     * Blocos NATURAIS de caverna. No subsolo o minerador so cava estes: abre tunel ate o jogador,
     * mas uma base feita com blocos de construcao (pedregulho, tijolo, madeira, vidro, pedra lapidada)
     * continua segura. Minerios ficam de fora.
     */
    static final Set<Material> CAVE_BLOCKS = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE, Material.SMOOTH_BASALT,
            Material.DIRT, Material.GRAVEL, Material.CLAY, Material.MOSS_BLOCK, Material.ROOTED_DIRT, Material.SCULK,
            Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT);

    /** No subsolo: sem ceu em cima e abaixo do terreno. */
    static boolean underground(Location l) {
        Block b = l.getBlock();
        return b.getLightFromSky() == 0 && l.getBlockY() < l.getWorld().getHighestBlockYAt(l) - 4;
    }

    private static boolean canBreak(State s, Block b) {
        Material m = b.getType();
        if (b.isPassable() || PROTECTED.contains(m) || Tag.BEDS.isTagged(m) || m.getHardness() < 0) return false;
        if (ZPLACED.contains(b)) return false; // nao desmonta a propria estrutura (derrubava quem estava em cima)
        // CAVERNA: so blocos naturais (a base do jogador no subsolo fica segura)
        if (s.cave) return CAVE_BLOCKS.contains(m);
        if (Tag.LEAVES.isTagged(m)) return true; // folha sai na mao (vanilla), qualquer papel: travava rampa e guerreiros
        return switch (s.role) {
            case MINER -> Tag.MINEABLE_PICKAXE.isTagged(m) && !Tag.NEEDS_STONE_TOOL.isTagged(m)
                    && !Tag.NEEDS_IRON_TOOL.isTagged(m) && !Tag.NEEDS_DIAMOND_TOOL.isTagged(m);
            case LUMBER -> Tag.MINEABLE_AXE.isTagged(m);
            case DIGGER -> Tag.MINEABLE_SHOVEL.isTagged(m); // terra, grama, areia, cascalho, argila, neve
            default -> false;
        };
    }

    private void progressBreak(Zombie z, State s, Block b) {
        long now = now();
        if (!b.equals(s.breaking)) { cancelBreak(z, s); s.breaking = b; s.breakStart = now; }
        float h = Math.max(0.2f, b.getType().getHardness());
        long duration = (long) Math.max(800, Math.min(12_000, 700 + h * 1500));
        if (!canBreak(s, b)) duration *= 2; // na mao: mais lento
        float progress = Math.min(1f, (now - s.breakStart) / (float) duration);
        z.swingMainHand();
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) p.sendBlockDamage(b.getLocation(), progress, z.getEntityId());
        }
        if (s.tick % 2 == 0) b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.7f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.3f);
        if (progress >= 1f) {
            b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 1f);
            cancelBreak(z, s);
            boolean log = Tag.LOGS.isTagged(b.getType());
            collect(s, b);
            s.lastMined = b;
            if (log && s.role == LUMBER && opt("fell-whole-tree")) fellTree(s, b);
            s.stuckTicks = 0;
        }
    }

    /**
     * Lenhador: derruba a arvore inteira a partir do tronco quebrado (troncos ligados, ate 64).
     * Nao deixa toco flutuando nem tronco furado; as folhas caem sozinhas (vanilla). O que nao cabe no
     * inventario cai no chao. Nao mexe em madeira que nao seja tronco (casas de tabua ficam intactas).
     */
    private void fellTree(State s, Block start) {
        java.util.ArrayDeque<Block> q = new java.util.ArrayDeque<>();
        java.util.Set<Block> seen = new java.util.HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            Block n = start.getRelative(dx, dy, dz);
            if (Tag.LOGS.isTagged(n.getType()) && seen.add(n)) q.add(n);
        }
        int felled = 0;
        while (!q.isEmpty() && felled < 64) {
            Block b = q.poll();
            if (!Tag.LOGS.isTagged(b.getType()) || ZPLACED.contains(b)) continue;
            collect(s, b);
            felled++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (Tag.LOGS.isTagged(n.getType()) && seen.add(n)) q.add(n);
            }
        }
        if (felled > 0) start.getWorld().playSound(start.getLocation(), Sound.BLOCK_WOOD_BREAK, 1f, 0.7f);
    }

    /** Quebra o bloco e guarda o drop no inventario (como se fosse com picareta); o que nao cabe cai no chao. */
    private void collect(State s, Block b) {
        java.util.Collection<ItemStack> drops = drops(s, b);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().spawnParticle(Particle.BLOCK, c, 25, 0.3, 0.3, 0.3, b.getBlockData());
        if (REC != null) REC.block(b, Material.AIR);
        ZPLACED.remove(b);
        b.setType(Material.AIR);
        for (ItemStack d : drops) {
            int left = 0;
            for (int i = 0; i < d.getAmount(); i++) if (!addBlock(s, d.getType())) left++;
            if (left > 0) b.getWorld().dropItemNaturally(c, new ItemStack(d.getType(), left));
        }
    }

    /**
     * Sem blocos para construir: quebra um bloco do lado (o mais facil que de material de construcao).
     * Nunca o bloco em que esta pisando.
     */
    /**
     * Junta material: quebra o bloco util mais facil num raio de 2 (nunca a coluna em que esta pisando).
     * Retorna true se achou algo para quebrar. nudge=true: se nao achou nada, da um pulo para destravar.
     */
    private boolean gather(Zombie z, State s, Block feet, Vector dir, boolean nudge) {
        if (s.invTotal >= capacity()) return false;
        if (s.breaking != null && breakable(s, s.breaking)) { progressBreak(z, s, s.breaking); return true; } // termina o que comecou
        Block best = null; double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dyb = -1; dyb <= 1; dyb++) {
            if (dx == 0 && dz == 0) continue; // a propria coluna (chao/teto do pilar)
            Block b = feet.getRelative(dx, dyb, dz);
            if (!breakable(s, b)) continue;
            boolean useful = false;
            for (ItemStack d : drops(s, b)) if (buildable(d.getType())) { useful = true; break; }
            if (!useful) continue;
            double score = b.getType().getHardness() + Math.abs(dx) + Math.abs(dz) * 0.5 + (dyb == 1 ? 0.5 : 0);
            if (score < bestScore) { bestScore = score; best = b; }
        }
        if (best != null) { progressBreak(z, s, best); return true; }
        if (nudge) safeNudge(z, dir, 0.25); // nada util em volta: anda um pouco (nunca para o vazio)
        return false;
    }

    // ---------------------------------------------------------------- escadas (lenhador crafta, todos sobem)

    private static BlockFace toFace(Vector dir) {
        return Math.abs(dir.getX()) > Math.abs(dir.getZ()) ? (dir.getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (dir.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    }

    private static Material planksOf(Material log) {
        String n = log.name().replace("STRIPPED_", "").replace("_LOG", "").replace("_WOOD", "")
                .replace("_STEM", "").replace("_HYPHAE", "");
        Material m = Material.matchMaterial(n + "_PLANKS");
        return m != null ? m : Material.OAK_PLANKS;
    }

    /** Vanilla: nao da para colocar bloco solido onde tem mob/jogador (fora o proprio zumbi que esta pulando). */
    private static boolean occupied(Block b, Entity self) {
        return !b.getWorld().getNearbyEntities(org.bukkit.util.BoundingBox.of(b),
                e -> e != self && e instanceof org.bukkit.entity.LivingEntity le && !le.isDead()
                        && !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR)).isEmpty();
    }

    private static boolean isWood(Material m) { return Tag.PLANKS.isTagged(m) || Tag.LOGS.isTagged(m); }

    /** Crafta escadas "na mao" com a madeira do inventario: ~4 tabuas (ou 1 tora) = 3 escadas. */
    /** Madeira disponivel em "unidades de tabua" (tora = 4 tabuas). */
    private static int woodUnits(State s) {
        int units = 0;
        for (var e : s.inv.entrySet()) if (isWood(e.getKey())) units += e.getValue() * (Tag.LOGS.isTagged(e.getKey()) ? 4 : 1);
        return units;
    }

    /** Escadas: receita 3x3 (7 gravetos = 3 escadas, ~4 tabuas). So funciona perto de uma bancada. */
    private boolean craftLadders(State s) {
        if (woodUnits(s) < 4) return false;
        consumeWood(s, 4);
        s.ladders += 3;
        return true;
    }

    private static void consumeWood(State s, int units) {
        int toUse = units;
        for (var it = s.inv.entrySet().iterator(); it.hasNext() && toUse > 0; ) {
            var e = it.next();
            if (!isWood(e.getKey())) continue;
            int per = Tag.LOGS.isTagged(e.getKey()) ? 4 : 1;
            while (toUse > 0 && e.getValue() > 0) {
                e.setValue(e.getValue() - 1);
                s.invTotal--;
                toUse -= per;
            }
            if (e.getValue() == 0) it.remove();
        }
    }

    /**
     * Prepara as escadas no chao, como um jogador: crafta a bancada (2x2, 4 tabuas) se nao tiver, coloca do lado,
     * "usa" por 1,5s, crafta todas as escadas que a subida precisa e guarda a bancada de volta.
     * Retorna true enquanto estiver ocupado com isso.
     */
    private boolean prepareLadders(Zombie z, State s, Block feet, BlockFace towardWall, int need) {
        long now = now();
        if (s.craftUntil > 0) {
            if (now < s.craftUntil) { z.swingMainHand(); return true; } // usando a bancada
            while (s.ladders < need && craftLadders(s)) { }                 // termina: crafta o lote
            s.craftUntil = 0;
            if (s.table != null && s.table.getType() == Material.CRAFTING_TABLE) { // guarda a bancada
                s.table.getWorld().spawnParticle(Particle.BLOCK, s.table.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, s.table.getBlockData());
                s.table.setType(Material.AIR);
                s.hasTable = true;
            }
            s.table = null;
            return false;
        }
        int cost = 4 + (s.hasTable ? 0 : 4); // um lote de escadas (+ a bancada se ainda nao tiver)
        if (woodUnits(s) < cost) {
            if (now > s.gatherCooldownUntil) { startGather(s, Math.max(needFor(s, need - 1), cost), now); return true; }
            return false; // sem arvore por perto: deixa o pilar/outros agirem
        }
        s.action = "bancada: crafta escadas";
        Block spot = null;
        for (BlockFace f : new BlockFace[]{towardWall.getOppositeFace(), BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block b = feet.getRelative(f);
            if (f != towardWall && replaceable(b) && b.getRelative(BlockFace.DOWN).isSolid()) { spot = b; break; }
        }
        if (spot == null) return false; // sem lugar para a bancada: segue sem escada
        if (!s.hasTable) consumeWood(s, 4); // crafta a bancada na mao (receita 2x2)
        s.hasTable = false;
        spot.setType(Material.CRAFTING_TABLE);
        spot.getWorld().playSound(spot.getLocation(), Sound.BLOCK_WOOD_PLACE, 1f, 1f);
        s.table = spot;
        s.craftUntil = now + 1500;
        z.lookAt(spot.getLocation().add(0.5, 0.5, 0.5));
        return true;
    }

    private static Vector toVec(BlockFace f) { return new Vector(f.getModX(), 0, f.getModZ()); }

    /** Lenhador sem madeira por perto: anda ate a tora mais proxima (raio 12) e corta. Busca cacheada. */
    private boolean goToTree(Zombie z, State s, Block feet) {
        if (s.tree != null && !Tag.LOGS.isTagged(s.tree.getType())) s.tree = null; // ja cortada
        if (s.tree == null && now() - s.lastTreeScan > 1500) {
            s.lastTreeScan = now();
            double bd = Double.MAX_VALUE;
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dyb = -3; dyb <= 6; dyb++) {
                Block b = feet.getRelative(dx, dyb, dz);
                if (!Tag.LOGS.isTagged(b.getType())) continue;
                double d = dx * dx + dz * dz + dyb * dyb * 2;
                if (d < bd) { bd = d; s.tree = b; }
            }
        }
        if (s.tree == null) return false;
        Location tl = s.tree.getLocation().add(0.5, 0, 0.5);
        if (z.getLocation().distanceSquared(tl) <= 3.2 * 3.2) { progressBreak(z, s, s.tree); return true; }
        z.getPathfinder().moveTo(tl, 1.1);
        return true;
    }

    private static boolean placeLadder(Block at, BlockFace towardWall) {
        Block wall = at.getRelative(towardWall);
        if (!wall.getType().isOccluding() || !(at.isPassable() || at.isLiquid()) || at.getType() == Material.LADDER) return false;
        at.setType(Material.LADDER, false);
        if (at.getBlockData() instanceof org.bukkit.block.data.type.Ladder l) {
            l.setFacing(towardWall.getOppositeFace()); // escada olha para longe da parede
            at.setBlockData(l, false);
        }
        placeFx(at);
        return true;
    }

    /** Lenhador com parede na direcao do alvo: crafta (se preciso) e prega a primeira escada. */
    private boolean buildLadder(Zombie z, State s, Block feet, Vector dir, double dy) {
        BlockFace face = toFace(dir);
        if (!feet.getRelative(face).getType().isOccluding()) return false; // sem parede: usa pilar
        int need = (int) Math.ceil(dy) + 1;
        if (s.ladders < need && prepareLadders(z, s, feet, face, need)) return true; // na bancada / juntando madeira
        if (s.ladders == 0) return false;                                  // sem madeira nem escada: usa pilar
        if (!placeLadder(feet, face)) return false;
        s.ladders--;
        s.lastPlace = now();
        return true;
    }

    /**
     * Dentro de uma escada: sobe 1 bloco por vez (o lenhador vai pregando a proxima se a parede continua).
     * Escada por perto: anda ate ela. No topo: passa para cima da parede.
     */
    private boolean climbLadder(Zombie z, State s, Location zl, Block feet, Vector dir) {
        if (feet.getType() == Material.LADDER) {
            BlockFace towardWall = feet.getBlockData() instanceof org.bukkit.block.data.type.Ladder l ? l.getFacing().getOppositeFace() : toFace(dir);
            Block above = feet.getRelative(BlockFace.UP);
            if (above.getType() != Material.LADDER && s.role == LUMBER && above.getRelative(towardWall).getType().isOccluding()) {
                if (s.ladders > 0 && placeLadder(above, towardWall)) s.ladders--;
            }
            if (above.getType() == Material.LADDER || (above.isPassable() && above.getRelative(BlockFace.UP).isPassable()
                    && !above.getRelative(towardWall).getType().isOccluding())) {
                if (above.getType() == Material.LADDER && above.getRelative(BlockFace.UP).isPassable()) {
                    z.teleport(above.getLocation().add(0.5, 0.05, 0.5).setDirection(dir));
                    return true;
                }
                // fim da escada: sobe para cima da parede
                Block top = feet.getRelative(towardWall).getRelative(BlockFace.UP);
                if (top.isPassable() && top.getRelative(BlockFace.UP).isPassable()) {
                    z.teleport(top.getLocation().add(0.5, 0.05, 0.5).setDirection(dir));
                    return true;
                }
            }
            return above.getType() == Material.LADDER; // escada continua mas bloqueada em cima: deixa o resto agir
        }
        // procura uma escada por perto (raio 3, altura do pe +-1) para usar
        Block best = null; double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dyb = -1; dyb <= 1; dyb++) {
            Block b = feet.getRelative(dx, dyb, dz);
            if (b.getType() != Material.LADDER || b.getRelative(BlockFace.UP).getType() != Material.LADDER) continue;
            double d = dx * dx + dz * dz + dyb * dyb;
            if (d < bd) { bd = d; best = b; }
        }
        if (best == null) return false;
        if (bd <= 2.5) z.teleport(best.getLocation().add(0.5, 0.05, 0.5).setDirection(dir)); // encosta e entra na escada
        else z.getPathfinder().moveTo(best.getLocation().add(0.5, 0, 0.5), 1.15);
        return true;
    }

    /** Horda nao pega fogo com o sol (setShouldBurnInDay sozinho nao segurou no 26.3). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent e) {
        if (e instanceof org.bukkit.event.entity.EntityCombustByEntityEvent || e instanceof org.bukkit.event.entity.EntityCombustByBlockEvent) return;
        if (isHorde(e.getEntity()) || e.getEntity().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    /** Zumbi da horda morreu: solta os blocos que carregava. */
    @org.bukkit.event.EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        State s = horde.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        for (var en : s.inv.entrySet()) {
            int n = en.getValue();
            while (n > 0) { int k = Math.min(64, n); e.getDrops().add(new ItemStack(en.getKey(), k)); n -= k; }
        }
        if (s.ladders > 0) e.getDrops().add(new ItemStack(Material.LADDER, Math.min(64, s.ladders)));
        if (s.hasTable) e.getDrops().add(new ItemStack(Material.CRAFTING_TABLE));
    }

    private void cancelBreak(Zombie z, State s) {
        if (s.breaking == null) return;
        Block b = s.breaking;
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 48 * 48) p.sendBlockDamage(b.getLocation(), 0f, z.getEntityId());
        }
        s.breaking = null;
    }

    private static void stopRecording() {
        if (REC != null) { REC.close(); REC = null; }
    }

    private static void placeFx(Block b) {
        if (ZPLACED.size() > 20000) ZPLACED.clear();
        ZPLACED.add(b);
        if (REC != null) REC.block(b, b.getType());
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1f, 0.9f);
    }

    String describe() {
        int[] roles = new int[5];
        int blocks = 0;
        for (State s : horde.values()) { roles[s.role]++; blocks += s.invTotal; }
        return alive() + " zumbis (mineradores " + roles[MINER] + ", cavadores " + roles[DIGGER] + ", lenhadores " + roles[LUMBER] + ", brutos " + roles[BRUTE] + ", tanques " + roles[TANK]
                + ") carregando " + blocks + " blocos (limite " + capacity() + " cada)";
    }

    // ---------------------------------------------------------------- cerco

    /** Ponto num anel de 4 blocos em volta do jogador, no angulo deste zumbi (fecha as rotas de fuga). */
    private Location flankGoal(Zombie z, LivingEntity t) {
        List<UUID> mates = new ArrayList<>();
        for (var en : horde.entrySet()) if (t.getUniqueId().equals(en.getValue().target)) mates.add(en.getKey());
        mates.sort(null);
        int k = Math.max(0, mates.indexOf(z.getUniqueId()));
        double ang = 2 * Math.PI * k / Math.max(1, mates.size());
        Location l = t.getLocation();
        int x = (int) Math.round(l.getX() + Math.cos(ang) * P("surround_r", 4)), zz = (int) Math.round(l.getZ() + Math.sin(ang) * P("surround_r", 4));
        Block top = l.getWorld().getHighestBlockAt(x, zz);
        if (Math.abs(top.getY() + 1 - l.getY()) > 3) return null; // anel cai em buraco/morro: vai direto
        return top.getLocation().add(0.5, 1, 0.5);
    }

    // ---------------------------------------------------------------- rampa da horda

    /**
     * Rampa da horda = lista de degraus de baixo (entrada) para cima (colado no jogador).
     * Nova: escadaria descendo do jogador numa direcao cardinal ate o chao.
     * Extensao: parte do topo de uma rampa ja construida e vai ate a nova posicao do jogador (reaproveita).
     */
    private static final class Ramp {
        final Block player; final List<Block> steps; final Block entry; final Block waitCenter; final String desc;
        boolean broken;
        /** Conserto em andamento: 1 = curva desobstruida/apoio colocado no degrau fixIndex; 2 = desmontou dali pra cima. */
        int fixStage, fixIndex = -1;
        UUID worker; // 1 construtor por vez na rampa (1 de largura: mais de um empurra e cai)
        UUID carrier; long carrierSince; // quem esta levando blocos ate o construtor
        long workerEmptySince;
        final java.util.Set<Block> lanes; // faixa lateral e recuos (onde quem esta sem bloco espera fora do caminho)
        Ramp(Block player, List<Block> steps, Block entry, Block waitCenter, String desc) {
            this.player = player; this.steps = steps; this.entry = entry; this.waitCenter = waitCenter; this.desc = desc;
            this.lanes = new java.util.HashSet<>(LANES);
            LANES.clear();
        }
        int next() { for (int i = 0; i < steps.size(); i++) if (!steps.get(i).getType().isSolid()) return i; return -1; }
        Block stand(int i) { return i == 0 ? entry : steps.get(i - 1).getRelative(BlockFace.UP); }
        int remaining() { int n = 0; for (Block b : steps) if (!b.getType().isSolid()) n++; return n; }
        /** Degrau mais alto ja construido em sequencia a partir da entrada (null = nada feito). */
        Block top() {
            Block t = null;
            for (Block b : steps) { if (!b.getType().isSolid()) break; t = b; }
            return t;
        }

        static int cost(List<Block> steps) {
            int c = 0;
            for (Block st : steps) {
                if (!st.getType().isSolid()) c++;
                for (int hy = 1; hy <= 2; hy++) if (!st.getRelative(0, hy, 0).isPassable()) c += 3;
            }
            return c;
        }

        /** Nova: melhor direcao cardinal (menos blocos e mais perto da horda). */
        /** Faixa extra ao lado do degrau (perpendicular a direcao) onde o degrau flutua: largura 2 no vao. */
        /** Faixas/recuos da ultima geracao de rampa (thread principal; o construtor do Ramp copia e limpa). */
        static final java.util.Set<Block> LANES = new java.util.HashSet<>();

        static void addLanes(List<Block> steps, List<BlockFace> dirs, boolean wide) {
            LANES.clear();
            if (!wide) return;
            for (int i = steps.size() - 1; i >= 0; i--) {
                Block st = steps.get(i);
                boolean floating = st.getRelative(BlockFace.DOWN).isPassable() && st.getRelative(0, -2, 0).isPassable();
                // flutuando: faixa dupla; com chao: recuo lateral a cada 5 degraus (um sobe, o outro espera do lado)
                if (!floating && i % 5 != 2) continue;
                BlockFace f = dirs.get(i);
                Block lane = st.getRelative(-f.getModZ(), 0, f.getModX());
                steps.add(i + 1, lane);
                dirs.add(i + 1, f);
                LANES.add(lane);
            }
        }

        /**
         * Gera uma rampa de cima (colada no jogador) para baixo seguindo um caminho de colunas, descendo 1 por coluna
         * (patamar quando o caminho manda ficar na mesma altura) ate encontrar o chao no nivel da horda.
         * cols: colunas (x,z) a partir da vizinha do jogador; flat[i] = patamar (nao desce nessa coluna).
         */
        static Ramp alongPath(Block pb, List<int[]> cols, List<Boolean> flat, List<BlockFace> dirs, int hordeY, boolean wide, String name) {
            World w = pb.getWorld();
            List<Block> top2down = new ArrayList<>();
            List<BlockFace> d2 = new ArrayList<>();
            int y = pb.getY();
            for (int i = 0; i < cols.size(); i++) {
                int[] c = cols.get(i);
                if (!flat.get(i)) y--;
                if (groundY(w, c[0], c[1]) >= y && y <= hordeY + 1) {
                    if (top2down.isEmpty()) return null;
                    List<Block> steps = new ArrayList<>(top2down);
                    List<BlockFace> dirs2 = new ArrayList<>(d2);
                    java.util.Collections.reverse(steps);
                    java.util.Collections.reverse(dirs2);
                    addLanes(steps, dirs2, wide);
                    Block entry = w.getBlockAt(c[0], groundY(w, c[0], c[1]) + 1, c[1]);
                    Block wc = w.getBlockAt(c[0] + (c[0] - pb.getX() == 0 ? 0 : Integer.signum(c[0] - pb.getX()) * 3), entry.getY(),
                            c[1] + (c[1] - pb.getZ() == 0 ? 0 : Integer.signum(c[1] - pb.getZ()) * 3));
                    return new Ramp(pb, steps, entry, wc, name + " x" + steps.size());
                }
                top2down.add(w.getBlockAt(c[0], y, c[1]));
                d2.add(dirs.get(i));
            }
            return null;
        }

        /** Zigue-zague: trechos de seg colunas, patamar de 2 para o lado e volta na direcao contraria. */
        static Ramp zigzag(Block pb, BlockFace f, boolean leftTurn, int seg, int hordeY, boolean wide) {
            List<int[]> cols = new ArrayList<>();
            List<Boolean> flat = new ArrayList<>();
            List<BlockFace> dirs = new ArrayList<>();
            int x = pb.getX(), z = pb.getZ();
            int dx = f.getModX(), dz = f.getModZ();
            int px = leftTurn ? -dz : dz, pz = leftTurn ? dx : -dx; // lado da curva
            for (int k = 0; k < 90; k++) {
                if (k > 0 && k % (seg + 2) >= seg) { // patamar: 2 colunas para o lado, mesma altura
                    x += px; z += pz;
                    cols.add(new int[]{x, z}); flat.add(true); dirs.add(f);
                    if (k % (seg + 2) == seg + 1) { dx = -dx; dz = -dz; }
                    continue;
                }
                x += dx; z += dz;
                cols.add(new int[]{x, z}); flat.add(false);
                dirs.add(dx > 0 ? BlockFace.EAST : dx < 0 ? BlockFace.WEST : dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            }
            return alongPath(pb, cols, flat, dirs, hordeY, wide, "zigzag " + f);
        }

        /** Espiral em volta do jogador a 2 blocos (1o degrau colado nele): compacta, com cantos de espera. */
        static Ramp spiral(Block pb, boolean clockwise, int hordeY, boolean wide) {
            List<int[]> ring = new ArrayList<>();
            for (int i = -2; i <= 2; i++) ring.add(new int[]{i, -2});
            for (int i = -1; i <= 2; i++) ring.add(new int[]{2, i});
            for (int i = 1; i >= -2; i--) ring.add(new int[]{i, 2});
            for (int i = 1; i >= -1; i--) ring.add(new int[]{-2, i});
            if (!clockwise) java.util.Collections.reverse(ring);
            List<int[]> cols = new ArrayList<>();
            List<Boolean> flat = new ArrayList<>();
            List<BlockFace> dirs = new ArrayList<>();
            cols.add(new int[]{pb.getX(), pb.getZ() - 1}); flat.add(false); dirs.add(BlockFace.NORTH); // colado no jogador
            int[] first = ring.get(0);
            cols.add(new int[]{pb.getX() + first[0], pb.getZ() + first[1]}); flat.add(true); dirs.add(BlockFace.NORTH);
            for (int lap = 0; lap < 6; lap++) for (int i = 1; i < ring.size(); i++) {
                int[] a = ring.get(i - 1), b = ring.get(i);
                cols.add(new int[]{pb.getX() + b[0], pb.getZ() + b[1]}); flat.add(false);
                dirs.add(b[0] > a[0] ? BlockFace.EAST : b[0] < a[0] ? BlockFace.WEST : b[1] > a[1] ? BlockFace.SOUTH : BlockFace.NORTH);
            }
            return alongPath(pb, cols, flat, dirs, hordeY, false, "espiral"); // espiral: 1 de largura (voltas ficam coladas)
        }

        static Ramp fresh(Block pb, double cx, double cz, int hordeY, boolean wide) {
            Ramp best = null; double bs = Double.MAX_VALUE;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                int length = -1;
                for (int d = 1; d < 60; d++) {
                    Block st = pb.getRelative(f.getModX() * d, -d, f.getModZ() * d);
                    // termina no chao E no nivel da horda (rampa que acaba num plato alto nao serve)
                    if (groundY(pb.getWorld(), st.getX(), st.getZ()) >= st.getY() && st.getY() <= hordeY + 1) { length = d; break; }
                }
                if (length < 2) continue; // chao ja colado no jogador nessa direcao: rampa nao ajuda
                List<Block> steps = new ArrayList<>();
                List<BlockFace> dirs = new ArrayList<>();
                for (int d = length - 1; d >= 1; d--) { steps.add(pb.getRelative(f.getModX() * d, -d, f.getModZ() * d)); dirs.add(f); }
                addLanes(steps, dirs, wide);
                Block e = pb.getRelative(f.getModX() * length, 0, f.getModZ() * length);
                Block entry = pb.getWorld().getBlockAt(e.getX(), groundY(pb.getWorld(), e.getX(), e.getZ()) + 1, e.getZ());
                Block wc = pb.getRelative(f.getModX() * (length + 3), 0, f.getModZ() * (length + 3));
                double sc = cost(steps) + 0.15 * Math.hypot(e.getX() - cx, e.getZ() - cz);
                if (sc < bs) { bs = sc; best = new Ramp(pb, steps, entry, wc, "nova " + f + " x" + steps.size()); }
            }
            return best;
        }

        /** Extensao: do topo de uma rampa antiga ate o jogador, 1 bloco de subida/descida por passo. */
        static Ramp extend(Block pb, Ramp old, boolean wide) {
            Block top = old.top();
            if (top == null || top.getWorld() != pb.getWorld()) return null;
            if (Math.abs(top.getX() - pb.getX()) > 14 || Math.abs(top.getZ() - pb.getZ()) > 14) return null;
            int yT = pb.getY() - 1;
            List<Block> steps = new ArrayList<>();
            List<BlockFace> dirs = new ArrayList<>();
            Block cur = top;
            for (int i = 0; i < 30; i++) {
                int dx = pb.getX() - cur.getX(), dz = pb.getZ() - cur.getZ();
                boolean adjacent = Math.abs(dx) + Math.abs(dz) == 1;
                if (adjacent && cur.getY() == yT) {
                    addLanes(steps, dirs, wide);
                    return new Ramp(pb, steps, top.getRelative(BlockFace.UP), old.waitCenter, "extensao x" + steps.size());
                }
                int nx = cur.getX(), nz = cur.getZ();
                if (adjacent) { // colado mas na altura errada: contorna pelo lado
                    if (dx != 0) nz += 1; else nx += 1;
                } else if (Math.abs(dx) >= Math.abs(dz)) nx += Integer.signum(dx);
                else nz += Integer.signum(dz);
                int ny = cur.getY() + Integer.signum(yT - cur.getY());
                if (nx == pb.getX() && nz == pb.getZ()) return null;
                BlockFace mv = nx != cur.getX() ? (nx > cur.getX() ? BlockFace.EAST : BlockFace.WEST)
                        : (nz > cur.getZ() ? BlockFace.SOUTH : BlockFace.NORTH);
                cur = pb.getWorld().getBlockAt(nx, ny, nz);
                steps.add(cur);
                dirs.add(mv);
            }
            return null;
        }
    }

    /** Chao de verdade da coluna: ignora folhas e troncos (getHighestBlockYAt conta copa de arvore). */
    private static int groundY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > w.getMinHeight()) {
            Material m = w.getBlockAt(x, y, z).getType();
            // chao = solido (nao arvore) com solido embaixo; bloco flutuante (degrau de rampa velha, ponte) nao conta
            if (!Tag.LOGS.isTagged(m) && !Tag.LEAVES.isTagged(m) && m.isSolid() && w.getBlockAt(x, y - 1, z).getType().isSolid()) break;
            y--;
        }
        return y;
    }

    private final Map<UUID, List<Ramp>> rampHistory = new HashMap<>();

    /** Recuo/faixa lateral mais perto (ate 5 blocos, mesma altura +-1, livre em cima e sem ninguem). */
    private static Block nearestBay(Ramp r, Zombie z) {
        Location zl = z.getLocation();
        Block best = null; double bd = 25;
        for (Block b : r.lanes) {
            if (b.getWorld() != zl.getWorld() || !b.getType().isSolid()) continue;
            if (Math.abs(b.getY() + 1 - zl.getBlockY()) > 1) continue;
            if (!b.getRelative(BlockFace.UP).isPassable() || !b.getRelative(0, 2, 0).isPassable()) continue;
            if (occupied(b.getRelative(BlockFace.UP), z)) continue;
            double d = b.getLocation().add(0.5, 1, 0.5).distanceSquared(zl);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private Ramp rampFor(LivingEntity t) {
        Ramp r = ramps.get(t.getUniqueId());
        Block pb = t.getLocation().getBlock();
        // mantem o plano enquanto o jogador ficar perto do ponto planejado (andar 1 bloco nao muda a estrategia)
        if (r != null && !r.broken && r.player.getWorld() == pb.getWorld()
                && Math.abs(r.player.getX() - pb.getX()) <= 3 && Math.abs(r.player.getZ() - pb.getZ()) <= 3
                && Math.abs(r.player.getY() - pb.getY()) <= 2) return r;
        List<Ramp> hist = rampHistory.computeIfAbsent(t.getUniqueId(), k -> new ArrayList<>());
        if (r != null && !hist.contains(r)) { hist.add(r); if (hist.size() > 4) hist.remove(0); }
        double cx = pb.getX(), cz = pb.getZ();
        List<Integer> ys = new ArrayList<>();
        int n = 0;
        for (var en : horde.entrySet()) {
            if (!t.getUniqueId().equals(en.getValue().target)) continue;
            Entity e = Bukkit.getEntity(en.getKey());
            if (e == null) continue;
            if (n == 0) { cx = 0; cz = 0; }
            cx += e.getLocation().getX(); cz += e.getLocation().getZ(); n++;
            ys.add(e.getLocation().getBlockY());
        }
        if (n > 0) { cx /= n; cz /= n; }
        ys.sort(null);
        int hordeY = ys.isEmpty() ? pb.getY() - 3 : ys.get(ys.size() / 2); // mediana: onde a maioria esta
        boolean wide = plugin.getConfig().getBoolean("horde.ramp-wide-floating", true);
        String shapes = plugin.getConfig().getString("horde.ramp-shapes", "straight,zigzag,spiral");
        Ramp best = shapes.contains("straight") ? Ramp.fresh(pb, cx, cz, hordeY, wide) : null;
        int bestCost = best == null ? Integer.MAX_VALUE : Ramp.cost(best.steps);
        List<Ramp> alts = new ArrayList<>();
        if (shapes.contains("zigzag")) for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            for (boolean left : new boolean[]{true, false}) alts.add(Ramp.zigzag(pb, f, left, 6, hordeY, wide));
        if (shapes.contains("spiral")) { alts.add(Ramp.spiral(pb, true, hordeY, wide)); alts.add(Ramp.spiral(pb, false, hordeY, wide)); }
        for (Ramp a : alts) {
            if (a == null) continue;
            int c = Ramp.cost(a.steps) + a.steps.size() / 4; // leve preferencia pela reta (mais simples de subir)
            if (c < bestCost) { best = a; bestCost = c; }
        }
        for (Ramp old : hist) { // reaproveita o que ja foi construido se sair mais barato
            Ramp ext = Ramp.extend(pb, old, wide);
            if (ext == null) continue;
            int c = Ramp.cost(ext.steps);
            if (c <= bestCost) { best = ext; bestCost = c; }
        }
        if (best == null) ramps.remove(t.getUniqueId()); else ramps.put(t.getUniqueId(), best);
        return best;
    }

    private final Map<UUID, Long> rampLog = new HashMap<>();

    /** Debug: por que a rampa nao rolou para este zumbi (1 linha a cada 5 s por zumbi). */
    private boolean rampWhy(Zombie z, State s, String why) {
        if (plugin.getConfig().getBoolean("horde.debug", false)) {
            long now = now();
            Long last = rampLog.get(z.getUniqueId());
            if (last == null || now - last > 5000) {
                rampLog.put(z.getUniqueId(), now);
                plugin.getLogger().info("[horda] rampa #" + z.getEntityId() % 10000 + ": " + why);
            }
        }
        return false;
    }

    /**
     * Zumbi travado numa rampa pronta (tipico na curva: a cabeca bate no degrau de cima ou nao ha onde virar).
     * 1a vez naquele degrau: tira blocos da horda acima da cabeca e coloca um degrau de apoio no canto da curva.
     * 2a vez no mesmo degrau: desmonta os degraus de cima (so blocos da horda, sem ninguem em cima; voltam pro
     * inventario) e marca a rampa para replanejar: o plano novo reaproveita a parte de baixo (extend).
     * Devolve o que fez, ou null se nao ha o que consertar.
     */
    private String repairRamp(Zombie z, State s, Ramp r) {
        Block under = z.getLocation().getBlock().getRelative(BlockFace.DOWN);
        int i = r.steps.indexOf(under);
        if (i < 0) { // nao esta exatamente num degrau: o mais proximo a ate 2 blocos
            double bd = 4.0;
            for (int k = 0; k < r.steps.size(); k++) {
                double d = r.steps.get(k).getLocation().add(0.5, 1, 0.5).distanceSquared(z.getLocation());
                if (d < bd) { bd = d; i = k; }
            }
        }
        if (i < 0 || i >= r.steps.size() - 1) return null;
        if (r.fixIndex != i) { r.fixIndex = i; r.fixStage = 0; }
        if (r.fixStage == 0) {
            r.fixStage = 1;
            int done = 0;
            // teto: bloco da horda logo acima da cabeca de quem vai pular para o proximo degrau
            Block feet = r.steps.get(i).getRelative(BlockFace.UP);
            for (Block c : new Block[]{feet.getRelative(0, 2, 0), r.steps.get(i + 1).getRelative(0, 3, 0)}) {
                if (ZPLACED.contains(c) && !r.steps.subList(0, i + 2).contains(c) && !occupied(c.getRelative(BlockFace.UP), z)) {
                    takeOwn(s, c);
                    done++;
                }
            }
            // apoio na curva: continua um bloco na direcao que vinha, para ter onde virar
            if (i > 0) {
                Block a = r.steps.get(i), prev = r.steps.get(i - 1), next = r.steps.get(i + 1);
                int pdx = a.getX() - prev.getX(), pdz = a.getZ() - prev.getZ();
                int ndx = next.getX() - a.getX(), ndz = next.getZ() - a.getZ();
                if ((pdx != ndx || pdz != ndz) && s.invTotal > 0) {
                    Block pad = a.getRelative(Integer.signum(pdx), 0, Integer.signum(pdz));
                    if (pad.isPassable() && !pad.isLiquid() && pad.getRelative(BlockFace.UP).isPassable()
                            && pad.getRelative(0, 2, 0).isPassable() && !r.steps.contains(pad) && !occupied(pad, z)) {
                        pad.setType(takeBlock(s));
                        placeFx(pad);
                        done++;
                    }
                }
            }
            if (done > 0) return "rampa: consertando a curva (" + done + " blocos)";
        }
        if (r.fixStage == 1) {
            r.fixStage = 2;
            int removed = 0;
            for (int j = r.steps.size() - 1; j > i; j--) {
                Block st = r.steps.get(j);
                if (ZPLACED.contains(st) && !occupied(st.getRelative(BlockFace.UP), z)) { takeOwn(s, st); removed++; }
            }
            r.broken = true;
            if (removed > 0) return "rampa: desmontou " + removed + " degraus de cima, refaz dali";
        }
        return null;
    }

    /** Recolhe um bloco que a propria horda colocou (volta pro inventario de quem recolheu). */
    private void takeOwn(State s, Block b) {
        Material m = b.getType();
        b.setType(Material.AIR);
        ZPLACED.remove(b);
        if (REC != null) REC.block(b, Material.AIR);
        addBlock(s, m);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 0.8f, 1.1f);
    }

    /** Um zumbi da horda trabalha na rampa: abre a cabeca do degrau, vai ate o degrau de baixo e coloca o proximo. */
    private boolean rampTick(Zombie z, State s, LivingEntity t, long now) {
        Ramp r = rampFor(t);
        if (r == null) { return rampWhy(z, s, "sem direcao valida"); }
        int d = r.next();
        if (d < 0) { // pronta: o pathfinder sobe por ela; se continuar parado, ela nao serve (bloqueada/desconectada)
            Location here = z.getLocation();
            if (s.lastPos != null && here.distanceSquared(s.lastPos) < 0.05) s.rampStuck++; else s.rampStuck = 0;
            s.lastPos = here.clone();
            if (s.rampStuck > 20) {
                s.rampStuck = 0;
                // antes de jogar a rampa fora: conserta a curva ou desmonta so o trecho de cima e refaz dali
                if (opt("ramp-repair")) {
                    String fix = repairRamp(z, s, r);
                    if (fix != null) return rampWhy(z, s, fix + " (" + r.desc + ")");
                }
                r.broken = true;
                return rampWhy(z, s, "pronta mas nao chega: replaneja (" + r.desc + ")");
            }
            return rampWhy(z, s, "pronta (" + r.desc + ")");
        }
        Block step = r.steps.get(d), stand = r.stand(d);
        Location zl = z.getLocation();
        for (int hy = 1; hy <= 2; hy++) {
            Block c = step.getRelative(0, hy, 0);
            if (c.isPassable()) continue;
            if (canBreak(s, c) && zl.distance(c.getLocation().add(0.5, 0.5, 0.5)) <= 4.5) {
                progressBreak(z, s, c); s.action = "rampa: abrindo"; return true;
            }
            boolean anyone = false;
            for (State o : horde.values()) if (canBreak(o, c)) { anyone = true; break; }
            if (!anyone) { r.broken = true; return rampWhy(z, s, "bloqueio " + c.getType().name().toLowerCase()); }
            z.getPathfinder().moveTo(stand.getLocation().add(0.5, 0, 0.5), 1.15);
            s.action = "rampa: espera"; return true;
        }
        Block wc = r.waitCenter;
        int groundAtWait = groundY(wc.getWorld(), wc.getX(), wc.getZ()) + 1;
        // em cima da rampa = pisando num bloco da horda / degrau do plano (antes comparava com o chao do ponto de
        // espera, que no zigue-zague caia num lugar baixo: todo mundo no chao achava que estava 'na rampa')
        Block under = zl.getBlock().getRelative(BlockFace.DOWN);
        boolean onRamp = ZPLACED.contains(under) || r.steps.contains(under);
        if (s.invTotal == 0 && onRamp) { // acabou o material la em cima: fica parado (descer cruza com o proximo e cai)
            z.getPathfinder().stopPathfinding();
            z.setVelocity(new Vector(0, Math.min(0, z.getVelocity().getY()), 0));
            if (z.getUniqueId().equals(r.worker)) {
                if (r.workerEmptySince == 0) r.workerEmptySince = now;
                Block bay = nearestBay(r, z);
                if (bay != null && now - r.workerEmptySince <= Pi("worker_empty_ms", 25_000)) {
                    Location top = bay.getLocation().add(0.5, 1.05, 0.5);
                    if (zl.distance(top) > 0.6) {
                        if (zl.distance(top) <= 1.7) { top.setYaw(zl.getYaw()); z.teleport(top); z.setVelocity(new Vector(0, 0, 0)); }
                        else z.getPathfinder().moveTo(top, 1.0);
                    }
                    s.action = "construtor no recuo (esperando entrega)";
                    return true;
                }
                r.worker = null; r.workerEmptySince = 0; // sem recuo ou ninguem trouxe: larga a vez
            }
            // sem bloco em cima da rampa: desce e vai coletar (parado aqui so trava o caminho de 1 de largura)
            z.getPathfinder().moveTo(r.entry.getLocation().add(0.5, 0, 0.5), 1.0);
            s.action = "descendo da rampa para coletar";
            return true;
        }
        if (s.invTotal == 0) {
            if (now > s.gatherCooldownUntil && z.isOnGround()) { startGather(s, needFor(s, 0), now); return true; }
            return rampWhy(z, s, "sem blocos, degrau " + (d + 1) + "/" + r.steps.size() + " " + r.desc);
        }
        // escolhe o construtor da vez: quem tem bloco e esta mais perto do degrau
        State ws = r.worker == null ? null : horde.get(r.worker);
        Entity we = r.worker == null ? null : Bukkit.getEntity(r.worker);
        boolean frontEmpty = ws != null && we != null && !we.isDead() && ws.invTotal == 0
                && we.getLocation().getBlockY() > groundAtWait + 1 && r.workerEmptySince > 0;
        if (ws != null && ws.invTotal > 0) r.workerEmptySince = 0;
        if (!frontEmpty && (ws == null || we == null || we.isDead() || ws.invTotal == 0 || ws.gathering)) {
            r.worker = null;
            double bd = Double.MAX_VALUE;
            for (var en : horde.entrySet()) {
                State o = en.getValue();
                if (!t.getUniqueId().equals(o.target) || o.invTotal == 0 || o.gathering || tool(o) == null) continue;
                Entity oe = Bukkit.getEntity(en.getKey());
                if (oe == null || oe.getWorld() != stand.getWorld()) continue;
                double dd = oe.getLocation().distanceSquared(stand.getLocation()) - o.invTotal * 4;
                if (dd < bd) { bd = dd; r.worker = en.getKey(); }
            }
        }
        if (!z.getUniqueId().equals(r.worker) && r.worker != null) {
            State wst = horde.get(r.worker);
            Entity wen = Bukkit.getEntity(r.worker);
            boolean needs = wst != null && wen != null && wst.invTotal < Pi("carrier_trigger", 4);
            State cst = r.carrier == null ? null : horde.get(r.carrier);
            if (cst == null || cst.invTotal == 0 || now - r.carrierSince > Pi("carrier_timeout_ms", 60_000)) r.carrier = null;
            if (needs && r.carrier == null) { // escolhe quem leva: mais blocos e mais perto da rampa
                double bd = Double.MAX_VALUE;
                for (var en : horde.entrySet()) {
                    State o = en.getValue();
                    if (en.getKey().equals(r.worker) || o.invTotal < 4 || o.gathering || !t.getUniqueId().equals(o.target)) continue;
                    Entity oe = Bukkit.getEntity(en.getKey());
                    if (oe == null || oe.getWorld() != r.entry.getWorld()) continue;
                    double dd = oe.getLocation().distanceSquared(r.entry.getLocation()) - o.invTotal * 6;
                    if (dd < bd) { bd = dd; r.carrier = en.getKey(); }
                }
                if (r.carrier != null) r.carrierSince = now;
            }
            if (z.getUniqueId().equals(r.carrier) && wen != null && wst != null) {
                Location wl = wen.getLocation();
                if (zl.getWorld() == wl.getWorld() && zl.distance(wl) <= 2.5) { // entrega
                    int moved = 0;
                    for (var it = s.inv.entrySet().iterator(); it.hasNext(); ) {
                        var e = it.next();
                        int room = capacity() - wst.invTotal;
                        if (room <= 0) break;
                        int k = Math.min(room, e.getValue());
                        wst.inv.merge(e.getKey(), k, Integer::sum);
                        wst.invTotal += k; s.invTotal -= k; moved += k;
                        if (k == e.getValue()) it.remove(); else e.setValue(e.getValue() - k);
                    }
                    r.carrier = null;
                    r.workerEmptySince = 0;
                    z.swingMainHand();
                    s.action = "entregou " + moved + " blocos ao construtor";
                    return true;
                }
                z.getPathfinder().moveTo(wl, 1.15);
                s.action = "levando blocos ao construtor";
                return true;
            }
        }
        if (!z.getUniqueId().equals(r.worker)) { // espera no chao, em formacao (cada um no seu ponto do anel)
            List<UUID> mates = new ArrayList<>();
            for (var en : horde.entrySet()) if (t.getUniqueId().equals(en.getValue().target)) mates.add(en.getKey());
            mates.sort(null);
            int k = Math.max(0, mates.indexOf(z.getUniqueId()));
            double ang = 2 * Math.PI * k / Math.max(1, mates.size());
            if (s.invTotal < capacity() / 2 && now > s.gatherCooldownUntil && z.isOnGround() && !onRamp) {
                startGather(s, capacity(), now); // abastece o estoque da horda enquanto o construtor trabalha
                return true;
            }
            Block far = r.waitCenter;
            int wx = (int) Math.round(far.getX() + Math.cos(ang) * P("wait_ring", 3)), wz = (int) Math.round(far.getZ() + Math.sin(ang) * P("wait_ring", 3));
            Location spot = new Location(far.getWorld(), wx + 0.5, groundY(far.getWorld(), wx, wz) + 1, wz + 0.5);
            if (zl.distanceSquared(spot) > 2.5) z.getPathfinder().moveTo(spot, 1.15);
            else z.getPathfinder().stopPathfinding();
            s.action = "esperando na base da rampa";
            return true;
        }
        Block feet = zl.getBlock();
        Block below1 = d >= 1 ? r.stand(d - 1) : null; // 1 degrau abaixo (quando o de cima esta ocupado)
        boolean atStand = feet.equals(stand) || feet.equals(below1) || (feet.getY() == stand.getY()
                && Math.max(Math.abs(feet.getX() - stand.getX()), Math.abs(feet.getZ() - stand.getZ())) <= 1
                && Math.max(Math.abs(feet.getX() - step.getX()), Math.abs(feet.getZ() - step.getZ())) <= 2);
        if (atStand) {
            if (now - s.lastPlace < Pi("place_ms", 650)) return true;
            if (occupied(step, z)) { s.action = "lugar ocupado"; return true; }
            step.setType(takeBlock(s));
            placeFx(step);
            s.lastPlace = now;
            z.teleport(step.getLocation().add(0.5, 1.05, 0.5).setDirection(t.getLocation().toVector().subtract(zl.toVector())));
            z.setVelocity(new Vector(0, 0, 0));
            z.getPathfinder().stopPathfinding();
            s.action = "rampa da horda (" + r.remaining() + " faltam, " + r.desc + ")";
            return true;
        }
        z.getPathfinder().moveTo(stand.getLocation().add(0.5, 0, 0.5), 1.15);
        s.action = "indo pra rampa";
        return true;
    }

    Component countLine() { return Component.text(alive() + " zumbis restantes"); }
}
