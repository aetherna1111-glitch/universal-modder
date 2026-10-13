package luminary.packindex;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On every server start (before the world loads), rebuilds manifest.json / manifest-extra.json in the client-pack
 * folder from the files actually there. The owner only uploads or deletes files with the hosting file manager;
 * players' launchers pick the change up on their next Play. Never stops the server: any problem is logged and the
 * previously published manifests stay as they are.
 */
public final class LuminaryPackIndex implements DedicatedServerModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("luminary-packindex");

    @Override
    public void onInitializeServer() {
        Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        try {
            PackConfig cfg = PackConfig.loadOrCreate(game.resolve("config").resolve("luminary-packindex.json"));
            if (!cfg.enabled) {
                LOG.info("[Luminary] Пересборка списка файлов сборки выключена (config/luminary-packindex.json)");
                return;
            }
            Path pack = game.resolve(cfg.packDir).normalize();
            if (!pack.startsWith(game) || pack.equals(game)) {
                LOG.error("[Luminary] pack_dir должен быть папкой внутри папки сервера: {}", cfg.packDir);
                return;
            }
            if (!java.nio.file.Files.isDirectory(pack)) {
                LOG.warn("[Luminary] Папки сборки {} нет — список файлов не обновлён", pack);
                return;
            }
            long t0 = System.nanoTime();
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
            String version = now.format(DateTimeFormatter.ofPattern("yyyy.MM.dd-HHmmss"));
            PackIndexer.Outcome out = PackIndexer.run(pack, cfg,
                    game.resolve("config").resolve("luminary-packindex-cache.json"), version, now.toString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            for (String s : out.skipped) {
                LOG.info("[Luminary]   пропущено: {}", s);
            }
            if (!out.problems.isEmpty()) {
                LOG.error("[Luminary] Список файлов сборки НЕ обновлён: исправь {} файл(ов) в {} и перезапусти сервер. "
                        + "Игроки пока получают прежнюю версию.", out.problems.size(), cfg.packDir);
                for (String p : out.problems) {
                    LOG.error("[Luminary]   {}", p);
                }
                return;
            }
            String size = String.format("%.1f МБ", out.bytes / 1024.0 / 1024.0);
            if (out.written) {
                LOG.info("[Luminary] Сборка обновлена: версия {}, файлов {}, {} ({} мс){}", out.version, out.files, size, ms,
                        out.keptEmpty > 0 ? ", пустых файлов из прежнего списка: " + out.keptEmpty : "");
            } else {
                LOG.info("[Luminary] Сборка без изменений: версия {}, файлов {}, {} ({} мс)", out.version, out.files, size, ms);
            }
        } catch (Exception e) {
            LOG.error("[Luminary] Не удалось обновить список файлов сборки — игроки получают прежнюю версию", e);
        }
    }
}
