package shed;

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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;

public class FileBuffer {
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 100L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 50000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;
    private static final String LARGE_FILE_PREVIEW_MARKER = "[shed large-file preview: remaining content is not loaded]";
    private static final AtomicLong BACKUP_STAMP = new AtomicLong();

    private File file;
    private String scratchName;
    private boolean modified;
    private String encodingName;
    private int lineCount;
    private final BoundedUndoManager undoManager;
    private final PlainDocument document;
    private String lineEnding;
    private FileType fileType;
    private final Map<Character, Integer> marks;
    private long lastKnownModifiedTime;
    private String savedContent;
    private boolean scratch;
    private boolean largeFile;
    private String largeFileTail;
    private LargeFileStore largeFileStore;
    private String largeFileError;
    private File backupFile;
    private boolean showingPreviewOnly;
    private long fileSizeBytes;
    private ConfigManager configManager;
    private ExternalFileStamp externalFileStamp;

    // Constructor for existing file
    public FileBuffer(File file) throws IOException {
        this(file, null);
    }

    public FileBuffer(File file, ConfigManager configManager) throws IOException {
        this.undoManager = new BoundedUndoManager(configManager == null
            ? UndoHistoryPolicy.defaults() : configManager.getUndoHistoryPolicy());
        this.document = new PlainDocument();
        this.document.addUndoableEditListener(undoManager);
        this.marks = new LinkedHashMap<>();
        this.configManager = configManager;
        this.file = file;
        this.scratch = false;
        this.scratchName = file == null ? "[No Name]" : file.getName();
        this.encodingName = StandardCharsets.UTF_8.name();
        this.lineEnding = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";
        this.largeFile = false;
        this.largeFileTail = null;
        this.largeFileStore = null;
        this.largeFileError = null;
        this.showingPreviewOnly = false;
        this.fileSizeBytes = 0L;
        load(configManager);
    }

    // Constructor for new unsaved file
    public FileBuffer(String filename) {
        this(filename, null);
    }

    FileBuffer(String filename, ConfigManager configManager) {
        this.undoManager = new BoundedUndoManager(configManager == null
            ? UndoHistoryPolicy.defaults() : configManager.getUndoHistoryPolicy());
        this.document = new PlainDocument();
        this.document.addUndoableEditListener(undoManager);
        this.marks = new LinkedHashMap<>();
        this.configManager = configManager;
        this.file = filename == null ? null : new File(filename);
        this.scratch = false;
        this.scratchName = filename == null || filename.isEmpty() ? "[No Name]" : filename;
        this.modified = false;
        this.encodingName = StandardCharsets.UTF_8.name();
        this.lineEnding = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";
        this.fileType = FileType.UNKNOWN;
        this.lineCount = 0;
        this.largeFile = false;
        this.largeFileTail = null;
        this.largeFileStore = null;
        this.largeFileError = null;
        this.backupFile = null;
        this.showingPreviewOnly = false;
        this.lastKnownModifiedTime = 0L;
        this.fileSizeBytes = 0L;
        this.externalFileStamp = observeExternalFile();
        setDocumentText("", false);
        this.savedContent = "";
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
        if (configManager != null) {
            this.configManager = configManager;
        }
        if (scratch || file == null) {
            return;
        }

        LargeFilePolicy policy = resolveLargeFilePolicy(configManager);
        Path source = file.toPath();
        long sourceBytes = Files.size(source);
        if (sourceBytes > policy.maxSizeBytes || LargeFileStore.exceedsLineLimit(source, policy.maxLineCount)) {
            loadLargeFile(source, sourceBytes, policy);
            return;
        }

        this.largeFileStore = null;
        this.largeFileError = null;
        byte[] bytes = Files.readAllBytes(source);
        this.fileSizeBytes = bytes.length;
        DecodedContent decoded = decode(bytes);
        this.encodingName = decoded.charsetName;
        this.lineEnding = detectLineEnding(decoded.content);
        this.fileType = FileType.detect(file, decoded.content);
        this.backupFile = null;
        this.lastKnownModifiedTime = file.exists() ? file.lastModified() : 0L;

        String normalized = normalizeLineEndings(decoded.content);
        int detectedLineCount = countLines(normalized);
        this.largeFile = false;
        this.showingPreviewOnly = false;
        this.largeFileTail = null;
        setDocumentText(normalized, false);

        updateLineCount();
        this.savedContent = getFullContent();
        this.externalFileStamp = observeExternalFile();
    }

    private void loadLargeFile(Path source, long sourceBytes, LargeFilePolicy policy) {
        this.fileSizeBytes = sourceBytes;
        this.backupFile = null;
        this.lastKnownModifiedTime = file.exists() ? file.lastModified() : 0L;
        this.largeFile = true;
        this.showingPreviewOnly = true;
        this.largeFileTail = null;
        LargeFileStore.OpenResult result = LargeFileStore.open(source, policy.previewLineCount);
        if (result.opened()) {
            this.largeFileStore = result.store();
            this.largeFileError = null;
            this.encodingName = StandardCharsets.UTF_8.name();
            this.lineEnding = largeFileStore.lineEnding();
            this.fileType = FileType.detect(file, largeFileStore.preview());
            String preview = largeFileStore.preview();
            if (largeFileStore.previewTruncated()) {
                preview += (preview.isEmpty() || preview.endsWith("\n") ? "" : "\n") + LARGE_FILE_PREVIEW_MARKER;
            }
            setDocumentText(preview, false);
        } else {
            this.largeFileStore = null;
            this.largeFileError = result.error();
            this.encodingName = StandardCharsets.UTF_8.name();
            this.lineEnding = "\n";
            this.fileType = FileType.detect(file, "");
            setDocumentText("[shed large-file unavailable: " + largeFileError + "]", false);
        }
        updateLineCount();
        this.savedContent = "";
        this.externalFileStamp = observeExternalFile();
    }

    private LargeFilePolicy resolveLargeFilePolicy(ConfigManager configManager) {
        long maxMb = configManager == null ? DEFAULT_LARGE_FILE_THRESHOLD_MB : configManager.getLargeFileThresholdMb();
        int maxLines = configManager == null ? DEFAULT_LARGE_FILE_LINE_THRESHOLD : configManager.getLargeFileLineThreshold();
        int previewLines = configManager == null ? DEFAULT_LARGE_FILE_PREVIEW_LINES : configManager.getLargeFilePreviewLines();
        return new LargeFilePolicy(Math.max(1L, maxMb) * 1024L * 1024L, Math.max(1000, maxLines), Math.max(50, previewLines));
    }

    void applyUndoHistoryPolicy() {
        undoManager.configure(resolveUndoHistoryPolicy());
    }

    private UndoHistoryPolicy resolveUndoHistoryPolicy() {
        return configManager == null ? UndoHistoryPolicy.defaults() : configManager.getUndoHistoryPolicy();
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
        if (largeFile) {
            if (largeFileStore == null) {
                throw new IOException("Large-file source is unavailable");
            }
            Path target = file.toPath();
            AtomicFileWriter.writeStream(target, largeFileStore::writeTo);
            this.modified = false;
            this.savedContent = "";
            this.lastKnownModifiedTime = Files.getLastModifiedTime(target).toMillis();
            this.fileSizeBytes = Files.size(target);
            this.externalFileStamp = observeExternalFile();
            return;
        }

        String textToWrite = getFullContent();
        String contentWithLineEndings = applyLineEndings(textToWrite);
        byte[] bytes;
        try {
            bytes = encode(contentWithLineEndings);
        } catch (IOException error) {
            throw new IOException("Save failed for " + file + ": content cannot be encoded. Check the selected encoding and retry.", error);
        }

        Path target = file.toPath();
        AtomicFileWriter.write(target, bytes);

        this.modified = false;
        this.savedContent = textToWrite;
        this.lastKnownModifiedTime = Files.getLastModifiedTime(target).toMillis();
        this.fileSizeBytes = bytes.length;
        this.fileType = FileType.detect(file, textToWrite);
        this.externalFileStamp = observeExternalFile();
    }

    // Save to a different file
    public void saveAs(File newFile) throws IOException {
        if (newFile == null) {
            throw new IOException("Save target is required; use :w <file>");
        }
        if (largeFile) {
            throw new IOException("Large-file save as is unavailable until bounded editing support is enabled");
        }
        File previousFile = this.file;
        String previousScratchName = this.scratchName;
        boolean previousScratch = this.scratch;
        File previousBackupFile = this.backupFile;
        FileType previousFileType = this.fileType;
        this.file = newFile;
        this.scratch = false;
        this.scratchName = newFile == null ? "[No Name]" : newFile.getName();
        this.backupFile = null;
        this.fileType = FileType.detect(newFile, getFullContent());
        try {
            save();
        } catch (IOException error) {
            this.file = previousFile;
            this.scratchName = previousScratchName;
            this.scratch = previousScratch;
            this.backupFile = previousBackupFile;
            this.fileType = previousFileType;
            throw error;
        }
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
        if (largeFile) {
            throw new IllegalStateException("Large-file editing is unavailable until bounded editing support is enabled");
        }
        String normalized = content == null ? "" : content;
        setDocumentText(normalized, modified);
        if (!modified) {
            this.savedContent = normalized;
        }
    }

    public String getContent() {
        try {
            return document.getText(0, document.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    public String getSavedContent() {
        return savedContent;
    }

    public String getFullContent() {
        if (largeFile) {
            throw new IllegalStateException("Large-file content is not materialized");
        }
        String content = getContent();
        if (largeFile && largeFileTail != null) {
            String visibleContent = removeLargeFilePreviewMarker(content);
            return visibleContent + (visibleContent.isEmpty() || visibleContent.endsWith("\n") || largeFileTail.startsWith("\n") ? "" : "\n") + largeFileTail;
        }
        return content;
    }

    private String removeLargeFilePreviewMarker(String content) {
        int markerIndex = content.indexOf(LARGE_FILE_PREVIEW_MARKER);
        if (markerIndex < 0) {
            return content;
        }
        int start = markerIndex;
        int end = markerIndex + LARGE_FILE_PREVIEW_MARKER.length();
        if (start > 0 && content.charAt(start - 1) == '\n') {
            start--;
        } else if (end < content.length() && content.charAt(end) == '\n') {
            end++;
        }
        return content.substring(0, start) + content.substring(end);
    }

    private void updateLineCount() {
        this.lineCount = countLines(getContent());
    }

    public PlainDocument getDocument() {
        return document;
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

    void retargetFile(File newFile) {
        this.file = newFile;
        this.scratch = false;
        this.scratchName = newFile == null ? "[No Name]" : newFile.getName();
        this.backupFile = null;
        this.fileType = FileType.detect(newFile, getFullContent());
        this.lastKnownModifiedTime = newFile != null && newFile.exists() ? newFile.lastModified() : 0L;
        this.externalFileStamp = observeExternalFile();
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
        return getExternalFileState() != ExternalFileState.UNCHANGED;
    }

    public void refreshExternalTimestamp() {
        externalFileStamp = observeExternalFile();
        if (file != null && file.exists()) {
            lastKnownModifiedTime = file.lastModified();
        }
    }

    public ExternalFileState getExternalFileState() {
        ExternalFileStamp current = observeExternalFile();
        if (externalFileStamp == null || current.kind() == ExternalFileKind.REGULAR && externalFileStamp.kind() == ExternalFileKind.REGULAR
            && current.equals(externalFileStamp)) {
            return ExternalFileState.UNCHANGED;
        }
        return switch (current.kind()) {
            case MISSING -> externalFileStamp != null && externalFileStamp.kind() == ExternalFileKind.MISSING
                ? ExternalFileState.UNCHANGED : ExternalFileState.DELETED;
            case UNSUPPORTED -> externalFileStamp != null && externalFileStamp.kind() == ExternalFileKind.UNSUPPORTED
                ? ExternalFileState.UNCHANGED : ExternalFileState.UNSUPPORTED;
            case REGULAR -> {
                if (externalFileStamp == null) {
                    yield ExternalFileState.UNCHANGED;
                }
                if (externalFileStamp.kind() != ExternalFileKind.REGULAR || !sameIdentity(current, externalFileStamp)) {
                    yield ExternalFileState.REPLACED;
                }
                yield ExternalFileState.EXTERNALLY_CHANGED;
            }
        };
    }

    private boolean sameIdentity(ExternalFileStamp current, ExternalFileStamp previous) {
        if (current.fileKey() != null && previous.fileKey() != null && !current.fileKey().equals(previous.fileKey())) {
            return false;
        }
        return current.creationTime().equals(previous.creationTime());
    }

    private ExternalFileStamp observeExternalFile() {
        if (file == null) {
            return new ExternalFileStamp(ExternalFileKind.MISSING, null, null, null, 0L);
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                return new ExternalFileStamp(ExternalFileKind.UNSUPPORTED, null, null, null, 0L);
            }
            return new ExternalFileStamp(ExternalFileKind.REGULAR, attributes.fileKey(), attributes.creationTime(),
                attributes.lastModifiedTime(), attributes.size());
        } catch (java.nio.file.NoSuchFileException error) {
            return new ExternalFileStamp(ExternalFileKind.MISSING, null, null, null, 0L);
        } catch (IOException | SecurityException error) {
            return new ExternalFileStamp(ExternalFileKind.UNSUPPORTED, null, null, null, 0L);
        }
    }

    public boolean isLargeFile() {
        return largeFile;
    }

    public boolean isLargeFileUnavailable() {
        return largeFile && largeFileStore == null;
    }

    public String getLargeFileStatus() {
        if (!largeFile) {
            return "";
        }
        if (largeFileError != null) {
            return largeFileError;
        }
        return "bounded preview: " + largeFileStore.byteSize() + " bytes, " + largeFileStore.lineCount() + " lines";
    }

    public long getLargeFileLineCount() {
        return largeFileStore == null ? 0L : largeFileStore.lineCount();
    }

    void showLargeFileWindow(long firstLine, int requestedLines) throws IOException {
        if (largeFileStore == null) {
            throw new IOException("Large-file source is unavailable");
        }
        LargeFileStore.Window window = largeFileStore.readWindow(firstLine, requestedLines);
        String content = window.content();
        if (window.truncated()) {
            content += (content.isEmpty() || content.endsWith("\n") ? "" : "\n") + LARGE_FILE_PREVIEW_MARKER;
        }
        setDocumentText(content, false);
    }

    public boolean isShowingPreviewOnly() {
        return showingPreviewOnly;
    }

    public void expandLargeFilePreview() {
        if (largeFileStore != null || largeFileError != null) {
            return;
        }
        String content = getContent();
        if (largeFile && largeFileTail != null && content.contains("[shed large-file preview:")) {
            int markerIndex = content.indexOf("[shed large-file preview:");
            String visibleContent = markerIndex > 0 ? content.substring(0, markerIndex) : "";
            if (visibleContent.endsWith("\n")) {
                visibleContent = visibleContent.substring(0, visibleContent.length() - 1);
            }
            setDocumentText(visibleContent + (visibleContent.isEmpty() || largeFileTail.isEmpty() ? "" : "\n") + largeFileTail, modified);
            this.largeFileTail = null;
            this.showingPreviewOnly = false;
            updateLineCount();
        }
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void createBackup() throws IOException {
        if (scratch || file == null || !modified || largeFile) {
            return;
        }
        BackupPolicy policy = backupPolicy();
        if (!policy.enabled()) {
            return;
        }
        Path directory = policy.directoryPath();
        Files.createDirectories(directory);
        String key = backupKey(file.toPath());
        Path target = reserveBackupPath(directory, key);
        try {
            AtomicFileWriter.write(target, encode(applyLineEndings(getFullContent())));
        } catch (IOException error) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        backupFile = target.toFile();
        pruneBackups(directory, key, policy.retentionCount());
    }

    public void removeBackup() {
        if (backupFile != null && backupFile.exists()) {
            backupFile.delete();
        }
    }

    public File getBackupFile() {
        return backupFile;
    }

    private void setDocumentText(String text, boolean modified) {
        try {
            document.remove(0, document.getLength());
            document.insertString(0, text == null ? "" : text, null);
        } catch (BadLocationException e) {
            throw new IllegalStateException("Unable to update buffer document", e);
        }
        undoManager.discardAllEdits();
        this.modified = modified;
        this.fileType = FileType.detect(file, largeFile ? getContent() : getFullContent());
        updateLineCount();
    }

    private BackupPolicy backupPolicy() throws IOException {
        if (configManager != null) {
            try {
                return configManager.getBackupPolicy();
            } catch (RuntimeException error) {
                throw new IOException("Backup configuration is invalid; set backup.directory to a valid path", error);
            }
        }
        Path parent = file.toPath().toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Backup directory is unavailable");
        }
        return new BackupPolicy(true, parent.toString(), 1);
    }

    private static String backupKey(Path source) throws IOException {
        String name = source.getFileName() == null ? "buffer" : source.getFileName().toString();
        String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(source.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return safeName + "-" + java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable for backup naming", error);
        }
    }

    private static long nextBackupStamp() {
        long now = System.currentTimeMillis();
        return BACKUP_STAMP.updateAndGet(previous -> Math.max(now, previous + 1));
    }

    private static Path reserveBackupPath(Path directory, String key) throws IOException {
        while (true) {
            Path target = directory.resolve(key + "-" + nextBackupStamp() + "-" + UUID.randomUUID() + ".bak");
            try {
                return Files.createFile(target);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
        }
    }

    private static void pruneBackups(Path directory, String key, int retentionCount) throws IOException {
        List<Path> backups;
        try (Stream<Path> paths = Files.list(directory)) {
            backups = paths.filter(path -> path.getFileName().toString().startsWith(key + "-"))
                .filter(path -> path.getFileName().toString().endsWith(".bak"))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList();
        }
        for (int index = retentionCount; index < backups.size(); index++) {
            Files.delete(backups.get(index));
        }
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

    public enum ExternalFileState {
        UNCHANGED,
        EXTERNALLY_CHANGED,
        DELETED,
        REPLACED,
        UNSUPPORTED
    }

    private enum ExternalFileKind {
        MISSING,
        REGULAR,
        UNSUPPORTED
    }

    private record ExternalFileStamp(ExternalFileKind kind, Object fileKey, FileTime creationTime, FileTime modifiedTime, long size) {
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
