"""Fixed-protocol graph metadata example; no Torch, model, input data or fit."""
import argparse
import hashlib
from research_runtime.experiment_config import (
    GRAPH_ARMS, GRAPH_SEEDS, GRAPH_FAMILY, GRAPH_PROTOCOL_SHA256,
    export_experiment_config, load_experiment_config)

def constructed_selection(arm='prior', single_seed=None):
    seeds = list(GRAPH_SEEDS) if single_seed is None else [single_seed]
    return {
        'specification': 'multimodal_values', 'family': GRAPH_FAMILY,
        'predictionPolicy': 'raw_uncalibrated_24month_CIF',
        'predictionWindowMonths': 24, 'referencePolicy': 'SAVED_GRAPH_MEDIAN',
        'availabilityPolicy': 'RETROSPECTIVE_SNAPSHOT', 'splitPolicy': 'FINAL_MODEL_NO_SPLIT',
        'hashes': {**{name: str(i)*64 for i,name in enumerate([
            'manifestSha256', 'trainingPlanSha256', 'runtimeSourceSha256', 'softwareLockSha256'],1)},
            'graphProtocolSha256': GRAPH_PROTOCOL_SHA256},
        'graph': {'arm': arm, 'initializationSeeds': seeds,
            'aggregationPolicy': 'MEAN_3_SEED_CIF' if single_seed is None else 'SINGLE_SEED_CIF',
            'artifactSha256BySeed': {str(seed):str(GRAPH_SEEDS.index(seed)+5)*64 for seed in seeds}},
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arm', choices=GRAPH_ARMS, default='prior')
    parser.add_argument('--single-seed', type=int, choices=GRAPH_SEEDS)
    args = parser.parse_args()
    selected = constructed_selection(args.arm, args.single_seed)
    raw = export_experiment_config(selected)
    assert load_experiment_config(raw, expected_sha256=hashlib.sha256(raw).hexdigest())['selection'] == selected
    print(raw.decode('utf-8'), end='')

if __name__ == '__main__':
    main()
