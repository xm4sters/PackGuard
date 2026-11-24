package pl.mtsit.packguard.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import pl.mtsit.packguard.loader.PackGuardPlatform;

public final class BlockHistory {
    private static final long MAX_BYTES = 256 * 1024;
    private static final int MAX_RECENT = 256;
    private static final long WINDOW = TimeUnit.SECONDS.toNanos(10);
    private static final System.Logger LOGGER = System.getLogger("PackGuard");
    private static final Map<Event, Long> RECENT = new LinkedHashMap<>();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> {
                Thread thread = new Thread(task, "PackGuard history");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    private BlockHistory() {}

    public static String archiveId(Path archive) {
        Path filename = archive.getFileName();
        String value = filename == null ? "" : filename.toString();
        return value.isEmpty() ? "unknown" : UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static boolean record(String kind, String archive, String reason) {
        Event event = new Event(kind, archive, reason);
        if (!firstRecent(event, System.nanoTime())) return false;
        Instant time = Instant.now();
        WRITER.execute(() -> {
            try {
                Path directory = PackGuardPlatform.gameDirectory().resolve("logs");
                append(directory, time + "\t" + kind + "\t" + archive + "\t" + reason + "\n");
            } catch (IOException | RuntimeException failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not write PackGuard history: " + failure.getClass().getSimpleName());
            }
        });
        return true;
    }

    private static synchronized boolean firstRecent(Event event, long now) {
        Long previous = RECENT.get(event);
        if (previous != null && now - previous < WINDOW) return false;
        RECENT.remove(event);
        if (RECENT.size() >= MAX_RECENT) RECENT.remove(RECENT.keySet().iterator().next());
        RECENT.put(event, now);
        return true;
    }

    static void append(Path directory, String line) throws IOException {
        Files.createDirectories(directory);
        Path history = directory.resolve("packguard-blocks.tsv");
        if (Files.exists(history) && Files.size(history) + line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            Files.move(history, directory.resolve("packguard-blocks.previous.tsv"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(history, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private record Event(String kind, String archive, String reason) {}
}
