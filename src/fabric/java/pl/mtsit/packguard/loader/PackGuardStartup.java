package pl.mtsit.packguard.loader;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class PackGuardStartup implements PreLaunchEntrypoint {
    public void onPreLaunch() {
        PackGuardBootstrap.initialize();
    }
}
