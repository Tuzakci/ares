package dev.tigr.ares.fabric.impl.modules.misc;

import dev.tigr.ares.core.Ares;
import dev.tigr.ares.core.feature.module.Category;
import dev.tigr.ares.core.feature.module.Module;
import net.arikia.dev.drpc.DiscordEventHandlers;
import net.arikia.dev.drpc.DiscordRPC;
import net.arikia.dev.drpc.DiscordRichPresence;

/**
 * @author Tigermouthbear 9/26/20
 */
@Module.Info(name = "DiscordRPC", description = "Show's the Ares in your discord rich presence", category = Category.MISC, enabled = true, visible = false)
public class DiscordPresence extends Module {
    private static final DiscordRichPresence PRESENCE = new DiscordRichPresence();
    private static final String APP_ID = "659123451973599253";

    public DiscordPresence() {
        if(getEnabled()) {
            init();
        }

        new Thread(() -> {
            while(!Thread.currentThread().isInterrupted()) {
                if(getEnabled()) {
                    try {
                        DiscordRPC.discordRunCallbacks();

                        String details;
                        String state = "";

                        if(MC.isIntegratedServerRunning()) {
                            details = "Singleplayer";
                        } else {
                            if(MC.getCurrentServerEntry() != null && !MC.getCurrentServerEntry().name.equals("")) {
                                details = "Multiplayer";
                                state = MC.getCurrentServerEntry().address;
                            } else {
                                details = "Main Menu";
                                state = "discord.gg/ncQkFKU";
                            }
                        }

                        if(!details.equals(PRESENCE.details) || !state.equals(PRESENCE.state)) {
                            PRESENCE.startTimestamp = System.currentTimeMillis() / 1000;
                        }

                        PRESENCE.details = details;
                        PRESENCE.state = state;

                        DiscordRPC.discordUpdatePresence(PRESENCE);
                    } catch(Exception e) {
                        e.printStackTrace();
                    }
                }

                try {
                    Thread.sleep(4000);
                } catch(InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, "Discord-RPC-Callback-Handler").start();
    }

    @Override
    public void onEnable() {
        init();
    }

    private void init() {
        DiscordRPC.discordInitialize(APP_ID, new DiscordEventHandlers.Builder().build(), true);

        PRESENCE.startTimestamp = System.currentTimeMillis() / 1000;
        PRESENCE.details = "Main Menu";
        PRESENCE.state = "discord.gg/YPbqmFK";
        PRESENCE.largeImageKey = "areslogored";
        PRESENCE.largeImageText = "AnnoyanceMod " + Ares.VERSION_FULL;

        DiscordRPC.discordUpdatePresence(PRESENCE);
    }

    @Override
    public void onDisable() {
        DiscordRPC.discordShutdown();
    }
}
