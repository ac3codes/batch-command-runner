package com.ac3codes.batchcommandrunner;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class BatchCommandRunnerClient implements ClientModInitializer {
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("batch_command_runner", "main")
        );

        openKey = KeyBindingHelper.registerKeyMapping(new KeyMapping(
                "key.batch_command_runner.open",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_B,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CommandBatchRunner.tick(client);

            while (openKey.consumeClick()) {
                if (client.player != null) {
                    client.setScreen(new BatchCommandScreen(client.currentScreen));
                }
            }
        });
    }
}
