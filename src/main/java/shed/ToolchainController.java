package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Ex-command surface for the deliberately small local toolchain selection feature. */
final class ToolchainController {
    private final Texteditor editor;

    ToolchainController(Texteditor editor) { this.editor = editor; }

    String handle(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty() || "status".equalsIgnoreCase(value) || "detect".equalsIgnoreCase(value)) return status();
        int split = value.indexOf(' ');
        String command = (split < 0 ? value : value.substring(0, split)).toLowerCase(java.util.Locale.ROOT);
        String rest = split < 0 ? "" : value.substring(split + 1).trim();
        if ("select".equals(command)) return select(rest);
        if ("clear".equals(command)) return clear(rest);
        return "Usage: :toolchain [status|detect|select <python|node|go> <absolute-executable>|clear <python|node|go>]";
    }

    private String select(String arguments) {
        int split = arguments.indexOf(' ');
        if (split < 0) return "Usage: :toolchain select <python|node|go> <absolute-executable>";
        ToolchainService.Runtime runtime = ToolchainService.Runtime.parse(arguments.substring(0, split));
        if (runtime == null) return "Toolchain runtime must be python, node, or go";
        try {
            Path selected = editor.toolchainService.select(workspace(), runtime, arguments.substring(split + 1).trim());
            return "Selected " + runtime.id() + " toolchain: " + selected;
        } catch (IOException | IllegalArgumentException error) {
            return "Toolchain selection failed: " + error.getMessage();
        }
    }

    private String clear(String arguments) {
        ToolchainService.Runtime runtime = ToolchainService.Runtime.parse(arguments);
        if (runtime == null) return "Usage: :toolchain clear <python|node|go>";
        try {
            return editor.toolchainService.clear(workspace(), runtime) ? "Cleared " + runtime.id() + " toolchain selection"
                : "No " + runtime.id() + " toolchain selection";
        } catch (IOException | IllegalArgumentException error) {
            return "Toolchain selection failed: " + error.getMessage();
        }
    }

    private String status() {
        Path workspace = workspace();
        ToolchainService.Report report = editor.toolchainService.report(workspace);
        if (!report.usable()) return "Toolchain selection unavailable: " + report.failure();
        List<String> lines = new ArrayList<>();
        lines.add("Toolchains for " + workspace + ":");
        for (ToolchainService.Runtime runtime : ToolchainService.Runtime.values()) {
            Path selected = report.selections().get(runtime);
            lines.add("  " + runtime.id() + ": " + (selected == null ? "not selected" : selected));
        }
        if (report.candidates().isEmpty()) {
            lines.add("Detected candidates: none");
        } else {
            lines.add("Detected candidates (not selected automatically):");
            for (ToolchainService.Candidate candidate : report.candidates()) {
                lines.add("  " + candidate.runtime().id() + " " + candidate.executable() + " [" + candidate.source() + "]");
            }
        }
        lines.add("Select with: :toolchain select <python|node|go> <absolute-executable>");
        editor.showScratchBuffer("[toolchains]", String.join("\n", lines) + "\n");
        return "Showing local toolchains";
    }

    private Path workspace() {
        File root = editor.resolveTaskProjectRoot();
        return (root == null ? new File(".") : root).toPath().toAbsolutePath().normalize();
    }
}
