package com.ac3codes.batchcommandrunner;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("batch")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> {
                        Minecraft client = context.getSource().getClient();
                        if (client.player != null) {
                            client.setScreenAndShow(new BatchCommandScreen(client.gui.screen()));
                        }
                        return 1;
                    }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CommandBatchRunner.tick(client);

            // Deliberately never touches client.options.keyCommand at all: vanilla's own "Open
            // Command" handling (Gui#tick) only fires when no screen is currently open, so as
            // long as this only opens the batch screen when batch priority actually applies,
            // vanilla's own key keeps working completely independently and normally on its own
            // binding - there's nothing left to suppress or restore.
            while (openKey.consumeClick()) {
                if (client.player != null && (!isSlashConflict() || batchSlashPriority)) {
                    client.setScreenAndShow(new BatchCommandScreen(client.gui.screen()));
                }
            }
        });
    }
}
