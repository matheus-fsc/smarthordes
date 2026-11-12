package dev.math.smarthordes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * SmartHordes: hordas de zumbis que cavam, constroem e cercam, invasao noturna avisada a tarde,
 * horda do Nether e arena de teste. Funciona sozinho; com o HardcoreRoulette usa o mundo da run
 * e o tempo vivo da run pela API dele, e limpa a horda quando uma run nova comeca.
 */
public final class SmartHordes extends JavaPlugin {

    Invasion invasion;
    NetherHorde netherHorde;
    Arena arena;
    DiagLog diag;
    private boolean roulette;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateFromRoulette();
        diag = new DiagLog(this);
        diag.setDebug(getConfig().getBoolean("debug", false));
        if (getConfig().getBoolean("arena-mode", false)) { // servidor de teste: so a arena da horda
            arena = new Arena(this);
            getLogger().info("MODO ARENA: so a arena de teste esta ativa");
            return;
        }
        netherHorde = new NetherHorde(this);
        invasion = new Invasion(this);
        getServer().getPluginManager().registerEvents(invasion, this);
        roulette = Bukkit.getPluginManager().isPluginEnabled("HardcoreRoulette");
        if (roulette) {
            try { RouletteLink.register(this); }
            catch (LinkageError old) { roulette = false; getLogger().warning("HardcoreRoulette sem a API v2: rodando sozinho."); }
        }
        getLogger().info("Hordas ligadas" + (roulette ? " (integrado ao HardcoreRoulette)" : " (sozinho)"));
    }

    @Override
    public void onDisable() {
        if (invasion != null) invasion.abortSilently();
        if (diag != null) diag.close();
    }

    /**
     * Primeira vez: copia as secoes da horda do config.yml do HardcoreRoulette (antes a horda morava la),
     * para quem atualiza nao perder o balanceamento.
     */
    private void migrateFromRoulette() {
        if (getConfig().getBoolean("migrated", false)) return;
        File old = new File(getDataFolder().getParentFile(), "HardcoreRoulette/config.yml");
        if (old.isFile()) {
            YamlConfiguration hr = YamlConfiguration.loadConfiguration(old);
            int n = 0;
            for (String key : List.of("invasion", "nether-horde", "horde", "arena-mode", "arena-slots")) {
                if (!hr.contains(key)) continue;
                Object v = hr.get(key);
                if (v instanceof ConfigurationSection sec) {
                    for (String k : sec.getKeys(true)) if (!sec.isConfigurationSection(k)) getConfig().set(key + "." + k, sec.get(k));
                } else getConfig().set(key, v);
                n++;
            }
            if (n > 0) getLogger().info("Config da horda copiada do HardcoreRoulette (" + n + " secoes).");
        }
        getConfig().set("migrated", true);
        saveConfig();
    }

    // ---------------------------------------------------------------- mundo e tempo (com ou sem HardcoreRoulette)

    static String baseOf(String worldName) {
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - 7);
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - 8);
        return worldName;
    }

    /** Mundo onde a horda acontece: o da run (HardcoreRoulette) ou o mundo principal. */
    World currentWorld() {
        if (roulette) {
            World w = RouletteLink.currentWorld();
            if (w != null) return w;
        }
        String main = getConfig().getString("world", "");
        World w = main.isEmpty() ? null : Bukkit.getWorld(main);
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    /** Tempo vivo da run em segundos (sem HardcoreRoulette: maior tempo desde a ultima morte entre os online). */
    long runAliveSeconds() {
        if (roulette) return RouletteLink.runAliveSeconds();
        long m = 0;
        for (Player p : Bukkit.getOnlinePlayers()) m = Math.max(m, p.getStatistic(Statistic.TIME_SINCE_DEATH) / 20L);
        return m;
    }

    /** O HardcoreRoulette esta votando o proximo mundo (sem horda nesse meio tempo). */
    boolean isVoting() { return roulette && RouletteLink.isVoting(); }

    List<Player> humans() { return new ArrayList<>(Bukkit.getOnlinePlayers()); }

    // ---------------------------------------------------------------- comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "arena" -> {
                if (arena == null) sender.sendMessage("arena-mode desligado neste servidor");
                else arena.command(sender, args);
            }
            case "horda" -> {
                if (arena != null) { sender.sendMessage("Servidor em arena-mode: use /arena."); return true; }
                if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                    sender.sendMessage(Component.text("Horda: " + invasion.status()
                            + "  |  /horda zumbis [qtd] [alvo] [construtores]  |  /horda nether [alvo]  |  /horda parar  |  /horda report",
                            NamedTextColor.GOLD));
                } else if (args[0].equalsIgnoreCase("report")) {
                    try {
                        File f = diag.report(sender, List.of(
                                "Integrado ao HardcoreRoulette: " + roulette,
                                "Mundo da horda: " + currentWorld().getName() + ", tempo vivo: " + runAliveSeconds() + " s",
                                "Horda: " + invasion.status()));
                        sender.sendMessage("Relatorio salvo em " + f.getPath() + ". Anexe esse arquivo no issue do GitHub.");
                    } catch (java.io.IOException ex) { sender.sendMessage("Falha ao gerar o relatorio: " + ex.getMessage()); }
                } else if (args[0].equalsIgnoreCase("nether")) {
                    Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player sp ? sp : null);
                    if (t == null || t.getWorld().getEnvironment() != World.Environment.NETHER) {
                        sender.sendMessage(Component.text("O alvo precisa estar no Nether.", NamedTextColor.RED)); return true;
                    }
                    netherHorde.forceStart(t);
                } else if (args[0].equalsIgnoreCase("parar")) {
                    invasion.finish(Component.text("A horda foi encerrada por " + sender.getName() + ".", NamedTextColor.GRAY));
                } else {
                    Invasion.Faction f = Invasion.Faction.of(args[0]);
                    if (f != Invasion.Faction.ZUMBIS) {
                        sender.sendMessage(Component.text("Use /horda zumbis. Piratas e guilda (bots) foram descontinuados.", NamedTextColor.RED));
                        return true;
                    }
                    Integer n = null;
                    if (args.length > 1) try { n = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
                    Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : null;
                    if (args.length > 2 && target == null) { sender.sendMessage(Component.text("Jogador " + args[2] + " nao esta online.", NamedTextColor.RED)); return true; }
                    boolean builders = args.length > 3 && args[3].equalsIgnoreCase("construtores");
                    invasion.start(f, n, sender, target, builders);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("horda") && a.length == 1) return List.of("status", "zumbis", "nether", "parar", "report");
        return null;
    }
}
