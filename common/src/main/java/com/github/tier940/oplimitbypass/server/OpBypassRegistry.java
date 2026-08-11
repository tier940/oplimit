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

    public static final Logger LOGGER = LogManager.getLogger("OpLimitBypass");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<UUID, Boolean> BY_UUID = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BY_NAME = new ConcurrentHashMap<>();

    private static volatile boolean active = false;
    private static volatile File opsFile = null;
    private static volatile ProfileReader profileReader = null;

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
        profileReader = reader;
        active = true;
        int count = reload();
        LOGGER.info("Watching {} ({} operator(s) bypassing the player limit)", opsFile.getAbsolutePath(), count);
    }

    public static void shutdown() {
        active = false;
        opsFile = null;
        profileReader = null;
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
        if (active && joining != null && isBypassing(joining.getId(), joining.getName())) {
            // Never full for a bypassing operator, whatever max-players happens to be.
            return Integer.MIN_VALUE;
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
                if (profile != null && isBypassing(profile.getId(), profile.getName())) {
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
    private static JsonElement parseJson(@NotNull Reader reader) {
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
        if (file == null) {
            return false;
        }
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
