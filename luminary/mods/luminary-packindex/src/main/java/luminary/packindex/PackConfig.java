package luminary.packindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** config/luminary-packindex.json: which client-pack folders are published, and how players' launchers sync each. */
public final class PackConfig {
    public boolean enabled = true;
    /** The servarium client-pack folder, relative to the server folder. */
    public String packDir = "client-pack";
    public String manifestName = "manifest.json";
    /** Folders every launcher version accepts (launchers before 2.1.2 reject a manifest with any other folder). */
    public Map<String, String> folders = new LinkedHashMap<>();
    /** Folders only launchers 2.1.3+ read, published in a second manifest named in the first one. */
    public String extraManifestName = "manifest-extra.json";
    public Map<String, String> extraFolders = new LinkedHashMap<>();

    public static PackConfig defaults() {
        PackConfig c = new PackConfig();
        c.folders.put("mods", "mirror");
        c.folders.put("config", "update");
        c.folders.put("defaultconfigs", "update");
        c.folders.put("fancymenu_data", "update");
        c.folders.put("patchouli_books", "update");
        c.extraFolders.put("resourcepacks", "update");
        c.extraFolders.put("shaderpacks", "update");
        return c;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Reads the file, or writes the defaults there first. */
    public static PackConfig loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            PackConfig c = defaults();
            Files.createDirectories(file.getParent());
            Files.writeString(file, c.toJson(), StandardCharsets.UTF_8);
            return c;
        }
        JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        PackConfig c = new PackConfig();
        if (o.has("enabled")) c.enabled = o.get("enabled").getAsBoolean();
        if (o.has("pack_dir")) c.packDir = o.get("pack_dir").getAsString();
        if (o.has("manifest_name")) c.manifestName = o.get("manifest_name").getAsString();
        if (o.has("extra_manifest_name")) c.extraManifestName = o.get("extra_manifest_name").getAsString();
        c.folders = readModes(o, "folders", defaults().folders);
        c.extraFolders = readModes(o, "extra_folders", defaults().extraFolders);
        return c;
    }

    private static Map<String, String> readModes(JsonObject o, String key, Map<String, String> fallback) {
        if (!o.has(key)) return fallback;
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject(key).entrySet()) {
            m.put(e.getKey(), e.getValue().getAsString());
        }
        return m;
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("pack_dir", packDir);
        o.addProperty("manifest_name", manifestName);
        o.add("folders", GSON.toJsonTree(folders));
        o.addProperty("extra_manifest_name", extraManifestName);
        o.add("extra_folders", GSON.toJsonTree(extraFolders));
        return GSON.toJson(o) + "\n";
    }
}
