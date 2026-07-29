package shed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

final class LargeFileStore {
    static final int PAGE_BYTES = 64 * 1024;
    static final int MAX_PREVIEW_CHARS = 256 * 1024;

    private final Path source;
    private final long byteSize;
    private final long lineCount;
    private final String preview;
    private final boolean previewTruncated;
    private final String lineEnding;

    private LargeFileStore(Path source, long byteSize, long lineCount, String preview, boolean previewTruncated, String lineEnding) {
        this.source = source;
        this.byteSize = byteSize;
        this.lineCount = lineCount;
        this.preview = preview;
        this.previewTruncated = previewTruncated;
        this.lineEnding = lineEnding;
    }

    static boolean exceedsLineLimit(Path source, int maximumLines) throws IOException {
        if (maximumLines < 1) {
            throw new IllegalArgumentException("maximum lines must be positive");
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer page = ByteBuffer.allocate(PAGE_BYTES);
            boolean hasContent = false;
            boolean previousCarriageReturn = false;
            long lineBreaks = 0L;
            while (channel.read(page) >= 0) {
                page.flip();
                while (page.hasRemaining()) {
                    byte value = page.get();
                    hasContent = true;
                    if (value == '\r') {
                        lineBreaks++;
                        previousCarriageReturn = true;
                    } else if (value == '\n') {
                        if (!previousCarriageReturn) {
                            lineBreaks++;
                        }
                        previousCarriageReturn = false;
                    } else {
                        previousCarriageReturn = false;
                    }
                    if (hasContent && lineBreaks + 1L > maximumLines) {
                        return true;
                    }
                }
                page.clear();
            }
            return false;
        }
    }

    static OpenResult open(Path input, int previewLines) {
        if (previewLines < 1) {
            return OpenResult.failure("preview line limit must be positive");
        }
        Path source = input.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                return OpenResult.failure("large-file path requires a regular non-symlink file");
            }
            Scan scan;
            try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
                scan = scan(channel, previewLines);
            }
            return OpenResult.success(new LargeFileStore(source, attributes.size(), scan.lineCount(), scan.preview(),
                scan.previewTruncated(), scan.lineEnding()));
        } catch (CharacterCodingException error) {
            return OpenResult.failure("large-file path requires well-formed UTF-8");
        } catch (IOException | SecurityException error) {
            String message = error.getMessage();
            return OpenResult.failure(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
        }
    }

    private static Scan scan(FileChannel channel, int previewLines) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes = ByteBuffer.allocate(PAGE_BYTES + 4);
        CharBuffer characters = CharBuffer.allocate(PAGE_BYTES);
        ScanBuilder builder = new ScanBuilder(previewLines);
        boolean endOfInput = false;
        while (!endOfInput) {
            int read = channel.read(bytes);
            endOfInput = read < 0;
            bytes.flip();
            decode(decoder, bytes, characters, endOfInput, builder);
            bytes.compact();
        }
        flush(decoder, characters, builder);
        return builder.build();
    }

    private static void decode(CharsetDecoder decoder, ByteBuffer bytes, CharBuffer characters, boolean endOfInput,
                               ScanBuilder builder) throws CharacterCodingException {
        while (true) {
            CoderResult result = decoder.decode(bytes, characters, endOfInput);
            append(characters, builder);
            if (result.isError()) {
                result.throwException();
            }
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private static void flush(CharsetDecoder decoder, CharBuffer characters, ScanBuilder builder) throws CharacterCodingException {
        while (true) {
            CoderResult result = decoder.flush(characters);
            append(characters, builder);
            if (result.isError()) {
                result.throwException();
            }
            if (!result.isOverflow()) {
                return;
            }
        }
    }

    private static void append(CharBuffer characters, ScanBuilder builder) {
        characters.flip();
        while (characters.hasRemaining()) {
            builder.append(characters.get());
        }
        characters.clear();
    }

    Path source() {
        return source;
    }

    long byteSize() {
        return byteSize;
    }

    long lineCount() {
        return lineCount;
    }

    String preview() {
        return preview;
    }

    boolean previewTruncated() {
        return previewTruncated;
    }

    String lineEnding() {
        return lineEnding;
    }

    record OpenResult(LargeFileStore store, String error) {
        static OpenResult success(LargeFileStore store) {
            return new OpenResult(store, "");
        }

        static OpenResult failure(String error) {
            return new OpenResult(null, error == null || error.isBlank() ? "large-file open failed" : error);
        }

        boolean opened() {
            return store != null;
        }
    }

    private record Scan(long lineCount, String preview, boolean previewTruncated, String lineEnding) {
    }

    private static final class ScanBuilder {
        private final int previewLines;
        private final StringBuilder preview;
        private boolean firstCharacter = true;
        private boolean hasContent;
        private boolean previousCarriageReturn;
        private long lineBreaks;
        private boolean previewTruncated;
        private String lineEnding = "\n";

        private ScanBuilder(int previewLines) {
            this.previewLines = previewLines;
            this.preview = new StringBuilder(Math.min(MAX_PREVIEW_CHARS, 4096));
        }

        private void append(char value) {
            if (firstCharacter) {
                firstCharacter = false;
                if (value == '\ufeff') {
                    return;
                }
            }
            hasContent = true;
            long currentLine = lineBreaks + 1L;
            if (currentLine <= previewLines && preview.length() < MAX_PREVIEW_CHARS) {
                preview.append(value);
            } else {
                previewTruncated = true;
            }
            if (value == '\r') {
                lineBreaks++;
                previousCarriageReturn = true;
                if (!"\r\n".equals(lineEnding)) {
                    lineEnding = "\r";
                }
            } else if (value == '\n') {
                if (previousCarriageReturn) {
                    lineEnding = "\r\n";
                } else {
                    lineBreaks++;
                }
                previousCarriageReturn = false;
            } else {
                previousCarriageReturn = false;
            }
        }

        private Scan build() {
            return new Scan(hasContent ? lineBreaks + 1L : 0L, preview.toString(), previewTruncated, lineEnding);
        }
    }
}
