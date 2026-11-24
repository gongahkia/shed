package shed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestWatchServiceTest {
    @TempDir Path root;

    @Test
    void watchesSourceChangesButExcludesGeneratedDirectories() throws Exception {
        Path source = root.resolve("src/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Main {}\n");
        Path generated = root.resolve("target/result.xml");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "before\n");
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<Path> observed = new AtomicReference<>();

        try (TestWatchService watcher = TestWatchService.start(root, path -> { observed.set(path); changed.countDown(); })) {
            Files.writeString(source, "class Main { int value; }\n");

            assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertTrue(observed.get().startsWith(root));
            assertFalse(TestWatchService.watches(root, generated));
            assertFalse(TestWatchService.watches(root, root.resolve("node_modules/package/index.js")));
        }
    }
    @Test
    void movedDirectoryRegistrationRetiresItsOldWatchKey() throws Exception {
        Path old=Files.createDirectory(root.resolve("before"));Path moved=root.resolve("after");
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var key=new java.nio.file.WatchKey(){
            public boolean isValid(){return !cancelled.get();}
            public java.util.List<java.nio.file.WatchEvent<?>> pollEvents(){return java.util.List.of();}
            public boolean reset(){return isValid();}
            public void cancel(){cancelled.set(true);}
            public java.nio.file.Watchable watchable(){return old;}
        };
        var registrations=new java.util.concurrent.ConcurrentHashMap<java.nio.file.WatchKey,Path>();registrations.put(key,old);
        TestWatchService.retireMissingDirectories(registrations);assertFalse(cancelled.get());assertTrue(registrations.containsKey(key));
        Files.move(old,moved);
        TestWatchService.retireMissingDirectories(registrations);
        assertTrue(cancelled.get());assertTrue(registrations.isEmpty());assertTrue(Files.isDirectory(moved));
    }
}
