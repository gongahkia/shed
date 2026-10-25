package shed;

// File Buffer Class
// Manages individual file state including scratch buffers, file metadata, and persistence

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.undo.UndoManager;

public class FileBuffer {
    private static final long DEFAULT_LARGE_FILE_THRESHOLD_MB = 25L;
    private static final int DEFAULT_LARGE_FILE_LINE_THRESHOLD = 500000;
    private static final int DEFAULT_LARGE_FILE_PREVIEW_LINES = 1000;
    private static final String LARGE_FILE_PREVIEW_MARKER = "[shed large-file preview: remaining content is not loaded]";
    private static final AtomicLong BACKUP_STAMP = new AtomicLong();

    private File file;
    private String scratchName;
    private volatile boolean modified;
    private String encodingName;
    private boolean byteOrderMark;
    private TextFileCodec.Image diskTextImage;
    private boolean explicitLineEnding;
    private int lineCount;
    private final BoundedUndoManager undoManager;
    private final PlainDocument document;
    private VersionedTextSnapshot textSnapshot;
    private long documentRevision;
    private String lineEnding;
    private FileType fileType;
    private final Map<Character, Integer> marks;
    private long lastKnownModifiedTime;
    private String savedContent;
    private boolean scratch;
    private boolean syntaxHighlightingEnabled = true;
    private boolean largeFile;
    private String largeFileTail;
    private LargeFileStore largeFileStore;
    private String largeFileError;
    private volatile File backupFile;
    private boolean showingPreviewOnly;
    private long fileSizeBytes;
    private ConfigManager configManager;
    private ExternalFileStamp externalFileStamp;
    private byte[] savedDiskDigest;
    private long diskBindingRevision;

    // Constructor for existing file
    public FileBuffer(File file) throws IOException {
        this(file, null);
    }

    public FileBuffer(File file, ConfigManager configManager) throws IOException {
        this.undoManager = new BoundedUndoManager(configManager == null
            ? UndoHistoryPolicy.defaults() : configManager.getUndoHistoryPolicy());
        this.document = new PlainDocument();
        trackDocumentRevisions();
        this.document.addUndoableEditListener(undoManager);
        this.textSnapshot = VersionedTextSnapshot.empty();
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
        trackDocumentRevisions();
        this.document.addUndoableEditListener(undoManager);
        this.textSnapshot = VersionedTextSnapshot.empty();
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
        this.savedDiskDigest = null;
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

    public static FileBuffer createPlainScratch(String name, String content) {
        FileBuffer buffer = createScratch(name, content);
        buffer.syntaxHighlightingEnabled = false;
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
        TextFileCodec.Image decoded = TextFileCodec.decode(bytes, true);
        this.diskTextImage = decoded;
        this.explicitLineEnding = false;
        this.encodingName = decoded.encoding();
        this.byteOrderMark = decoded.bom();
        this.lineEnding = detectLineEnding(decoded.rawText());
        this.fileType = FileType.detect(file, decoded.text());
        this.backupFile = null;
        this.lastKnownModifiedTime = file.exists() ? file.lastModified() : 0L;

        String normalized = decoded.text();
        this.largeFile = false;
        this.showingPreviewOnly = false;
        this.largeFileTail = null;
        setDocumentText(normalized, false);

        updateLineCount();
        this.savedContent = getFullContent();
        this.externalFileStamp = observeExternalFile();
        this.savedDiskDigest = digest(bytes);
    }

    void adoptReloadedState(FileBuffer loaded) {
        if (loaded == null || file == null || !file.equals(loaded.file)) {
            throw new IllegalArgumentException("Reloaded buffer must refer to the same file");
        }
        diskBindingRevision++;
        this.encodingName = loaded.encodingName;
        this.byteOrderMark = loaded.byteOrderMark;
        this.diskTextImage = loaded.diskTextImage;
        this.explicitLineEnding = loaded.explicitLineEnding;
        this.lineEnding = loaded.lineEnding;
        this.largeFile = loaded.largeFile;
        this.largeFileTail = loaded.largeFileTail;
        this.largeFileStore = loaded.largeFileStore;
        this.largeFileError = loaded.largeFileError;
        this.showingPreviewOnly = loaded.showingPreviewOnly;
        this.fileSizeBytes = loaded.fileSizeBytes;
        this.lastKnownModifiedTime = loaded.lastKnownModifiedTime;
        this.backupFile = loaded.backupFile;
        setDocumentText(loaded.getContent(), false);
        this.savedContent = loaded.savedContent;
        this.externalFileStamp = loaded.externalFileStamp;
        this.savedDiskDigest = loaded.savedDiskDigest;
    }

    private void loadLargeFile(Path source, long sourceBytes, LargeFilePolicy policy) {
        this.diskTextImage = null;
        this.explicitLineEnding = false;
        this.fileSizeBytes = sourceBytes;
        this.backupFile = null;
        this.lastKnownModifiedTime = file.exists() ? file.lastModified() : 0L;
        this.largeFile = true;
        this.showingPreviewOnly = true;
        this.largeFileTail = null;
        this.byteOrderMark = false;
        LargeFileStore.OpenResult result = LargeFileStore.open(source, policy.previewLineCount);
        if (result.opened()) {
            this.largeFileStore = result.store();
            this.largeFileError = null;
            this.encodingName = StandardCharsets.UTF_8.name();
            try (var input = Files.newInputStream(source)) {
                byte[] prefix = input.readNBytes(3);
                this.byteOrderMark = prefix.length == 3 && prefix[0] == (byte) 0xef
                    && prefix[1] == (byte) 0xbb && prefix[2] == (byte) 0xbf;
            } catch (IOException ignored) { /* The projection is already marked incomplete/unavailable on source errors. */ }
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
        this.savedDiskDigest = null;
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

    private static String detectLineEnding(String content) {
        if (content.contains("\r\n")) {
            return "\r\n";
        }
        if (content.contains("\r")) {
            return "\r";
        }
        return "\n";
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
            if (!modified) return;
            if (largeFileStore == null) {
                throw new IOException("Large-file source is unavailable");
            }
            Path target = file.toPath();
            AtomicFileWriter.writeStream(target, largeFileStore::writeTo, this::requireUnchangedSource);
            this.modified = false;
            this.savedContent = "";
            this.lastKnownModifiedTime = Files.getLastModifiedTime(target).toMillis();
            this.fileSizeBytes = Files.size(target);
            this.externalFileStamp = observeExternalFile();
            this.savedDiskDigest = null;
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
        AtomicFileWriter.write(target, bytes, this::requireUnchangedSource);

        this.modified = false;
        this.savedContent = textToWrite;
        this.lastKnownModifiedTime = Files.getLastModifiedTime(target).toMillis();
        this.fileSizeBytes = bytes.length;
        this.fileType = FileType.detect(file, textToWrite);
        this.externalFileStamp = observeExternalFile();
        this.savedDiskDigest = digest(bytes);
        this.diskTextImage = TextFileCodec.image(contentWithLineEndings, encodingName, byteOrderMark);
        this.explicitLineEnding = false;
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
        ExternalFileStamp previousExternalFileStamp = this.externalFileStamp;
        byte[] previousSavedDiskDigest = this.savedDiskDigest;
        long previousBindingRevision = diskBindingRevision;
        diskBindingRevision++;
        this.file = newFile;
        this.scratch = false;
        this.scratchName = newFile == null ? "[No Name]" : newFile.getName();
        this.backupFile = null;
        this.fileType = FileType.detect(newFile, getFullContent());
        this.externalFileStamp = observeExternalFile();
        this.savedDiskDigest = null;
        try {
            save();
        } catch (IOException error) {
            this.file = previousFile;
            this.scratchName = previousScratchName;
            this.scratch = previousScratch;
            this.backupFile = previousBackupFile;
            this.fileType = previousFileType;
            this.externalFileStamp = previousExternalFileStamp;
            this.savedDiskDigest = previousSavedDiskDigest;
            diskBindingRevision = previousBindingRevision;
            throw error;
        }
    }

    SaveSnapshot captureSaveSnapshot(File requestedTarget) throws IOException {
        if (largeFile) throw new IOException("Large-file asynchronous save is unavailable");
        File target = requestedTarget == null ? file : requestedTarget;
        if (scratch && requestedTarget == null || target == null) {
            throw new IOException("Scratch buffer has no file path; use :w <file>");
        }
        String content = getFullContent();
        ExternalFileStamp expected = requestedTarget == null ? externalFileStamp : observeExternalFile(target);
        return new SaveSnapshot(target, requestedTarget != null, content, encodingName, lineEnding, byteOrderMark, diskTextImage, explicitLineEnding,
            expected, requestedTarget == null ? savedDiskDigest : null, diskBindingRevision);
    }

    void writeSaveSnapshot(SaveSnapshot snapshot) throws IOException {
        if (snapshot == null) throw new IOException("Save snapshot is required");
        String raw = snapshot.diskTextImage != null && !snapshot.explicitLineEnding
            ? snapshot.diskTextImage.render(snapshot.content) : applyLineEndings(snapshot.content, snapshot.lineEnding);
        byte[] bytes = encode(raw, snapshot.encodingName, snapshot.byteOrderMark);
        AtomicFileWriter.write(snapshot.target.toPath(), bytes, () -> {
            ExternalFileStamp actual = observeExternalFile(snapshot.target);
            if (!actual.equals(snapshot.expectedStamp)) {
                throw new IOException("File changed on disk: " + snapshot.target
                    + ". Reload or save to a different path to keep both versions.");
            }
            requireMatchingDigest(snapshot.target, snapshot.expectedDigest);
        });
        snapshot.bytes = bytes;
        snapshot.writtenImage = TextFileCodec.image(raw, snapshot.encodingName, snapshot.byteOrderMark);
        snapshot.writtenDigest = digest(bytes);
    }

    void completeSaveSnapshot(SaveSnapshot snapshot) {
        // A later restore/reload/rename owns the binding, even if this earlier write just completed.
        if (snapshot.bindingRevision != diskBindingRevision) return;
        if (snapshot.saveAs) {
            diskBindingRevision++;
            this.file = snapshot.target;
            this.scratch = false;
            this.scratchName = snapshot.target.getName();
            this.backupFile = null;
        }
        this.savedContent = snapshot.content;
        this.modified = !snapshot.content.equals(getContent()) || !snapshot.encodingName.equals(encodingName)
            || !snapshot.lineEnding.equals(lineEnding) || snapshot.byteOrderMark != byteOrderMark
            || snapshot.explicitLineEnding != explicitLineEnding;
        boolean sameFormat = snapshot.lineEnding.equals(lineEnding) && snapshot.explicitLineEnding == explicitLineEnding;
        this.diskTextImage = snapshot.writtenImage;
        if (sameFormat) explicitLineEnding = false;
        this.lastKnownModifiedTime = snapshot.target.lastModified();
        this.fileSizeBytes = snapshot.bytes.length;
        this.fileType = FileType.detect(snapshot.target, snapshot.content);
        this.externalFileStamp = observeExternalFile();
        this.savedDiskDigest = snapshot.writtenDigest == null ? digest(snapshot.bytes) : snapshot.writtenDigest.clone();
    }

    static final class SaveSnapshot {
        private final File target;
        private final boolean saveAs;
        private final String content;
        private volatile byte[] bytes;
        private final String encodingName;
        private final String lineEnding;
        private final boolean byteOrderMark;
        private final TextFileCodec.Image diskTextImage;
        private final boolean explicitLineEnding;
        private final ExternalFileStamp expectedStamp;
        private final byte[] expectedDigest;
        private volatile byte[] writtenDigest;
        private volatile TextFileCodec.Image writtenImage;
        private final long bindingRevision;

        private SaveSnapshot(File target, boolean saveAs, String content, String encodingName, String lineEnding, boolean byteOrderMark, TextFileCodec.Image diskTextImage, boolean explicitLineEnding,
                             ExternalFileStamp expectedStamp, byte[] expectedDigest, long bindingRevision) {
            this.target = target;
            this.saveAs = saveAs;
            this.content = content;
            this.encodingName = encodingName;
            this.lineEnding = lineEnding;
            this.byteOrderMark = byteOrderMark;
            this.diskTextImage = diskTextImage;
            this.explicitLineEnding = explicitLineEnding;
            this.expectedStamp = expectedStamp;
            this.expectedDigest = expectedDigest == null ? null : expectedDigest.clone();
            this.bindingRevision = bindingRevision;
        }
    }

    private byte[] encode(String content) throws IOException {
        return encode(content, encodingName, byteOrderMark);
    }

    private static byte[] encode(String content, String encodingName, boolean byteOrderMark) throws IOException {
        return TextFileCodec.encodeRaw(content, encodingName, byteOrderMark);
    }

    private String applyLineEndings(String text) {
        return diskTextImage != null && !explicitLineEnding ? diskTextImage.render(text) : applyLineEndings(text, lineEnding);
    }

    private static String applyLineEndings(String text, String lineEnding) {
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

    void replaceContentUndoably(String content) {
        if (largeFile) throw new IllegalStateException("Large-file editing is unavailable");
        String value = content == null ? "" : content;
        if (value.equals(getContent())) return;
        try (TextTransaction transaction = beginTextTransaction()) { transaction.replace(value); transaction.commit(); }
    }

    TextTransaction beginTextTransaction() { return new TextTransaction(); }

    long documentRevision() { return documentRevision; }

    private void trackDocumentRevisions() {
        document.addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(DocumentEvent event) { documentRevision++; }
            public void removeUpdate(DocumentEvent event) { documentRevision++; }
            public void changedUpdate(DocumentEvent event) { documentRevision++; }
        });
    }

    final class TextTransaction implements AutoCloseable {
        private final VersionedTextSnapshot before;
        private final boolean wasModified;
        private final FileType beforeType;
        private final BindingState bindingBefore;
        private boolean finished;
        private TextTransaction() {
            if (largeFile) throw new IllegalStateException("Large-file editing is unavailable");
            reconcileDocumentText();
            before = textSnapshot; wasModified = modified; beforeType = fileType;
            bindingBefore = new BindingState(file, scratchName, scratch, backupFile, diskTextImage, savedContent,
                externalFileStamp, savedDiskDigest, lastKnownModifiedTime, fileSizeBytes, diskBindingRevision);
            undoManager.beginCompoundEdit();
        }
        void replace(String value) {
            if (finished) throw new IllegalStateException("Text transaction is closed");
            var hunks = WorkspaceTextEdits.diff(getContent(), value);
            try {
                for (int i = hunks.size() - 1; i >= 0; i--) {
                    var change = hunks.get(i);
                    if (change.end() > change.start()) document.remove(change.start(), change.end() - change.start());
                    if (!change.replacement().isEmpty()) document.insertString(change.start(), change.replacement(), null);
                }
            } catch (BadLocationException failure) { throw new IllegalStateException("Unable to update buffer document", failure); }
            if (!hunks.isEmpty()) {
                textSnapshot = VersionedTextSnapshot.of(value); modified = true;
                fileType = FileType.detect(file, value); updateLineCount();
            }
        }
        void commit() {
            if (finished) throw new IllegalStateException("Text transaction is closed");
            undoManager.endCompoundEdit(); finished = true;
        }
        void bind(WorkspaceDiskBinding binding) {
            if (finished) throw new IllegalStateException("Text transaction is closed");
            documentRevision++;
            diskBindingRevision++;
            file = binding.file().toFile(); scratch = false; scratchName = file.getName(); backupFile = null;
            diskTextImage = binding.image(); savedContent = binding.image().text(); savedDiskDigest = binding.digest();
            externalFileStamp = new ExternalFileStamp(ExternalFileKind.REGULAR, binding.fileKey(), binding.created(), binding.modified(), binding.size(), null);
            lastKnownModifiedTime = binding.modified().toMillis(); fileSizeBytes = binding.size();
            fileType = FileType.detect(file, getContent());
            modified = !getContent().equals(savedContent) || !encodingName.equals(binding.image().encoding())
                || byteOrderMark != binding.image().bom()
                || explicitLineEnding && !applyLineEndings(savedContent, lineEnding).equals(binding.image().rawText());
        }
        @Override public void close() {
            if (finished) return;
            finished = true;
            undoManager.cancelCompoundEdit();
            if (!getContent().equals(before.text())) throw new IllegalStateException("Document rollback was incomplete");
            file = bindingBefore.file(); scratchName = bindingBefore.name(); scratch = bindingBefore.scratch(); backupFile = bindingBefore.backup();
            diskTextImage = bindingBefore.image(); savedContent = bindingBefore.saved(); externalFileStamp = bindingBefore.stamp();
            savedDiskDigest = bindingBefore.digest(); lastKnownModifiedTime = bindingBefore.modified(); fileSizeBytes = bindingBefore.size();
            diskBindingRevision = bindingBefore.revision();
            textSnapshot = before; modified = wasModified; fileType = beforeType; updateLineCount();
        }
    }

    private record BindingState(File file, String name, boolean scratch, File backup, TextFileCodec.Image image,
                                String saved, ExternalFileStamp stamp, byte[] digest, long modified, long size, long revision) { }

    public String getContent() {
        try {
            return document.getText(0, document.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    String snapshotContent() {
        String[] value = new String[1];
        document.render(() -> {
            try {
                value[0] = document.getText(0, document.getLength());
            } catch (BadLocationException error) {
                throw new IllegalStateException("Unable to capture buffer content", error);
            }
        });
        return value[0];
    }

    public String getSavedContent() {
        return savedContent;
    }

    public String getFullContent() {
        if (largeFile) {
            throw new IllegalStateException("Large-file content is not materialized");
        }
        String content = getContent();
        if (textSnapshot.length() != content.length() || !textSnapshot.text().equals(content)) {
            textSnapshot = VersionedTextSnapshot.of(content);
        }
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

    VersionedTextSnapshot textSnapshot() {
        return textSnapshot;
    }

    DocumentTextChange applyDocumentChange(DocumentEvent event) {
        VersionedTextSnapshot before = textSnapshot;
        if (event == null || event.getType() == DocumentEvent.EventType.CHANGE) {
            VersionedTextSnapshot current = VersionedTextSnapshot.of(getContent());
            textSnapshot = current;
            return new DocumentTextChange(before, current, 0, 0, "", false);
        }
        try {
            int offset = event.getOffset();
            int removedLength = event.getType() == DocumentEvent.EventType.REMOVE ? event.getLength() : 0;
            String insertedText = event.getType() == DocumentEvent.EventType.INSERT
                ? event.getDocument().getText(offset, event.getLength()) : "";
            VersionedTextSnapshot after = before.replace(offset, removedLength, insertedText);
            boolean incremental = after.length() == document.getLength();
            if (!incremental) {
                after = VersionedTextSnapshot.of(getContent());
            }
            textSnapshot = after;
            return new DocumentTextChange(before, after, offset, removedLength, insertedText, incremental);
        } catch (BadLocationException | RuntimeException error) {
            VersionedTextSnapshot current = VersionedTextSnapshot.of(getContent());
            textSnapshot = current;
            return new DocumentTextChange(before, current, 0, 0, "", false);
        }
    }

    DocumentTextChange reconcileDocumentText() {
        String current = getContent();
        if (textSnapshot.length() == current.length() && textSnapshot.text().equals(current)) {
            return null;
        }
        VersionedTextSnapshot before = textSnapshot;
        VersionedTextSnapshot after = VersionedTextSnapshot.of(current);
        textSnapshot = after;
        return new DocumentTextChange(before, after, 0, 0, "", false);
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
        diskBindingRevision++;
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
        String requested = encodingName == null || encodingName.isEmpty() ? StandardCharsets.UTF_8.name() : encodingName;
        try { requested = Charset.forName(requested).name(); }
        catch (IllegalArgumentException unsupported) { /* Retain the existing explicit save-time encoding error. */ }
        String normalized = requested.toUpperCase(java.util.Locale.ROOT);
        boolean marked = normalized.equals("UTF-16") || byteOrderMark
            && (normalized.equals("UTF-8") || normalized.equals("UTF-16LE") || normalized.equals("UTF-16BE"));
        if (!requested.equals(this.encodingName) || marked != byteOrderMark) modified = true;
        this.encodingName = requested;
        byteOrderMark = marked;
    }

    public boolean hasByteOrderMark() { return byteOrderMark; }

    public UndoManager getUndoManager() {
        return undoManager;
    }

    public boolean exists() {
        return file != null && file.exists();
    }

    public boolean isScratch() {
        return scratch;
    }

    public boolean isSyntaxHighlightingEnabled() {
        return syntaxHighlightingEnabled;
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
        if (diskTextImage != null && !explicitLineEnding && diskTextImage.lineEnding().equals("MIXED")) return "MIXED";
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
            if (!lineEnding.equals(this.lineEnding) || !explicitLineEnding && diskTextImage != null && diskTextImage.lineEnding().equals("MIXED")) modified = true;
            this.lineEnding = lineEnding;
            this.explicitLineEnding = true;
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

    void acceptExternalVersionForOverwrite() throws IOException {
        if (file == null) throw new IOException("No file is associated with this buffer");
        ExternalFileStamp accepted = observeExternalFile();
        if (accepted.kind() == ExternalFileKind.UNSUPPORTED) {
            throw new IOException("The external target cannot be saved as a regular file");
        }
        byte[] acceptedDigest = accepted.kind() == ExternalFileKind.REGULAR ? digest(file.toPath()) : null;
        if (!accepted.equals(observeExternalFile())) {
            throw new IOException("The external file changed again; review it before saving");
        }
        externalFileStamp = accepted;
        savedDiskDigest = acceptedDigest;
        if (accepted.kind() == ExternalFileKind.REGULAR) lastKnownModifiedTime = file.lastModified();
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

    private void requireUnchangedSource() throws IOException {
        ExternalFileState state = getExternalFileState();
        if (state != ExternalFileState.UNCHANGED) {
            throw new IOException("File changed on disk (" + state + "): " + file
                + ". Reload or save to a different path to keep both versions.");
        }
        requireMatchingDigest(file, savedDiskDigest);
    }

    private static void requireMatchingDigest(File target, byte[] expected) throws IOException {
        if (expected != null && !MessageDigest.isEqual(expected, digest(target.toPath()))) {
            throw new IOException("File content changed on disk despite unchanged metadata: " + target
                + ". Reload or save to a different path to keep both versions.");
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] chunk = new byte[8192];
                int length;
                while ((length = input.read(chunk)) != -1) digest.update(chunk, 0, length);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private boolean sameIdentity(ExternalFileStamp current, ExternalFileStamp previous) {
        if (current.fileKey() != null && previous.fileKey() != null && !current.fileKey().equals(previous.fileKey())) {
            return false;
        }
        return current.creationTime().equals(previous.creationTime());
    }

    private ExternalFileStamp observeExternalFile() {
        return observeExternalFile(file);
    }

    private static ExternalFileStamp observeExternalFile(File targetFile) {
        if (targetFile == null) {
            return new ExternalFileStamp(ExternalFileKind.MISSING, null, null, null, 0L, null);
        }
        try {
            Path path = targetFile.toPath();
            Path linkTarget = Files.isSymbolicLink(path) ? Files.readSymbolicLink(path) : null;
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return new ExternalFileStamp(ExternalFileKind.UNSUPPORTED, null, null, null, 0L, linkTarget);
            }
            return new ExternalFileStamp(ExternalFileKind.REGULAR, attributes.fileKey(), attributes.creationTime(),
                attributes.lastModifiedTime(), attributes.size(), linkTarget);
        } catch (java.nio.file.NoSuchFileException error) {
            return new ExternalFileStamp(ExternalFileKind.MISSING, null, null, null, 0L, null);
        } catch (IOException | SecurityException error) {
            return new ExternalFileStamp(ExternalFileKind.UNSUPPORTED, null, null, null, 0L, null);
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
        BackupSnapshot snapshot = captureBackupSnapshot();
        if (snapshot != null) {
            writeBackupSnapshot(snapshot);
        }
    }

    BackupSnapshot captureBackupSnapshot() throws IOException {
        if (scratch || file == null || !modified || largeFile) {
            return null;
        }
        BackupPolicy policy = backupPolicy();
        if (!policy.enabled()) {
            return null;
        }
        return new BackupSnapshot(file.toPath().toAbsolutePath().normalize(), policy, encode(applyLineEndings(getFullContent())));
    }

    void writeBackupSnapshot(BackupSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        BackupPolicy policy = snapshot.policy();
        Path directory = policy.directoryPath();
        Files.createDirectories(directory);
        String key = backupKey(snapshot.source());
        Path target = reserveBackupPath(directory, key);
        try {
            AtomicFileWriter.write(target, snapshot.content());
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
        String value = text == null ? "" : text;
        try {
            document.remove(0, document.getLength());
            document.insertString(0, value, null);
        } catch (BadLocationException e) {
            throw new IllegalStateException("Unable to update buffer document", e);
        }
        textSnapshot = VersionedTextSnapshot.of(value);
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

    record DocumentTextChange(
        VersionedTextSnapshot before,
        VersionedTextSnapshot after,
        int offset,
        int removedLength,
        String insertedText,
        boolean incremental
    ) { }

    record BackupSnapshot(Path source, BackupPolicy policy, byte[] content) {
        BackupSnapshot {
            source = source == null ? null : source.toAbsolutePath().normalize();
            if (source == null || policy == null || content == null) {
                throw new IllegalArgumentException("backup snapshot is incomplete");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
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

    private record ExternalFileStamp(ExternalFileKind kind, Object fileKey, FileTime creationTime,
                                     FileTime modifiedTime, long size, Path linkTarget) {
    }

}
