"""Static/synthetic checks only. Native execution is an explicit separate opt-in."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from native_fixture import Fixture, UnsafeFixture, checked, digest


class NativeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)

    def test_empty_home_and_private_root(self):
        f = self.fixture
        f.validate()
        self.assertEqual(f.root.stat().st_mode & 0o777, 0o700)
        self.assertEqual(set(Path(f.env['HOME']).iterdir()), {Path(f.env['ENV'])})

    def test_existing_owned_disposable_home(self):
        path = Path(self.fixture.env['HOME']) / 'synthetic-config'
        path.write_text('keep')
        self.fixture.validate()
        self.assertEqual(path.read_text(), 'keep')

    def test_no_parent_environment_inheritance(self):
        with patch.dict(os.environ, {'SSH_AUTH_SOCK': '/private-sentinel', 'AWS_SECRET_ACCESS_KEY': 'synthetic'}):
            f = Fixture()
            try:
                self.assertNotIn('SSH_AUTH_SOCK', f.env)
                self.assertNotIn('AWS_SECRET_ACCESS_KEY', f.env)
            finally:
                f.close()

    def test_missing_history_rejected_before_launch(self):
        env = dict(self.fixture.env)
        env.pop('HISTFILE')
        with patch('native_fixture.subprocess.Popen') as launch:
            with self.assertRaises(UnsafeFixture) as error:
                self.fixture.validate(env)
            self.assertIn('HISTFILE', str(error.exception))
            launch.assert_not_called()

    def test_external_history_rejected(self):
        env = dict(self.fixture.env, HISTFILE=str(self.fixture.root / 'outside'))
        with self.assertRaises(UnsafeFixture):
            self.fixture.validate(env)

    def test_explicit_internal_history(self):
        checked(self.fixture.env['HISTFILE'], self.fixture.run_root)

    def test_injected_credentials_rejected_without_exposing_value(self):
        env = dict(self.fixture.env, SSH_AUTH_SOCK='synthetic-secret')
        with self.assertRaises(UnsafeFixture) as error:
            self.fixture.validate(env)
        self.assertNotIn('synthetic-secret', str(error.exception))

    def test_injected_shell_startup_rejected(self):
        for key in ['BASH_ENV', 'ENV', 'ZDOTDIR', 'PATH', 'JAVA_TOOL_OPTIONS']:
            with self.subTest(key=key), self.assertRaises(UnsafeFixture):
                self.fixture.validate(dict(self.fixture.env, **{key: '/synthetic'}))

    def test_unexpected_working_directory_rejected(self):
        with self.assertRaises(UnsafeFixture):
            self.fixture.validate(cwd=self.fixture.root)

    def test_missing_home_rejected(self):
        path = Path(self.fixture.env['HOME'])
        renamed = path.with_name('moved')
        path.rename(renamed)
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            renamed.rename(path)

    def test_readonly_home_rejected(self):
        path = Path(self.fixture.env['HOME'])
        path.chmod(0o500)
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            path.chmod(0o700)

    def test_symlink_escape_rejected(self):
        link = self.fixture.run_root / 'escape'
        link.symlink_to(self.fixture.root, target_is_directory=True)
        with self.assertRaises(UnsafeFixture):
            checked(link / 'owner.json', self.fixture.run_root)

    def test_hardlink_rejected(self):
        link = self.fixture.run_root / 'linked-marker'
        os.link(self.fixture.root / 'owner.json', link)
        try:
            with self.assertRaises(UnsafeFixture):
                checked(link, self.fixture.run_root)
        finally:
            link.unlink()

    def test_parent_traversal_rejected(self):
        with self.assertRaises(UnsafeFixture):
            checked(self.fixture.run_root / '../owner.json', self.fixture.run_root)

    def test_prefix_collision_rejected(self):
        with self.assertRaises(UnsafeFixture):
            checked(Path(str(self.fixture.run_root) + '-sibling/file'), self.fixture.run_root)

    def test_relative_root_rejected(self):
        with self.assertRaises(UnsafeFixture):
            Fixture(Path('relative'))

    def test_symlink_parent_rejected(self):
        link = self.fixture.root / 'parent-alias'
        link.symlink_to(self.fixture.run_root, target_is_directory=True)
        with self.assertRaises(UnsafeFixture):
            Fixture(link)

    def test_wrong_owner_marker_rejected(self):
        marker = self.fixture.root / 'owner.json'
        original = marker.read_text()
        marker.write_text(json.dumps({'id': 'wrong', 'identity': list(self.fixture.identity)}))
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            marker.write_text(original)

    def test_unsafe_execution_rejected_without_popen(self):
        with patch('native_fixture.subprocess.Popen') as launch:
            with self.assertRaises(UnsafeFixture):
                self.fixture.run()
            self.fixture.verified = True
            for options in [dict(scenario='rm -rf /'), dict(shell='fish'), dict(mode='unknown'), dict(timeout=10)]:
                with self.subTest(options=options), self.assertRaises(UnsafeFixture):
                    self.fixture.run(**options)
            launch.assert_not_called()

    def test_unsupported_platform_fail_closed(self):
        with patch('native_fixture.platform.system', return_value='Linux'), self.assertRaises(UnsafeFixture):
            self.fixture.profile()

    def test_global_zshenv_rejected(self):
        original = Path.exists
        def exists(path):
            return str(path) == '/etc/zshenv' or original(path)
        with patch.object(Path, 'exists', exists), self.assertRaises(UnsafeFixture):
            self.fixture.profile()

    def test_profile_no_fork_network_or_user_home_access(self):
        text = self.fixture.profile().read_text()
        self.assertNotIn('(allow process-fork', text)
        self.assertNotIn('(allow network', text)
        self.assertNotIn('(allow mach', text)
        self.assertNotIn(str(Path.home()), text)

    def test_cleanup_preserves_external_synthetic_sentinel(self):
        with tempfile.TemporaryDirectory() as parent:
            sentinel = Path(parent) / 'sentinel'
            sentinel.write_text('synthetic external data')
            before = digest(sentinel), sentinel.stat().st_mtime_ns
            (self.fixture.run_root / 'external-link').symlink_to(sentinel)
            self.fixture.close()
            self.assertEqual(before, (digest(sentinel), sentinel.stat().st_mtime_ns))

    def test_cleanup_idempotent(self):
        root = self.fixture.root
        self.fixture.close()
        self.fixture.close()
        self.assertFalse(root.exists())

    def test_cleanup_rejects_changed_marker(self):
        marker = self.fixture.root / 'owner.json'
        original = marker.read_text()
        marker.write_text('{}')
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.close()
            self.assertTrue(marker.exists())
        finally:
            marker.write_text(original)

    def test_permission_error_propagates_no_launch(self):
        with patch('native_fixture.tempfile.mkdtemp', side_effect=PermissionError('synthetic denial')):
            with self.assertRaises(PermissionError):
                Fixture()

    def test_repeated_creation_cleanup(self):
        for _ in range(5):
            f = Fixture()
            f.validate()
            f.close()
            self.assertFalse(f.root.exists())

class BenchmarkAdmissionTest(unittest.TestCase):
    def test_whole_ide_runtime_rejects_before_toolchain_or_native_work(self):
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'blocked'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('audit-whole-ide.py')),
                                     '--jar', 'unused.jar', '--output', str(output)],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn('unqualified', result.stderr)
            self.assertEqual(json.loads((output / 'blocked.json').read_text())['result'], 'BLOCKED')
            self.assertEqual({p.name for p in output.iterdir()}, {'plan.json', 'blocked.json'})

    def test_portable_runner_rejects_native_test_before_tool_lookup(self):
        import subprocess
        import sys
        for name in ['PtyTerminalConnectorTest', 'LspServiceTest', 'TexteditorSwingIntegrationTest']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as scratch:
                output = Path(scratch) / 'not-created'
                result = subprocess.run([sys.executable, str(Path(__file__).with_name('run-portable-tests.py')),
                                         '--jdk', '/unused', '--maven', '/unused', '--repository', '/unused',
                                         '--output', str(output), '--tests', name],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 2)
                self.assertIn('blocked', result.stderr)
                self.assertFalse(output.exists())

    def test_reviewed_test_manifest_matches_sources(self):
        root = Path(__file__).resolve().parents[1]
        lines = (root / 'src/test/resources/shed/portable-fixture-tests.properties').read_text().splitlines()
        approved = {
            'AsyncJobPayloadLifecycleTest',
            'AsyncJobServiceTest',
            'BoundedUndoManagerTest',
            'CenteredWritingLayoutTest',
            'DocumentFormatPreservationTest',
            'DocumentLifecycleTest',
            'DocumentRequestsTest',
            'DocumentWorkbenchTest',
            'EditorSurfaceTest',
            'EmacsKeymapTest',
            'FileBufferTest',
            'BundledEditorFontTest',
            'IndicConjunctBoundaryTest',
            'UnicodeEditingRegressionTest',
            'GlyphRenderingMeasurementTest',
            'GlyphRenderingQualificationTest',
            'GutterPerformanceTest',
            'GutterRenderingTest',
            'LineNumberPanelTest',
            'IncrementalDiffGutterTest',
            'GraphemeBoundaryTest',
            'GraphemeEditRangeTest',
            'KeymapOverlayTest',
            'LspCompletionApplicationTest',
            'LspDefinitionPerformanceTest',
            'LspDefinitionRequestsTest',
            'LspDefinitionResponsivenessTest',
            'LspQueuedCancellationTest',
            'LspTransportConcurrencyTest',
            'LspWorkspaceFormatTest',
            'MarkdownEditTest',
            'MarkdownHighlightTest',
            'MarkdownLinkNavigationTest',
            'NativeFixtureGateTest',
            'NavigationHistoryTest',
            'PlainKeymapTest',
            'RecoveryJournalTest',
            'SourcePlainViewTest',
            'SyntaxHighlightServiceTest',
            'TerminalControllerTest',
            'ThemePreviewTest',
            'UiDensityTest',
            'VersionedTextSnapshotTest',
            'WorkspaceStatusContractTest',
            'WorkspaceStatusTransportTest',
            'WorkspaceRpcTest',
            'PrivateWorkspaceFilesTest',
            'WorkspaceCliProcessGuardTest',
            'WorkspaceStatusProcessTest',
            'WorkspaceCliTest',
            'WorkspaceBridgeTest',
            'WorkspaceStatusMeasurementTest',
            'EditorMetadataTest',
            'WorkspaceMetadataTransportTest',
            'WorkspaceMetadataMeasurementTest',
            'ArchitectureBoundaryTest',
            'EditorNavigationTest',
            'WorkspaceNavigationTransportTest',
            'WorkspaceNavigationMeasurementTest',
            'ProjectFileOpenPrerequisiteTest',
            'ProjectBoundFileAcquisitionTest',
            'ProjectDocumentInstallTest',
            'ProjectFileOpenTest',
            'EditorControlTest',
            'ShedControlCliTest',
            'AgentActivityStateTest',
            'EditorProjectChangesTest',
            'EditorObservationTest',
            'ExternalChangeSnapshotTest',
            'TestWatchServiceTest',
            'DataLossRegressionTest',
            'ProviderIconsTest',
            'VisualWatchControlTest',
            'AgentEditPresentationTest',
            'AgentBufferIntentTest',
            'AgentTerminalCommandTest',
            'AgentTerminalDecorationTest',
            'AgentTerminalTransportTest',
            'AgentViewportHandoffTest',
            'AgentAnimationEdgeTest',
            'EditorWorkspaceDocumentsTest',
            'AgentChangeTourTest', 'SmoothScrollPaneUITest',
            'AgentReviewPanelTest',
            'WorkspaceProposalServiceTest',
        }
        self.assertEqual({line.split("=", 1)[0] for line in lines}, set(approved))
        for line in lines:
            name, expected = line.split('=')
            self.assertEqual(digest(root / 'src/test/java/shed' / (name + '.java')), expected)
        helpers = (root / 'src/test/resources/shed/portable-fixture-helpers.properties').read_text().splitlines()
        self.assertEqual({'WorkspaceCliTestProcess'}, {line.split('=', 1)[0] for line in helpers})
        for line in helpers:
            name, expected = line.split('=')
            self.assertEqual(digest(root / 'src/test/java/shed' / (name + '.java')), expected)


class DependencyCopyTest(unittest.TestCase):
    def load_runner(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('portable_runner', Path(__file__).with_name('run-portable-tests.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_dependency_copy_cannot_write_through_to_source(self):
        with tempfile.TemporaryDirectory() as scratch:
            source = Path(scratch) / 'source'
            source.mkdir()
            (source / 'artifact').write_text('synthetic dependency')
            before = digest(source / 'artifact'), (source / 'artifact').stat().st_mtime_ns
            target = Path(scratch) / 'copy'
            self.load_runner().copy_dependencies(source, target)
            (target / 'artifact').write_text('changed copy')
            self.assertEqual(before, (digest(source / 'artifact'), (source / 'artifact').stat().st_mtime_ns))

    def test_dependency_symlinks_rejected_before_copy(self):
        with tempfile.TemporaryDirectory() as scratch:
            source = Path(scratch) / 'source'
            source.mkdir()
            (source / 'escape').symlink_to('/dev/null')
            target = Path(scratch) / 'not-created'
            with self.assertRaises(UnsafeFixture):
                self.load_runner().copy_dependencies(source, target)
            self.assertFalse(target.exists())

    def test_dependency_copy_budget_rejected_before_copy(self):
        with tempfile.TemporaryDirectory() as scratch:
            source = Path(scratch) / 'source'
            source.mkdir()
            (source / 'artifact').write_bytes(b'bounded')
            target = Path(scratch) / 'not-created'
            with self.assertRaises(UnsafeFixture):
                self.load_runner().copy_dependencies(source, target, maximum_bytes=1)
            self.assertFalse(target.exists())


class SafeVerificationModeTest(unittest.TestCase):
    def test_gutter_measurements_require_explicit_control_artifact(self):
        import subprocess
        import sys
        script = Path(__file__).with_name('run-portable-tests.py')
        for arguments in [['--measure-gutter'], ['--gutter-baseline', '/unused'],
                          ['--package-only', '--measure-gutter', '--gutter-baseline', '/unused']]:
            with tempfile.TemporaryDirectory() as scratch:
                output = Path(scratch) / 'not-created'
                result = subprocess.run([sys.executable, str(script), '--jdk', '/unused', '--maven', '/unused',
                                         '--repository', '/unused', '--output', str(output), *arguments],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())
                self.assertIn('must be supplied together' if len(arguments) < 3 else 'cannot be combined', result.stderr)

    def test_packaging_cannot_be_combined_with_test_execution_modes(self):
        import subprocess
        import sys
        script = Path(__file__).with_name('run-portable-tests.py')
        for mode in ['--compile-only', '--verify-gate', '--measure-lsp', '--measure-glyph', '--measure-status']:
            with tempfile.TemporaryDirectory() as scratch:
                output = Path(scratch) / 'not-created'
                result = subprocess.run([sys.executable, str(script), '--jdk', '/unused', '--maven', '/unused',
                                         '--repository', '/unused', '--output', str(output), '--package-only', mode],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(2, result.returncode)
                self.assertIn('cannot be combined', result.stderr)
                self.assertFalse(output.exists())

    def test_media_smoke_redirects_compiler_and_runtime_java_state(self):
        import importlib.util
        import sys
        from types import SimpleNamespace
        script = Path(__file__).with_name('test-packaged-markdown.py')
        spec = importlib.util.spec_from_file_location('safe_media_smoke', script)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            return SimpleNamespace(returncode=0, stdout='SMOKE_PASS\n', stderr='')
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch); jar = root / 'fixture.jar'; jar.write_bytes(b'synthetic')
            with patch.object(sys, 'argv', [str(script), '--jdk', str(root), '--jar', str(jar)]), patch.object(module.subprocess, 'run', side_effect=run), patch('builtins.print'):
                self.assertEqual(0, module.main())
            self.assertEqual(2, len(commands))
            for option in ['user.home', 'java.io.tmpdir', 'java.util.prefs.userRoot']:
                self.assertTrue(any(value.startswith('-J-D' + option + '=') for value in commands[0]))
                self.assertTrue(any(value.startswith('-D' + option + '=') for value in commands[1]))


if __name__ == '__main__':
    unittest.main()
