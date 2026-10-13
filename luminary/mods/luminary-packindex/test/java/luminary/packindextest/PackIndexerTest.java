package luminary.packindextest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import luminary.packindex.PackConfig;
import luminary.packindex.PackIndexer;

/** Plain-Java tests (no JUnit): `java ... PackIndexerTest` runs the scenarios; `... cli <packDir> <cache>` indexes a folder. */
public final class PackIndexerTest {
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static Path write(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    static String pyStr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static java.util.List<String> paths(JsonObject m) {
        java.util.List<String> out = new java.util.ArrayList<>();
        m.getAsJsonArray("files").forEach(e -> out.add(e.getAsJsonObject().get("path").getAsString()));
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("cli")) {
            PackIndexer.Outcome o = PackIndexer.run(Path.of(args[1]), PackConfig.defaults(), Path.of(args[2]),
                    args.length > 3 ? args[3] : "cli-version", "2026-10-13T00:00:00");
            System.out.println("written=" + o.written + " version=" + o.version + " files=" + o.files + " bytes=" + o.bytes
                    + " keptEmpty=" + o.keptEmpty + " problems=" + o.problems + " skipped=" + o.skipped.size());
            return;
        }
        Path tmp = Files.createTempDirectory("packindex-test");
        try {
            Path pack = tmp.resolve("client-pack");
            Path cache = tmp.resolve("cache.json");
            PackConfig cfg = PackConfig.defaults();
            write(pack, "mods/a.jar", "A");
            write(pack, "mods/Some Mod 1.2.jar", "S");
            write(pack, "config/sub/x.toml", "x=1");
            write(pack, "resourcepacks/r.zip", "R");
            write(pack, "mods/upload.jar.part", "half");
            write(pack, "config/.hidden.json", "{}");
            write(pack, "launcher/launcher.json", "{}"); // the self-update folder is never part of the pack

            PackIndexer.Outcome o = PackIndexer.run(pack, cfg, cache, "v1", "t1");
            check(o.problems.isEmpty() && o.written, "first run writes: " + o.problems);
            JsonObject main = json(pack.resolve("manifest.json"));
            JsonObject extra = json(pack.resolve("manifest-extra.json"));
            check(paths(main).equals(java.util.List.of("config/sub/x.toml", "mods/Some Mod 1.2.jar", "mods/a.jar")), "main files " + paths(main));
            check(paths(extra).equals(java.util.List.of("resourcepacks/r.zip")), "extra files " + paths(extra));
            check(main.getAsJsonArray("extra_manifests").get(0).getAsString().equals("manifest-extra.json"), "extra named");
            check(main.getAsJsonObject("folders").get("mods").getAsString().equals("mirror"), "mods mirror");
            check(main.get("version").getAsString().equals("v1"), "version");
            check(!Files.exists(pack.resolve(".manifest.json.writing")), "temp file gone");
            check(o.skipped.stream().anyMatch(s -> s.contains("upload.jar.part")) && o.skipped.stream().anyMatch(s -> s.contains(".hidden.json")), "skipped " + o.skipped);
            String sha = main.getAsJsonArray("files").get(2).getAsJsonObject().get("sha256").getAsString();
            check(sha.equals("559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd"), "sha256(\"A\")");

            // unchanged: nothing rewritten, version kept
            String before = Files.readString(pack.resolve("manifest.json"));
            o = PackIndexer.run(pack, cfg, cache, "v2", "t2");
            check(!o.written && "v1".equals(o.version) && before.equals(Files.readString(pack.resolve("manifest.json"))), "unchanged run");

            // a changed file publishes a new version
            write(pack, "config/sub/x.toml", "x=2");
            o = PackIndexer.run(pack, cfg, cache, "v3", "t3");
            check(o.written && json(pack.resolve("manifest.json")).get("version").getAsString().equals("v3"), "change -> v3");

            // a name launchers reject stops the update and keeps the published manifest
            before = Files.readString(pack.resolve("manifest.json"));
            write(pack, "mods/CON.jar", "bad");
            o = PackIndexer.run(pack, cfg, cache, "v4", "t4");
            check(!o.written && o.problems.size() == 1 && o.problems.get(0).contains("CON.jar"), "CON.jar " + o.problems);
            check(before.equals(Files.readString(pack.resolve("manifest.json"))), "manifest kept on problems");
            Files.delete(pack.resolve("mods/CON.jar"));
            write(pack, "mods/A.JAR", "dup");
            o = PackIndexer.run(pack, cfg, cache, "v4", "t4");
            check(!o.written && o.problems.stream().anyMatch(s -> s.contains("регистром")), "case duplicate " + o.problems);
            Files.delete(pack.resolve("mods/A.JAR"));

            // empty files from the previous list survive (servarium cannot store them)
            String m = Files.readString(pack.resolve("manifest.json")).replace("\"files\": [",
                    "\"files\": [\n    {\"path\": \"config/empty.cfg\", \"sha256\": \"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\", \"size\": 0},");
            Files.writeString(pack.resolve("manifest.json"), m);
            o = PackIndexer.run(pack, cfg, cache, "v5", "t5");
            check(o.keptEmpty == 1 && paths(json(pack.resolve("manifest.json"))).contains("config/empty.cfg"), "empty kept " + o.keptEmpty);
            o = PackIndexer.run(pack, cfg, cache, "v6", "t6");
            check(!o.written, "empty-file list stable");

            // removing a file republishes without it
            Files.delete(pack.resolve("mods/a.jar"));
            o = PackIndexer.run(pack, cfg, cache, "v7", "t7");
            check(o.written && !paths(json(pack.resolve("manifest.json"))).contains("mods/a.jar"), "removal");

            // bad config never writes
            PackConfig bad = PackConfig.defaults();
            bad.folders.put("saves", "update");
            o = PackIndexer.run(pack, bad, cache, "v8", "t8");
            check(!o.written && o.problems.stream().anyMatch(s -> s.contains("saves")), "protected folder " + o.problems);
            PackConfig badMode = PackConfig.defaults();
            badMode.folders.put("mods", "wipe");
            o = PackIndexer.run(pack, badMode, cache, "v8", "t8");
            check(!o.written && !o.problems.isEmpty(), "bad mode");

            // links are refused (servarium does not serve them)
            try {
                Files.createSymbolicLink(pack.resolve("mods/link.jar"), pack.resolve("mods/Some Mod 1.2.jar"));
                o = PackIndexer.run(pack, cfg, cache, "v9", "t9");
                check(!o.written && o.problems.stream().anyMatch(s -> s.contains("link.jar")), "symlink " + o.problems);
                Files.delete(pack.resolve("mods/link.jar"));
            } catch (UnsupportedOperationException ignored) {
                // no symlinks on this file system
            }

            // non-ASCII names (Cyrillic, '§'), created outside Java so this also runs where the JVM has an ASCII locale
            Process py = new ProcessBuilder("python3", "-c",
                    "import os; os.chdir(" + pyStr(pack.toString()) + "); "
                            + "open('mods/\\u041d\\u043e\\u0432\\u044b\\u0439 \\u043c\\u0438\\u0440.jar','w').write('N'); "
                            + "open('resourcepacks/\\u00a7c pack+1.zip','w').write('P')").inheritIO().start();
            if (py.waitFor() == 0) {
                o = PackIndexer.run(pack, cfg, cache, "v10", "t10");
                check(o.written && o.problems.isEmpty(), "non-ascii run " + o.problems);
                check(paths(json(pack.resolve("manifest.json"))).contains("mods/\u041d\u043e\u0432\u044b\u0439 \u043c\u0438\u0440.jar"), "cyrillic name kept exactly");
                check(paths(json(pack.resolve("manifest-extra.json"))).contains("resourcepacks/\u00a7c pack+1.zip"), "section-sign name kept exactly");
                check(!PackIndexer.run(pack, cfg, cache, "v11", "t11").written, "non-ascii names stable");
            }

            // config file round trip
            Path cfgFile = tmp.resolve("config/luminary-packindex.json");
            PackConfig created = PackConfig.loadOrCreate(cfgFile);
            check(Files.exists(cfgFile) && created.folders.equals(PackConfig.defaults().folders), "config created");
            check(PackConfig.loadOrCreate(cfgFile).extraFolders.equals(PackConfig.defaults().extraFolders), "config reread");
            System.out.println("PackIndexerTest: " + checks + " checks passed");
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
