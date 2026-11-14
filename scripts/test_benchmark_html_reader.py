"""Deterministic small tooling checks; no JVM, rendering or memory exhaustion."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('reader_benchmark', Path(__file__).with_name('benchmark-html-reader.py'))
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class HtmlReaderBenchmarkTest(unittest.TestCase):
    def test_original_failure_fixture_is_reproducible_without_saved_temporary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = bench.fixture(Path(directory) / 'input.md', 'original', None)
            self.assertEqual(1048576, manifest['bytes'])
            self.assertEqual('2bd8379d31ec16a14bd860a6550dd76fd774045354839709dd2bac5179c46825', manifest['sha256'])

    def test_multiline_and_single_line_have_same_bounded_body_size(self):
        with tempfile.TemporaryDirectory() as directory:
            for case, lines in [('code256', 2048), ('single', 1)]:
                path = Path(directory) / (case + '.md')
                manifest = bench.fixture(path, case, None)
                body = path.read_text().split('```text\n')[1].split('```')[0]
                self.assertEqual(262144, len(body))
                self.assertEqual(lines, body.count('\n'))
                self.assertLess(manifest['bytes'], 1048576)

    def test_special_characters_and_raw_html_are_preserved_or_escaped(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'special.md'
            bench.fixture(path, 'special', None)
            for marker in ['\t', '<tag>', '&', '😀', 'e\u0301']:
                self.assertIn(marker, path.read_text())
            raw = Path(directory) / 'raw.html'
            bench.fixture(raw, 'raw-pre', None)
            self.assertTrue(raw.read_text().startswith('<html><body><pre><code>'))

    def test_oversized_original_is_rejected_before_copying(self):
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / 'large.md'
            with original.open('wb') as file:
                file.truncate(1048577)
            output = Path(directory) / 'never.md'
            with self.assertRaises(ValueError): bench.fixture(output, 'original', original)
            self.assertFalse(output.exists())


if __name__ == '__main__': unittest.main()
