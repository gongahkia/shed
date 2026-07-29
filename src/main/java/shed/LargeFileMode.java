package shed;

final class LargeFileMode {
    private LargeFileMode() {
    }

    static String report(FileBuffer buffer) {
        if (buffer == null || !buffer.isLargeFile()) {
            return "Large File Mode\n\nNo large-file buffer is active.\n";
        }
        StringBuilder report = new StringBuilder("Large File Mode\n\n");
        report.append("State: ").append(buffer.isLargeFileUnavailable() ? "unavailable" : "active").append('\n');
        report.append("Source: ").append(buffer.getFilePath()).append('\n');
        report.append("Status: ").append(buffer.getLargeFileStatus()).append("\n\n");
        if (buffer.isLargeFileUnavailable()) {
            report.append("Reason: the file is not a supported regular, well-formed UTF-8 source.\n");
            report.append("Remediation: use a supported UTF-8 regular file or raise the in-memory threshold only when safe.\n");
            return report.toString();
        }
        report.append("Available\n");
        report.append("- bounded viewport scrolling, caret movement, and resize\n");
        report.append("- reload and external-change detection\n");
        report.append("- streamed atomic save of unchanged source content\n\n");
        report.append("Unavailable\n");
        report.append("- editing, undo/redo, save-as, backups, and recovery snapshots\n");
        report.append("- LSP synchronization, Markdown preview, full-document search, and syntax analysis\n\n");
        report.append("Reason: these operations require a bounded piece-table or incremental implementation not yet available.\n");
        report.append("Remediation: use the supported viewport/save path, or work on a smaller file below configured limits.\n");
        return report.toString();
    }
}
