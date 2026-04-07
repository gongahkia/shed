package shed;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskService {
    private static final String TASKS_FILE_NAME = ".shedtasks";

    public Map<String, String> loadTasks(File projectRoot) {
        Map<String, String> tasks = new LinkedHashMap<>();
        File taskFile = taskFile(projectRoot);
        if (taskFile == null || !taskFile.isFile()) {
            return tasks;
        }
        try {
            List<String> lines = Files.readAllLines(taskFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, separator).trim();
                String command = trimmed.substring(separator + 1).trim();
                if (!name.isEmpty() && !command.isEmpty()) {
                    tasks.put(name, command);
                }
            }
        } catch (IOException ignored) {
        }
        return tasks;
    }

    public void saveTasks(File projectRoot, Map<String, String> tasks) throws IOException {
        File taskFile = taskFile(projectRoot);
        if (taskFile == null) {
            throw new IOException("project root required");
        }
        File parent = taskFile.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Shed project tasks");
        List<String> names = new ArrayList<>(tasks.keySet());
        Collections.sort(names);
        for (String name : names) {
            String command = tasks.get(name);
            if (name == null || name.isBlank() || command == null || command.isBlank()) {
                continue;
            }
            lines.add(name + "=" + command);
        }
        Files.write(
            taskFile.toPath(),
            lines,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    public File taskFile(File projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        return new File(projectRoot, TASKS_FILE_NAME);
    }
}
