package com.ac3codes.batchcommandrunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists {@link BatchSettings} (delay, Heavy Command Protection, and its per-type minimums)
 * to a small JSON file in the standard Fabric config directory. Deliberately does not persist
 * the batch editor text itself - that stays an in-memory, session-only draft as before, and
 * this file is only written at meaningful lifecycle points (batch start, a setting actually
 * changing, screen close) rather than on every keystroke or every tick.
 *
 * <p>Reads as a raw {@link JsonObject} rather than binding straight to a record, so an older
 * 1.1.0 config (which used {@code safeFillMode}/{@code fillDelay}) still loads correctly. The
 * actual key-precedence/migration rules live in {@link BatchSettingsCodec} (dependency-free and
 * unit tested); this class only adapts the parsed {@link JsonObject} into the generic lookup
 * that codec expects, and handles the file I/O and JSON parsing around it. Once saved again,
 * the file is rewritten with the current schema.
 */
public final class BatchConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("batch_command_runner.json");

    private BatchConfig() {
    }

    public static BatchSettings load() {
        if (!Files.isRegularFile(PATH)) {
            return BatchSettings.DEFAULT;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) {
                return BatchSettings.DEFAULT;
            }
            return BatchSettingsCodec.decode(new BatchSettingsCodec.SettingsLookup() {
                @Override
                public int getInt(String key, int fallback) {
                    return intOrDefault(obj, key, fallback);
                }

                @Override
                public boolean getBoolean(String key, boolean fallback) {
                    return boolOrDefault(obj, key, fallback);
                }
            });
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warn("[BatchCommandRunner] Failed to load config, using defaults: {}", e.getMessage());
            return BatchSettings.DEFAULT;
        }
    }

    public static void save(BatchSettings settings) {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("normalDelay", settings.normalDelay());
            obj.addProperty("heavyCommandProtection", settings.heavyCommandProtection());
            obj.addProperty("fillMinimum", settings.fillMinimum());
            obj.addProperty("cloneMinimum", settings.cloneMinimum());
            obj.addProperty("placeMinimum", settings.placeMinimum());
            obj.addProperty("summonMinimum", settings.summonMinimum());
            Files.writeString(PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[BatchCommandRunner] Failed to save config: {}", e.getMessage());
        }
    }

    private static int intOrDefault(JsonObject obj, String key, int fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static boolean boolOrDefault(JsonObject obj, String key, boolean fallback) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (UnsupportedOperationException e) {
            return fallback;
        }
    }
}
