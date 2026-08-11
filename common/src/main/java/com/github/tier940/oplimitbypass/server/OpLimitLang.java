package com.github.tier940.oplimitbypass.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Resolves this mod's translation keys on the server.
 *
 * <p>
 * Messages are sent as translation keys so a client that has the mod renders them in its own
 * language. This mod is server-only though, so most clients will not have the keys; the resolved
 * string travels along as a fallback and is what those clients display. That means the server needs
 * its own copy of the language table, which is what this class is.
 *
 * <p>
 * The language is chosen once at startup from the {@code oplimit.lang} system property, falling back
 * to {@code en_us}. Lang files live in {@code assets/oplimitbypass/lang/} in the same JSON format
 * Minecraft uses, so the client-side and server-side texts cannot drift apart.
 */
public final class OpLimitLang {

    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final String RESOURCE_ROOT = "/assets/oplimitbypass/lang/";

    private static volatile Map<String, String> translations = Collections.emptyMap();
    private static volatile Map<String, String> defaults = Collections.emptyMap();

    private OpLimitLang() {}

    /**
     * Loads {@code en_us} plus the configured language.
     *
     * @param language a Minecraft language code such as {@code ja_jp}, or null for the default.
     */
    public static void load(@Nullable String language) {
        defaults = read(DEFAULT_LANGUAGE);
        String chosen = language == null || language.trim().isEmpty()
                ? DEFAULT_LANGUAGE
                : language.trim().toLowerCase(Locale.ROOT);
        translations = DEFAULT_LANGUAGE.equals(chosen) ? defaults : read(chosen);
        if (translations.isEmpty() && !DEFAULT_LANGUAGE.equals(chosen)) {
            OpBypassRegistry.LOGGER.warn("No translations for {}, falling back to {}", chosen, DEFAULT_LANGUAGE);
        }
    }

    /**
     * @return the translated text with {@code %s} placeholders filled in, or the key itself when it
     *         is not in the table at all.
     */
    @NotNull
    public static String translate(@NotNull String key, Object... args) {
        String pattern = translations.get(key);
        if (pattern == null) {
            pattern = defaults.get(key);
        }
        if (pattern == null) {
            return key;
        }
        if (args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            OpBypassRegistry.LOGGER.error("Bad format string for {}", key, e);
            return pattern;
        }
    }

    /**
     * Reads a language file, in whichever format this build ships.
     *
     * <p>
     * 1.20.1 packages the JSON straight from common/, while 1.12.2 converts it to the key=value
     * .lang format its own clients need. Both are read here so the same code serves either jar.
     */
    @NotNull
    private static Map<String, String> read(@NotNull String language) {
        Map<String, String> json = readJson(RESOURCE_ROOT + language + ".json");
        return json.isEmpty() ? readLang(RESOURCE_ROOT + language + ".lang") : json;
    }

    @NotNull
    private static Map<String, String> readJson(@NotNull String path) {
        try (InputStream in = OpLimitLang.class.getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonElement root = OpBypassRegistry.parseJson(reader);
                if (root == null || !root.isJsonObject()) {
                    return Collections.emptyMap();
                }
                Map<String, String> map = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && value.isJsonPrimitive()) {
                        map.put(entry.getKey(), value.getAsString());
                    }
                }
                return Collections.unmodifiableMap(map);
            }
        } catch (IOException | RuntimeException e) {
            OpBypassRegistry.LOGGER.error("Could not read {}", path, e);
            return Collections.emptyMap();
        }
    }

    @NotNull
    private static Map<String, String> readLang(@NotNull String path) {
        try (InputStream in = OpLimitLang.class.getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            Map<String, String> map = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    int split = line.indexOf('=');
                    if (split > 0) {
                        map.put(line.substring(0, split), line.substring(split + 1));
                    }
                }
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException | RuntimeException e) {
            OpBypassRegistry.LOGGER.error("Could not read {}", path, e);
            return Collections.emptyMap();
        }
    }
}
