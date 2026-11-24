package pl.mtsit.packguard;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class PackGuardStartup implements PreLaunchEntrypoint {
    public void onPreLaunch() {
        PackGuardMixinConfig.verifyInstalled();
        WindowsProcessGuard.install();
    }
}
