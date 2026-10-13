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
    static final List<String> SKIP_SUFFIXES = List.of(".part", ".tmp", ".crdownload", ".filepart"); // FIX: WinSCP
    /** Names Windows refuses besides CON/PRN/AUX/NUL/COM1-9/LPT1-9 (superscript digits count as digits there). */
    static final Set<String> WIN_RESERVED_EXTRA = Set.of("com\u00b9", "com\u00b2", "com\u00b3", "lpt\u00b9", "lpt\u00b2",
            "lpt\u00b3", "conin$", "conout$");
    static final String WIN_BAD_CHARS = "<>\"|?*";
    static final int MAX_FOLDERS = 64;
    static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
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
        /** When problems stop the update: published files the folder no longer matches (players fail to fetch them). */
        public final List<String> stale = new ArrayList<>();
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
        // FIX: the published manifests are the only record of the empty files; do not drop them silently
        for (String name : List.of(cfg.manifestName, cfg.extraManifestName)) {
            Path f = packDir.resolve(name);
            JsonObject o = readJson(f);
            if (Files.exists(f, LinkOption.NOFOLLOW_LINKS) && (o == null || !o.has("files") || !o.get("files").isJsonArray())) {
                out.problems.add(name + ": прежний список не читается, пустые файлы из него не перенести. Верни рабочую "
                        + "копию или удали " + name + " (пустые файлы пропадут из сборки)");
            }
        }
        if (!out.problems.isEmpty()) {
            return out;
        }
        HashCache cache = HashCache.load(cacheFile);
        Map<String, String> seenLower = new HashMap<>();
        Set<String> present = new HashSet<>(); // every name in the folders, published or not (case key)
        Set<String> presentFiles = new HashSet<>(); // the ones that are not folders
        List<Entry> main = scan(packDir, cfg.folders, cache, seenLower, present, presentFiles, out);
        List<Entry> extra = scan(packDir, cfg.extraFolders, cache, seenLower, present, presentFiles, out);
        // keep empty files a previous manifest listed: they can never be uploaded to servarium
        for (Entry e : previous.values()) {
            String lower = caseKey(e.path());
            if (e.size() != 0 || present.contains(lower) || seenLower.containsKey(lower)) { // FIX: no case duplicates
                continue;
            }
            if (ancestorIsFile(lower, presentFiles)) { // config/x became a file: config/x/y can no longer exist
                out.skipped.add(e.path() + " (пустой файл из прежнего списка: на месте его папки теперь файл)");
                continue;
            }
            String top = e.path().split("/", 2)[0];
            List<Entry> target = cfg.folders.containsKey(top) ? main : cfg.extraFolders.containsKey(top) ? extra : null;
            if (target != null && validPath(e.path(), top) == null) {
                target.add(new Entry(e.path(), EMPTY_SHA, 0)); // FIX: an empty file always has this hash
                seenLower.put(lower, e.path());
                out.keptEmpty++;
            }
        }
        cache.save();
        // a folder that is gone or has no files left, while the published list had files in it: never publish that,
        // or every player's copy is moved to .backup (a failed upload or unpack looks exactly like this)
        for (Map<String, String> group : List.of(cfg.folders, cfg.extraFolders)) {
            for (String folder : group.keySet()) {
                String prefix = caseKey(folder + "/");
                long had = previous.values().stream().filter(e -> e.size() > 0 && caseKey(e.path()).startsWith(prefix)).count();
                long now = java.util.stream.Stream.concat(main.stream(), extra.stream())
                        .filter(e -> e.size() > 0 && caseKey(e.path()).startsWith(prefix)).count();
                if (had > 0 && now == 0) {
                    out.problems.add(folder + "/: папки нет или в ней не осталось файлов, а в прежнем списке их " + had
                            + ". Если так и задумано, убери " + folder + " из config/luminary-packindex.json");
                }
            }
        }
        if (!out.problems.isEmpty()) {
            Map<String, Entry> now = new HashMap<>();
            for (Entry e : main) now.put(caseKey(e.path()), e);
            for (Entry e : extra) now.put(caseKey(e.path()), e);
            for (Entry e : previous.values()) {
                if (e.size() == 0) continue;
                Entry n = now.get(caseKey(e.path()));
                if (!present.contains(caseKey(e.path()))) out.stale.add(e.path() + " (удалён)");
                else if (n != null && (!n.sha256().equals(e.sha256()) || n.size() != e.size())) out.stale.add(e.path() + " (заменён)");
            }
            return out;
        }
        main.sort(Comparator.comparing(Entry::path));
        extra.sort(Comparator.comparing(Entry::path));
        out.files = main.size() + extra.size();
        out.bytes = main.stream().mapToLong(Entry::size).sum() + extra.stream().mapToLong(Entry::size).sum();

        boolean useExtra = !cfg.extraFolders.isEmpty();
        // "unchanged" only if each published file is valid by the launcher's own (strict) JSON rules and lists exactly
        // the same folders and files. A BOM, comment, single quote or "size": "123" (Gson's lenient parser accepts
        // them, launchers do not) or a damaged field gets rewritten instead of being kept forever. Entry order does
        // not matter (make_manifest.py lists folder by folder, this mod sorts by path).
        String prevMainVersion = sameContent(packDir.resolve(cfg.manifestName), cfg.folders, main,
                useExtra ? cfg.extraManifestName : null);
        if (prevMainVersion != null
                && (!useExtra || sameContent(packDir.resolve(cfg.extraManifestName), cfg.extraFolders, extra, null) != null)) {
            out.version = prevMainVersion;
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
        if (cfg.folders.size() > MAX_FOLDERS || cfg.extraFolders.size() > MAX_FOLDERS) {
            out.problems.add("config: больше " + MAX_FOLDERS + " папок в одном манифесте (лаунчер отклонит)");
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
        if (path.isEmpty() || path.codePointCount(0, path.length()) > 400 || path.indexOf('\0') >= 0) {
            return "пустой или слишком длинный путь";
        }
        if (path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return "недопустимые символы в пути";
        String[] parts = path.split("/", -1);
        if (parts.length < 2 || !parts[0].equals(folder)) return "недопустимый путь";
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return "недопустимый путь";
            String stem = part.split("\\.", -1)[0].toLowerCase(Locale.ROOT);
            if (WIN_RESERVED.contains(stem) || part.endsWith(" ") || part.endsWith(".")) return "недопустимое имя файла";
            // Windows players could not create these, and every launcher version accepts them: refuse here
            String bare = stem.replaceFirst(" +$", "");
            if (WIN_RESERVED.contains(bare) || WIN_RESERVED_EXTRA.contains(bare)) return "Windows не может создать такой файл";
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 0x20 || WIN_BAD_CHARS.indexOf(c) >= 0) return "Windows не может создать такой файл";
            }
            // a letter this Java does not know (newer Unicode) may compare differently from the launcher's Python
            if (part.codePoints().anyMatch(cp -> !Character.isDefined(cp))) return "символ, неизвестный этой версии Java";
        }
        return null;
    }

    /**
     * Key for "same name apart from letter case". Folds the final sigma so Java never sees two names where the
     * launcher's Python str.lower() sees one (they disagree on where a word ends).
     */
    static String caseKey(String s) {
        return s.toLowerCase(Locale.ROOT).replace('\u03c2', '\u03c3');
    }

    /** For tests. */
    public static String caseKeyForTest(String s) {
        return caseKey(s);
    }

    static boolean ancestorIsFile(String key, Set<String> presentFiles) {
        for (int i = key.indexOf('/'); i >= 0; i = key.indexOf('/', i + 1)) {
            if (presentFiles.contains(key.substring(0, i))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ scanning

    private static List<Entry> scan(Path packDir, Map<String, String> folders, HashCache cache, Map<String, String> seenLower,
                                    Set<String> present, Set<String> presentFiles, Outcome out) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String folder : folders.keySet()) {
            Path root = packDir.resolve(folder);
            if (Files.isSymbolicLink(root)) {
                out.problems.add(folder + ": ссылка на папку (положи саму папку)");
                continue;
            }
            if (!Files.isDirectory(root)) {
                out.skipped.add(folder + "/ (папки нет)"); // run() refuses it if the published list had files there
                continue;
            }
            walk(packDir, root, folder, cache, seenLower, present, presentFiles, out, entries);
        }
        return entries;
    }

    private static void walk(Path packDir, Path dir, String folder, HashCache cache, Map<String, String> seenLower,
                             Set<String> present, Set<String> presentFiles, Outcome out, List<Entry> entries)
            throws IOException {
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
            present.add(caseKey(rel));
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory()) {
                presentFiles.add(caseKey(rel));
            }
            if (name.startsWith(".")) { // hidden files, folders and links alike: servarium does not serve them
                out.skipped.add(rel + (attrs.isDirectory() ? "/" : "") + " (скрытое: servarium такое не раздаёт)");
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            boolean temp = SKIP_NAMES.contains(lower) || SKIP_SUFFIXES.stream().anyMatch(lower::endsWith);
            if (attrs.isSymbolicLink()) {
                if (temp) {
                    out.skipped.add(rel + " (временный файл)");
                } else {
                    out.problems.add(rel + ": ссылка (положи сам файл или папку)");
                }
                continue;
            }
            if (attrs.isDirectory()) {
                walk(packDir, p, folder, cache, seenLower, present, presentFiles, out, entries);
                continue;
            }
            if (!attrs.isRegularFile()) {
                continue;
            }
            if (temp) {
                out.skipped.add(rel + " (временный файл)");
                continue;
            }
            String why = validPath(rel, folder);
            if (why != null) {
                out.problems.add(rel + ": " + why + " (лаунчер отклонит такой список)");
                continue;
            }
            String key = caseKey(rel);
            if (seenLower.containsKey(key)) {
                out.problems.add(rel + ": отличается от " + seenLower.get(key) + " только регистром");
                continue;
            }
            if (attrs.size() > MAX_FILE_SIZE) {
                out.problems.add(rel + ": файл больше 2 ГБ");
                continue;
            }
            String digest = cache.sha256(rel, p, attrs);
            if (digest == null) { // FIX: changed while being hashed (upload still running)
                out.problems.add(rel + ": файл меняется (ещё загружается?) — дождись конца загрузки и перезапусти сервер");
                continue;
            }
            seenLower.put(key, rel);
            entries.add(new Entry(rel, digest, attrs.size()));
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
            if (o == null || !o.has("files") || !o.get("files").isJsonArray()) continue; // FIX: "files": null/{}/""
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
            String text = readText(file);
            if (text == null) return null;
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** FIX: size cap, so a stray multi-GB file named manifest.json cannot throw OutOfMemoryError (an Error). */
    static String readText(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64L * 1024 * 1024) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The published file's version if it parses as strict JSON (what the launcher's Python json.loads accepts: no
     * BOM, comments, single quotes or trailing data), every field has the type launchers require, and it lists exactly
     * these folders, files and extra manifests. Otherwise null, and the file is rewritten.
     */
    static String sameContent(Path file, Map<String, String> folders, List<Entry> files, String extraName) {
        String text = readText(file);
        if (text == null || text.startsWith("\uFEFF")) return null;
        try {
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text));
            reader.setLenient(false);
            JsonElement root = new Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT || !root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (!isString(o.get("version")) || !isString(o.get("updated")) || !o.has("folders") || !o.get("folders").isJsonObject()
                    || !o.has("files") || !o.get("files").isJsonArray()) {
                return null;
            }
            Map<String, String> beforeFolders = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("folders").entrySet()) {
                if (!isString(e.getValue())) return null;
                beforeFolders.put(e.getKey(), e.getValue().getAsString());
            }
            Set<Entry> before = new HashSet<>();
            for (JsonElement el : o.getAsJsonArray("files")) {
                if (!el.isJsonObject()) return null;
                JsonObject f = el.getAsJsonObject();
                JsonElement size = f.get("size");
                if (!isString(f.get("path")) || !isString(f.get("sha256")) || !(size instanceof com.google.gson.JsonPrimitive sp)
                        || !sp.isNumber() || sp.getAsBigDecimal().scale() > 0) {
                    return null;
                }
                before.add(new Entry(f.get("path").getAsString(), f.get("sha256").getAsString(), size.getAsLong()));
            }
            List<String> extras = new ArrayList<>();
            if (o.has("extra_manifests")) {
                if (!o.get("extra_manifests").isJsonArray()) return null;
                for (JsonElement el : o.getAsJsonArray("extra_manifests")) {
                    if (!isString(el)) return null;
                    extras.add(el.getAsString());
                }
            }
            boolean same = beforeFolders.equals(folders) && before.size() == files.size() && before.equals(new HashSet<>(files))
                    && extras.equals(extraName == null ? List.of() : List.of(extraName));
            return same ? o.get("version").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isString(JsonElement e) {
        return e instanceof com.google.gson.JsonPrimitive p && p.isString();
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
        // FIX: never write through a leftover link at the temp name, and flush before the rename (crash safety)
        Files.deleteIfExists(tmp);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
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
                        JsonArray a = e.getValue().getAsJsonArray(); // old 3-field entries fail here: re-hashed once
                        c.data.put(e.getKey(), new Object[]{a.get(0).getAsLong(), a.get(1).getAsLong(), a.get(2).getAsLong(),
                                a.get(3).getAsString()});
                    } catch (RuntimeException ignored) {
                        // stale or damaged entry: re-hash
                    }
                }
            }
            return c;
        }

        /** null if the file changed while it was being hashed. */
        String sha256(String rel, Path p, BasicFileAttributes attrs) throws IOException {
            used.add(rel);
            long size = attrs.size();
            // FIX: nanoseconds, plus ctime: no tool can set ctime, so an upload/unpack that restores the old mtime on
            // same-size new content (tar/unzip of a reproducible archive, WinSCP "preserve timestamp") still re-hashes
            long mtime = attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
            long ctime = ctime(p);
            Object[] hit = data.get(rel);
            if (hit != null && Objects.equals(hit[0], size) && Objects.equals(hit[1], mtime) && Objects.equals(hit[2], ctime)) {
                return (String) hit[3];
            }
            long[] read = new long[1];
            String digest = hash(p, read);
            BasicFileAttributes after = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (read[0] != size || after.size() != size || !after.lastModifiedTime().equals(attrs.lastModifiedTime())
                    || ctime(p) != ctime) {
                data.remove(rel);
                return null; // FIX: still being written: do not publish or cache a hash that matches no complete file
            }
            data.put(rel, new Object[]{size, mtime, ctime, digest});
            dirty = true;
            return digest;
        }

        static long ctime(Path p) {
            try {
                return ((java.nio.file.attribute.FileTime) Files.getAttribute(p, "unix:ctime", LinkOption.NOFOLLOW_LINKS))
                        .to(java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (IOException | RuntimeException e) {
                return 0; // no unix view (Windows host): size + mtime only, as before
            }
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
                a.add((Long) e.getValue()[2]);
                a.add((String) e.getValue()[3]);
                o.add(e.getKey(), a);
            }
            Files.createDirectories(file.getParent());
            writeAtomically(file, new Gson().toJson(o));
        }
    }

    static String hash(Path p) throws IOException {
        return hash(p, new long[1]);
    }

    static String hash(Path p, long[] read) throws IOException {
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
                read[0] += n;
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
