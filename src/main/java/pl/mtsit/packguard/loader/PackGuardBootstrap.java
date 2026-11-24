package pl.mtsit.packguard.loader;

import pl.mtsit.packguard.process.WindowsProcessGuard;

public final class PackGuardBootstrap {
    private PackGuardBootstrap() {}

    public static void initialize() {
        if (!PackGuardPlatform.client()) return;
        PackGuardMixinConfig.verifyInstalled();
        WindowsProcessGuard.install();
    }
}
