package pl.mtsit.packguard.archive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import pl.mtsit.packguard.font.FontWarning;
import pl.mtsit.packguard.history.BlockHistory;

public final class PackArchivePolicy {
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_ENTRY_BYTES = 64L << 20;
    private static final long MAX_EXPANDED_BYTES = 1L << 30;
    private static final long MAX_SCAN_NANOS = 30_000_000_000L;

    private PackArchivePolicy() {}

    public static void validateLegacy(File archive) {
        validate(archive.toPath(), 250 << 20);
    }

    public static Path validate(Path path, int maxBytes) {
        try {
            scan(path, maxBytes);
            return path;
        } catch (IOException | IllegalArgumentException failure) {
            FontWarning.archiveBlocked(BlockHistory.archiveId(path), failure instanceof ArchiveViolation violation
                    ? violation.getMessage() : "unreadable-or-invalid-zip");
            throw new UncheckedIOException("PackGuard rejected the server resource pack",
                    new IOException("Invalid ZIP or archive limits exceeded", failure));
        }
    }

    static void scan(Path path, int maxBytes) throws IOException {
        long started = System.nanoTime();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) throw new ArchiveViolation("file-type-or-download-limit");
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[65536];
        CRC32 crc = new CRC32();
        long total = 0;
        boolean metadata = false;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > MAX_ENTRIES) throw new ArchiveViolation("entry-count-limit");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String key = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (!validName(key) || !names.add(key.toLowerCase(Locale.ROOT))) throw new ArchiveViolation("invalid-or-duplicate-entry-name");
                long compressed = entry.getCompressedSize();
                long declared = entry.getSize();
                if (compressed < 0 || compressed > maxBytes) throw new ArchiveViolation("compressed-size-invalid");
                long limit = Math.min(MAX_ENTRY_BYTES, Math.max(1, compressed) * 1000);
                if (name.equals("pack.mcmeta")) {
                    metadata = true;
                    limit = Math.min(limit, 1L << 20);
                }
                if (declared < 0 || declared > limit || declared > MAX_EXPANDED_BYTES - total) throw new ArchiveViolation("expanded-size-limit");
                long size = 0;
                crc.reset();
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        size += read;
                        if (size > declared || System.nanoTime() - started > MAX_SCAN_NANOS) throw new ArchiveViolation("entry-size-or-scan-time-limit");
                        crc.update(buffer, 0, read);
                    }
                }
                if (size != declared || crc.getValue() != entry.getCrc()
                        || System.nanoTime() - started > MAX_SCAN_NANOS) throw new ArchiveViolation("entry-integrity-or-scan-time-limit");
                total += size;
            }
        }
        if (!metadata) throw new ArchiveViolation("missing-pack-metadata");
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 512
                || name.chars().anyMatch(value -> Character.isISOControl(value) || "\\:\"<>|?*".indexOf(value) >= 0)) return false;
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.endsWith(".") || segment.endsWith(" ")) return false;
        }
        return true;
    }

    private static final class ArchiveViolation extends IOException {
        ArchiveViolation(String reason) {
            super(reason, null);
        }
    }
}