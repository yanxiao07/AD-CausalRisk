"""Exact mathematical laws only; no fitting or clinical/model imports."""
from dataclasses import replace
from fractions import Fraction as F
from itertools import product
import unittest

from research_runtime.competing_event_functional import Transition, evaluate_known_policy


def constant_law(h1, h2, horizon=2):
    row = Transition(h1, h2, (1 - h1 - h2,))
    return (F(1),), (((row,),),) * horizon, (((F(1),),),) * horizon


def trajectory_oracle(initial, kernels, policy):
    """Full action/state sequences, independently product-weighted terminal paths.

    Unlike the implementation, no live-state distribution is updated. For each
    possible terminal time, enumerate every prefix and multiply all its factors.
    """
    horizon, states = len(kernels), len(initial)
    actions = len(kernels[0][0])
    totals = [F(0), F(0), F(0)]
    paths = 0
    for length in range(1, horizon + 1):
        for state_seq in product(range(states), repeat=length):
            for action_seq in product(range(actions), repeat=length):
                mass = initial[state_seq[0]]
                for t, (s, a) in enumerate(zip(state_seq, action_seq)):
                    mass *= policy[t][s][a]
                    if t < length - 1:
                        mass *= kernels[t][s][a].free[state_seq[t + 1]]
                last = kernels[length - 1][state_seq[-1]][action_seq[-1]]
                totals[0] += mass * last.event1
                totals[1] += mass * last.event2
                paths += 2
                if length == horizon:
                    totals[2] += mass * sum(last.free)
                    paths += states
    return tuple(totals), paths


def history_law():
    # A_0 changes L_1 before the second interval's outcome.
    first = tuple((Transition(F(0), F(0), (F(1), F(0))),
                   Transition(F(0), F(0), (F(0), F(1)))) for _ in range(2))
    second = tuple(tuple(Transition(F(1 + 2*s, 4), F(0), (F(3 - 2*s, 4), F(0)))
                         for _ in range(2)) for s in range(2))
    return (F(1), F(0)), (first, second)


class CompetingFunctionalTests(unittest.TestCase):
    def test_geometric_two_cause_and_24_period_closed_form(self):
        for horizon in (1, 2, 24):
            out = evaluate_known_policy(*constant_law(F(1, 10), F(3, 10), horizon))[-1]
            self.assertEqual(out.event1, (1 - F(3, 5)**horizon) / 4)
            self.assertEqual(out.event2, 3 * out.event1)
            self.assertEqual(sum(out.free), F(3, 5)**horizon)

    def test_unmodified_event1_hazard_different_competition(self):
        ordinary = evaluate_known_policy(*constant_law(F(1, 10), F(3, 10)))[-1]
        altered = evaluate_known_policy(*constant_law(F(1, 10), F(0)))[-1]
        self.assertEqual((ordinary.event1, altered.event1), (F(4, 25), F(19, 100)))
        self.assertLess(ordinary.event1, altered.event1)
        self.assertGreater(ordinary.all_event, altered.all_event)

    def test_higher_cause1_hazard_can_have_lower_cif(self):
        ref = evaluate_known_policy(*constant_law(F(1, 10), F(1, 10)))[-1]
        active = evaluate_known_policy(*constant_law(F(3, 25), F(3, 5)))[-1]
        self.assertEqual((ref.event1, active.event1), (F(9, 50), F(96, 625)))
        self.assertLess(active.event1, ref.event1)
        self.assertGreater(active.all_event, ref.all_event)

    def test_competing_first_event_absorbs_before_later_cause1(self):
        end = Transition(F(0), F(1), (F(0),))
        later = Transition(F(1), F(0), (F(0),))
        out = evaluate_known_policy((F(1),), (((end,),), ((later,),)), (((F(1),),),) * 2)
        self.assertEqual(out[-1].event1, 0)
        self.assertEqual(out[-1].event2, 1)
        self.assertEqual(out[-1].increment1, 0)

    def test_longitudinal_state_is_propagated_under_policy(self):
        initial, kernels = history_law()
        risks = []
        for action in (0, 1):
            rule = tuple(F(int(a == action)) for a in range(2))
            risks.append(evaluate_known_policy(initial, kernels, ((rule, rule),) * 2)[-1].event1)
        self.assertEqual(risks, [F(1, 4), F(3, 4)])
        self.assertEqual(risks[1] - risks[0], F(1, 2))
        # Holding baseline L=0 fixed would incorrectly return 1/4 in both arms.
        self.assertNotEqual(risks[1], kernels[1][0][1].event1)

    def test_state_dependent_policy_uses_current_history(self):
        initial = (F(1, 3), F(2, 3))
        rows = tuple((Transition(F(0), F(0), (F(1), F(0))),
                      Transition(F(1), F(0), (F(0), F(0)))) for _ in range(2))
        rules = (((F(1), F(0)), (F(0), F(1))),)
        self.assertEqual(evaluate_known_policy(initial, (rows,), rules)[-1].event1, F(2, 3))

    def test_stochastic_mixture_at_single_assignment(self):
        initial, kernels = history_law()
        # Only the first assignment affects this law, so policy mixture is linear.
        weights = (F(2, 5), F(3, 5))
        out = evaluate_known_policy(initial, kernels, ((weights, weights),) * 2)[-1]
        self.assertEqual(out.event1, F(2, 5)*F(1, 4) + F(3, 5)*F(3, 4))

    def test_exhaustive_paths_for_nonstationary_history_policies(self):
        # Independent finite-tree oracle: 2 states, 2 actions, 3 intervals.
        kernels = tuple(tuple(tuple(
            Transition(F(1+t+s+a, 20), F(2+a, 20),
                       (F(3+s, 20), F(14-t-2*s-2*a, 20)))
            for a in range(2)) for s in range(2)) for t in range(3))
        policy = tuple(tuple((F(1+t+s, 6), 1-F(1+t+s, 6)) for s in range(2)) for t in range(3))
        initial = (F(2, 5), F(3, 5))
        out = evaluate_known_policy(initial, kernels, policy)
        for t in (1, 2, 3):
            expected, paths = trajectory_oracle(initial, kernels[:t], policy[:t])
            actual = out[t]
            self.assertEqual((actual.event1, actual.event2, sum(actual.free)), expected)
            self.assertEqual(actual.all_event + sum(actual.free), 1)
            self.assertGreater(paths, 0)

    def test_state_relabeling_invariance(self):
        initial, kernels = history_law()
        weights = ((F(2, 3), F(1, 3)), (F(1, 4), F(3, 4)))
        policy = (weights,) * 2
        original = evaluate_known_policy(initial, kernels, policy)[-1]
        changed = tuple(tuple(tuple(replace(row, free=row.free[::-1]) for row in choices)
                              for choices in layer[::-1]) for layer in kernels)
        swapped = evaluate_known_policy(initial[::-1], changed, tuple(p[::-1] for p in policy))[-1]
        self.assertEqual((original.event1, original.event2), (swapped.event1, swapped.event2))
        self.assertEqual(original.free, swapped.free[::-1])

    def test_no_event_and_certain_event_boundaries(self):
        self.assertEqual(evaluate_known_policy(*constant_law(F(0), F(0)))[-1].free, (F(1),))
        self.assertEqual(evaluate_known_policy(*constant_law(F(1), F(0)))[-1].event1, 1)
        self.assertEqual(evaluate_known_policy(*constant_law(F(0), F(1)))[-1].event2, 1)

    def test_invalid_probability_laws_are_not_normalized(self):
        initial, kernels, policy = constant_law(F(1, 10), F(1, 10))
        for bad in (F(-1, 10), F(11, 10), 0.1, True):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                row = Transition(bad, F(0), (F(1),))
                evaluate_known_policy(initial, (((row,),),), policy[:1])
        with self.assertRaises(ValueError):
            row = Transition(F(1, 10), F(1, 10), (F(1),))
            evaluate_known_policy(initial, (((row,),),), policy[:1])
        with self.assertRaises(ValueError):
            evaluate_known_policy(initial, kernels, (((F(1, 2),),),) * 2)

    def test_missing_or_inconsistent_dimensions_rejected(self):
        initial, kernels, policy = constant_law(F(1, 10), F(1, 10))
        invalid = [((), kernels, policy), (initial, (), ()), (initial, kernels, policy[:1]),
                   ((F(1, 2),), kernels, policy), (initial, ((),), policy[:1]),
                   (initial, kernels, (((),),) * 2),
                   (initial, (((),),), policy[:1]),
                   (initial, (((None,),),), policy[:1])]
        for args in invalid:
            with self.subTest(args=args), self.assertRaises(ValueError):
                evaluate_known_policy(*args)


if __name__ == "__main__":
    unittest.main()
