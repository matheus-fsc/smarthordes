package dev.math.smarthordes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Invasoes de faccoes controladas por bots Mineflayer (rodando na VPS).
 * O plugin decide quando/onde, escolhe o ponto de spawn (reconhecimento do terreno),
 * equipa os bots e os teleporta; o controlador externo cuida da IA.
 */
public final class Invasion implements Listener {

    public enum Faction {
        ZUMBIS("Zumbi_", "🧟 INVASAO ZUMBI", "Uma horda de zumbis se aproxima!", NamedTextColor.DARK_GREEN, 28, 40),
        PIRATAS("Pirata_", "🏴‍☠ PIRATAS!", "Piratas desembarcaram atras dos seus baus!", NamedTextColor.GOLD, 30, 50),
        GUILDA("Guilda_", "⚔ GUILDA INIMIGA", "Uma guilda inimiga esta passando pela regiao...", NamedTextColor.DARK_PURPLE, 50, 70);

        final String prefix, title, subtitle;
        final NamedTextColor color;
        final int minDist, maxDist;

        Faction(String prefix, String title, String subtitle, NamedTextColor color, int minDist, int maxDist) {
            this.prefix = prefix; this.title = title; this.subtitle = subtitle; this.color = color;
            this.minDist = minDist; this.maxDist = maxDist;
        }

        String id() { return name().toLowerCase(Locale.ROOT); }

        static Faction of(String s) {
            for (Faction f : values()) if (f.id().equals(s.toLowerCase(Locale.ROOT))) return f;
            return null;
        }
    }

    private static final Pattern BOT_NAME = Pattern.compile("^(Zumbi|Pirata|Guilda)_\\d{1,2}$");

    private final SmartHordes plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Set<UUID> bots = new HashSet<>();
    private final Map<String, Location> slots = new java.util.concurrent.ConcurrentHashMap<>(); // nome do bot -> lugar de spawn
    private Faction active;
    private int expected;
    private int spawned;
    private long startedAt;
    private BukkitTask watchdog;
    private final SmartHorde horde;
    private boolean forced;    // iniciada por comando (ignora "recua ao amanhecer")
    private boolean autoRoll;  // true so durante o sorteio automatico
    private BukkitTask flareTask;
    private UUID targetId;
    private int flareTicks;

    /**
     * Flare: a cada 1s manda a posicao real dos jogadores para o cerebro da horda (que faz A* ate la mesmo
     * sem enxergar), e a cada 15s solta um sinal visivel sobre o alvo ("a horda sentiu seu cheiro").
     */
    private void startFlares() {
        stopFlares();
        flareTicks = 0;
        flareTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (active == null) { stopFlares(); return; }
            World w = plugin.currentWorld();
            StringBuilder sb = new StringBuilder("{\"players\":[");
            boolean first = true;
            for (Player p : realPlayers(null)) {
                if (w == null || p.getWorld() != w) continue;
                Location l = p.getLocation();
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(p.getName()).append("\",\"x\":").append(String.format(Locale.ROOT, "%.2f", l.getX()))
                        .append(",\"y\":").append(String.format(Locale.ROOT, "%.2f", l.getY()))
                        .append(",\"z\":").append(String.format(Locale.ROOT, "%.2f", l.getZ())).append('}');
            }
            sb.append("]}");
            if (active != Faction.ZUMBIS) sendController("/flare", sb.toString()); // horda zumbi roda no proprio servidor
            if (++flareTicks % 15 == 1) {
                Player t = targetId == null ? null : Bukkit.getPlayer(targetId);
                if (t != null && !isBot(t)) {
                    Location l = t.getLocation().add(0, 2.5, 0);
                    t.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, l, 40, 0.2, 0.8, 0.2, 0.08);
                    t.getWorld().spawnParticle(org.bukkit.Particle.DUST, l.clone().add(0, 1, 0), 30, 0.3, 0.3, 0.3, 0,
                            new org.bukkit.Particle.DustOptions(Color.RED, 2f));
                    t.getWorld().playSound(l, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 0.7f);
                    subtitle(t, Component.text("☣ A horda sentiu seu cheiro...", NamedTextColor.RED)); // titulo: a barra de acao e do DirectionHUD
                }
            }
        }, 20L, 20L);
    }

    private void stopFlares() {
        if (flareTask != null) { flareTask.cancel(); flareTask = null; }
    }
    private long lastRollKey = -1;
    private long lastHordeDay = -100;

    /** Tipo de horda: muda a mistura de papeis, o tamanho e as mensagens de aviso. */
    record Variant(String id, int[] cycle, double size, String warn, String dusk, String arrive, Sound sound) {}
    static final Variant[] VARIANTS = {
            new Variant("podridao", new int[]{SmartHorde.MINER, SmartHorde.DIGGER, SmartHorde.LUMBER, SmartHorde.BRUTE, SmartHorde.TANK}, 1.0,
                    "Voce sente o cheiro de podridao no horizonte...",
                    "O cheiro esta mais forte. Eles vem com a noite.",
                    "A horda sente seu cheiro...", Sound.ENTITY_ZOMBIE_AMBIENT),
            new Variant("picaretas", new int[]{SmartHorde.MINER, SmartHorde.DIGGER, SmartHorde.MINER, SmartHorde.DIGGER, SmartHorde.BRUTE}, 1.0,
                    "Voce escuta o tinir de picaretas debaixo da terra...",
                    "As picaretas estao mais perto. Paredes de pedra nao vao segurar.",
                    "As picaretas param. Eles chegaram.", Sound.BLOCK_STONE_HIT),
            new Variant("machados", new int[]{SmartHorde.LUMBER, SmartHorde.LUMBER, SmartHorde.MINER, SmartHorde.DIGGER, SmartHorde.BRUTE}, 1.0,
                    "Arvores tombam ao longe, uma atras da outra...",
                    "Eles estao juntando madeira. Ficar no alto nao vai salvar ninguem.",
                    "Escadas e rampas surgem na escuridao.", Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR),
            new Variant("passos", new int[]{SmartHorde.BRUTE, SmartHorde.TANK, SmartHorde.BRUTE, SmartHorde.MINER, SmartHorde.DIGGER}, 0.7,
                    "O chao treme com passos pesados ao longe...",
                    "Os passos estao mais perto. Sao poucos, mas sao grandes.",
                    "Os brutos chegaram.", Sound.ENTITY_RAVAGER_STEP),
    };
    private Variant pending, nextVariant;
    private long pendingDay = -1;
    private boolean duskSent;

    private void whisper(String msg, NamedTextColor color, Sound sound, World w) {
        Bukkit.broadcast(Component.text(msg, color, TextDecoration.ITALIC));
        for (Player p : realPlayers(w)) p.playSound(p.getLocation(), sound, 0.7f, 0.6f);
    }

    /** Ninguem dorme com horda anunciada ou em andamento (dormir pulava a noite e a horda). */
    @EventHandler(ignoreCancelled = true)
    public void onBed(org.bukkit.event.player.PlayerBedEnterEvent e) {
        if (pending == null && active != Faction.ZUMBIS) return;
        e.setCancelled(true);
        Component bed = Component.text("Voce nao consegue dormir com a horda chegando.", NamedTextColor.RED);
        subtitle(e.getPlayer(), bed);
        e.getPlayer().sendMessage(bed);
    }

    public Invasion(SmartHordes plugin) {
        this.plugin = plugin;
        this.horde = new SmartHorde(plugin);
        var c = plugin.getConfig();
        c.addDefault("invasion.enabled", true);
        c.addDefault("invasion.controller-url", "http://127.0.0.1:8765");
        c.addDefault("invasion.token", UUID.randomUUID().toString());
        c.addDefault("invasion.chance.zumbis", 0.25);
        // dificuldade crescente: quanto mais tempo a run esta viva, maior a chance e a horda
        c.addDefault("invasion.ramp.chance-per-hour", 0.06);
        c.addDefault("invasion.ramp.max-chance", 0.55);
        c.addDefault("invasion.ramp.size-per-hour", 2);
        c.addDefault("invasion.ramp.days-per-zombie", 4);
        c.addDefault("invasion.max-zombies", 24);
        c.addDefault("invasion.rest-nights", 1); // noites sem horda depois de uma horda
        c.addDefault("invasion.chance.piratas", 0.0); // bots descontinuados
        c.addDefault("invasion.chance.guilda", 0.0);
        c.addDefault("invasion.min-day", 1);
        c.options().copyDefaults(true);
        plugin.saveConfig();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 100L);
    }

    // ---------------------------------------------------------------- identificacao / auth dos bots

    public boolean isBot(Player p) { return bots.contains(p.getUniqueId()); }

    private static boolean loopback(InetAddress a) { return a != null && (a.isLoopbackAddress()); }

    /** Bots so entram com nome de faccao vindo de dentro da VPS (IP repassado e assinado pelo Velocity). */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent e) {
        if (!BOT_NAME.matcher(e.getPlayer().getName()).matches()) return;
        if (loopback(e.getAddress()) && active != null && slots.containsKey(e.getPlayer().getName())) {
            e.allow();
        } else {
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text("Nome reservado."));
        }
    }

    /**
     * Bot ja nasce no ponto escolhido (mundo da run), sem teleporte entre mundos depois do login:
     * a troca de mundo traduzida pelo ViaBackwards deixava a posicao do Mineflayer invalida (kick).
     */
    @EventHandler
    public void onSpawnLocation(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent e) {
        String name = e.getConnection().getProfile().getName();
        if (name == null || !BOT_NAME.matcher(name).matches()) return;
        Location slot = slots.get(name);
        if (slot != null) e.setSpawnLocation(slot);
    }

    /** Chamado pelo onJoin principal. Retorna true se for bot (e o fluxo normal deve ser ignorado). */
    public boolean handleJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!BOT_NAME.matcher(p.getName()).matches()) return false;
        var addr = p.getAddress();
        Location slot = slots.get(p.getName());
        if (addr == null || !loopback(addr.getAddress()) || active == null || slot == null) {
            e.joinMessage(null);
            Bukkit.getScheduler().runTask(plugin, () -> p.kick(Component.text("Nome reservado.")));
            return true;
        }
        bots.add(p.getUniqueId());
        e.joinMessage(null);
        p.setSleepingIgnored(true);
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.playerListName(Component.text("[" + active.id().substring(0, 1).toUpperCase() + "] " + p.getName(), active.color));
        equip(p, active, roleOf(p.getName()));
        // so teleporta se o spawn direto falhou; mesmo mundo = teleporte simples (sem troca de dimensao)
        if (p.getWorld() != slot.getWorld()) p.teleportAsync(slot);
        else if (p.getLocation().distanceSquared(slot) > 4) p.teleport(slot);
        spawned++;
        return true;
    }

    public void handleQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (bots.remove(e.getPlayer().getUniqueId())) {
            e.quitMessage(null);
            Bukkit.getScheduler().runTask(plugin, this::checkEnd);
        }
    }

    /** Morte de bot: nao conta como morte hardcore. */
    public boolean handleDeath(PlayerDeathEvent e) {
        if (!isBot(e.getPlayer())) return false;
        Player killer = e.getPlayer().getKiller();
        Component msg = Component.text("☠ " + e.getPlayer().getName(), active != null ? active.color : NamedTextColor.GRAY)
                .append(Component.text(killer != null ? " foi abatido por " + killer.getName() : " caiu", NamedTextColor.GRAY));
        e.deathMessage(msg);
        e.setShouldDropExperience(true);
        e.setDroppedExp(15);
        return true;
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (isBot(e.getPlayer())) e.message(null);
    }

    private static int roleOf(String name) {
        try { return Integer.parseInt(name.substring(name.indexOf('_') + 1)) % 4; } catch (NumberFormatException ex) { return 0; }
    }

    // ---------------------------------------------------------------- equipamento por faccao/papel

    private static ItemStack dyed(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(c); it.setItemMeta(lm); }
        return it;
    }

    private static void equip(Player p, Faction f, int role) {
        PlayerInventory inv = p.getInventory();
        switch (f) {
            case ZUMBIS -> {
                Color rot = Color.fromRGB(0x3B5A2A);
                inv.setHelmet(new ItemStack(Material.ZOMBIE_HEAD));
                inv.setChestplate(role == 3 ? new ItemStack(Material.CHAINMAIL_CHESTPLATE) : dyed(Material.LEATHER_CHESTPLATE, rot));
                inv.setLeggings(dyed(Material.LEATHER_LEGGINGS, rot));
                inv.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(0x2A2A2A)));
                switch (role) {
                    case 0 -> inv.addItem(new ItemStack(Material.STONE_SWORD));                       // brutamontes
                    case 1 -> inv.addItem(new ItemStack(Material.IRON_PICKAXE), new ItemStack(Material.STONE_SHOVEL), new ItemStack(Material.STONE_AXE)); // escavador
                    case 2 -> inv.addItem(new ItemStack(Material.WOODEN_SWORD), new ItemStack(Material.STONE_PICKAXE), new ItemStack(Material.DIRT, 64), new ItemStack(Material.COBBLESTONE, 64)); // construtor
                    default -> inv.addItem(new ItemStack(Material.IRON_SHOVEL));                       // tanque
                }
            }
            case PIRATAS -> {
                inv.setHelmet(dyed(Material.LEATHER_HELMET, Color.fromRGB(0x111111)));
                inv.setChestplate(dyed(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x8B1A1A)));
                inv.setLeggings(dyed(Material.LEATHER_LEGGINGS, Color.fromRGB(0x2B2B5A)));
                inv.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(0x3D2B1F)));
                switch (role) {
                    case 0 -> { inv.addItem(new ItemStack(Material.IRON_SWORD)); inv.setItemInOffHand(new ItemStack(Material.SHIELD)); } // espadachim
                    case 1, 3 -> inv.addItem(new ItemStack(Material.BOW), new ItemStack(Material.ARROW, 64), new ItemStack(Material.STONE_SWORD)); // atirador
                    default -> inv.addItem(new ItemStack(Material.IRON_AXE), new ItemStack(Material.COBBLESTONE, 32)); // saqueador
                }
            }
            case GUILDA -> {
                inv.setHelmet(new ItemStack(Material.IRON_HELMET));
                inv.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                inv.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                inv.setBoots(new ItemStack(Material.IRON_BOOTS));
                switch (role) {
                    case 0 -> { inv.addItem(new ItemStack(Material.DIAMOND_SWORD)); inv.setItemInOffHand(new ItemStack(Material.SHIELD)); } // cavaleiro
                    case 1 -> inv.addItem(new ItemStack(Material.BOW), new ItemStack(Material.ARROW, 64), new ItemStack(Material.IRON_SWORD)); // arqueiro
                    case 2 -> inv.addItem(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.IRON_PICKAXE), new ItemStack(Material.COOKED_BEEF, 16)); // minerador
                    default -> inv.addItem(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.STONE_PICKAXE)); // construtor
                }
            }
        }
    }

    // ---------------------------------------------------------------- agendamento automatico

    private List<Player> realPlayers(World w) {
        List<Player> out = new ArrayList<>();
        for (Player p : (w != null ? w.getPlayers() : new ArrayList<Player>(Bukkit.getOnlinePlayers()))) {
            if (!isBot(p) && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) out.add(p);
        }
        return out;
    }

    private void tick() {
        if (active != null) { checkEnd(); return; }
        var c = plugin.getConfig();
        if (!c.getBoolean("invasion.enabled", true) || plugin.isVoting()) return;
        World w = plugin.currentWorld();
        if (w == null || realPlayers(w).isEmpty()) return;
        long day = w.getFullTime() / 24000L;
        if (day < c.getInt("invasion.min-day", 1)) return;
        long t = w.getTime();
        Faction roll = null;
        long key;
        if (pending != null && pendingDay < day) pending = null; // aviso velho (dia pulado)
        if (t >= 9000 && t < 9400) {            // tarde: sorteia e avisa (da tempo de preparar)
            key = day * 2 + 1;
            if (key == lastRollKey) return;
            lastRollKey = key;
            double hours = plugin.runAliveSeconds() / 3600.0;
            double chance = Math.min(c.getDouble("invasion.ramp.max-chance", 0.9),
                    c.getDouble("invasion.chance.zumbis") + hours * c.getDouble("invasion.ramp.chance-per-hour", 0.12));
            boolean resting = day - lastHordeDay <= c.getInt("invasion.rest-nights", 1);
            if (resting || ThreadLocalRandom.current().nextDouble() >= chance) return;
            pending = VARIANTS[ThreadLocalRandom.current().nextInt(VARIANTS.length)];
            pendingDay = day;
            duskSent = false;
            whisper(pending.warn(), NamedTextColor.DARK_GREEN, pending.sound(), w);
            plugin.getLogger().info("Horda anunciada para a noite: " + pending.id());
            return;
        } else if (t >= 12000 && t < 12400) {   // por do sol: lembrete
            if (pending != null && pendingDay == day && !duskSent) {
                duskSent = true;
                whisper(pending.dusk(), NamedTextColor.GOLD, pending.sound(), w);
            }
            return;
        } else if (t >= 13000 && t < 13400) {   // anoitecer: a horda anunciada chega
            if (pending == null || pendingDay != day) return;
            key = day * 2 + 1;
            nextVariant = pending;
            pending = null;
            roll = Faction.ZUMBIS;
        } else if (t >= 1000 && t < 1400) {     // manha: piratas ou guilda
            key = day * 2;
            if (key != lastRollKey) {
                double r = ThreadLocalRandom.current().nextDouble();
                double cp = c.getDouble("invasion.chance.piratas"), cg = c.getDouble("invasion.chance.guilda");
                if (r < cp) roll = Faction.PIRATAS; else if (r < cp + cg) roll = Faction.GUILDA;
            }
        } else return;
        lastRollKey = key;
        if (roll != null) {
            autoRoll = true;
            try { start(roll, null, Bukkit.getConsoleSender()); } finally { autoRoll = false; }
        }
    }

    private void checkEnd() {
        if (active == null) return;
        World w = plugin.currentWorld();
        long age = System.currentTimeMillis() - startedAt;
        boolean allSpawned = spawned >= expected || age > 90_000;
        boolean empty = active == Faction.ZUMBIS ? horde.alive() == 0 : bots.isEmpty();
        if (allSpawned && empty && age > 20_000) {
            finish(Component.text("✔ Invasao repelida! " + active.title.replaceAll("^\\S+ ", "") + " derrotados.", NamedTextColor.GREEN, TextDecoration.BOLD));
            return;
        }
        boolean day = w != null && (w.getTime() >= 23300 || w.getTime() < 12000);
        if (active == Faction.ZUMBIS && ((!forced && day && age > 60_000) || age > 8 * 60_000)) {
            finish(Component.text(day ? "☀ Amanheceu: a horda recua para as sombras." : "A horda se dispersa na escuridao.", NamedTextColor.YELLOW));
        } else if (active == Faction.PIRATAS && age > 6 * 60_000) {
            finish(Component.text("⛵ Os piratas voltaram para o mar.", NamedTextColor.GOLD));
        } else if (active == Faction.GUILDA && age > 8 * 60_000) {
            finish(Component.text("⚔ A guilda seguiu viagem.", NamedTextColor.DARK_PURPLE));
        }
    }

    // ---------------------------------------------------------------- iniciar / terminar

    public void start(Faction f, Integer forcedCount, org.bukkit.command.CommandSender who) {
        start(f, forcedCount, who, null);
    }

    public void start(Faction f, Integer forcedCount, org.bukkit.command.CommandSender who, Player forcedTarget) {
        start(f, forcedCount, who, forcedTarget, false);
    }

    public void start(Faction f, Integer forcedCount, org.bukkit.command.CommandSender who, Player forcedTarget, boolean buildersOnly) {
        if (active != null) { who.sendMessage(Component.text("Ja tem uma invasao acontecendo.", NamedTextColor.RED)); return; }
        World w = plugin.currentWorld();
        List<Player> targets = w == null ? List.of() : realPlayers(w);
        if (targets.isEmpty()) { who.sendMessage(Component.text("Ninguem no overworld do mundo atual para invadir.", NamedTextColor.RED)); return; }
        long day = w.getFullTime() / 24000L + 1;
        forced = !autoRoll;
        int n = forcedCount != null ? forcedCount : switch (f) {
            // horda cresce com os jogadores e com os dias sobrevividos
            case ZUMBIS -> (int) Math.min(plugin.getConfig().getInt("invasion.max-zombies", 24), Math.max(4, (nextVariant == null ? 1.0 : nextVariant.size())
                    * (targets.size() * 3 + 2
                    + day / Math.max(1, plugin.getConfig().getInt("invasion.ramp.days-per-zombie", 4))
                    + plugin.runAliveSeconds() / 3600.0 * plugin.getConfig().getDouble("invasion.ramp.size-per-hour", 2))));
            case PIRATAS -> Math.min(8, Math.max(3, targets.size() * 2 + 2));
            case GUILDA -> Math.min(6, Math.max(3, targets.size() * 2 + 1));
        };
        n = Math.max(1, Math.min(n, f == Faction.ZUMBIS ? 40 : 20));
        final int count = n;
        final Variant variant = nextVariant != null ? nextVariant : VARIANTS[0];
        nextVariant = null;
        active = f; // reserva enquanto faz o reconhecimento
        startedAt = System.currentTimeMillis();
        Player target = forcedTarget != null && targets.contains(forcedTarget) ? forcedTarget
                : targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        scout(f, w, targets, count, target).thenAccept(found -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (found == null || found.isEmpty()) {
                active = null;
                who.sendMessage(Component.text("Reconhecimento: nenhum ponto de spawn bom perto dos jogadores. Invasao adiada.", NamedTextColor.YELLOW));
                plugin.getLogger().info("Invasao " + f.id() + " adiada: sem ponto de spawn valido");
                return;
            }
            slots.clear();
            expected = count;
            spawned = 0;
            startedAt = System.currentTimeMillis();
            if (f == Faction.ZUMBIS) { // horda com mobs do servidor (sem bots/controlador)
                lastHordeDay = w.getFullTime() / 24000L;
                horde.spawn(w, found, count, target, buildersOnly ? new int[]{SmartHorde.MINER, SmartHorde.DIGGER, SmartHorde.LUMBER} : variant.cycle());
                spawned = count;
                targetId = target.getUniqueId();
                // sem foguete/sinalizador (era debug) e sem titulo na tela: so o aviso no chat
                Bukkit.broadcast(Component.text("🧟 " + variant.arrive(), NamedTextColor.DARK_GREEN, TextDecoration.ITALIC));
                for (Player p : Bukkit.getOnlinePlayers()) if (!isBot(p)) p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.6f, 0.7f);
                plugin.getLogger().info("Invasao zumbis (" + variant.id() + ") x" + count + " em " + found.get(0).getBlockX() + "," + found.get(0).getBlockY() + "," + found.get(0).getBlockZ()
                        + " alvo " + target.getName());
                return;
            }
            for (int i = 0; i < count; i++) slots.put(f.prefix + (i + 1), found.get(i % found.size()));
            sendController("/start", "{\"faction\":\"" + f.id() + "\",\"count\":" + count + "}").thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    active = null; slots.clear();
                    who.sendMessage(Component.text("Controlador de bots offline (VPS). Invasao cancelada.", NamedTextColor.RED));
                    return;
                }
                targetId = target.getUniqueId();
                startFlares();
                announce(f, count, target, found.get(0));
            }));
        }));
    }

    private void announce(Faction f, int count, Player target, Location c) {
        Title t = Title.title(Component.text(f.title, f.color, TextDecoration.BOLD), Component.text(f.subtitle, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isBot(p)) continue;
            p.showTitle(t);
            p.playSound(p.getLocation(), f == Faction.ZUMBIS ? Sound.ENTITY_ZOMBIE_AMBIENT : Sound.EVENT_RAID_HORN, 1f, 0.8f);
        }
        Bukkit.broadcast(Component.text(f.title + " ", f.color, TextDecoration.BOLD)
                .append(Component.text(count + " inimigos indo atras de " + target.getName() + ", vindo do " + direction(target.getLocation(), c) + "!", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)));
        plugin.getLogger().info("Invasao " + f.id() + " x" + count + " em " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ());
    }

    /** Jogador de verdade morreu: a invasao acaba e a horda some (nao fica cacando quem sobrou/o mundo novo). */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onRealDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (active == null || isBot(e.getEntity())) return;
        Bukkit.getScheduler().runTask(plugin, () -> finish(Component.text("☠ A horda se dispersa depois da morte de " + e.getEntity().getName() + ".", NamedTextColor.GRAY)));
    }

    public void finish(Component msg) {
        if (active == null) return;
        Bukkit.broadcast(msg);
        stopFlares();
        horde.despawn(true);
        sendController("/stop", "{}");
        active = null;
        slots.clear();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) if (isBot(p)) p.kick(Component.text("fim da invasao"));
        }, 100L);
    }

    /** Reset de mundo: some com todo mundo sem mensagem. */
    public void abortSilently() {
        horde.despawn(false);
        if (active == null && bots.isEmpty()) return;
        stopFlares();
        sendController("/stop", "{}");
        active = null;
        slots.clear();
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) if (isBot(p)) p.kick(Component.text("reset"));
    }

    public String status() {
        if (active == Faction.ZUMBIS) return "zumbis | " + horde.describe();
        return active == null ? "nenhuma invasao ativa" : active.id() + " | bots vivos " + bots.size() + "/" + expected;
    }

    private CompletableFuture<Boolean> sendController(String path, String json) {
        String url = plugin.getConfig().getString("invasion.controller-url");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("X-Token", plugin.getConfig().getString("invasion.token", ""))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() / 100 == 2)
                .exceptionally(ex -> { plugin.getLogger().warning("Controlador de bots: " + ex.getMessage()); return false; });
    }

    private static String direction(Location from, Location to) {
        double ang = Math.toDegrees(Math.atan2(to.getX() - from.getX(), from.getZ() - to.getZ()));
        String[] d = {"norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste"};
        return d[(int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8];
    }

    // ---------------------------------------------------------------- reconhecimento do terreno

    private static boolean badGround(Block b) {
        Material m = b.getType();
        return b.isLiquid() || !m.isSolid() || Tag.LEAVES.isTagged(m) || m == Material.CACTUS || m == Material.MAGMA_BLOCK
                || m == Material.ICE || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH || m == Material.CAMPFIRE
                || m == Material.SOUL_CAMPFIRE || m.name().contains("BED") || m.name().contains("CHEST") || m.name().contains("DOOR");
    }

    private static boolean standable(World w, int x, int y, int z) {
        Block ground = w.getBlockAt(x, y, z);
        return !badGround(ground) && w.getBlockAt(x, y + 1, z).isPassable() && !w.getBlockAt(x, y + 1, z).isLiquid()
                && w.getBlockAt(x, y + 2, z).isPassable() && !w.getBlockAt(x, y + 2, z).isLiquid();
    }

    private static int surfaceY(World w, int x, int z) {
        return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    /** Pontua um candidato; menor e melhor. null = invalido. */
    private Double evaluate(Faction f, World w, int x, int z, Location center, Collection<Player> players) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = surfaceY(w, x, z);
        if (!standable(w, x, y, z)) return null;
        if (w.getBlockAt(x, y + 1, z).getLightFromSky() < 12) return null; // ceu aberto: nada de caverna/telhado
        if (Math.abs(y - center.getBlockY()) > 10) return null;
        for (Player p : players) if (p.getLocation().distanceSquared(new Location(w, x, y, z)) < 20 * 20) return null;

        // Trajeto ate os jogadores: sem penhasco nem agua larga no caminho (horda chega conectada)
        double dx = center.getX() - x, dz = center.getZ() - z, len = Math.sqrt(dx * dx + dz * dz);
        int steps = (int) (len / 2), water = 0, maxWater = 0, cliffs = 0, unknown = 0, prev = y;
        for (int i = 1; i < steps; i++) {
            int sx = (int) Math.floor(x + dx * i / steps), sz = (int) Math.floor(z + dz * i / steps);
            if (!w.isChunkLoaded(sx >> 4, sz >> 4)) { unknown++; continue; }
            int sy = surfaceY(w, sx, sz);
            Block top = w.getBlockAt(sx, sy, sz);
            if (top.isLiquid()) { water++; maxWater = Math.max(maxWater, water); } else water = 0;
            if (top.getType() == Material.LAVA) return null;
            if (Math.abs(sy - prev) > 4) cliffs++;
            prev = sy;
        }
        if (unknown > steps / 2) return null;
        if (f != Faction.PIRATAS && maxWater * 2 > 8) return null;
        if (cliffs > 2) return null;

        double score = cliffs * 3 + maxWater + Math.abs(y - center.getBlockY()) * 0.5 + unknown;
        if (f == Faction.PIRATAS) { // pirata vem do mar: prefere costa
            boolean coast = false;
            for (int ox = -5; ox <= 5 && !coast; ox += 5)
                for (int oz = -5; oz <= 5 && !coast; oz += 5)
                    if (w.isChunkLoaded((x + ox) >> 4, (z + oz) >> 4) && w.getBlockAt(x + ox, surfaceY(w, x + ox, z + oz), z + oz).isLiquid()) coast = true;
            if (coast) score -= 6;
        }
        return score + ThreadLocalRandom.current().nextDouble(); // desempate aleatorio
    }

    /** Modo caverna: alvo sem ceu acima -> horda vem de tuneis escuros na mesma profundidade. */
    private Double evaluateCave(World w, int x, int z, Location center, Collection<Player> players, int[] outY) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int cy = center.getBlockY();
        for (int i = 0; i <= 16; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -(i + 1) / 2; // 0, -1, 1, -2, 2 ...
            int y = cy + dy;
            if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 3) continue;
            if (!standable(w, x, y, z)) continue;
            Block head = w.getBlockAt(x, y + 1, z);
            if (head.getLightFromSky() >= 8) continue; // continua subterraneo
            if (w.getBlockAt(x, y, z).getType() == Material.BEDROCK) continue;
            Location l = new Location(w, x, y, z);
            boolean tooClose = false;
            for (Player p : players) if (p.getWorld() == w && p.getLocation().distanceSquared(l) < 12 * 12) { tooClose = true; break; }
            if (tooClose) continue;
            outY[0] = y;
            return Math.abs(dy) + head.getLightFromBlocks() * 0.5 + ThreadLocalRandom.current().nextDouble(); // prefere escuro
        }
        return null;
    }

    private CompletableFuture<List<Location>> scout(Faction f, World w, List<Player> players, int count, Player target) {
        Location center = target.getLocation();
        boolean cave = target.getEyeLocation().getBlock().getLightFromSky() < 8;
        int minD = cave ? 14 : f.minDist, maxD = cave ? 26 : f.maxDist;

        // candidatos em anel ao redor do alvo; carrega os chunks ja gerados (sem gerar terreno novo)
        List<int[]> cands = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < (cave ? 64 : 32); i++) {
            double ang = r.nextDouble(Math.PI * 2);
            double dist = minD + r.nextDouble() * (maxD - minD);
            cands.add(new int[]{(int) (center.getX() + Math.cos(ang) * dist), (int) (center.getZ() + Math.sin(ang) * dist)});
        }
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int[] c : cands) loads.add(w.getChunkAtAsync(c[0] >> 4, c[1] >> 4, false).exceptionally(ex -> null));

        CompletableFuture<List<Location>> out = new CompletableFuture<>();
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            int[] best = null;
            int bestY = 0;
            double bestScore = Double.MAX_VALUE;
            for (int[] c : cands) {
                int[] yy = {0};
                Double s = cave ? evaluateCave(w, c[0], c[1], center, players, yy) : evaluate(f, w, c[0], c[1], center, players);
                if (s != null && s < bestScore) { bestScore = s; best = c; bestY = cave ? yy[0] : surfaceY(w, c[0], c[1]); }
            }
            if (best == null && f == Faction.ZUMBIS) {
                // zumbis cavam/constroem: aceita qualquer chao firme longe dos jogadores (sem exigir rota limpa)
                for (int[] c : cands) {
                    if (!w.isChunkLoaded(c[0] >> 4, c[1] >> 4)) continue;
                    int y = surfaceY(w, c[0], c[1]);
                    if (!standable(w, c[0], y, c[1])) continue;
                    boolean close = false;
                    for (Player p : players) if (p.getWorld() == w && p.getLocation().distanceSquared(new Location(w, c[0], y, c[1])) < 16 * 16) close = true;
                    if (close) continue;
                    best = c; bestY = y; bestScore = 99;
                    break;
                }
            }
            if (best == null) { out.complete(null); return; }
            int bx = best[0], bz = best[1], by = bestY;
            plugin.getLogger().info("Reconhecimento " + f.id() + (cave ? " (caverna)" : " (superficie)") + ": alvo " + target.getName()
                    + " -> ponto " + bx + "," + by + "," + bz + " score " + String.format("%.1f", bestScore));
            // espalha o grupo em volta do ponto, validando cada lugar
            List<Location> spots = new ArrayList<>();
            for (int rad = 0; rad <= 5 && spots.size() < count; rad++) {
                for (int ox = -rad; ox <= rad && spots.size() < count; ox++) {
                    for (int oz = -rad; oz <= rad && spots.size() < count; oz++) {
                        if (Math.max(Math.abs(ox), Math.abs(oz)) != rad || (ox + oz) % 2 != 0) continue;
                        int x = bx + ox, z = bz + oz, y = by;
                        if (cave) { // acha o chao proximo da mesma altura no tunel
                            int found = Integer.MIN_VALUE;
                            for (int d = 0; d <= 3 && found == Integer.MIN_VALUE; d++) {
                                if (standable(w, x, by - d, z)) found = by - d; else if (d > 0 && standable(w, x, by + d, z)) found = by + d;
                            }
                            if (found == Integer.MIN_VALUE) continue;
                            y = found;
                        } else y = surfaceY(w, x, z);
                        if (Math.abs(y - by) <= 3 && standable(w, x, y, z)) {
                            Location l = new Location(w, x + 0.5, y + 1, z + 0.5);
                            l.setDirection(center.toVector().subtract(l.toVector()));
                            spots.add(l);
                        }
                    }
                }
            }
            out.complete(spots.isEmpty() ? null : spots);
        }));
        return out;
    }

    /** Aviso no meio da tela (subtitulo). A barra de acao fica livre para HUDs como o DirectionHUD. */
    private static void subtitle(org.bukkit.entity.Player p, Component msg) {
        p.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), msg,
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(2200), java.time.Duration.ofMillis(400))));
    }
}
