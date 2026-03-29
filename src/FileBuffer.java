// File Buffer Class
// Manages individual file state including scratch buffers, file metadata, and persistence

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.undo.UndoManager;

public class FileBuffer {
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 100L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 50000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;

    private File file;
    private String scratchName;
    private String content;
    private boolean modified;
    private String encodingName;
    private int lineCount;
    private final UndoManager undoManager;
    private String lineEnding;
    private FileType fileType;
    private final Map<Character, Integer> marks;
    private long lastKnownModifiedTime;
    private boolean scratch;
    private boolean largeFile;
    private String largeFileTail;
    private File backupFile;
    private boolean showingPreviewOnly;
    private long fileSizeBytes;

    // Constructor for existing file
    public FileBuffer(File file) throws IOException {
        this(file, null);
    }

    public FileBuffer(File file, ConfigManager configManager) throws IOException {
        this.undoManager = new UndoManager();
        this.marks = new LinkedHashMap<>();
        this.file = file;
        this.scratch = false;
        this.scratchName = file == null ? "[No Name]" : file.getName();
        this.encodingName = StandardCharsets.UTF_8.name();
        this.lineEnding = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";
        this.largeFile = false;
        this.largeFileTail = null;
        this.showingPreviewOnly = false;
        this.fileSizeBytes = 0L;
        load(configManager);
    }

    // Constructor for new unsaved file
    public FileBuffer(String filename) {
        this.undoManager = new UndoManager();
        this.marks = new LinkedHashMap<>();
        this.file = filename == null ? null : new File(filename);
        this.scratch = false;
        this.scratchName = filename == null || filename.isEmpty() ? "[No Name]" : filename;
        this.content = "";
        this.modified = false;
        this.encodingName = StandardCharsets.UTF_8.name();
        this.lineEnding = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";
        this.fileType = FileType.UNKNOWN;
        this.lineCount = 0;
        this.largeFile = false;
        this.largeFileTail = null;
        this.backupFile = buildBackupFile(this.file);
        this.showingPreviewOnly = false;
        this.lastKnownModifiedTime = 0L;
        this.fileSizeBytes = 0L;
    }

    public static FileBuffer createScratch(String name, String content) {
        FileBuffer buffer = new FileBuffer((String) null);
        buffer.scratch = true;
        buffer.scratchName = name == null || name.isEmpty() ? "[Scratch]" : name;
        buffer.file = null;
        buffer.backupFile = null;
        buffer.fileType = FileType.TEXT;
        buffer.setContent(content == null ? "" : content, false);
        buffer.setModified(false);
        return buffer;
    }

    // Load file content from disk
    public void load() throws IOException {
        load(null);
    }

    public void load(ConfigManager configManager) throws IOException {
        if (scratch || file == null) {
            return;
        }

        byte[] bytes = Files.readAllBytes(file.toPath());
        this.fileSizeBytes = bytes.length;
        DecodedContent decoded = decode(bytes);
        this.encodingName = decoded.charsetName;
        this.lineEnding = detectLineEnding(decoded.content);
        this.fileType = FileType.detect(file, decoded.content);
        this.backupFile = buildBackupFile(file);
        this.lastKnownModifiedTime = file.exists() ? file.lastModified() : 0L;

        String normalized = normalizeLineEndings(decoded.content);
        LargeFilePolicy policy = resolveLargeFilePolicy(configManager);
        int detectedLineCount = countLines(normalized);
        boolean isLargeBySize = bytes.length > policy.maxSizeBytes;
        boolean isLargeByLines = detectedLineCount > policy.maxLineCount;
        this.largeFile = isLargeBySize || isLargeByLines;

        if (largeFile) {
            this.showingPreviewOnly = true;
            String[] lines = normalized.split("\n", -1);
            int previewLineCount = Math.min(policy.previewLineCount, lines.length);
            StringBuilder previewBuilder = new StringBuilder();
            for (int i = 0; i < previewLineCount; i++) {
                if (i > 0) {
                    previewBuilder.append("\n");
                }
                previewBuilder.append(lines[i]);
            }
            if (previewLineCount < lines.length) {
                if (previewBuilder.length() > 0) {
                    previewBuilder.append("\n");
                }
                previewBuilder.append("[shed large-file preview: remaining content hidden until save or reload]");
            }
            this.largeFileTail = buildTail(lines, previewLineCount);
            this.content = previewBuilder.toString();
        } else {
            this.showingPreviewOnly = false;
            this.largeFileTail = null;
            this.content = normalized;
        }

        this.modified = false;
        updateLineCount();
    }

    private LargeFilePolicy resolveLargeFilePolicy(ConfigManager configManager) {
        long maxMb = configManager == null ? DEFAULT_LARGE_FILE_THRESHOLD_MB : configManager.getLargeFileThresholdMb();
        int maxLines = configManager == null ? DEFAULT_LARGE_FILE_LINE_THRESHOLD : configManager.getLargeFileLineThreshold();
        int previewLines = configManager == null ? DEFAULT_LARGE_FILE_PREVIEW_LINES : configManager.getLargeFilePreviewLines();
        return new LargeFilePolicy(Math.max(1L, maxMb) * 1024L * 1024L, Math.max(1000, maxLines), Math.max(50, previewLines));
    }

    private String buildTail(String[] lines, int previewLineCount) {
        if (previewLineCount >= lines.length) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = previewLineCount; i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String detectLineEnding(String content) {
        if (content.contains("\r\n")) {
            return "\r\n";
        }
        if (content.contains("\r")) {
            return "\r";
        }
        return "\n";
    }

    private static DecodedContent decode(byte[] bytes) {
        if (bytes.length >= 3
            && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB
            && bytes[2] == (byte) 0xBF) {
            return new DecodedContent(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8.name());
        }

        if (bytes.length >= 2) {
            if (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                return new DecodedContent(new String(bytes, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE.name());
            }
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                return new DecodedContent(new String(bytes, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE.name());
            }
        }

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return new DecodedContent(decoded.toString(), StandardCharsets.UTF_8.name());
        } catch (CharacterCodingException ignored) {
            return new DecodedContent(new String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.name());
        }
    }

    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    // Save content to disk
    public void save() throws IOException {
        if (scratch || file == null) {
            throw new IOException("Scratch buffer has no file path; use :w <file>");
        }

        String textToWrite = getFullContent();
        String contentWithLineEndings = applyLineEndings(textToWrite);
        byte[] bytes = encode(contentWithLineEndings);

        Path target = file.toPath();
        Path tempFile = Files.createTempFile(target.getParent(), "shed-", ".tmp");
        Files.write(tempFile, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        this.modified = false;
        this.lastKnownModifiedTime = file.lastModified();
        removeBackup();
        this.fileSizeBytes = bytes.length;
        this.fileType = FileType.detect(file, textToWrite);
    }

    // Save to a different file
    public void saveAs(File newFile) throws IOException {
        this.file = newFile;
        this.scratch = false;
        this.scratchName = newFile == null ? "[No Name]" : newFile.getName();
        this.backupFile = buildBackupFile(newFile);
        this.fileType = FileType.detect(newFile, getFullContent());
        save();
    }

    private byte[] encode(String content) throws IOException {
        try {
            Charset charset = Charset.forName(encodingName);
            return content.getBytes(charset);
        } catch (UnsupportedCharsetException e) {
            throw new IOException("Unsupported encoding: " + encodingName, e);
        }
    }

    private String applyLineEndings(String text) {
        if ("\r\n".equals(lineEnding)) {
            return text.replace("\n", "\r\n");
        }
        if ("\r".equals(lineEnding)) {
            return text.replace("\n", "\r");
        }
        return text;
    }

    // Update content and mark as modified
    public void setContent(String content) {
        setContent(content, true);
    }

    // Update content while explicitly controlling modification state
    public void setContent(String content, boolean modified) {
        this.content = content == null ? "" : content;
        this.modified = modified;
        this.fileType = FileType.detect(file, getFullContent());
        updateLineCount();
    }

    public String getContent() {
        return content == null ? "" : content;
    }

    public String getFullContent() {
        if (largeFile && largeFileTail != null && !getContent().contains("[shed large-file preview:")) {
            return getContent() + (getContent().endsWith("\n") || largeFileTail.startsWith("\n") ? "" : "\n") + largeFileTail;
        }
        return getContent();
    }

    private void updateLineCount() {
        this.lineCount = countLines(getContent());
    }

    public String getDisplayName() {
        if (scratch) {
            return scratchName;
        }
        if (file == null) {
            return scratchName == null ? "[No Name]" : scratchName;
        }
        return file.getName();
    }

    public String getFilePath() {
        return file == null ? null : file.getAbsolutePath();
    }

    public boolean hasFilePath() {
        return file != null;
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public File getFile() {
        return file;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getEncoding() {
        return encodingName;
    }

    public void setEncoding(String encodingName) {
        this.encodingName = encodingName == null || encodingName.isEmpty() ? StandardCharsets.UTF_8.name() : encodingName;
    }

    public UndoManager getUndoManager() {
        return undoManager;
    }

    public boolean exists() {
        return file != null && file.exists();
    }

    public boolean isScratch() {
        return scratch;
    }

    public void setScratch(boolean scratch) {
        this.scratch = scratch;
    }

    public FileType getFileType() {
        return fileType == null ? FileType.UNKNOWN : fileType;
    }

    public void setMark(char mark, int offset) {
        marks.put(mark, Math.max(0, offset));
    }

    public Integer getMark(char mark) {
        return marks.get(mark);
    }

    public Map<Character, Integer> getMarks() {
        return Collections.unmodifiableMap(marks);
    }

    public String getLineEndingLabel() {
        if ("\r\n".equals(lineEnding)) {
            return "CRLF";
        }
        if ("\r".equals(lineEnding)) {
            return "CR";
        }
        return "LF";
    }

    public String getLineEnding() {
        return lineEnding;
    }

    public void setLineEnding(String lineEnding) {
        if ("\r\n".equals(lineEnding) || "\r".equals(lineEnding) || "\n".equals(lineEnding)) {
            this.lineEnding = lineEnding;
        }
    }

    public boolean hasExternalChanges() {
        return file != null && file.exists() && file.lastModified() > lastKnownModifiedTime;
    }

    public void refreshExternalTimestamp() {
        if (file != null && file.exists()) {
            this.lastKnownModifiedTime = file.lastModified();
        }
    }

    public boolean isLargeFile() {
        return largeFile;
    }

    public boolean isShowingPreviewOnly() {
        return showingPreviewOnly;
    }

    public void expandLargeFilePreview() {
        if (largeFile && largeFileTail != null && content != null && content.contains("[shed large-file preview:")) {
            int markerIndex = content.indexOf("[shed large-file preview:");
            String visibleContent = markerIndex > 0 ? content.substring(0, markerIndex) : "";
            if (visibleContent.endsWith("\n")) {
                visibleContent = visibleContent.substring(0, visibleContent.length() - 1);
            }
            this.content = visibleContent + (visibleContent.isEmpty() || largeFileTail.isEmpty() ? "" : "\n") + largeFileTail;
            this.largeFileTail = null;
            this.showingPreviewOnly = false;
            updateLineCount();
        }
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void createBackup() throws IOException {
        if (scratch || backupFile == null || !modified) {
            return;
        }
        Files.write(backupFile.toPath(), encode(applyLineEndings(getFullContent())), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void removeBackup() {
        if (backupFile != null && backupFile.exists()) {
            backupFile.delete();
        }
    }

    public File getBackupFile() {
        return backupFile;
    }

    private static File buildBackupFile(File sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        return new File(sourceFile.getParentFile(), "." + sourceFile.getName() + ".swp");
    }

    private static class LargeFilePolicy {
        private final long maxSizeBytes;
        private final int maxLineCount;
        private final int previewLineCount;

        private LargeFilePolicy(long maxSizeBytes, int maxLineCount, int previewLineCount) {
            this.maxSizeBytes = maxSizeBytes;
            this.maxLineCount = maxLineCount;
            this.previewLineCount = previewLineCount;
        }
    }

    private static class DecodedContent {
        private final String content;
        private final String charsetName;

        private DecodedContent(String content, String charsetName) {
            this.content = content;
            this.charsetName = charsetName;
        }
    }
}
