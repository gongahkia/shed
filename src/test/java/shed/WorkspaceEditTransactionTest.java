package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceEditTransactionTest {
    @TempDir Path root;

    @Test void exclusiveRenameCannotOverwriteAWriterThatOccupiesTheDestinationDuringItsIntent() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "original"), target = root.resolve("target");
        var expected = WorkspaceEditTransaction.resourceIdentity(source);
        try (var transaction = new WorkspaceEditTransaction(step -> {
            if (step.kind().equals("RENAME_FILE")) Files.writeString(target, "concurrent human file");
        })) {
            assertThrows(IOException.class, () -> transaction.renameFile(source, target, expected));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
        }
        assertEquals("original", Files.readString(source)); assertEquals("concurrent human file", Files.readString(target));
    }

    @Test void exclusiveRenamePreservesIdentityAndRollsBackARecordedIntermediateLink() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "original"), target = root.resolve("nested/target");
        var expected = WorkspaceEditTransaction.resourceIdentity(source);
        var steps = new java.util.ArrayList<String>();
        try (var transaction = new WorkspaceEditTransaction(step -> {
            steps.add(step.kind());
            if (step.kind().equals("UNLINK_SOURCE")) {
                assertTrue(Files.isSameFile(source, target)); throw new IOException("fixture interruption");
            }
        })) {
            assertThrows(IOException.class, () -> transaction.renameFile(source, target, expected));
        }
        assertEquals(java.util.List.of("CREATE_DIRECTORY", "RENAME_FILE", "UNLINK_SOURCE"), steps);
        assertEquals(expected, WorkspaceEditTransaction.resourceIdentity(source)); assertFalse(Files.exists(target.getParent()));
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.renameFile(source, target, expected); transaction.commit();
            assertTrue(transaction.cleanup().failures().isEmpty());
        }
        assertFalse(Files.exists(source)); assertEquals(expected, WorkspaceEditTransaction.resourceIdentity(target));
    }

    @Test void aliasRecoveryAndItsRollbackNeverReplaceASeparateHumanFile() throws Exception {
        Path survivor = Files.writeString(root.resolve("source"), "original"), alias = root.resolve("alias");
        Files.createLink(alias, survivor); var expected = WorkspaceEditTransaction.resourceIdentity(survivor);
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.unlinkAlias(alias, survivor, expected); assertFalse(Files.exists(alias));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(Files.isSameFile(alias, survivor));
        }
        Files.delete(alias); Files.writeString(alias, "human");
        try (var transaction = new WorkspaceEditTransaction()) {
            assertThrows(IOException.class, () -> transaction.unlinkAlias(alias, survivor, expected));
        }
        assertEquals("human", Files.readString(alias)); assertEquals("original", Files.readString(survivor));
    }

    @Test void retainedDeletionKeepsTheExactOriginalForUndoAndStillRollsBackNormally() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "original\r\n");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-----"));
        var identity = WorkspaceEditTransaction.resourceIdentity(file);
        Path original;
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.deleteRetaining(file, identity); transaction.commit();
            assertTrue(transaction.cleanup().failures().isEmpty());
            original = transaction.report().recoveryPaths().getFirst().resolve("original");
        }
        assertFalse(Files.exists(file)); assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(original));
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.rename(original, file, identity, null); transaction.commit();
        }
        assertEquals("original\r\n", Files.readString(file));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            assertEquals(PosixFilePermissions.fromString("rwxr-----"), Files.getPosixFilePermissions(file));
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.deleteRetaining(file, WorkspaceEditTransaction.resourceIdentity(file));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().recoveryPaths().isEmpty());
        }
        assertEquals(identity, WorkspaceEditTransaction.resourceIdentity(file));
    }

    @Test void abandoningUninstalledStagingAlsoRemovesItsNewEmptyParentDirectories() throws Exception {
        Path target = root.resolve("new/nested/file");
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.stage(target, "unaccepted".getBytes(StandardCharsets.UTF_8));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().failures().isEmpty());
        }
        assertFalse(Files.exists(root.resolve("new")));
    }

    @Test void createHasAnAtomicAbsentTargetPreconditionEvenAfterItsIntentWasRecorded() throws Exception {
        Path target = root.resolve("new");
        try (var transaction = new WorkspaceEditTransaction(step -> {
            if (step.kind().equals("CREATE")) Files.writeString(target, "concurrent human file");
        })) {
            Path staged = transaction.stage(target, "agent".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> transaction.create(target, staged));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().failures().isEmpty());
        }
        assertEquals("concurrent human file", Files.readString(target));
    }

    @Test void createdFilesParticipateInRollbackAndCommitWithoutLeavingTemporaryLinks() throws Exception {
        Path target = root.resolve("new/nested/source");
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.create(target, transaction.stage(target, "agent".getBytes(StandardCharsets.UTF_8)));
            assertEquals("agent", Files.readString(target));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().failures().isEmpty());
            assertFalse(Files.exists(root.resolve("new")));
        }
        try (var transaction = new WorkspaceEditTransaction()) {
            // An absent-target replacement uses the same strict creation path.
            transaction.replace(target, transaction.stage(target, "accepted".getBytes(StandardCharsets.UTF_8)), null);
            transaction.commit(); assertTrue(transaction.cleanup().failures().isEmpty());
            assertTrue(transaction.report().recoveryPaths().isEmpty());
        }
        assertEquals("accepted", Files.readString(target));
    }

    @Test void reviewedResourceIdentitiesGuardWritesRenamesAndDeletion() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "reviewed");
        Path target = Files.writeString(root.resolve("target"), "old destination");
        var sourceIdentity = WorkspaceEditTransaction.resourceIdentity(source);
        var targetIdentity = WorkspaceEditTransaction.resourceIdentity(target);
        Files.writeString(source, "newer human text");
        try (var transaction = new WorkspaceEditTransaction()) {
            Path staged = transaction.stage(source, "agent".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> transaction.replace(source, staged, sourceIdentity));
            assertThrows(IOException.class, () -> transaction.delete(source, sourceIdentity));
            assertThrows(IOException.class, () -> transaction.rename(source, target, sourceIdentity, targetIdentity));
            var current = WorkspaceEditTransaction.resourceIdentity(source);
            Files.writeString(target, "new destination");
            assertThrows(IOException.class, () -> transaction.rename(source, target, current, targetIdentity));
            assertThrows(IOException.class, () -> transaction.rename(source, target, current, null));
        }
        assertEquals("newer human text", Files.readString(source)); assertEquals("new destination", Files.readString(target));
        try (var transaction = new WorkspaceEditTransaction()) {
            transaction.rename(source, target, WorkspaceEditTransaction.resourceIdentity(source), WorkspaceEditTransaction.resourceIdentity(target));
            transaction.commit(); assertTrue(transaction.cleanup().failures().isEmpty());
        }
        assertFalse(Files.exists(source)); assertEquals("newer human text", Files.readString(target));
    }

    @Test void changesDuringIntentPersistenceAreRecheckedBeforeMovingReviewedResources() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "reviewed");
        var expected = WorkspaceEditTransaction.resourceIdentity(source);
        try (var transaction = new WorkspaceEditTransaction(step -> {
            if (step.kind().equals("BACKUP")) Files.writeString(source, "changed while journaling");
        })) {
            assertThrows(IOException.class, () -> transaction.delete(source, expected));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().failures().isEmpty());
        }
        assertEquals("changed while journaling", Files.readString(source));
        try (var paths = Files.list(root)) { assertEquals(java.util.List.of(source), paths.toList()); }
        Path destination = root.resolve("renamed"); var next = WorkspaceEditTransaction.resourceIdentity(source);
        try (var transaction = new WorkspaceEditTransaction(step -> {
            if (step.kind().equals("RENAME")) Files.writeString(source, "changed before rename");
        })) {
            assertThrows(IOException.class, () -> transaction.rename(source, destination, next, null));
        }
        assertFalse(Files.exists(destination)); assertEquals("changed before rename", Files.readString(source));
    }

    @Test void preparedStepsDescribeResourcesBeforeMovingOriginalsAndObserverFailureRollsBack() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "original");
        var steps = new java.util.ArrayList<WorkspaceEditTransaction.Step>();
        try (var transaction = new WorkspaceEditTransaction(step -> {
            steps.add(step);
            assertEquals(WorkspacePaths.hash(Files.readAllBytes(step.source())), step.fingerprint().get("digest"));
            if (step.kind().equals("BACKUP")) {
                assertEquals(file, step.source()); assertFalse(Files.exists(step.target()));
                assertEquals("original", Files.readString(file));
            } else if (step.kind().equals("REPLACE")) {
                assertFalse(Files.exists(file));
                assertEquals("original", Files.readString(steps.get(1).target()));
                throw new IOException("Journal cannot publish the replacement intent");
            }
        })) {
            Path staged = transaction.stage(file, "agent".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> transaction.replace(file, staged));
        }
        assertEquals(java.util.List.of("STAGED", "BACKUP", "REPLACE"), steps.stream().map(WorkspaceEditTransaction.Step::kind).toList());
        assertEquals("original", Files.readString(file));
    }

    @Test void abruptWorkerExitLeavesJournaledOriginalAndStagedBytesDiscoverable() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("project"));
        Files.writeString(workspace.resolve("source"), "original");
        Path history = root.resolve("private");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(javaExecutable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            "shed.WorkspaceEditCrashFixture", workspace.toString(), history.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(77, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        try (var lease = new WorkspaceChangeJournal(history).open(false)) {
            var entry = lease.read(WorkspaceEditCrashFixture.ID);
            assertEquals("RESOURCE_PREPARED", entry.kind());
            var steps = MiniJson.asArray(entry.data().get("resourceSteps"));
            assertEquals(3, steps.size());
            var backup = MiniJson.asObject(steps.get(1)); var replace = MiniJson.asObject(steps.get(2));
            Path original = Path.of(java.net.URI.create((String) backup.get("target")));
            Path staged = Path.of(java.net.URI.create((String) replace.get("source")));
            assertFalse(Files.exists(workspace.resolve("source")));
            assertEquals("original", Files.readString(original)); assertEquals("agent", Files.readString(staged));
            assertEquals(WorkspacePaths.hash(Files.readAllBytes(original)), MiniJson.asObject(backup.get("fingerprint")).get("digest"));
        }
    }

    @Test void mixedResourceEditsCommitPreservePermissionsAndRemoveOwnedArtifacts() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "original source");
        Path target = Files.writeString(root.resolve("target"), "overwritten target");
        Path deleted = Files.writeString(root.resolve("deleted"), "deleted text");
        boolean posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-----"));
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            Path staged = transaction.stage(target, "edited source".getBytes(StandardCharsets.UTF_8));
            transaction.rename(source, target, true);
            transaction.replace(target, staged);
            transaction.delete(deleted);
            Path created = root.resolve("new/nested/file");
            transaction.replace(created, transaction.stage(created, "created".getBytes(StandardCharsets.UTF_8)));
            transaction.commit();
            assertEquals(WorkspaceEditTransaction.State.COMMITTED, transaction.state());
            assertTrue(transaction.cleanup().failures().isEmpty());
            assertFalse(Files.exists(source)); assertFalse(Files.exists(deleted));
            assertEquals("edited source", Files.readString(target)); assertEquals("created", Files.readString(created));
            if (posix) assertEquals(PosixFilePermissions.fromString("rwxr-----"), Files.getPosixFilePermissions(target));
            assertTrue(transaction.report().recoveryPaths().isEmpty());
            assertThrows(IllegalStateException.class, () -> transaction.delete(target));
        }
    }

    @Test void laterFailureRestoresAllEarlierFilesAndRemovesNewParentDirectories() throws Exception {
        Path first = Files.writeString(root.resolve("first"), "first original");
        Path second = Files.writeString(root.resolve("second"), "second original");
        Path created = root.resolve("new/nested/file");
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.replace(first, transaction.stage(first, "first edit".getBytes(StandardCharsets.UTF_8)));
            transaction.rename(second, created, false);
            assertThrows(IOException.class, () -> transaction.rename(first, created, false));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertTrue(transaction.cleanup().recoveryPaths().isEmpty());
            assertEquals("first original", Files.readString(first)); assertEquals("second original", Files.readString(second));
            assertFalse(Files.exists(root.resolve("new")));
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
        }
    }

    @Test void rollbackPreservesLaterHumanChangesAndRetainsRecoverableOriginalsWhileRestoringUnrelatedFiles() throws Exception {
        Path first = Files.writeString(root.resolve("first"), "first original");
        Path second = Files.writeString(root.resolve("second"), "second original");
        WorkspaceEditTransaction transaction = new WorkspaceEditTransaction();
        transaction.replace(first, transaction.stage(first, "agent first".getBytes(StandardCharsets.UTF_8)));
        transaction.replace(second, transaction.stage(second, "agent second".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(second, "newer human text");
        var report = transaction.rollback();
        assertEquals(WorkspaceEditTransaction.State.RECOVERY_REQUIRED, report.state());
        assertEquals("first original", Files.readString(first)); assertEquals("newer human text", Files.readString(second));
        assertTrue(report.failures().stream().anyMatch(failure -> failure.phase().equals("rollback")));
        assertTrue(report.recoveryPaths().stream().map(path -> path.resolve("original")).anyMatch(path -> {
            try { return Files.isRegularFile(path) && Files.readString(path).equals("second original"); }
            catch (IOException ignored) { return false; }
        }));
        Files.writeString(second, "still newer human text");
        transaction.close(); transaction.close();
        assertEquals("still newer human text", Files.readString(second));
        assertEquals(report, transaction.report());
    }

    @Test void anExternalWriterHoldingTheOriginalResourceInvalidatesCommitAndItsNewerDataSurvivesRollback() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "original");
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.replace(file, transaction.stage(file, "agent edit".getBytes(StandardCharsets.UTF_8)));
            Path original = transaction.report().recoveryPaths().getFirst().resolve("original");
            Files.writeString(original, "external writer's newer content");
            assertThrows(IOException.class, transaction::commit);
            assertEquals(WorkspaceEditTransaction.State.ROLLED_BACK, transaction.rollback().state());
            assertEquals("external writer's newer content", Files.readString(file));
            assertTrue(transaction.cleanup().recoveryPaths().isEmpty());
        }
    }

    @Test void directoryCleanupNeverFollowsLinksOutsideTheOwnedResource() throws Exception {
        Path outside = Files.writeString(root.resolve("outside"), "keep outside");
        Path tree = Files.createDirectories(root.resolve("tree/nested"));
        Files.writeString(tree.resolve("inside"), "inside");
        try { Files.createSymbolicLink(tree.resolve("outside-link"), outside); }
        catch (UnsupportedOperationException | IOException unavailable) { /* Link support is optional on Windows. */ }
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.delete(root.resolve("tree")); transaction.commit();
            assertTrue(transaction.cleanup().failures().isEmpty());
            assertTrue(transaction.report().recoveryPaths().isEmpty());
            assertFalse(Files.exists(root.resolve("tree"))); assertEquals("keep outside", Files.readString(outside));
        }
    }

    @Test void cleanupRetainsForeignAdditionsAndReportsThemWithoutChangingTheCommittedResult() throws Exception {
        Path file = Files.writeString(root.resolve("file"), "original");
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.delete(file);
            Path retained = transaction.report().recoveryPaths().getFirst();
            Files.writeString(retained.resolve("foreign"), "new data");
            transaction.commit();
            var report = transaction.cleanup();
            assertEquals(WorkspaceEditTransaction.State.COMMITTED, report.state());
            assertFalse(report.failures().isEmpty()); assertTrue(report.notice().contains("retained recovery paths"));
            assertEquals("new data", Files.readString(retained.resolve("foreign")));
            assertEquals(report, transaction.cleanup());
        }
    }

    @Test void failedEditorCallbacksDoNotPreventOtherRollbackActions() throws Exception {
        Path file = Files.writeString(root.resolve("file"), "original");
        AtomicInteger editorState = new AtomicInteger();
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.replace(file, transaction.stage(file, "edited".getBytes(StandardCharsets.UTF_8)));
            transaction.addRollback("restore selection", () -> editorState.set(0));
            transaction.addRollback("restore disposed buffer", () -> { throw new IllegalStateException("buffer disposed"); });
            transaction.addCommit(() -> editorState.set(1));
            transaction.addCommit(() -> { throw new IOException("editor finalization failed"); });
            assertThrows(IOException.class, transaction::commit);
            var report = transaction.rollback();
            assertEquals(WorkspaceEditTransaction.State.RECOVERY_REQUIRED, report.state());
            assertEquals(0, editorState.get()); assertEquals("original", Files.readString(file));
            assertTrue(report.notice().contains("restore disposed buffer"));
        }
    }

    @Test void rollbackDoesNotDeleteANewerFileThatReplacedACreatedDirectory() throws Exception {
        Path target = root.resolve("created/file");
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            Path staged = transaction.stage(target, "proposal".getBytes(StandardCharsets.UTF_8));
            Files.delete(staged); Files.delete(target.getParent());
            Files.writeString(target.getParent(), "newer human file");
            assertEquals(WorkspaceEditTransaction.State.RECOVERY_REQUIRED, transaction.rollback().state());
            transaction.cleanup();
            assertEquals("newer human file", Files.readString(target.getParent()));
        }
    }

    @Test void cleanupDoesNotDeleteAnOriginalMovedBackIntoTheBackupAfterRollback() throws Exception {
        Path file = Files.writeString(root.resolve("original"), "keep original");
        try (WorkspaceEditTransaction transaction = new WorkspaceEditTransaction()) {
            transaction.delete(file);
            Path retained = transaction.report().recoveryPaths().getFirst().resolve("original");
            transaction.rollback();
            Files.move(file, retained);
            var report = transaction.cleanup();
            assertFalse(report.failures().isEmpty()); assertEquals("keep original", Files.readString(retained));
            assertTrue(report.notice().contains("retained"));
        }
    }
}
