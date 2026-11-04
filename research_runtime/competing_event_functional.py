"""Exact finite-state policy functional for *specified*, fully observed laws.

No kernels are learned or extracted here. Supplying observational predictions
does not turn the result into a causal estimate. A state must encode every
history variable needed by the specified law and policy (Markov sufficiency).
This module has no connection to clinical data, saved predictors or HTTP.
"""
from dataclasses import dataclass
from fractions import Fraction


@dataclass(frozen=True)
class Transition:
    """One joint next-step distribution conditional on being event free.

    event1/event2 are mutually exclusive first-event probabilities in this
    interval. free[j] is P(no event AND next state j), *not* a conditional
    distribution renormalized to one. Event states are absorbing.
    """

    event1: Fraction
    event2: Fraction
    free: tuple[Fraction, ...]


@dataclass(frozen=True)
class RiskStep:
    period: int
    event1: Fraction
    event2: Fraction
    free: tuple[Fraction, ...]
    increment1: Fraction
    increment2: Fraction

    @property
    def all_event(self) -> Fraction:
        return self.event1 + self.event2


def _prob(value):
    # Exact arithmetic intentionally excludes floats, booleans and decimals.
    if type(value) not in (int, Fraction):
        raise ValueError("probabilities must be exact Fraction or int")
    if not 0 <= value <= 1:
        raise ValueError("probabilities must be in [0, 1]")
    return Fraction(value)


def _distribution(values, size):
    if type(values) is not tuple or len(values) != size:
        raise ValueError("probability vector has wrong tuple shape")
    result = tuple(_prob(v) for v in values)
    if sum(result) != 1:
        raise ValueError("probability vector must sum exactly to one")
    return result


def evaluate_known_policy(initial, kernels, policy) -> tuple[RiskStep, ...]:
    """Propagate the longitudinal g-formula for supplied finite-state laws.

    initial: tuple P(L_0=s), initially event free.
    kernels[t][s][a]: Transition for interval t, state s, action a.
    policy[t][s][a]: action probability at the start of that interval.
    Order: L_t -> A_t -> mutually exclusive event / (free, L_{t+1}).

    Kernels and policies are finite immutable tuples. Every row, including
    unreachable rows, must be defined. Conditional exchangeability, consistency,
    positivity, measurement and clinical applicability cannot be checked by
    this arithmetic routine. In an SCM benchmark the interventional kernels
    are stipulated; from observational data they require separate identification.

    Output includes baseline (period 0). No competing event is censored, no
    hazard is zeroed and no event-free mass is renormalized internally.
    """
    if type(initial) is not tuple or not initial:
        raise ValueError("initial must be a nonempty probability tuple")
    states = len(initial)
    free = _distribution(initial, states)
    if type(kernels) is not tuple or not kernels:
        raise ValueError("kernels must contain at least one interval")
    if type(policy) is not tuple or len(policy) != len(kernels):
        raise ValueError("policy and kernel horizons must match")
    actions = None
    # Validate the entire supplied mathematical law before evaluating it.
    for period, (layer, rule) in enumerate(zip(kernels, policy)):
        if type(layer) is not tuple or len(layer) != states:
            raise ValueError("kernel state dimension must stay fixed")
        if type(rule) is not tuple or len(rule) != states:
            raise ValueError("policy state dimension must stay fixed")
        for choices, weights in zip(layer, rule):
            if type(choices) is not tuple or not choices:
                raise ValueError("each state needs an action kernel tuple")
            if actions is None:
                actions = len(choices)
            if len(choices) != actions:
                raise ValueError("action dimension must stay fixed")
            _distribution(weights, actions)
            for row in choices:
                if type(row) is not Transition:
                    raise ValueError("each kernel row must be a Transition")
                if type(row.free) is not tuple or len(row.free) != states:
                    raise ValueError("transition has wrong next-state dimension")
                _distribution((row.event1, row.event2) + row.free, states + 2)

    zero = Fraction(0)
    event1 = event2 = zero
    result = [RiskStep(0, zero, zero, free, zero, zero)]
    for period, (layer, rule) in enumerate(zip(kernels, policy), 1):
        next_free = [zero] * states
        increment1 = increment2 = zero
        for s, mass in enumerate(free):
            for a, chance in enumerate(rule[s]):
                weighted = mass * chance
                row = layer[s][a]
                increment1 += weighted * row.event1
                increment2 += weighted * row.event2
                for j, chance_free in enumerate(row.free):
                    next_free[j] += weighted * chance_free
        event1 += increment1
        event2 += increment2
        free = tuple(next_free)
        if event1 + event2 + sum(free) != 1:
            raise ArithmeticError("probability mass was not conserved")
        result.append(RiskStep(period, event1, event2, free, increment1, increment2))
    return tuple(result)
