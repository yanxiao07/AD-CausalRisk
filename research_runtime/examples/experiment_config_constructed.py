"""Artificial release metadata only; stdout export and in-memory roundtrip."""
import hashlib
from research_runtime.experiment_config import export_experiment_config, load_experiment_config

def main():
    selection = {
        'specification': 'seven', 'family': 'logistic',
        'predictionPolicy': 'raw_uncalibrated_24month_CIF',
        'predictionWindowMonths': 24,
        'referencePolicy': 'SAVED_PIPELINE_MEDIAN',
        'availabilityPolicy': 'RETROSPECTIVE_SNAPSHOT',
        'splitPolicy': 'FINAL_MODEL_NO_SPLIT', 'randomSeed': 20260926,
        'hashes': {key: str(i)*64 for i,key in enumerate([
            'manifestSha256', 'artifactSha256', 'trainingPlanSha256',
            'runtimeSourceSha256', 'softwareLockSha256'], 1)},
    }
    raw = export_experiment_config(selection)
    retained_digest = hashlib.sha256(raw).hexdigest()
    restored = load_experiment_config(raw, expected_sha256=retained_digest)
    assert restored['selection'] == selection
    print(raw.decode('utf-8'), end='')

if __name__ == '__main__':
    main()
