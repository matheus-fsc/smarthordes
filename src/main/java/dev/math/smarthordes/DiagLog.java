package dev.math.smarthordes;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

/**
 * Log de diagnostico do plugin.
 *
 * Tudo que o plugin escreve no console (info, avisos, erros) e os erros dele que o Paper captura
 * (evento ou tarefa que deu excecao) vao tambem para plugins/<Plugin>/logs/AAAA-MM-DD.log.
 * Os arquivos ficam 7 dias. {@link #report} junta versao, servidor, plugins, config (sem segredos)
 * e as ultimas linhas do log num arquivo so, para o dono do servidor anexar num issue.
 *
 * Mesma classe nos plugins Classforge, HardcoreRoulette e Herobrine.
 */
final class DiagLog implements Listener {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP_DAYS = 7, RECENT = 400;
    /** Chaves de config que nunca entram no relatorio. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)^(\\s*(?:[\\w-]*[-_])?(token|password|senha|secret|signature|url|host|ip|value)(?:[-_][\\w-]*)?\\s*:)\\s*\\S.*$");

    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b|\\b([0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\\b");

    private final JavaPlugin plugin;
    private final File dir;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Handler handler;
    private volatile boolean debug;
    private int errors;

    DiagLog(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "logs");
        dir.mkdirs();
        cleanup();
        SimpleFormatter fmt = new SimpleFormatter();
        handler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() < Level.INFO.intValue() && !debug) return;
                write(r.getLevel().getName(), fmt.formatMessage(r), r.getThrown());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        plugin.getLogger().addHandler(handler);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        write("INFO", "---- " + plugin.getName() + " " + plugin.getPluginMeta().getVersion() + " ligado em "
                + Bukkit.getName() + " " + Bukkit.getVersion() + ", Java " + System.getProperty("java.version"), null);
    }

    /** Liga mensagens de depuracao (config: debug: true). */
    void setDebug(boolean on) { debug = on; }

    boolean debug() { return debug; }

    /** Linha de depuracao: so grava com debug ligado. */
    void debug(String msg) { if (debug) write("DEBUG", msg, null); }

    void close() {
        write("INFO", "---- " + plugin.getName() + " desligado", null);
        plugin.getLogger().removeHandler(handler);
    }

    // ---------- erros que o Paper captura (eventos e tarefas deste plugin) ----------

    @EventHandler
    public void onServerException(com.destroystokyo.paper.event.server.ServerExceptionEvent e) {
        if (!(e.getException() instanceof com.destroystokyo.paper.exception.ServerPluginException pe)) return;
        Plugin who = pe.getResponsiblePlugin();
        if (who != plugin) return;
        Throwable cause = pe.getCause() != null ? pe.getCause() : pe;
        write("ERROR", pe.getMessage(), cause);
    }

    // ---------- arquivo ----------

    private synchronized void write(String level, String msg, Throwable t) {
        if ("SEVERE".equals(level) || "ERROR".equals(level)) errors++;
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(LocalDateTime.now().format(TIME)).append(' ').append(level).append("] ").append(msg).append('\n');
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        String text = sb.toString();
        for (String line : text.split("\n")) {
            recent.addLast(line);
            if (recent.size() > RECENT) recent.removeFirst();
        }
        try {
            Files.writeString(new File(dir, LocalDate.now().format(DAY) + ".log").toPath(), text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private void cleanup() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null) return;
        LocalDate limit = LocalDate.now().minusDays(KEEP_DAYS);
        for (File f : files) {
            try {
                if (LocalDate.parse(f.getName().replace(".log", ""), DAY).isBefore(limit)) f.delete();
            } catch (Exception ignored) { }
        }
    }

    // ---------- relatorio ----------

    /** Gera plugins/<Plugin>/reports/report-<data>.txt e devolve o arquivo. */
    synchronized File report(CommandSender by, List<String> extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Relatorio de ").append(plugin.getName()).append('\n');
        sb.append("Gerado em: ").append(LocalDateTime.now()).append(" por ").append(by == null ? "console" : by.getName()).append("\n\n");
        sb.append("## Ambiente\n");
        sb.append("Plugin: ").append(plugin.getName()).append(' ').append(plugin.getPluginMeta().getVersion()).append('\n');
        sb.append("Servidor: ").append(Bukkit.getName()).append(' ').append(Bukkit.getVersion()).append('\n');
        sb.append("Minecraft: ").append(Bukkit.getMinecraftVersion()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("Sistema: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
        Runtime rt = Runtime.getRuntime();
        sb.append("Memoria: ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" / ").append(rt.maxMemory() >> 20).append(" MB\n");
        sb.append("Jogadores online: ").append(Bukkit.getOnlinePlayers().size()).append('\n');
        sb.append("TPS (1m, 5m, 15m): ");
        for (double tps : Bukkit.getTPS()) sb.append(String.format(Locale.ROOT, "%.1f ", tps));
        sb.append("\nErros nesta sessao: ").append(errors).append("\n\n");

        sb.append("## Plugins\n");
        for (Plugin p : Bukkit.getPluginManager().getPlugins())
            sb.append(p.isEnabled() ? "  " : "  (desligado) ").append(p.getName()).append(' ').append(p.getPluginMeta().getVersion()).append('\n');
        sb.append('\n');

        if (extra != null && !extra.isEmpty()) {
            sb.append("## Estado\n");
            for (String l : extra) sb.append(l).append('\n');
            sb.append('\n');
        }

        sb.append("## config.yml (segredos removidos)\n");
        File cfg = new File(plugin.getDataFolder(), "config.yml");
        if (cfg.isFile()) {
            for (String line : Files.readAllLines(cfg.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("#")) continue;
                sb.append(SECRET.matcher(line).replaceAll("$1 <removido>")).append('\n');
            }
        } else sb.append("(sem config.yml)\n");
        sb.append('\n');

        sb.append("## Ultimas linhas do log\n");
        for (String l : recent) sb.append(IP.matcher(l).replaceAll("<ip>")).append('\n');

        File out = new File(new File(plugin.getDataFolder(), "reports"), "report-" + LocalDateTime.now().format(STAMP) + ".txt");
        out.getParentFile().mkdirs();
        Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
        write("INFO", "Relatorio gerado: " + out.getPath(), null);
        return out;
    }
}
