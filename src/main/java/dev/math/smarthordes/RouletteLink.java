package dev.math.smarthordes;

import dev.math.hcroulette.api.HardcoreRouletteAPI;
import dev.math.hcroulette.api.RunStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Ponte com o HardcoreRoulette (API oficial). So e carregada quando ele esta ligado.
 * Run nova: a horda do mundo antigo e descartada sem anuncio.
 */
final class RouletteLink implements Listener {

    private final SmartHordes plugin;

    private RouletteLink(SmartHordes plugin) { this.plugin = plugin; }

    static void register(SmartHordes plugin) {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        if (api == null || api.apiVersion() < 2) throw new LinkageError("API do HardcoreRoulette ausente ou antiga");
        Bukkit.getPluginManager().registerEvents(new RouletteLink(plugin), plugin);
    }

    @EventHandler
    public void onRunStart(RunStartEvent e) {
        if (plugin.invasion != null) plugin.invasion.abortSilently();
    }

    static World currentWorld() {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        return api == null ? null : api.currentWorld();
    }

    static long runAliveSeconds() {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        return api == null ? 0 : api.runAliveSeconds();
    }

    static boolean isVoting() {
        HardcoreRouletteAPI api = HardcoreRouletteAPI.get();
        return api != null && api.isVoting();
    }
}
