package pl.mtsit.packguard.loader;

import java.nio.file.Path;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

public final class PackGuardPlatform {
    private PackGuardPlatform() {}

    public static boolean forge() {
        return true;
    }

    public static boolean intermediary() {
        return false;
    }

    static boolean client() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    public static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }
}
