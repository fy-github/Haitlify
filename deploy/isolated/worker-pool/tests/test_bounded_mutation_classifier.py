"""Persistent unit contract for bounded mutation result classification."""
import importlib.util
import copy
import socket
import pathlib
import unittest


TARGET = pathlib.Path(__file__).with_name("verify_bounded_control_mutations.py")
SPEC = importlib.util.spec_from_file_location("bounded_mutation_runner", TARGET)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("unable to load bounded mutation runner")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


METHOD = "test_selected_case"
ASSERTION = "EXPECTED_MARKER"


def result(*, run=1, failures=None, errors=None, skipped=None,
           expected_failures=None, unexpected_successes=None):
    return {
        "run": run,
        "failures": [] if failures is None else failures,
        "errors": [] if errors is None else errors,
        "skipped": [] if skipped is None else skipped,
        "expected_failures": ([] if expected_failures is None
                              else expected_failures),
        "unexpected_successes": ([] if unexpected_successes is None
                                  else unexpected_successes),
    }


def run_record(*, exit_code=1, data=None, timed_out=False, launch_error=None):
    return {
        "exit_code": exit_code,
        "timed_out": timed_out,
        "launch_error": launch_error,
        "result": result() if data is None else data,
    }


def matching_failure():
    return {
        "test": METHOD + " (subtest=one)",
        "trace": "Traceback\nAssertionError: " + ASSERTION,
    }


def unrelated_failure():
    return {
        "test": METHOD + " (subtest=other)",
        "trace": "Traceback\nAssertionError: OTHER_FAILURE",
    }


def green_baseline():
    return run_record(exit_code=0, data=result())


class BoundedMutationClassifierTests(unittest.TestCase):
    def classify(self, baseline=None, mutant=None, expected_failure_count=1):
        return runner.classify(
            green_baseline() if baseline is None else baseline,
            mutant,
            METHOD,
            ASSERTION,
            expected_failure_count=expected_failure_count,
        )

    def test_duplicate_matching_failure_is_invalid(self):
        mutant = run_record(data=result(failures=[matching_failure(), matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_missing_failure_is_invalid(self):
        mutant = run_record(data=result(failures=[]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_mixed_matching_and_unrelated_failures_are_invalid(self):
        mutant = run_record(data=result(failures=[matching_failure(), unrelated_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_wrong_run_count_is_invalid(self):
        mutant = run_record(data=result(run=2, failures=[matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_boolean_run_count_is_not_an_integer_run_count(self):
        mutant = run_record(data=result(run=True, failures=[matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_boolean_exit_code_is_not_an_integer_exit_code(self):
        mutant = run_record(exit_code=True, data=result(failures=[matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_single_matching_failure_is_detected(self):
        mutant = run_record(data=result(failures=[matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "DETECTED")

    def test_green_mutant_is_survived(self):
        mutant = run_record(exit_code=0, data=result())
        self.assertEqual(self.classify(mutant=mutant)[0], "SURVIVED")

    def test_red_baseline_is_invalid(self):
        baseline = run_record(exit_code=1, data=result(failures=[matching_failure()]))
        mutant = run_record(data=result(failures=[matching_failure()]))
        self.assertEqual(self.classify(baseline=baseline, mutant=mutant)[0], "INVALID")

    def test_skip_is_invalid(self):
        mutant = run_record(data=result(skipped=["selected test"]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_error_is_invalid(self):
        mutant = run_record(data=result(errors=[{"test": METHOD, "trace": "error"}]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_timeout_is_invalid(self):
        mutant = run_record(timed_out=True, data=result(failures=[matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant)[0], "INVALID")

    def test_duplicate_failure_is_invalid_even_with_explicit_two_count(self):
        mutant = run_record(data=result(failures=[matching_failure(), matching_failure()]))
        self.assertEqual(self.classify(mutant=mutant, expected_failure_count=2)[0], "INVALID")


# Independent test specification from test_bounded_edge's two real subTest
# dimensions; do not obtain expected identities from the runner or a mutant.
C8_METHOD = 'test_c8_connection_failure_and_close_failure_restore_capacity'
C6_METHOD = 'test_c6_upstream_lock_revoke_rejects_then_fences_both_roles'
MULTI_CASES = (
    ('C8-diagnostic', C8_METHOD, 'C8 fixed local diagnostic', 'plane',
     (socket.AF_UNIX, socket.AF_INET)),
    ('C8-close-fallback', C8_METHOD, 'C8 failed close', 'plane',
     (socket.AF_UNIX, socket.AF_INET)),
    ('C8-active-accounting', C8_METHOD, 'C8 active accounting', 'plane',
     (socket.AF_UNIX, socket.AF_INET)),
    ('C6-forwarding-unlocked', C6_METHOD, 'C6 forwarding lock', 'role',
     ('mcp', 'provider')),
)


def real_subtest_failures(method, marker, dimension, values):
    def selected(self):
        for value in values:
            with self.subTest(**{dimension: value}):
                self.fail(marker)
    cls = type('BoundedEdgeTests', (unittest.TestCase,), {
        '__module__': 'test_bounded_edge', method: selected})
    collected = unittest.TestResult()
    cls(method).run(collected)
    assert collected.testsRun == 1 and not collected.errors
    return [dict(test=str(test), trace=trace) for test, trace in collected.failures]


class MultiSubscenarioIdentityTests(unittest.TestCase):
    def check_all(self, change, expected):
        for name, method, marker, dimension, values in MULTI_CASES:
            with self.subTest(case=name):
                failures = real_subtest_failures(method, marker, dimension, values)
                self.assertEqual(len(failures), 2)
                self.assertNotEqual(failures[0]['test'], failures[1]['test'])
                failures = change(failures)
                verdict = runner.classify(green_baseline(),
                    run_record(data=result(failures=failures)), method, marker,
                    expected_failure_count=2)
                self.assertEqual(verdict[0], expected)

    def test_all_four_duplicate_replacement_is_invalid(self):
        self.check_all(lambda f: [f[0], copy.deepcopy(f[0])], 'INVALID')

    def test_all_four_same_identity_different_trace_is_invalid(self):
        def replace(f):
            f[1]['test'] = f[0]['test']
            f[1]['trace'] += '\nAdditional traceback detail'
            return f
        self.check_all(replace, 'INVALID')

    def test_all_four_missing_subscenario_is_invalid(self):
        self.check_all(lambda f: f[:1], 'INVALID')

    def test_all_four_wrong_subscenario_is_invalid(self):
        def replace(f):
            f[1]['test'] += ' (unexpected=True)'
            return f
        self.check_all(replace, 'INVALID')

    def test_all_four_wrong_test_class_is_invalid(self):
        def replace(f):
            for item in f:
                item['test'] = item['test'].replace('BoundedEdgeTests', 'OtherTests')
            return f
        self.check_all(replace, 'INVALID')

    def test_all_four_correct_distinct_subscenarios_are_detected(self):
        self.check_all(lambda f: f, 'DETECTED')

    def test_all_four_same_trace_distinct_identities_are_detected(self):
        def same_trace(f):
            f[1]['trace'] = f[0]['trace']
            return f
        self.check_all(same_trace, 'DETECTED')

    def test_all_four_order_does_not_define_identity(self):
        self.check_all(lambda f: list(reversed(f)), 'DETECTED')


class MalformedClassificationTests(unittest.TestCase):
    def check_invalid(self, record, baseline=False, **options):
        args = (record, run_record(data=result(failures=[matching_failure()]))) if baseline else (
            green_baseline(), record)
        try:
            verdict = runner.classify(*args, METHOD, ASSERTION, **options)
        except Exception as error:
            self.fail('malformed record escaped classification: ' + repr(error))
        self.assertEqual(verdict[0], 'INVALID')

    def test_timeout_requires_an_explicit_boolean_false(self):
        for baseline in (False, True):
            for value in (None, 0, '', [], {}, 'false'):
                with self.subTest(baseline=baseline, value=value):
                    record = green_baseline() if baseline else run_record(
                        data=result(failures=[matching_failure()]))
                    record['timed_out'] = value
                    self.check_invalid(record, baseline)
            record = green_baseline()
            del record['timed_out']
            self.check_invalid(record, baseline)

    def test_failure_count_requires_a_positive_strict_integer(self):
        for count in (True, False, 1.0, 2.0, '1', None, 0, -1):
            with self.subTest(count=count):
                self.check_invalid(run_record(data=result(failures=[matching_failure()])),
                                   expected_failure_count=count)

    def test_records_and_result_fields_fail_closed(self):
        for baseline in (False, True):
            for record in (None, [], '', 1, True):
                with self.subTest(baseline=baseline, record=record):
                    self.check_invalid(record, baseline)
            original = green_baseline() if baseline else run_record(
                data=result(failures=[matching_failure()]))
            for field in ('exit_code', 'timed_out', 'result'):
                record = copy.deepcopy(original)
                del record[field]
                with self.subTest(baseline=baseline, missing=field):
                    self.check_invalid(record, baseline)
            for field in result():
                for value in (None, {}, '', True, 1.0):
                    record = copy.deepcopy(original)
                    record['result'][field] = value
                    with self.subTest(baseline=baseline, field=field, value=value):
                        self.check_invalid(record, baseline)
                record = copy.deepcopy(original)
                del record['result'][field]
                self.check_invalid(record, baseline)

    def test_malformed_failure_entries_fail_closed_without_keyerror(self):
        for item in (None, [], 1, '', {}, {'test': METHOD}, {'trace': ASSERTION},
                     {'test': None, 'trace': 'text'}, {'test': [], 'trace': 'text'},
                     {'test': METHOD, 'trace': None}, {'test': METHOD, 'trace': []},
                     {'test': '', 'trace': 'text'}, {'test': METHOD, 'trace': ''}):
            with self.subTest(item=item):
                self.check_invalid(run_record(data=result(failures=[item])))

    def test_falsey_launch_errors_and_parse_errors_are_not_success(self):
        for field in ('launch_error', 'parse_error'):
            for value in ('', False, 0, [], {}):
                with self.subTest(field=field, value=value):
                    record = run_record(data=result(failures=[matching_failure()]))
                    record[field] = value
                    self.check_invalid(record)


if __name__ == "__main__":
    unittest.main()
