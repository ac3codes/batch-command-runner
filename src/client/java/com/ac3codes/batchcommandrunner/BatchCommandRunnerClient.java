package com.ac3codes.batchcommandrunner;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class BatchCommandRunnerClient implements ClientModInitializer {
    private static KeyMapping openKey;
    private static boolean batchSlashPriority = KeybindConfig.loadBatchSlashPriority();

    public static boolean isBatchSlashPriority() {
        return batchSlashPriority;
    }

    public static void setBatchSlashPriority(boolean value) {
        batchSlashPriority = value;
        KeybindConfig.saveBatchSlashPriority(value);
    }

    /** Whether the batch UI's own "/" keybind and vanilla's "Open Command" keybind are
     * currently bound to the same key - the only situation {@link #batchSlashPriority} is
     * relevant for, and the UI's own toggle button is only shown while this is true. */
    public static boolean isSlashConflict() {
        if (openKey == null || openKey.isUnbound()) {
            return false;
        }
        return openKey.same(Minecraft.getInstance().options.keyCommand);
    }

    /** The currently configured display name for the batch UI's own open keybind (e.g. "/" or
     * whatever the user has rebound it to) - used by the tutorial's "Open It Anytime" page so it
     * never shows a hard-coded key that could drift from what's actually bound. */
    public static Component getOpenKeyDisplay() {
        return openKey.getTranslatedKeyMessage();
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("batch_command_runner", "main")
        );

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.batch_command_runner.open",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_SLASH,
                category
        ));

        // No .requires() gate here - this is a manually-typed chat command with no reason to
        // ever refuse a normal player, unlike commands that guard against automation.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("batch")
                        .executes(context -> {
                            // Deferred via client.execute() rather than opened inline here: this
                            // callback runs while ChatScreen is still open and about to close
                            // itself once command dispatch returns (its normal post-send
                            // behavior, unconditional - it doesn't check whether some other
                            // screen was opened in the meantime). Opening synchronously here got
                            // immediately undone by that close, which is what made the batch UI
                            // flash open and instantly disappear. Queuing the open for the next
                            // drain of the client's task queue runs it after that close instead.
                            Minecraft client = context.getSource().getClient();
                            client.execute(() -> openScreen(client));
                            return 1;
                        })));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CommandBatchRunner.tick(client);

            // Deliberately never touches client.options.keyCommand at all: vanilla's own "Open
            // Command" handling (Gui#tick) only fires when no screen is currently open, so as
            // long as this only opens the batch screen when batch priority actually applies,
            // vanilla's own key keeps working completely independently and normally on its own
            // binding - there's nothing left to suppress or restore.
            while (openKey.consumeClick()) {
                if (!isSlashConflict() || batchSlashPriority) {
                    openScreen(client);
                }
            }
        });
    }

    /** Opens the batch UI, shared by both the "/" keybind and the {@code /batch} command so the
     * two can never behave differently from one another. */
    private static void openScreen(Minecraft client) {
        if (client.player != null) {
            client.setScreenAndShow(new BatchCommandScreen(client.gui.screen()));
        }
    }
}
