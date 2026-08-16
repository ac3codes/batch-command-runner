package com.ac3codes.batchcommandrunner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists whether the batch UI's own "/" keybind takes priority over vanilla's "Open Command"
 * keybind when the two happen to be bound to the same key. Kept in its own small file/class,
 * separate from {@link BatchConfig}, since keybind conflict resolution is an unrelated concern
 * from batch execution settings - this way the change can't put the already-tested
 * BatchSettings persistence at any risk.
 */
public final class KeybindConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("batch_command_runner_keybind.json");
    private static final boolean DEFAULT_BATCH_SLASH_PRIORITY = true;

    private KeybindConfig() {
    }

    private record Data(boolean batchSlashPriority) {
    }

    public static boolean loadBatchSlashPriority() {
        if (!Files.isRegularFile(PATH)) {
            return DEFAULT_BATCH_SLASH_PRIORITY;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            Data data = GSON.fromJson(json, Data.class);
            return data == null ? DEFAULT_BATCH_SLASH_PRIORITY : data.batchSlashPriority();
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warn("[BatchCommandRunner] Failed to load keybind config, using default: {}", e.getMessage());
            return DEFAULT_BATCH_SLASH_PRIORITY;
        }
    }

    public static void saveBatchSlashPriority(boolean value) {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(new Data(value)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[BatchCommandRunner] Failed to save keybind config: {}", e.getMessage());
        }
    }
}
