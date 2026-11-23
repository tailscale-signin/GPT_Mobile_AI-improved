"""Content identities for the executable task, hidden oracle and grader."""
from functools import lru_cache
from pathlib import Path

from benchmarks.contracts import digest

SCHEMA = 'gateway.coding-task.v2'
PROPOSAL_SCHEMA = {
    'type': 'object', 'additionalProperties': False,
    'required': ['changes', 'diagnosis', 'tests', 'evidence'],
    'properties': {
        'changes': {'type': 'array', 'minItems': 1, 'maxItems': 20, 'items': {'type': 'object'}},
        'diagnosis': {'type': 'object', 'additionalProperties': {'type': 'string'}},
        'tests': {'type': 'array', 'maxItems': 6, 'items': {'type': 'object', 'required': ['input', 'expected']}},
        'evidence': {'type': 'object', 'required': ['tests_executed', 'changed_files']},
    },
}


@lru_cache(maxsize=1)
def grader_digest():
    root = Path(__file__).resolve().parent
    names = ('coding.py', 'contracts.py', 'fixtures.py', 'capstones.py', 'sandbox.py',
             'verified_python.py', 'grader_manifest.py', 'calibration.py', 'variants.py', 'external.py', 'live_github.py',
             'runner.py', 'settings.py', 'service.py', 'store.py', 'phone_provision.py', 'phone_ci.py')
    sources = {'benchmarks/' + name: (root / name).read_text(encoding='utf-8') for name in names}
    # Patch interpretation and bounded execution are part of the grader contract,
    # even though the application also uses them outside benchmarks.
    for name in ('engineering_backend.py', 'engineering_workspace.py', 'engineering_validation.py', 'validation_host.py'):
        sources['v14/' + name] = (root.parent / 'v14' / name).read_text(encoding='utf-8')
    return digest(sources)


def seal(task, expected):
    manifest = {
        'schema': SCHEMA, 'problemId': task['problemId'], 'family': task['family'],
        'seed': task['seed'], 'requirements': task['requirements'], 'sources': task['sources'],
        'oracleSources': task['referenceSources'], 'hiddenVectors': task['vectors'],
        'hiddenExpected': expected, 'graderDigest': grader_digest(), 'proposalSchema': PROPOSAL_SCHEMA,
        'language': 'BOUNDED_PYTHON_SUBSET', 'inputContract': 'IN_PROCESS_ARGUMENT_PRESERVATION',
    }
    task.update(manifestDigest=digest(manifest), taskDigest=digest(manifest),
                graderDigest=manifest['graderDigest'], oracleDigest=digest(task['referenceSources']),
                hiddenTestsDigest=digest([task['vectors'], expected]),
                proposalSchemaDigest=digest(PROPOSAL_SCHEMA), contractVersion=SCHEMA)
    return task
