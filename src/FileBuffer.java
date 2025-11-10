// File Buffer Class
// Manages individual file state including content, modifications, and undo history

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import javax.swing.undo.UndoManager;
import javax.swing.JTextArea;

public class FileBuffer {
    private File file;
    private String content;
    private boolean modified;
    private String encoding;
    private int lineCount;
    private UndoManager undoManager;

    // Constructor for existing file
    public FileBuffer(File file) throws IOException {
        this.file = file;
        this.modified = false;
        this.encoding = "UTF-8";
        this.undoManager = new UndoManager();
        load();
    }

    // Constructor for new file
    public FileBuffer(String filename) {
        this.file = new File(filename);
        this.content = "";
        this.modified = false;
        this.encoding = "UTF-8";
        this.lineCount = 0;
        this.undoManager = new UndoManager();
    }

    // Load file content from disk
    public void load() throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        this.lineCount = 0;

        while ((line = reader.readLine()) != null) {
            sb.append(line);
            sb.append("\n");
            this.lineCount++;
        }

        reader.close();
        this.content = sb.toString();
        this.modified = false;
    }

    // Save content to disk
    public void save() throws IOException {
        FileWriter fileWriter = new FileWriter(file);
        BufferedWriter writer = new BufferedWriter(fileWriter);
        writer.write(content);
        writer.close();
        this.modified = false;
    }

    // Save to a different file
    public void saveAs(File newFile) throws IOException {
        this.file = newFile;
        save();
    }

    // Update content and mark as modified
    public void setContent(String content) {
        this.content = content;
        this.modified = true;
        updateLineCount();
    }

    // Get current content
    public String getContent() {
        return content;
    }

    // Update line count based on content
    private void updateLineCount() {
        if (content.isEmpty()) {
            this.lineCount = 0;
        } else {
            this.lineCount = content.split("\n", -1).length;
        }
    }

    // Get display name for status bar
    public String getDisplayName() {
        return file.getName();
    }

    // Get full path
    public String getFilePath() {
        return file.getAbsolutePath();
    }

    // Check if buffer is modified
    public boolean isModified() {
        return modified;
    }

    // Set modified flag
    public void setModified(boolean modified) {
        this.modified = modified;
    }

    // Get file object
    public File getFile() {
        return file;
    }

    // Get line count
    public int getLineCount() {
        return lineCount;
    }

    // Get encoding
    public String getEncoding() {
        return encoding;
    }

    // Get undo manager for this buffer
    public UndoManager getUndoManager() {
        return undoManager;
    }

    // Check if file exists on disk
    public boolean exists() {
        return file.exists();
    }
}
