package pl.mtsit.packguard.loader;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class PackGuardPlatform {
    private PackGuardPlatform() {}

    public static boolean forge() {
        return false;
    }

    public static boolean intermediary() {
        return FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace().equals("intermediary");
    }

    static boolean client() {
        return true;
    }

    public static Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }
}
