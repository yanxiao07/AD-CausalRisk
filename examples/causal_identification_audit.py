"""Exact identification counterexamples; no patient data, models or fitting.

Run with Python's standard library: python -I -B examples/causal_identification_audit.py
All numbers describe abstract binary variables, not ADNI treatment effects.
"""

from fractions import Fraction as Q
from itertools import product
import json


def require(condition, message):
    if not condition:
        raise ValueError(message)


def probability_law(table):
    require(all(p >= 0 for p in table.values()), "Negative probability")
    require(sum(table.values()) == 1, "Probability law must sum to one")
    return table


def scm_law(model, intervention=None):
    """Enumerate independent exogenous binary variables with exact mass."""
    joint = {(a, y): Q(0) for a, y in product((0, 1), repeat=2)}
    for primitive, error in product((0, 1), repeat=2):
        mass = Q(1, 2) * (Q(1, 5) if error else Q(4, 5))
        if model == "causal":
            a = primitive if intervention is None else intervention
            y = a ^ error
        elif model == "confounded":
            a = (primitive ^ error) if intervention is None else intervention
            y = primitive
        else:
            raise ValueError("Unknown SCM")
        joint[a, y] += mass
    return probability_law(joint)


def outcome_mean(joint):
    return sum(mass * y for (_, y), mass in joint.items())


def consistency_vertices(observed):
    """Four observed cells each have one unobserved binary potential outcome.

    All distributions compatible with consistency and this observed law form
    a product of four line segments. A linear ACE attains its extrema at one
    of the 2**4 vertices. No exchangeability is assumed.
    """
    cells = sorted(observed)
    vertices = []
    for missing in product((0, 1), repeat=len(cells)):
        ace = Q(0)
        for (a, y), other_y in zip(cells, missing):
            y0, y1 = (y, other_y) if a == 0 else (other_y, y)
            ace += observed[a, y] * (y1 - y0)
        vertices.append({"missing_potential_outcomes": list(missing), "ace": ace})
    return vertices


def randomized_coupling(t):
    """Same two interventional marginals, different cross-world joint law."""
    types = probability_law({
        (0, 0): t,
        (0, 1): Q(4, 5) - t,
        (1, 0): Q(1, 5) - t,
        (1, 1): t,
    })
    observed = {(a, y): Q(0) for a, y in product((0, 1), repeat=2)}
    for (y0, y1), mass in types.items():
        observed[0, y0] += mass / 2
        observed[1, y1] += mass / 2
    return {
        "types": types,
        "observed": probability_law(observed),
        "mean_Y0": sum(y0 * p for (y0, _), p in types.items()),
        "mean_Y1": sum(y1 * p for (_, y1), p in types.items()),
        "probability_Y1_greater_than_Y0": types[0, 1],
        "probability_Y1_less_than_Y0": types[1, 0],
    }


def audit():
    expected = {(0, 0): Q(2, 5), (0, 1): Q(1, 10),
                (1, 0): Q(1, 10), (1, 1): Q(2, 5)}
    checks = []

    causal, confounded = scm_law("causal"), scm_law("confounded")
    require(causal == confounded == expected, "Observed laws differ")
    checks.append("two distinct SCMs have exactly the same observed joint law")
    require(all(p > 0 for p in causal.values()), "Observed empty cell")
    checks.append("all four observed A,Y cells have positive probability")
    do_means = {
        model: [outcome_mean(scm_law(model, a)) for a in (0, 1)]
        for model in ("causal", "confounded")
    }
    require(do_means == {"causal": [Q(1, 5), Q(4, 5)],
                         "confounded": [Q(1, 2), Q(1, 2)]}, "Wrong do law")
    checks.append("surgical interventions yield ACE 3/5 versus 0")
    observed_difference = causal[1, 1] / sum(p for (a, _), p in causal.items() if a == 1)
    observed_difference -= causal[0, 1] / sum(p for (a, _), p in causal.items() if a == 0)
    require(observed_difference == Q(3, 5), "Wrong observed contrast")
    checks.append("observed conditional contrast is 3/5 in both SCMs")

    vertices = consistency_vertices(expected)
    bounds = [min(v["ace"] for v in vertices), max(v["ace"] for v in vertices)]
    p_a0 = sum(p for (a, _), p in expected.items() if a == 0)
    p_a1 = 1 - p_a0
    analytic_bounds = [expected[1, 1] - expected[0, 1] - p_a1,
                       expected[1, 1] + p_a0 - expected[0, 1]]
    require(len(vertices) == 16 and bounds == analytic_bounds == [Q(-1, 5), Q(4, 5)],
            "Vertex extrema disagree with analytic consistency bounds")
    checks.append("16 vertex completions attain sharp consistency-only ACE bounds [-1/5,4/5]")
    require(all(bounds[0] <= m[1] - m[0] <= bounds[1] for m in do_means.values()),
            "SCM effect outside consistency bounds")
    checks.append("both SCM effects are compatible with the same sharp bounds")

    couplings = [randomized_coupling(t) for t in (Q(0), Q(1, 5))]
    require(all(c["observed"] == expected and c["mean_Y0"] == Q(1, 5)
                and c["mean_Y1"] == Q(4, 5) for c in couplings), "Marginals differ")
    checks.append("two randomized populations share observed law and both do marginals")
    require([c["probability_Y1_greater_than_Y0"] for c in couplings] == [Q(4, 5), Q(3, 5)]
            and [c["probability_Y1_less_than_Y0"] for c in couplings] == [Q(1, 5), Q(0)],
            "Cross-world ambiguity not demonstrated")
    checks.append("same randomized ACE does not identify joint personal response types")

    # Corruption controls keep a silent invalid law from passing this argument.
    rejected = 0
    for bad in ({0: Q(-1), 1: Q(2)}, {0: Q(1, 3), 1: Q(1, 3)}):
        try:
            probability_law(bad)
        except ValueError:
            rejected += 1
    require(rejected == 2, "Invalid probability law accepted")
    checks.append("negative-mass and non-normalized laws rejected")

    return {
        "status": "EXACT_ABSTRACT_IDENTIFICATION_ARGUMENT_PASSED",
        "abstract_not_clinical": True,
        "observed_A_Y": expected,
        "observed_conditional_difference": observed_difference,
        "do_outcome_means": do_means,
        "consistency_only_ACE_bounds": bounds,
        "vertex_completions": vertices,
        "randomized_couplings": couplings,
        "checks": checks,
        "checks_passed": len(checks),
        "fits": 0,
        "conclusion": "Observed association alone does not identify intervention effects; identified interventional marginals alone do not identify a person's pair of potential outcomes.",
    }


def json_ready(value):
    if isinstance(value, Q):
        return str(value)
    if isinstance(value, dict):
        return {(str(k) if not isinstance(k, tuple) else "".join(map(str, k))): json_ready(v)
                for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_ready(v) for v in value]
    return value


if __name__ == "__main__":
    print(json.dumps(json_ready(audit()), ensure_ascii=False, indent=2))
