import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('scm_performance', Path(__file__).with_name('test-scm-performance.py'))
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


def result(candidate=False, latency=1.0):
    names = ['typing.hidden.Small.txt', 'typing.hidden.100KiB.txt', 'typing.hidden.1MiB.txt', 'typing.source_control_open']
    if candidate:
        names += ['typing.' + state + '.' + provider for state in ('pending', '5000_rows', 'installing', 'failed') for provider in ('MERCURIAL', 'SUBVERSION')]
    rows = [{'operation': name, 'samples_ms': [latency] * 100, 'queued_ms': [.1] * 100, 'warmup_samples_ms': [latency] * 5,
             'p95_ms': latency, 'queued_p95_ms': .1, 'first_ms': latency, 'warmups': 5} for name in names]
    rows += [{'operation': name, 'p95_ms': latency} for name in ('undo', 'redo', 'split_cycle', 'deletion', 'newline', 'caret', 'selection', 'scroll', 'buffer_switch')]
    rows += [{'operation': 'open.' + file, 'ms': 10} for file in ('Small.txt', '100KiB.txt', '1MiB.txt')]
    rows += [{'operation': 'common_settled_memory', 'values': {'used_heap_bytes': 10 * 1024 * 1024, 'threads': 20, 'open_descriptors': 20}}]
    if candidate:
        rows += [{'operation': 'hidden_polling.' + provider, 'observation_ms': 2700, 'calls_before': 10, 'calls_after': 10, 'timer_running': False} for provider in ('MERCURIAL', 'SUBVERSION')]
        rows += [{'operation': 'scm_disposal', 'cycles': 12, 'retained_panels': 0, 'active_client_calls': 0}]
    return {'state': 'PASS', 'native_shell': 'NOT_USED', 'rows': rows, 'after_close': {'descendants': 0}, 'startup_to_completed_source_paint_jvm_ms': 1000}


def runs(pairs=3, candidate=None):
    return [{'kind': kind, 'result': copy.deepcopy(candidate if kind == 'candidate' and candidate else result(kind == 'candidate'))}
            for kind in perf.order(pairs)]


class SourceControlPerformanceToolingTest(unittest.TestCase):
    def test_order_is_bounded_and_alternates_ab_ba_ab(self):
        self.assertEqual(['baseline', 'candidate', 'candidate', 'baseline', 'baseline', 'candidate'], perf.order(3))
        for n in (0, 6):
            with self.assertRaises(ValueError):
                perf.order(n)

    def test_three_complete_pairs_pass_and_one_pair_is_only_a_pilot(self):
        self.assertEqual('PASS', perf.compare(runs())['state'])
        self.assertEqual('INSUFFICIENT_PAIRS', perf.compare(runs(1))['state'])

    def test_failed_probe_or_unexpected_native_path_is_rejected(self):
        for key, value in [('state', 'FAIL'), ('native_shell', 'USED')]:
            data = result(True)
            data[key] = value
            with self.assertRaises(ValueError):
                perf.validate_result(data, True)

    def test_missing_provider_coverage_is_rejected(self):
        with self.assertRaises(ValueError):
            perf.validate_result(result(False), True)

    def test_duplicate_workloads_are_rejected(self):
        data = result(True)
        data['rows'].append(copy.deepcopy(data['rows'][0]))
        with self.assertRaises(ValueError):
            perf.validate_result(data, True)

    def test_nonfinite_and_forged_percentiles_are_rejected(self):
        for value in (float('nan'), -1, True, None):
            with self.assertRaises(ValueError):
                perf.finite(value)
        data = result(True)
        data['rows'][0]['p95_ms'] = .001
        with self.assertRaises(ValueError):
            perf.validate_result(data, True)

    def test_missing_warmups_or_samples_are_rejected(self):
        for key in ('samples_ms', 'queued_ms', 'warmup_samples_ms'):
            data = result(True)
            data['rows'][0][key].pop()
            with self.assertRaises(ValueError):
                perf.validate_result(data, True)

    def test_absolute_typing_gate_cannot_be_hidden_by_slow_baseline(self):
        with self.assertRaises(ValueError):
            perf.compare(runs(candidate=result(True, 17)))

    def test_large_relative_slowdown_below_absolute_target_still_fails(self):
        self.assertEqual('FAIL', perf.compare(runs(candidate=result(True, 3)))['state'])

    def test_startup_and_retained_heap_regressions_fail(self):
        startup = result(True)
        startup['startup_to_completed_source_paint_jvm_ms'] = 1500
        self.assertEqual('FAIL', perf.compare(runs(candidate=startup))['state'])
        heap = result(True)
        next(r for r in heap['rows'] if r['operation'] == 'common_settled_memory')['values']['used_heap_bytes'] += 9 * 1024 * 1024
        self.assertEqual('FAIL', perf.compare(runs(candidate=heap))['state'])

    def test_retained_panels_workers_or_descendants_are_rejected(self):
        for key in ('retained_panels', 'active_client_calls'):
            data = result(True)
            next(r for r in data['rows'] if r['operation'] == 'scm_disposal')[key] = 1
            with self.assertRaises(ValueError):
                perf.validate_result(data, True)
        data = result(True)
        data['after_close']['descendants'] = 1
        with self.assertRaises(ValueError):
            perf.validate_result(data, True)

    def test_bulk_install_stall_cannot_hide_in_first_use(self):
        data = result(True)
        next(r for r in data['rows'] if r['operation'] == 'typing.installing.MERCURIAL')['first_ms'] = 51
        with self.assertRaises(ValueError):
            perf.validate_result(data, True)

    def test_hidden_timers_and_polling_are_rejected(self):
        for key, value in [('timer_running', True), ('calls_after', 11), ('observation_ms', 100)]:
            data = result(True)
            next(r for r in data['rows'] if r['operation'] == 'hidden_polling.MERCURIAL')[key] = value
            with self.assertRaises(ValueError):
                perf.validate_result(data, True)

    def test_pilot_and_failed_comparisons_do_not_exit_successfully(self):
        self.assertEqual(0, perf.exit_code('PASS'))
        self.assertEqual(0, perf.exit_code('COMPILE_ONLY'))
        self.assertEqual(2, perf.exit_code('INSUFFICIENT_PAIRS'))
        self.assertEqual(1, perf.exit_code('FAIL'))

    def test_incomplete_pairs_are_rejected(self):
        with self.assertRaises(ValueError):
            perf.compare(runs()[:-1])


if __name__ == '__main__':
    unittest.main()
