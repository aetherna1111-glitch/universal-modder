package luminary.packindex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Builds the pack manifests from the files in the client-pack folder, with the same rules as the launcher
 * (luminary_app/modpack.py) and make_manifest.py, so a manifest it writes is one every launcher accepts.
 *
 * <ul>
 * <li>Hidden files and folders, links, *.part / *.tmp / *.crdownload and Thumbs.db are not published (servarium does
 * not serve hidden files or links, and the others are uploads in progress).</li>
 * <li>A name a launcher would reject (CON.jar, a trailing space or dot, ':' or '\', two names differing only in
 * case, a file over 2 GB) stops the update: the previous manifests stay as they are and the problems are logged.</li>
 * <li>Empty files listed in the previous manifest are kept even though they are not in the folder: servarium
 * refuses to store empty files, and launchers create them locally.</li>
 * <li>Nothing is rewritten when the file list is unchanged, so players are not offered an update for nothing.</li>
 * </ul>
 * Hosting often runs the JVM without a UTF-8 locale (sun.jnu.encoding=ANSI_X3.4-1968). Java then cannot turn a
 * non-ASCII name (Cyrillic, '§') into a Path, and Path.toString() of a listed file is lossy. So names are only ever
 * read from paths the directory listing returned, via toUri() (which keeps the raw bytes, percent-encoded) and decoded
 * as UTF-8, and no Path is ever built from a manifest entry.
 */
public final class PackIndexer {
    public static final Set<String> MODES = Set.of("mirror", "update");
    static final Set<String> WIN_RESERVED = new HashSet<>(List.of("con", "prn", "aux", "nul"));
    static final Set<String> PROTECTED = Set.of("saves", "versions", "libraries", "assets", "logs", "crash-reports",
            "screenshots", "natives", "bin", "backups", "runtime", "jre", ".luminary_staging", "launcher");
    static final Set<String> SKIP_NAMES = Set.of("thumbs.db", "desktop.ini", ".ds_store");
    static final List<String> SKIP_SUFFIXES = List.of(".part", ".tmp", ".crdownload");
    static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    static final Pattern FOLDER_RE = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_\\-]*$");
    static final Pattern MANIFEST_NAME_RE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.\\-]*\\.json$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static {
        for (int i = 1; i <= 9; i++) {
            WIN_RESERVED.add("com" + i);
            WIN_RESERVED.add("lpt" + i);
        }
    }

    public record Entry(String path, String sha256, long size) {
    }

    public static final class Outcome {
        public boolean written;
        public String version;
        public int files;
        public long bytes;
        public int keptEmpty;
        public final List<String> problems = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
    }

    private PackIndexer() {
    }

    /** Index packDir and (re)write the manifests if the file list changed. */
    public static Outcome run(Path packDir, PackConfig cfg, Path cacheFile, String version, String updatedIso)
            throws IOException {
        Outcome out = new Outcome();
        checkConfig(cfg, out);
        if (!out.problems.isEmpty()) {
            return out;
        }
        Map<String, Entry> previous = readPrevious(packDir, cfg);
        HashCache cache = HashCache.load(cacheFile);
        Map<String, String> seenLower = new HashMap<>();
        Set<String> present = new HashSet<>(); // every name in the folders, published or not (lower case)
        List<Entry> main = scan(packDir, cfg.folders, cache, seenLower, present, out);
        List<Entry> extra = scan(packDir, cfg.extraFolders, cache, seenLower, present, out);
        // keep empty files a previous manifest listed: they can never be uploaded to servarium
        for (Entry e : previous.values()) {
            if (e.size() != 0 || present.contains(e.path().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String top = e.path().split("/", 2)[0];
            List<Entry> target = cfg.folders.containsKey(top) ? main : cfg.extraFolders.containsKey(top) ? extra : null;
            if (target != null && validPath(e.path(), top) == null) {
                target.add(e);
                seenLower.put(e.path().toLowerCase(Locale.ROOT), e.path());
                out.keptEmpty++;
            }
        }
        cache.save();
        if (!out.problems.isEmpty()) {
            return out;
        }
        main.sort(Comparator.comparing(Entry::path));
        extra.sort(Comparator.comparing(Entry::path));
        out.files = main.size() + extra.size();
        out.bytes = main.stream().mapToLong(Entry::size).sum() + extra.stream().mapToLong(Entry::size).sum();

        boolean useExtra = !cfg.extraFolders.isEmpty();
        JsonObject prevMain = readJson(packDir.resolve(cfg.manifestName));
        JsonObject prevExtra = useExtra ? readJson(packDir.resolve(cfg.extraManifestName)) : null;
        if (prevMain != null && sameFiles(prevMain, main) && sameFolders(prevMain, cfg.folders)
                && sameExtras(prevMain, useExtra ? cfg.extraManifestName : null)
                && (!useExtra || (prevExtra != null && sameFiles(prevExtra, extra) && sameFolders(prevExtra, cfg.extraFolders)))) {
            out.version = prevMain.has("version") ? prevMain.get("version").getAsString() : "?";
            return out; // unchanged: keep the published files byte for byte
        }
        if (useExtra) {
            writeAtomically(packDir.resolve(cfg.extraManifestName), manifest(version, updatedIso, cfg.extraFolders, extra, null));
        }
        writeAtomically(packDir.resolve(cfg.manifestName),
                manifest(version, updatedIso, cfg.folders, main, useExtra ? cfg.extraManifestName : null));
        out.written = true;
        out.version = version;
        return out;
    }

    // ------------------------------------------------------------------ validation (mirrors modpack.py)

    static void checkConfig(PackConfig cfg, Outcome out) {
        Set<String> all = new HashSet<>();
        for (Map<String, String> group : List.of(cfg.folders, cfg.extraFolders)) {
            for (Map.Entry<String, String> f : group.entrySet()) {
                String name = f.getKey();
                if (!FOLDER_RE.matcher(name).matches() || name.length() > 64 || PROTECTED.contains(name.toLowerCase(Locale.ROOT))
                        || WIN_RESERVED.contains(name.split("\\.")[0].toLowerCase(Locale.ROOT))) {
                    out.problems.add("config: недопустимая папка " + name);
                }
                if (!MODES.contains(f.getValue())) {
                    out.problems.add("config: неизвестный режим " + f.getValue() + " у папки " + name);
                }
                if (!all.add(name.toLowerCase(Locale.ROOT))) {
                    out.problems.add("config: папка указана дважды: " + name);
                }
            }
        }
        if (cfg.folders.isEmpty()) {
            out.problems.add("config: пустой список folders");
        }
        for (String n : List.of(cfg.manifestName, cfg.extraManifestName)) {
            if (!MANIFEST_NAME_RE.matcher(n).matches()) {
                out.problems.add("config: недопустимое имя манифеста " + n);
            }
        }
        if (cfg.manifestName.equalsIgnoreCase(cfg.extraManifestName)) {
            out.problems.add("config: у двух манифестов одно имя");
        }
    }

    /** null if the launcher accepts this manifest path, else the reason it would reject the whole manifest. */
    static String validPath(String path, String folder) {
        if (path.isEmpty() || path.length() > 400 || path.indexOf('\0') >= 0) return "пустой или слишком длинный путь";
        if (path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return "недопустимые символы в пути";
        String[] parts = path.split("/", -1);
        if (parts.length < 2 || !parts[0].equals(folder)) return "недопустимый путь";
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return "недопустимый путь";
            String stem = part.split("\\.", -1)[0].toLowerCase(Locale.ROOT);
            if (WIN_RESERVED.contains(stem) || part.endsWith(" ") || part.endsWith(".")) return "недопустимое имя файла";
        }
        return null;
    }

    // ------------------------------------------------------------------ scanning

    private static List<Entry> scan(Path packDir, Map<String, String> folders, HashCache cache,
                                    Map<String, String> seenLower, Set<String> present, Outcome out) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String folder : folders.keySet()) {
            Path root = packDir.resolve(folder);
            if (Files.isSymbolicLink(root)) {
                out.problems.add(folder + ": ссылка на папку (положи саму папку)");
                continue;
            }
            if (!Files.isDirectory(root)) {
                out.skipped.add(folder + "/ (папки нет)");
                continue;
            }
            walk(packDir, root, folder, cache, seenLower, present, out, entries);
        }
        return entries;
    }

    private static void walk(Path packDir, Path dir, String folder, HashCache cache, Map<String, String> seenLower,
                             Set<String> present, Outcome out, List<Entry> entries) throws IOException {
        List<Path> listed;
        try (Stream<Path> s = Files.list(dir)) {
            listed = new ArrayList<>(s.toList());
        }
        List<Map.Entry<String, Path>> children = new ArrayList<>();
        for (Path p : listed) {
            String rel = relName(packDir, p);
            if (rel == null) {
                out.problems.add(p.toUri().getRawPath() + ": имя файла не в UTF-8");
                continue;
            }
            children.add(Map.entry(rel, p));
        }
        children.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Path> child : children) {
            String rel = child.getKey();
            Path p = child.getValue();
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            present.add(rel.toLowerCase(Locale.ROOT));
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                out.problems.add(rel + ": ссылка (положи сам файл или папку)");
                continue;
            }
            if (name.startsWith(".")) {
                out.skipped.add(rel + (attrs.isDirectory() ? "/" : "") + " (скрытое: servarium такое не раздаёт)");
                continue;
            }
            if (attrs.isDirectory()) {
                walk(packDir, p, folder, cache, seenLower, present, out, entries);
                continue;
            }
            if (!attrs.isRegularFile()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (SKIP_NAMES.contains(lower) || SKIP_SUFFIXES.stream().anyMatch(lower::endsWith)) {
                out.skipped.add(rel + " (временный файл)");
                continue;
            }
            String why = validPath(rel, folder);
            if (why != null) {
                out.problems.add(rel + ": " + why + " (лаунчер отклонит такой список)");
                continue;
            }
            String key = rel.toLowerCase(Locale.ROOT);
            if (seenLower.containsKey(key)) {
                out.problems.add(rel + ": отличается от " + seenLower.get(key) + " только регистром");
                continue;
            }
            if (attrs.size() > MAX_FILE_SIZE) {
                out.problems.add(rel + ": файл больше 2 ГБ");
                continue;
            }
            seenLower.put(key, rel);
            entries.add(new Entry(rel, cache.sha256(rel, p, attrs), attrs.size()));
        }
    }

    /**
     * The path of a listed file relative to packDir, decoded from the raw bytes as UTF-8 ('/'-separated), or null
     * if the name is not valid UTF-8. Works whatever the JVM's file-name encoding is (see the class comment).
     */
    static String relName(Path packDir, Path p) {
        String base = packDir.toUri().getRawPath();
        if (!base.endsWith("/")) base += "/";
        String raw = p.toUri().getRawPath();
        if (!raw.startsWith(base)) return null;
        raw = raw.substring(base.length());
        if (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                bytes.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ manifests

    private static Map<String, Entry> readPrevious(Path packDir, PackConfig cfg) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (String name : List.of(cfg.manifestName, cfg.extraManifestName)) {
            JsonObject o = readJson(packDir.resolve(name));
            if (o == null || !o.has("files")) continue;
            for (JsonElement el : o.getAsJsonArray("files")) {
                try {
                    JsonObject f = el.getAsJsonObject();
                    Entry e = new Entry(f.get("path").getAsString(), f.get("sha256").getAsString(), f.get("size").getAsLong());
                    m.put(e.path(), e);
                } catch (RuntimeException ignored) {
                    // a damaged previous entry is simply not carried over
                }
            }
        }
        return m;
    }

    static JsonObject readJson(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean sameFiles(JsonObject manifest, List<Entry> entries) {
        if (!manifest.has("files")) return false;
        Set<Entry> before = new HashSet<>();
        try {
            for (JsonElement el : manifest.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                before.add(new Entry(f.get("path").getAsString(), f.get("sha256").getAsString(), f.get("size").getAsLong()));
            }
        } catch (RuntimeException e) {
            return false;
        }
        return before.equals(new HashSet<>(entries));
    }

    private static boolean sameFolders(JsonObject manifest, Map<String, String> folders) {
        if (!manifest.has("folders") || !manifest.get("folders").isJsonObject()) return false;
        Map<String, String> before = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : manifest.getAsJsonObject("folders").entrySet()) {
            before.put(e.getKey(), e.getValue().getAsString());
        }
        return before.equals(folders);
    }

    private static boolean sameExtras(JsonObject manifest, String extraName) {
        JsonArray before = manifest.has("extra_manifests") ? manifest.getAsJsonArray("extra_manifests") : new JsonArray();
        List<String> names = new ArrayList<>();
        before.forEach(e -> names.add(e.getAsString()));
        return names.equals(extraName == null ? List.of() : List.of(extraName));
    }

    private static String manifest(String version, String updated, Map<String, String> folders, List<Entry> files,
                                   String extraName) {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        o.addProperty("updated", updated);
        o.add("folders", GSON.toJsonTree(folders));
        JsonArray arr = new JsonArray();
        for (Entry e : files) {
            JsonObject f = new JsonObject();
            f.addProperty("path", e.path());
            f.addProperty("sha256", e.sha256());
            f.addProperty("size", e.size());
            arr.add(f);
        }
        o.add("files", arr);
        if (extraName != null) {
            JsonArray x = new JsonArray();
            x.add(extraName);
            o.add("extra_manifests", x);
        }
        return GSON.toJson(o);
    }

    /** Hidden temp name (servarium does not serve it), then an atomic replace: readers see old or new, never half. */
    static void writeAtomically(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling("." + target.getFileName() + ".writing");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ hash cache

    static final class HashCache {
        private final Path file;
        private final Map<String, Object[]> data = new HashMap<>();
        private final Set<String> used = new HashSet<>();
        private boolean dirty;

        private HashCache(Path file) {
            this.file = file;
        }

        static HashCache load(Path file) {
            HashCache c = new HashCache(file);
            JsonObject o = file == null ? null : readJson(file);
            if (o != null) {
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    try {
                        JsonArray a = e.getValue().getAsJsonArray();
                        c.data.put(e.getKey(), new Object[]{a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsString()});
                    } catch (RuntimeException ignored) {
                        // stale or damaged entry: re-hash
                    }
                }
            }
            return c;
        }

        String sha256(String rel, Path p, BasicFileAttributes attrs) throws IOException {
            used.add(rel);
            long size = attrs.size();
            long mtime = attrs.lastModifiedTime().toMillis();
            Object[] hit = data.get(rel);
            if (hit != null && Objects.equals(hit[0], size) && Objects.equals(hit[1], mtime)) {
                return (String) hit[2];
            }
            String digest = hash(p);
            data.put(rel, new Object[]{size, mtime, digest});
            dirty = true;
            return digest;
        }

        void save() throws IOException {
            if (file == null) return;
            if (data.keySet().retainAll(used)) dirty = true;
            if (!dirty) return;
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Object[]> e : data.entrySet()) {
                JsonArray a = new JsonArray();
                a.add((Long) e.getValue()[0]);
                a.add((Long) e.getValue()[1]);
                a.add((String) e.getValue()[2]);
                o.add(e.getKey(), a);
            }
            Files.createDirectories(file.getParent());
            writeAtomically(file, new Gson().toJson(o));
        }
    }

    static String hash(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
