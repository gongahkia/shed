package shed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QuickfixService {
    public static final class Entry {
        private final String filePath;
        private final int line;
        private final int column;
        private final String message;
        private final String source;

        public Entry(String filePath, int line, int column, String message, String source) {
            this.filePath = filePath == null ? "" : filePath;
            this.line = Math.max(1, line);
            this.column = Math.max(1, column);
            this.message = message == null ? "" : message;
            this.source = source == null ? "" : source;
        }

        public String getFilePath() {
            return filePath;
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }

        public String getMessage() {
            return message;
        }

        public String getSource() {
            return source;
        }
    }

    private final List<Entry> entries;
    private int currentIndex;
    private String title;

    public QuickfixService() {
        this.entries = new ArrayList<>();
        this.currentIndex = -1;
        this.title = "quickfix";
    }

    public void clear() {
        entries.clear();
        currentIndex = -1;
    }

    public void setEntries(String title, List<Entry> incoming) {
        entries.clear();
        if (incoming != null) {
            entries.addAll(incoming);
        }
        this.title = title == null || title.isBlank() ? "quickfix" : title.trim();
        currentIndex = entries.isEmpty() ? -1 : 0;
    }

    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public String getTitle() {
        return title;
    }

    public Entry current() {
        if (!hasEntries()) {
            return null;
        }
        if (currentIndex < 0 || currentIndex >= entries.size()) {
            currentIndex = 0;
        }
        return entries.get(currentIndex);
    }

    public Entry first() {
        if (!hasEntries()) {
            return null;
        }
        currentIndex = 0;
        return entries.get(currentIndex);
    }

    public Entry last() {
        if (!hasEntries()) {
            return null;
        }
        currentIndex = entries.size() - 1;
        return entries.get(currentIndex);
    }

    public Entry next() {
        if (!hasEntries()) {
            return null;
        }
        currentIndex = (currentIndex + 1) % entries.size();
        return entries.get(currentIndex);
    }

    public Entry previous() {
        if (!hasEntries()) {
            return null;
        }
        currentIndex = currentIndex <= 0 ? entries.size() - 1 : currentIndex - 1;
        return entries.get(currentIndex);
    }

    public Entry select(int oneBasedIndex) {
        if (!hasEntries()) {
            return null;
        }
        int zeroBased = Math.max(1, oneBasedIndex) - 1;
        if (zeroBased >= entries.size()) {
            return null;
        }
        currentIndex = zeroBased;
        return entries.get(currentIndex);
    }

    public Entry atLine(int oneBasedLine) {
        return select(oneBasedLine);
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String render() {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            builder.append(i + 1)
                .append(" ")
                .append(entry.getFilePath())
                .append(":")
                .append(entry.getLine())
                .append(":")
                .append(entry.getColumn())
                .append(": ");
            if (!entry.getMessage().isBlank()) {
                builder.append(entry.getMessage().strip());
            } else {
                builder.append("(no message)");
            }
            if (!entry.getSource().isBlank()) {
                builder.append(" [").append(entry.getSource().strip()).append("]");
            }
            if (i < entries.size() - 1) {
                builder.append("\n");
            }
        }
        return builder.toString();
    }
}
