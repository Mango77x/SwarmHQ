"""Unit tests for boids.py's pure flocking math. No MQTT/Drone involved,
just the position-in/position-out function - the one piece of the
simulator that's pure enough to unit test cheaply, held to the same
level of rigor as the backend's own tests."""

import math

from boids import BoidsParams, compute_step


def _params(**overrides):
    defaults = dict(
        neighbor_radius_degrees=1.0,
        separation_weight=1.0,
        alignment_weight=1.0,
        cohesion_weight=1.0,
        center_weight=0.0,
        max_step_degrees=1.0,
        center=(0.0, 0.0),
    )
    defaults.update(overrides)
    return BoidsParams(**defaults)


def test_a_lone_drone_with_no_neighbors_drifts_only_towards_the_center():
    params = _params(center_weight=0.5, center=(1.0, 0.0), max_step_degrees=10.0)
    positions = {"drone-1": (0.0, 0.0)}

    result = compute_step(positions, {}, params)

    lat, lon = result["drone-1"]
    assert lat > 0.0
    assert math.isclose(lon, 0.0, abs_tol=1e-9)


def test_two_drones_too_close_move_apart():
    params = _params(alignment_weight=0.0, cohesion_weight=0.0, center_weight=0.0, max_step_degrees=10.0)
    positions = {"a": (0.0, 0.0), "b": (0.001, 0.0)}

    result = compute_step(positions, {}, params)

    assert result["a"][0] < 0.0  # steers south, away from b
    assert result["b"][0] > 0.001  # steers north, away from a


def test_two_distant_drones_with_cohesion_move_towards_each_other():
    params = _params(separation_weight=0.0, alignment_weight=0.0, center_weight=0.0,
                      neighbor_radius_degrees=10.0, max_step_degrees=10.0)
    positions = {"a": (0.0, 0.0), "b": (1.0, 0.0)}

    result = compute_step(positions, {}, params)

    assert result["a"][0] > 0.0  # steers north, towards b
    assert result["b"][0] < 1.0  # steers south, towards a


def test_drones_outside_the_neighbor_radius_dont_affect_each_other():
    params = _params(neighbor_radius_degrees=0.5, separation_weight=0.0, alignment_weight=0.0,
                      cohesion_weight=1.0, center_weight=0.0)
    positions = {"a": (0.0, 0.0), "b": (10.0, 0.0)}

    result = compute_step(positions, {}, params)

    assert result["a"] == (0.0, 0.0)
    assert result["b"] == (10.0, 0.0)


def test_step_size_is_clamped_to_max_step_degrees():
    params = _params(center_weight=100.0, center=(50.0, 0.0), max_step_degrees=0.01)
    positions = {"drone-1": (0.0, 0.0)}

    result = compute_step(positions, {}, params)

    lat, lon = result["drone-1"]
    step = math.hypot(lat - 0.0, lon - 0.0)
    assert step <= 0.01 + 1e-9
