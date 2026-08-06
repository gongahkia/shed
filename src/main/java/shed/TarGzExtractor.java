package shed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** Minimal regular-file tar.gz extractor for trusted managed-language archives. */
final class TarGzExtractor {
    interface Cancellation {
        boolean isCancelled();
    }

    private static final int BLOCK_SIZE = 512;

    private TarGzExtractor() {
    }

    static void extract(Path archive, Path destination, Cancellation cancellation) throws IOException {
        if (archive == null || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("archive is unavailable");
        if (destination == null) throw new IOException("archive destination is required");
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (InputStream raw = Files.newInputStream(archive); InputStream gzip = new GZIPInputStream(new BufferedInputStream(raw))) {
            byte[] header = new byte[BLOCK_SIZE];
            while (readBlock(gzip, header)) {
                if (cancelled(cancellation)) throw new InterruptedException("archive extraction cancelled");
                if (allZero(header)) break;
                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (name.isEmpty()) throw new IOException("archive entry has no name");
                long size = octal(header, 124, 12);
                int type = header[156] & 0xff;
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) throw new IOException("archive entry escapes managed cache: " + name);
                if (type == 0 || type == '0') {
                    Files.createDirectories(target.getParent());
                    copy(gzip, target, size, cancellation);
                } else if (type == '5') {
                    Files.createDirectories(target);
                    skip(gzip, size, cancellation);
                } else {
                    throw new IOException("managed archive contains unsupported entry type: " + name);
                }
                skip(gzip, padding(size), cancellation);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error.getMessage(), error);
        }
    }

    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = input.read(block, offset, block.length - offset);
            if (read < 0) return offset == 0 ? false : failTruncated();
            offset += read;
        }
        return true;
    }

    private static boolean failTruncated() throws IOException {
        throw new IOException("archive header is truncated");
    }

    private static void copy(InputStream input, Path target, long size, Cancellation cancellation) throws IOException, InterruptedException {
        try (var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                if (cancelled(cancellation)) throw new InterruptedException("archive extraction cancelled");
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("archive payload is truncated");
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static void skip(InputStream input, long size, Cancellation cancellation) throws IOException, InterruptedException {
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            if (cancelled(cancellation)) throw new InterruptedException("archive extraction cancelled");
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("archive payload is truncated");
            remaining -= read;
        }
    }

    private static long padding(long size) {
        long remainder = size % BLOCK_SIZE;
        return remainder == 0 ? 0 : BLOCK_SIZE - remainder;
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) if (current != 0) return false;
        return true;
    }

    private static String text(byte[] value, int offset, int length) {
        int end = offset;
        while (end < offset + length && value[end] != 0) end++;
        return new String(Arrays.copyOfRange(value, offset, end), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] value, int offset, int length) throws IOException {
        String raw = text(value, offset, length).strip();
        if (raw.isEmpty()) return 0;
        try { return Long.parseLong(raw, 8); }
        catch (NumberFormatException error) { throw new IOException("archive entry size is invalid", error); }
    }

    private static boolean cancelled(Cancellation cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }
}
