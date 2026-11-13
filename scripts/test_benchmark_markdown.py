"""Fast deterministic tooling tests; no editor launch, profiling, or large fixture allocation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('markdown_benchmark', Path(__file__).with_name('benchmark-markdown.py'))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class MarkdownBenchmarkTest(unittest.TestCase):
    def test_fixture_bytes_hash_and_syntax_are_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            a = Path(directory) / 'a.md'
            b = Path(directory) / 'b.md'
            first = bench.fixture(a, 102400, 'mixed')
            second = bench.fixture(b, 102400, 'mixed')
            self.assertEqual(first['bytes'], a.stat().st_size)
            self.assertEqual(first['sha256'], second['sha256'])
            self.assertGreater(first['complete_blocks'], 0)
            text = a.read_text()
            for syntax in ['# Section', '- [ ]', '> >', '[ref]:', '| a\\|b', '```java', '```mermaid', '$x^2$', '![local]']:
                self.assertIn(syntax, text)
            self.assertTrue(text.endswith('\n'))
            with self.assertRaises(FileExistsError): bench.fixture(a, 102400, 'mixed')

    def test_fixture_bounds_and_unknown_shape_are_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'never-created.md'
            for size, shape in [(0, 'mixed'), (101*1024**2, 'mixed'), (4096, 'invalid')]:
                with self.assertRaises(ValueError): bench.fixture(p, size, shape)
            self.assertFalse(p.exists())

    def test_percentiles_do_not_mislabel_small_samples_or_timestamps(self):
        rows = [{'state': 'OK', 'operation': 'insert_0', 'wall_ms': n, 'epoch_ms': 12345} for n in range(1, 101)]
        result = bench.summarize(rows)
        self.assertEqual(95, result['insert_0/wall_ms']['p95_ms'])
        self.assertEqual(99, result['insert_0/wall_ms']['p99_ms'])
        self.assertNotIn('insert_0/epoch_ms', result)
        self.assertIsNone(bench.summarize(rows[:3])['insert_0/wall_ms']['p99_ms'])
        self.assertEqual({}, bench.summarize([{'state': 'UNSUPPORTED', 'operation': 'save', 'wall_ms': 0}]))


if __name__ == '__main__': unittest.main()
