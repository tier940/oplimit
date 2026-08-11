package com.github.tier940.oplimitbypass.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

/**
 * Tracks which operators carry {@code bypassesPlayerLimit} and keeps that state editable at runtime.
 *
 * <p>
 * Vanilla loads ops.json once at startup, so editing the file by hand has no effect until the server is
 * restarted and there is no command to toggle the flag at all. This registry parses ops.json directly,
 * caches the flag in memory and writes changes straight back, then asks vanilla to re-read the file so
 * both views stay in sync. ops.json remains the single source of truth.
 *
 * <p>
 * The registry stays inert until {@link #init(File)} is called on server start, reporting the plain
 * vanilla count until then, so the mixin is harmless no matter when it happens to run.
 *
 * <p>
 * This class is shared verbatim by the 1.12.2 and 1.20.1 builds and therefore must not reference any
 * Minecraft type: player objects reach it as a {@link ProfileReader} supplied by the version-specific
 * {@code OpBypassCounter}. It also has to compile on Java 8, since the 1.12.2 build targets Java 8
 * bytecode via Jabel.
 */
public final class OpBypassRegistry {

    /** Key used by vanilla's operator list serialisation. */
    private static final String KEY_BYPASS = "bypassesPlayerLimit";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_NAME = "name";
    private static final String OPS_FILE_NAME = "ops.json";
    private static final String MAINTENANCE_FILE_NAME = "oplimit-maintenance.json";

    public static final Logger LOGGER = LogManager.getLogger("OpLimitBypass");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<UUID, Boolean> BY_UUID = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BY_NAME = new ConcurrentHashMap<>();

    private static volatile boolean active = false;
    private static volatile File opsFile = null;
    private static volatile ProfileReader profileReader = null;

    /** Maintenance mode is in-memory only: a restart always comes back with it off. */
    private static volatile boolean maintenance = false;
    private static volatile int savedMaxPlayers = -1;
    private static volatile String savedMotd = null;
    private static volatile File maintenanceFile = null;

    /**
     * Same shape as ops.json: uuid takes priority, name is the offline-mode fallback.
     *
     * <p>
     * A player who has never connected has no known UUID, and ConcurrentHashMap forbids null
     * values, so the name set is kept separate from the uuid set rather than mapping one to the
     * other.
     */
    private static final Set<UUID> MAINTENANCE_UUIDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MAINTENANCE_NAMES = ConcurrentHashMap.newKeySet();

    private OpBypassRegistry() {}

    public enum Result {
        OK,
        NOT_OP,
        IO_ERROR
    }

    /**
     * Pulls the profile out of whatever type the running version uses for an online player.
     *
     * <p>
     * The only thing that differs between 1.12.2 and 1.20.1 here is how a player object yields its
     * {@link GameProfile}, so that single step is all the version-specific code has to provide.
     * May return null for an entry that is not a player at all.
     */
    public interface ProfileReader {

        @Nullable
        GameProfile profileOf(@NotNull Object player);
    }

    // ----------------------------------------------------------------------------------- lifecycle

    /**
     * @param serverRoot the directory holding ops.json, i.e. the server root.
     * @param reader     how this Minecraft version yields a profile from an online player.
     */
    public static void init(@NotNull File serverRoot, @NotNull ProfileReader reader) {
        opsFile = new File(serverRoot, OPS_FILE_NAME);
        maintenanceFile = new File(serverRoot, MAINTENANCE_FILE_NAME);
        profileReader = reader;
        active = true;
        int count = reload();
        LOGGER.info("Watching {} ({} operator(s) bypassing the player limit)", opsFile.getAbsolutePath(), count);
        int listed = reloadMaintenance();
        LOGGER.info("Maintenance list: {} entry/entries in {}", listed, maintenanceFile.getAbsolutePath());
    }

    public static void shutdown() {
        active = false;
        opsFile = null;
        profileReader = null;
        maintenance = false;
        savedMaxPlayers = -1;
        savedMotd = null;
        maintenanceFile = null;
        MAINTENANCE_UUIDS.clear();
        MAINTENANCE_NAMES.clear();
        BY_UUID.clear();
        BY_NAME.clear();
    }

    public static boolean isActive() {
        return active;
    }

    // --------------------------------------------------------------------------------------- query

    /**
     * Replacement for the online player count inside the player limit check.
     *
     * @param joining the profile currently trying to connect, may be null.
     * @return the number of online players that consume a slot.
     */
    public static int countTowardsLimit(@NotNull List<?> players, @Nullable GameProfile joining) {
        if (active && joining != null) {
            // Never full for anyone allowed past the limit, whatever max-players happens to be.
            // During maintenance max-players is 0, so everyone else is rejected by the plain
            // vanilla comparison without needing a branch here.
            if (maintenance ? isAllowedDuringMaintenance(joining.getId(), joining.getName())
                    : isBypassing(joining.getId(), joining.getName())) {
                return Integer.MIN_VALUE;
            }
        }
        return countNonBypassing(players);
    }

    /**
     * Number of online players that occupy a slot. This is also what the server list ping and the query
     * protocol report, so "21/20" cannot happen: a bypassing operator is invisible to the counter in
     * exactly the same way they are invisible to the limit.
     */
    public static int countNonBypassing(@NotNull List<?> players) {
        ProfileReader reader = profileReader;
        if (!active || reader == null) {
            return players.size();
        }
        try {
            int count = 0;
            for (Object player : players) {
                GameProfile profile = reader.profileOf(player);
                if (profile != null && !occupiesSlot(profile)) {
                    continue;
                }
                count++;
            }
            return count;
        } catch (RuntimeException e) {
            LOGGER.error("Player limit check failed, falling back to the vanilla count", e);
            return players.size();
        }
    }

    /**
     * Whether an online player counts against max-players.
     *
     * <p>
     * Both the limit check and the reported figure go through this, so the two can never disagree.
     * During maintenance everyone still allowed in is invisible to the counter, which is what makes
     * the server advertise "0/0" rather than "1/0" once someone reconnects.
     */
    private static boolean occupiesSlot(@NotNull GameProfile profile) {
        if (maintenance) {
            return !isAllowedDuringMaintenance(profile.getId(), profile.getName());
        }
        return !isBypassing(profile.getId(), profile.getName());
    }

    public static int countBypassingOnline(@NotNull List<?> players) {
        ProfileReader reader = profileReader;
        if (reader == null) {
            return 0;
        }
        int count = 0;
        for (Object player : players) {
            GameProfile profile = reader.profileOf(player);
            if (profile != null && isBypassing(profile.getId(), profile.getName())) {
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------------------------------------- maintenance

    /**
     * Whether maintenance mode is on. Only held in memory, so a restart always comes back with it off.
     */
    public static boolean isMaintenance() {
        return maintenance;
    }

    /**
     * @return the max-players value saved when maintenance was enabled, or -1 when it is off.
     */
    public static int getSavedMaxPlayers() {
        return savedMaxPlayers;
    }

    /** Records that maintenance is starting and remembers what to restore later. */
    public static void beginMaintenance(int currentMaxPlayers, @Nullable String currentMotd) {
        savedMaxPlayers = currentMaxPlayers;
        savedMotd = currentMotd;
        maintenance = true;
    }

    /**
     * @return the MOTD saved when maintenance began, or null when it was not on. Reading it clears
     *         it, since it is only ever needed once to restore the server description.
     */
    @Nullable
    public static String takeSavedMotd() {
        String motd = savedMotd;
        savedMotd = null;
        return motd;
    }

    /**
     * Ends maintenance.
     *
     * @return the max-players value to restore, or -1 when maintenance was not on.
     */
    public static int endMaintenance() {
        if (!maintenance) {
            return -1;
        }
        int restore = savedMaxPlayers;
        maintenance = false;
        savedMaxPlayers = -1;
        // savedMotd is cleared by the caller after it has restored it.
        return restore;
    }

    /** Bypassing operators keep their access during maintenance, as does anyone on the list. */
    public static boolean isAllowedDuringMaintenance(@Nullable UUID uuid, @Nullable String name) {
        if (isBypassing(uuid, name)) {
            return true;
        }
        if (uuid != null && MAINTENANCE_UUIDS.contains(uuid)) {
            return true;
        }
        return name != null && MAINTENANCE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Adds a player to the maintenance list and writes it to disk.
     *
     * @param uuid may be null for a player who has never been seen on this server.
     * @return false when the name was already listed.
     */
    public static synchronized boolean addMaintenanceName(@NotNull String name, @Nullable UUID uuid) {
        if (!MAINTENANCE_NAMES.add(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (uuid != null) {
            MAINTENANCE_UUIDS.add(uuid);
        }
        writeMaintenance(name, uuid, true);
        return true;
    }

    /**
     * Fills in the UUID of a listed player once it becomes known.
     *
     * <p>
     * A player can be listed before the server has ever seen them, in which case only the name is
     * recorded. Calling this when they connect upgrades the entry so the listing survives a rename.
     */
    public static synchronized void rememberMaintenanceUuid(@NotNull String name, @NotNull UUID uuid) {
        if (!MAINTENANCE_NAMES.contains(name.toLowerCase(Locale.ROOT)) || !MAINTENANCE_UUIDS.add(uuid)) {
            return;
        }
        writeMaintenance(name, uuid, true);
    }

    /** @return false when the name was not on the list. */
    public static synchronized boolean removeMaintenanceName(@NotNull String name) {
        if (!MAINTENANCE_NAMES.remove(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        writeMaintenance(name, null, false);
        // The uuid set is rebuilt from the file, which no longer holds this entry.
        reloadMaintenance();
        return true;
    }

    /**
     * Re-reads the maintenance list from disk, creating an empty file when there is none.
     *
     * @return the number of entries loaded.
     */
    public static synchronized int reloadMaintenance() {
        MAINTENANCE_UUIDS.clear();
        MAINTENANCE_NAMES.clear();
        File file = maintenanceFile;
        if (file == null) {
            return 0;
        }
        if (!file.isFile()) {
            // Start the server with the file already in place, the way vanilla does for ops.json.
            writeMaintenanceFile(new JsonArray());
            return 0;
        }
        JsonArray entries = readJsonArray(file);
        if (entries == null) {
            return 0;
        }
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = readString(element, KEY_NAME);
            if (name == null) {
                continue;
            }
            UUID uuid = readUuid(element.getAsJsonObject());
            MAINTENANCE_NAMES.add(name.toLowerCase(Locale.ROOT));
            if (uuid != null) {
                MAINTENANCE_UUIDS.add(uuid);
            }
        }
        return MAINTENANCE_NAMES.size();
    }

    /** Rewrites the maintenance file with one entry added or removed. */
    private static void writeMaintenance(@NotNull String name, @Nullable UUID uuid, boolean add) {
        File file = maintenanceFile;
        if (file == null) {
            return;
        }
        JsonArray entries = file.isFile() ? readJsonArray(file) : new JsonArray();
        if (entries == null) {
            return;
        }
        JsonArray kept = new JsonArray();
        for (JsonElement element : entries) {
            String existing = readString(element, KEY_NAME);
            if (existing == null || !existing.equalsIgnoreCase(name)) {
                kept.add(element);
            }
        }
        if (add) {
            JsonObject entry = new JsonObject();
            entry.addProperty(KEY_UUID, uuid == null ? "" : uuid.toString());
            entry.addProperty(KEY_NAME, name);
            kept.add(entry);
        }
        writeMaintenanceFile(kept);
    }

    private static void writeMaintenanceFile(@NotNull JsonArray entries) {
        File file = maintenanceFile;
        if (file != null) {
            writeJsonArray(file, entries);
        }
    }

    @NotNull
    public static List<String> listMaintenanceNames() {
        List<String> names = new ArrayList<>(MAINTENANCE_NAMES);
        Collections.sort(names);
        return names;
    }

    /** UUID takes priority; the name is only consulted for offline-mode setups. */
    public static boolean isBypassing(@Nullable UUID uuid, @Nullable String name) {
        if (uuid != null) {
            Boolean flag = BY_UUID.get(uuid);
            if (flag != null) {
                return flag;
            }
        }
        if (name != null) {
            Boolean flag = BY_NAME.get(name.toLowerCase(Locale.ROOT));
            if (flag != null) {
                return flag;
            }
        }
        return false;
    }

    /** @return null when the player has no ops.json entry at all. */
    @Nullable
    public static Boolean getState(@NotNull String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    @NotNull
    public static List<String> listBypassing() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : BY_NAME.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                names.add(entry.getKey());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Every name present in ops.json, used for tab completion. */
    @NotNull
    public static List<String> listOperators() {
        List<String> names = new ArrayList<>();
        JsonArray entries = readOps();
        if (entries == null) {
            return names;
        }
        for (JsonElement element : entries) {
            String name = readString(element, KEY_NAME);
            if (name != null) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    // --------------------------------------------------------------------------------------- write

    /**
     * Re-reads ops.json from disk.
     *
     * @return the number of operators whose flag is set.
     */
    public static synchronized int reload() {
        BY_UUID.clear();
        BY_NAME.clear();
        JsonArray entries = readOps();
        if (entries == null) {
            return 0;
        }
        int count = 0;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has(KEY_BYPASS)) {
                continue;
            }
            boolean flag = readBoolean(entry.get(KEY_BYPASS));
            UUID uuid = readUuid(entry);
            String name = readString(element, KEY_NAME);
            if (uuid != null) {
                BY_UUID.put(uuid, flag);
            }
            if (name != null) {
                BY_NAME.put(name.toLowerCase(Locale.ROOT), flag);
            }
            if (flag) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sets the flag on an existing ops.json entry and applies it immediately.
     *
     * <p>
     * The player has to be an operator already; this never creates entries, use {@code /op} for that.
     */
    @NotNull
    public static synchronized Result setBypass(@NotNull String playerName, boolean value) {
        JsonArray entries = readOps();
        if (entries == null) {
            return Result.IO_ERROR;
        }
        boolean found = false;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = readString(element, KEY_NAME);
            if (name != null && name.equalsIgnoreCase(playerName)) {
                element.getAsJsonObject().addProperty(KEY_BYPASS, value);
                found = true;
            }
        }
        if (!found) {
            return Result.NOT_OP;
        }
        if (!writeOps(entries)) {
            return Result.IO_ERROR;
        }
        reload();
        return Result.OK;
    }

    // -------------------------------------------------------------------------------------- helpers

    @Nullable
    private static JsonArray readOps() {
        File file = opsFile;
        if (file == null || !file.isFile()) {
            return new JsonArray();
        }
        return readJsonArray(file);
    }

    /** @return the array, or null when the file exists but could not be read. */
    @Nullable
    private static JsonArray readJsonArray(@NotNull File file) {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonElement root = parseJson(reader);
            return root != null && root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            LOGGER.error("Could not read {}", file, e);
            return null;
        }
    }

    /**
     * 1.12.2 ships Gson 2.8, where the static {@code JsonParser.parseReader} does not exist yet, while
     * 1.20.1 ships a Gson new enough to deprecate the instance method. The deprecated-but-present
     * instance form is the only spelling that compiles clean on both.
     */
    @SuppressWarnings("deprecation")
    static JsonElement parseJson(@NotNull Reader reader) {
        return new JsonParser().parse(reader);
    }

    /**
     * Writes ops.json via a temporary file and an atomic rename.
     *
     * <p>
     * Writing in place would truncate ops.json first, so a crash mid-write leaves the file the whole
     * design treats as the single source of truth either empty or half-written. Renaming over it
     * means readers see the old file or the new one, never a partial one.
     */
    private static boolean writeOps(@NotNull JsonArray entries) {
        File file = opsFile;
        return file != null && writeJsonArray(file, entries);
    }

    private static boolean writeJsonArray(@NotNull File file, @NotNull JsonArray entries) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot rename atomically; a plain replace is still better than
                // having truncated the original before writing.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Could not write {}", file, e);
            return false;
        } finally {
            if (temp.exists() && !temp.delete()) {
                LOGGER.warn("Could not remove the temporary file {}", temp);
            }
        }
    }

    @Nullable
    private static String readString(@NotNull JsonElement element, @NotNull String key) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean readBoolean(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static UUID readUuid(@NotNull JsonObject entry) {
        String raw = readString(entry, KEY_UUID);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
