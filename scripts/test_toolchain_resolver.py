"""Config-free POSIX resolver tests with synthetic Java executables in disposable homes."""
from pathlib import Path
import subprocess
import sys
import unittest

from native_fixture import Fixture

ROOT = Path(__file__).resolve().parents[1]
RESOLVER = ROOT / 'scripts/resolve-toolchain.sh'


class ToolchainResolverTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)
        self.fixture.validate()
        self.environment = dict(self.fixture.env, PATH='/usr/bin:/bin')
        self.stable = Path(self.environment['HOME']) / '.local/share/shed-tools/jdk-21'
        self.java(self.stable)

    @staticmethod
    def java(home, version=21):
        binary = home / 'bin/java'
        binary.parent.mkdir(parents=True, exist_ok=True)
        binary.write_text('#!' + sys.executable + '\n'
                          'import sys\n'
                          'from pathlib import Path\n'
                          f'Path({str(home / "executed")!r}).touch()\n'
                          f'print(\'openjdk version "{version}.0.9"\')\n'
                          'if "-XshowSettings:properties" in sys.argv:\n'
                          f' print({("    java.home = " + str(home))!r})\n')
        binary.chmod(0o700)
        return binary

    def resolve(self, mode='java', **overrides):
        environment = dict(self.environment, **overrides)
        return subprocess.run(['/bin/sh', str(RESOLVER), mode], env=environment,
                              cwd=self.fixture.run_root, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=5)

    def test_missing_home_warns_and_selects_valid_shed_jdk(self):
        for mode, expected in [('java', self.stable / 'bin/java'), ('home', self.stable)]:
            result = self.resolve(mode, JAVA_HOME=str(self.fixture.run_root / 'missing-homebrew'))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(expected))
            self.assertIn('ignoring missing JAVA_HOME', result.stderr)

    def test_valid_explicit_home_takes_precedence(self):
        home = self.fixture.run_root / 'explicit-jdk'
        self.java(home)
        result = self.resolve(JAVA_HOME=str(home))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(home / 'bin/java'))
        self.assertEqual(result.stderr, '')
        self.assertFalse((self.stable / 'executed').exists())

    def test_existing_wrong_major_home_still_fails_closed(self):
        home = self.fixture.run_root / 'jdk-22'
        self.java(home, 22)
        result = self.resolve(JAVA_HOME=str(home))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('must point to a working JDK 21', result.stderr)
        self.assertFalse((self.stable / 'executed').exists())

    def test_explicit_invalid_java_does_not_fall_back(self):
        result = self.resolve(JAVA=str(self.fixture.run_root / 'missing-java'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('JAVA must point', result.stderr)
        self.assertFalse((self.stable / 'executed').exists())

    def test_explicit_java_overrides_home(self):
        binary = self.java(self.fixture.run_root / 'explicit-java')
        result = self.resolve(JAVA=str(binary), JAVA_HOME=str(self.fixture.run_root / 'missing-home'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(binary))
        self.assertEqual(result.stderr, '')
        self.assertFalse((self.stable / 'executed').exists())

    def test_homebrew_and_bundle_prefixes_resolve_real_home(self):
        for suffix in ['libexec/openjdk.jdk/Contents/Home', 'Contents/Home']:
            prefix = self.fixture.run_root / suffix.replace('/', '-')
            home = prefix / suffix
            self.java(home)
            result = self.resolve('home', JAVA_HOME=str(prefix))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(home))
            self.assertFalse((self.stable / 'executed').exists())

    def test_managed_jdk_avoids_system_java_stub(self):
        path_home = self.fixture.run_root / 'path-jdk'
        self.java(path_home)
        result = self.resolve(PATH=str(path_home / 'bin') + ':/usr/bin:/bin')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(self.stable / 'bin/java'))
        self.assertFalse((path_home / 'executed').exists())


if __name__ == '__main__':
    unittest.main()
