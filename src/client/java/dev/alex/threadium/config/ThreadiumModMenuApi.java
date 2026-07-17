package dev.alex.threadium.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu integration. Mod Menu is not required to run Threadium. */
public final class ThreadiumModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ThreadiumConfigScreen::new;
    }
}
