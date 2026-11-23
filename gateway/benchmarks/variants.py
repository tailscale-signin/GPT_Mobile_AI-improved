"""Deterministic, behavior-calibrated defects instead of data-only repeats."""
import ast
from copy import deepcopy
from functools import lru_cache

from benchmarks.contracts import digest
from benchmarks.verified_python import evaluate, StepLimit, UnsupportedLanguage


def candidates(source):
    tree = ast.parse(source)
    for index, node in enumerate(ast.walk(tree)):
        changes = []
        if isinstance(node, ast.Compare):
            inverse = {ast.Eq: ast.NotEq, ast.NotEq: ast.Eq, ast.Lt: ast.Gt, ast.Gt: ast.Lt,
                       ast.LtE: ast.Lt, ast.GtE: ast.Gt, ast.Is: ast.IsNot, ast.IsNot: ast.Is,
                       ast.In: ast.NotIn, ast.NotIn: ast.In}
            for position, operation in enumerate(node.ops):
                if type(operation) in inverse:
                    changed = deepcopy(node)
                    changed.ops[position] = inverse[type(operation)]()
                    changes.append(changed)
                if isinstance(operation, (ast.Eq, ast.NotEq, ast.Lt, ast.Gt, ast.LtE, ast.GtE)):
                    for alternate in (ast.Eq, ast.NotEq, ast.Lt, ast.Gt, ast.LtE, ast.GtE):
                        if not isinstance(operation, alternate):
                            changed = deepcopy(node)
                            changed.ops[position] = alternate()
                            changes.append(changed)
        elif isinstance(node, ast.If):
            for condition in (ast.Constant(True), ast.Constant(False), ast.UnaryOp(ast.Not(), deepcopy(node.test))):
                changed = deepcopy(node)
                changed.test = condition
                changes.append(changed)
        elif isinstance(node, ast.BoolOp):
            changed = deepcopy(node)
            changed.op = ast.Or() if isinstance(node.op, ast.And) else ast.And()
            changes.append(changed)
        elif isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult)):
            changed = deepcopy(node)
            changed.op = ast.Sub() if isinstance(node.op, ast.Add) else ast.Add()
            changes.append(changed)
        elif isinstance(node, ast.Constant) and type(node.value) is bool:
            changes.append(ast.Constant(not node.value))
        elif isinstance(node, ast.Constant) and type(node.value) is int and abs(node.value) < 100:
            changes.append(ast.Constant(node.value + 1))
        elif isinstance(node, ast.Return) and node.value is not None:
            changes.append(ast.Return(ast.Constant(None)))
        for replacement in changes:
            copied = deepcopy(tree)
            target = list(ast.walk(copied))[index]
            class Replace(ast.NodeTransformer):
                def visit(self, value):
                    return replacement if value is target else super().visit(value)
            yield ast.unparse(ast.fix_missing_locations(Replace().visit(copied))) + '\n'


@lru_cache(maxsize=128)
def distinct_defects(family, reference, original_broken, wrapper, vectors_json, targets_json):
    import json
    vectors, targets = json.loads(vectors_json), json.loads(targets_json)
    observed, result = set(), []
    for number, source in enumerate((original_broken, *candidates(reference))):
        if number > 180 or len(result) == 8:
            break
        identity = digest(source)
        if identity in observed:
            continue
        observed.add(identity)
        try:
            actual = evaluate({'core.py': source, 'service.py': wrapper}, vectors, max_steps=20000)
            differs = any(row.get('error') or row.get('output') != target for row, target in zip(actual['outputs'], targets))
        except (StepLimit, UnsupportedLanguage, RecursionError, ValueError, TypeError):
            # A mutant that only makes the interpreter unavailable is not a
            # useful coding problem. Keep defects with actual returned failures.
            continue
        if differs:
            result.append(source)
    if len(result) < 8:
        raise ValueError('Insufficient independently calibrated defect variants for ' + family)
    return tuple(result)
