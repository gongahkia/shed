package shed;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class FileWatcherService {

    public interface FileChangeListener {
        void onFileChanged(File file);
    }

    private WatchService watchService;
    private final Map<WatchKey, Path> watchedDirs;
    private final Map<String, FileChangeListener> listeners;
    private Thread watchThread;
    private volatile boolean running;

    public FileWatcherService() {
        watchedDirs = new ConcurrentHashMap<>();
        listeners = new ConcurrentHashMap<>();
        running = false;
    }

    public void start() {
        if (running) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            running = true;
            watchThread = new Thread(this::pollLoop, "shed-file-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (IOException e) {
            System.err.println("FileWatcher: failed to start: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    public void watch(File file, FileChangeListener listener) {
        if (file == null || listener == null || !running) return;
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) return;

        String absPath = file.getAbsolutePath();
        listeners.put(absPath, listener);

        Path parentPath = parent.toPath();
        boolean alreadyWatched = false;
        for (Path p : watchedDirs.values()) {
            if (p.equals(parentPath)) {
                alreadyWatched = true;
                break;
            }
        }
        if (!alreadyWatched) {
            try {
                WatchKey key = parentPath.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
                watchedDirs.put(key, parentPath);
            } catch (IOException e) {
                System.err.println("FileWatcher: failed to watch " + parentPath + ": " + e.getMessage());
            }
        }
    }

    public void unwatch(File file) {
        if (file == null) return;
        listeners.remove(file.getAbsolutePath());
    }

    public boolean isWatching(File file) {
        return file != null && listeners.containsKey(file.getAbsolutePath());
    }

    private void pollLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                break;
            }

            Path dir = watchedDirs.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path changed = dir.resolve(ev.context());
                String absPath = changed.toAbsolutePath().toString();

                FileChangeListener listener = listeners.get(absPath);
                if (listener != null) {
                    try {
                        listener.onFileChanged(changed.toFile());
                    } catch (Exception e) {
                        System.err.println("FileWatcher: listener error: " + e.getMessage());
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                watchedDirs.remove(key);
            }
        }
    }
}
