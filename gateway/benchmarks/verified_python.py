"""Bounded Python contract interpreter with candidate-independent observations.

Candidate syntax is interpreted, never passed to exec/eval/compile or imported.
Only JSON values, explicit builtin operations and small module facades cross the
language boundary. Candidate code cannot inspect frames, replace the grader's
serializer, print an envelope, or obtain its globals. This is a declared Python
subset, not a claim of arbitrary Python or OS isolation; Docker remains required.
"""
import ast
import copy
import hashlib
import heapq
import json
import math
import operator
from dataclasses import dataclass

VERSION = 'restricted-python-contract-v1'
MAX_STEPS = 200000


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False)


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


class UnsupportedLanguage(ValueError):
    pass


class StepLimit(RuntimeError):
    pass


class Control(BaseException):
    def __init__(self, kind, value=None):
        self.kind, self.value = kind, value


@dataclass
class Module:
    values: dict


class Scope:
    def __init__(self, values=None, parent=None, local_names=()):
        self.values, self.parent, self.local_names = values or {}, parent, frozenset(local_names)

    def read(self, name):
        if name.startswith('__'):
            raise UnsupportedLanguage('RESERVED_NAME')
        if name in self.values:
            return self.values[name]
        if name in self.local_names:
            raise UnboundLocalError(name)
        if self.parent is not None:
            return self.parent.read(name)
        raise NameError(name)


class Function:
    def __init__(self, interpreter, node, closure):
        self.interpreter, self.node, self.closure = interpreter, node, closure
        self.defaults = [interpreter.expr(value, closure) for value in node.args.defaults]
        self.kwdefaults = [interpreter.expr(value, closure) if value is not None else None for value in node.args.kw_defaults]
        class Locals(ast.NodeVisitor):
            names = None
            def __init__(self):
                self.names = set()
            def visit_Name(self, item):
                if isinstance(item.ctx, (ast.Store, ast.Del)):
                    self.names.add(item.id)
            def visit_FunctionDef(self, item):
                self.names.add(item.name)
            def visit_Lambda(self, item):
                pass
            def visit_Import(self, item):
                self.names.update(alias.asname or alias.name.split('.')[0] for alias in item.names)
            def visit_ImportFrom(self, item):
                self.names.update(alias.asname or alias.name for alias in item.names)
            def visit_ExceptHandler(self, item):
                if item.name:
                    self.names.add(item.name)
                self.generic_visit(item)
            def visit_ListComp(self, item):
                pass
            visit_SetComp = visit_ListComp
            visit_DictComp = visit_ListComp
            visit_GeneratorExp = visit_ListComp
        assigned = Locals()
        for statement in node.body if isinstance(node, ast.FunctionDef) else []:
            assigned.visit(statement)
        self.local_names = assigned.names

    def __call__(self, *args, **kwargs):
        node, engine = self.node, self.interpreter
        positional = [*node.args.posonlyargs, *node.args.args]
        names = [arg.arg for arg in positional]
        values = {}
        if len(args) > len(names) and node.args.vararg is None:
            raise TypeError('ARGUMENT_COUNT')
        for index, name in enumerate(names):
            if index < len(args):
                if name in kwargs and index >= len(node.args.posonlyargs):
                    raise TypeError('DUPLICATE_ARGUMENT')
                values[name] = args[index]
            elif name in kwargs and index >= len(node.args.posonlyargs):
                values[name] = kwargs.pop(name)
            elif index >= len(names) - len(self.defaults):
                values[name] = self.defaults[index - (len(names) - len(self.defaults))]
            else:
                raise TypeError('MISSING_ARGUMENT')
        if node.args.vararg:
            values[node.args.vararg.arg] = tuple(args[len(names):])
        for index, arg in enumerate(node.args.kwonlyargs):
            if arg.arg in kwargs:
                values[arg.arg] = kwargs.pop(arg.arg)
            elif node.args.kw_defaults[index] is not None:
                values[arg.arg] = self.kwdefaults[index]
            else:
                raise TypeError('MISSING_ARGUMENT')
        if node.args.kwarg:
            values[node.args.kwarg.arg] = kwargs
        elif kwargs:
            raise TypeError('UNEXPECTED_ARGUMENT')
        scope = Scope(values, self.closure, self.local_names)
        if isinstance(node, ast.Lambda):
            return engine.expr(node.body, scope)
        try:
            engine.block(node.body, scope)
        except Control as signal:
            if signal.kind == 'return':
                return signal.value
            raise UnsupportedLanguage('INVALID_CONTROL')
        return None


def value_type(value):
    # The three-argument type constructor would reintroduce arbitrary classes.
    observed = type(value)
    if observed not in (dict, list, tuple, set, frozenset, str, int, float, bool, type(None)):
        raise UnsupportedLanguage('TYPE_AUTHORITY')
    return observed


BUILTINS = {name: value for name, value in {
    'len': len, 'range': range, 'enumerate': enumerate, 'zip': zip,
    'sorted': sorted, 'reversed': reversed, 'sum': sum, 'min': min, 'max': max,
    'any': any, 'all': all, 'abs': abs, 'round': round, 'pow': pow,
    'int': int, 'float': float, 'str': str, 'bool': bool, 'list': list,
    'dict': dict, 'set': set, 'tuple': tuple, 'frozenset': frozenset,
    'isinstance': isinstance, 'type': value_type, 'chr': chr, 'ord': ord,
    'next': next, 'iter': iter, 'map': map, 'filter': filter,
    'Exception': Exception, 'ValueError': ValueError, 'TypeError': TypeError,
    'KeyError': KeyError, 'IndexError': IndexError, 'RuntimeError': RuntimeError,
    'StopIteration': StopIteration, 'AssertionError': AssertionError,
}.items()}
MODULES = {
    'json': Module({'loads': json.loads, 'dumps': json.dumps, 'JSONDecodeError': json.JSONDecodeError}),
    'heapq': Module({'heappush': heapq.heappush, 'heappop': heapq.heappop,
                     'heapify': heapq.heapify, 'heappushpop': heapq.heappushpop, 'heapreplace': heapq.heapreplace}),
    'copy': Module({'copy': copy.copy, 'deepcopy': copy.deepcopy}),
    'math': Module({name: getattr(math, name) for name in ('isfinite', 'isinf', 'isnan', 'ceil', 'floor', 'sqrt', 'inf', 'nan')}),
}
METHODS = {
    dict: frozenset({'get', 'keys', 'values', 'items', 'copy', 'setdefault', 'update', 'pop', 'popitem', 'clear'}),
    list: frozenset({'append', 'extend', 'insert', 'pop', 'remove', 'clear', 'copy', 'sort', 'reverse', 'count', 'index'}),
    tuple: frozenset({'count', 'index'}),
    set: frozenset({'add', 'update', 'remove', 'discard', 'pop', 'clear', 'copy', 'union', 'intersection', 'difference',
                    'symmetric_difference', 'issubset', 'issuperset', 'isdisjoint', 'intersection_update', 'difference_update'}),
    str: frozenset({'join', 'split', 'rsplit', 'splitlines', 'strip', 'lstrip', 'rstrip', 'startswith', 'endswith',
                    'replace', 'count', 'index', 'find', 'rfind', 'lower', 'upper', 'casefold', 'isdigit',
                    'isalpha', 'isalnum', 'isspace', 'removeprefix', 'removesuffix', 'partition', 'rpartition'}),
}
BINOPS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul,
          ast.Div: operator.truediv, ast.FloorDiv: operator.floordiv, ast.Mod: operator.mod,
          ast.Pow: operator.pow, ast.BitAnd: operator.and_, ast.BitOr: operator.or_,
          ast.BitXor: operator.xor, ast.LShift: operator.lshift, ast.RShift: operator.rshift}
CMPOPS = {ast.Eq: operator.eq, ast.NotEq: operator.ne, ast.Lt: operator.lt, ast.LtE: operator.le,
          ast.Gt: operator.gt, ast.GtE: operator.ge, ast.Is: operator.is_, ast.IsNot: operator.is_not,
          ast.In: lambda a, b: a in b, ast.NotIn: lambda a, b: a not in b}


class Interpreter:
    def __init__(self, sources, max_steps=MAX_STEPS):
        self.sources, self.modules, self.steps, self.max_steps = sources, {}, 0, max_steps
        self.trees = {}
        for path, source in sources.items():
            if '/' in path or not path.endswith('.py') or path.startswith('__'):
                raise UnsupportedLanguage('MODULE_PATH')
            tree = ast.parse(source)
            for node in ast.walk(tree):
                if isinstance(node, (ast.ClassDef, ast.AsyncFunctionDef, ast.Await, ast.AsyncFor, ast.AsyncWith,
                                     ast.With, ast.Global, ast.Nonlocal, ast.Yield, ast.YieldFrom, ast.NamedExpr)):
                    raise UnsupportedLanguage('UNSUPPORTED_SYNTAX')
                if isinstance(node, ast.Name) and node.id.startswith('__'):
                    raise UnsupportedLanguage('RESERVED_NAME')
                if isinstance(node, ast.Attribute) and (node.attr.startswith('_') or not isinstance(node.ctx, ast.Load)):
                    raise UnsupportedLanguage('ATTRIBUTE_AUTHORITY')
                if isinstance(node, ast.FunctionDef) and node.decorator_list:
                    raise UnsupportedLanguage('DECORATOR_AUTHORITY')
                if isinstance(node, ast.ImportFrom) and (node.level or any(name.name == '*' for name in node.names)):
                    raise UnsupportedLanguage('IMPORT_AUTHORITY')
            self.trees[path[:-3]] = tree

    def tick(self):
        self.steps += 1
        if self.steps > self.max_steps:
            raise StepLimit('INTERPRETER_STEP_LIMIT')

    def module(self, name):
        if name in MODULES:
            return MODULES[name]
        if name in self.modules:
            return self.modules[name]
        if name not in self.trees:
            raise UnsupportedLanguage('IMPORT_AUTHORITY')
        scope = Scope(parent=Scope(dict(BUILTINS)))
        module = Module(scope.values)
        self.modules[name] = module
        self.block(self.trees[name].body, scope)
        return module

    def attr(self, value, name):
        if name.startswith('_'):
            raise UnsupportedLanguage('ATTRIBUTE_AUTHORITY')
        if isinstance(value, Module):
            if name not in value.values:
                raise UnsupportedLanguage('MODULE_MEMBER')
            return value.values[name]
        allowed = METHODS.get(type(value), ())
        if name not in allowed:
            raise UnsupportedLanguage('ATTRIBUTE_AUTHORITY')
        return getattr(value, name)

    def assign(self, node, value, scope):
        self.tick()
        if isinstance(node, ast.Name):
            if node.id.startswith('__'):
                raise UnsupportedLanguage('RESERVED_NAME')
            scope.values[node.id] = value
        elif isinstance(node, (ast.Tuple, ast.List)):
            values = list(value)
            stars = [i for i, target in enumerate(node.elts) if isinstance(target, ast.Starred)]
            if stars:
                index = stars[0]
                tail = len(node.elts) - index - 1
                if len(stars) != 1 or len(values) < len(node.elts) - 1:
                    raise ValueError('UNPACK_ARGUMENTS')
                values = values[:index] + [values[index:len(values) - tail if tail else None]] + (values[-tail:] if tail else [])
            if len(values) != len(node.elts):
                raise ValueError('UNPACK_ARGUMENTS')
            for target, item in zip(node.elts, values):
                self.assign(target.value if isinstance(target, ast.Starred) else target, item, scope)
        elif isinstance(node, ast.Subscript):
            target, key = self.expr(node.value, scope), self.expr(node.slice, scope)
            if type(target) not in (list, dict):
                raise UnsupportedLanguage('ASSIGNMENT_AUTHORITY')
            target[key] = value
        else:
            raise UnsupportedLanguage('ASSIGNMENT_AUTHORITY')

    def comprehension(self, generators, scope, emit, index=0):
        if index == len(generators):
            yield emit(scope)
            return
        generator = generators[index]
        if generator.is_async:
            raise UnsupportedLanguage('ASYNC_COMPREHENSION')
        for value in self.expr(generator.iter, scope):
            self.tick()
            self.assign(generator.target, value, scope)
            if all(self.expr(condition, scope) for condition in generator.ifs):
                yield from self.comprehension(generators, scope, emit, index + 1)

    def expr(self, node, scope):
        self.tick()
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.Name):
            return scope.read(node.id)
        if isinstance(node, (ast.List, ast.Tuple, ast.Set)):
            values = []
            for item in node.elts:
                values.extend(self.expr(item.value, scope)) if isinstance(item, ast.Starred) else values.append(self.expr(item, scope))
            return tuple(values) if isinstance(node, ast.Tuple) else set(values) if isinstance(node, ast.Set) else values
        if isinstance(node, ast.Dict):
            result = {}
            for key, value in zip(node.keys, node.values):
                if key is None:
                    result.update(self.expr(value, scope))
                else:
                    result[self.expr(key, scope)] = self.expr(value, scope)
            return result
        if isinstance(node, ast.Attribute):
            return self.attr(self.expr(node.value, scope), node.attr)
        if isinstance(node, ast.Slice):
            return slice(*(self.expr(item, scope) if item is not None else None for item in (node.lower, node.upper, node.step)))
        if isinstance(node, ast.Subscript):
            value = self.expr(node.value, scope)
            if type(value) not in (dict, list, tuple, str, range):
                raise UnsupportedLanguage('SUBSCRIPT_AUTHORITY')
            return value[self.expr(node.slice, scope)]
        if isinstance(node, ast.BinOp):
            operation = BINOPS.get(type(node.op))
            if operation is None:
                raise UnsupportedLanguage('OPERATOR')
            return operation(self.expr(node.left, scope), self.expr(node.right, scope))
        if isinstance(node, ast.UnaryOp):
            operation = {ast.Not: operator.not_, ast.USub: operator.neg, ast.UAdd: operator.pos, ast.Invert: operator.invert}.get(type(node.op))
            if operation is None:
                raise UnsupportedLanguage('OPERATOR')
            return operation(self.expr(node.operand, scope))
        if isinstance(node, ast.BoolOp):
            value = None
            for item in node.values:
                value = self.expr(item, scope)
                if isinstance(node.op, ast.And) and not value or isinstance(node.op, ast.Or) and value:
                    break
            return value
        if isinstance(node, ast.Compare):
            left = self.expr(node.left, scope)
            for op, item in zip(node.ops, node.comparators):
                right = self.expr(item, scope)
                if type(op) not in CMPOPS or not CMPOPS[type(op)](left, right):
                    return False
                left = right
            return True
        if isinstance(node, ast.IfExp):
            return self.expr(node.body if self.expr(node.test, scope) else node.orelse, scope)
        if isinstance(node, ast.NamedExpr):
            value = self.expr(node.value, scope)
            self.assign(node.target, value, scope)
            return value
        if isinstance(node, ast.Lambda):
            return Function(self, node, scope)
        if isinstance(node, ast.Call):
            function = self.expr(node.func, scope)
            if not callable(function) or isinstance(function, (Interpreter, Scope, Module)):
                raise UnsupportedLanguage('CALL_AUTHORITY')
            args, kwargs = [], {}
            for item in node.args:
                args.extend(self.expr(item.value, scope)) if isinstance(item, ast.Starred) else args.append(self.expr(item, scope))
            for item in node.keywords:
                value = self.expr(item.value, scope)
                incoming = {item.arg: value} if item.arg is not None else value
                if set(kwargs) & set(incoming):
                    raise TypeError('DUPLICATE_ARGUMENT')
                kwargs.update(incoming)
            return function(*args, **kwargs)
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.GeneratorExp, ast.DictComp)):
            inner = Scope(parent=scope)
            emit = (lambda current: (self.expr(node.key, current), self.expr(node.value, current))) if isinstance(node, ast.DictComp) else (lambda current: self.expr(node.elt, current))
            values = self.comprehension(node.generators, inner, emit)
            return dict(values) if isinstance(node, ast.DictComp) else set(values) if isinstance(node, ast.SetComp) else values if isinstance(node, ast.GeneratorExp) else list(values)
        if isinstance(node, ast.JoinedStr):
            result = []
            for item in node.values:
                if isinstance(item, ast.Constant):
                    result.append(item.value)
                elif isinstance(item, ast.FormattedValue):
                    value = self.expr(item.value, scope)
                    value = repr(value) if item.conversion == 114 else str(value) if item.conversion == 115 else ascii(value) if item.conversion == 97 else value
                    result.append(format(value, self.expr(item.format_spec, scope) if item.format_spec else ''))
            return ''.join(result)
        raise UnsupportedLanguage('EXPRESSION_' + type(node).__name__)

    def block(self, statements, scope):
        for node in statements:
            self.tick()
            if isinstance(node, ast.FunctionDef):
                scope.values[node.name] = Function(self, node, scope)
            elif isinstance(node, ast.Assign):
                value = self.expr(node.value, scope)
                for target in node.targets:
                    self.assign(target, value, scope)
            elif isinstance(node, ast.AnnAssign):
                if node.value is not None:
                    self.assign(node.target, self.expr(node.value, scope), scope)
            elif isinstance(node, ast.AugAssign):
                target, key = None, None
                if isinstance(node.target, ast.Name):
                    value = scope.read(node.target.id)
                elif isinstance(node.target, ast.Subscript):
                    target, key = self.expr(node.target.value, scope), self.expr(node.target.slice, scope)
                    if type(target) not in (list, dict):
                        raise UnsupportedLanguage('ASSIGNMENT_AUTHORITY')
                    value = target[key]
                else:
                    raise UnsupportedLanguage('ASSIGNMENT_AUTHORITY')
                operation = {ast.Add: operator.iadd, ast.Sub: operator.isub, ast.Mult: operator.imul,
                             ast.BitOr: operator.ior, ast.BitAnd: operator.iand, ast.BitXor: operator.ixor,
                             ast.Div: operator.itruediv, ast.FloorDiv: operator.ifloordiv,
                             ast.Mod: operator.imod, ast.Pow: operator.ipow,
                             ast.LShift: operator.ilshift, ast.RShift: operator.irshift}.get(type(node.op))
                if operation is None:
                    raise UnsupportedLanguage('OPERATOR')
                updated = operation(value, self.expr(node.value, scope))
                if target is not None:
                    target[key] = updated
                else:
                    self.assign(node.target, updated, scope)
            elif isinstance(node, ast.Expr):
                self.expr(node.value, scope)
            elif isinstance(node, ast.Return):
                raise Control('return', self.expr(node.value, scope) if node.value is not None else None)
            elif isinstance(node, ast.If):
                self.block(node.body if self.expr(node.test, scope) else node.orelse, scope)
            elif isinstance(node, (ast.For, ast.While)):
                iterator = iter(self.expr(node.iter, scope)) if isinstance(node, ast.For) else None
                broke = False
                while True:
                    self.tick()
                    if iterator is not None:
                        try:
                            value = next(iterator)
                        except StopIteration:
                            break
                        self.assign(node.target, value, scope)
                    elif not self.expr(node.test, scope):
                        break
                    try:
                        self.block(node.body, scope)
                    except Control as signal:
                        if signal.kind == 'break':
                            broke = True
                            break
                        if signal.kind != 'continue':
                            raise
                if not broke:
                    self.block(node.orelse, scope)
            elif isinstance(node, (ast.Break, ast.Continue)):
                raise Control('break' if isinstance(node, ast.Break) else 'continue')
            elif isinstance(node, (ast.Import, ast.ImportFrom)):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        scope.values[alias.asname or alias.name] = self.module(alias.name)
                else:
                    module = self.module(node.module)
                    for alias in node.names:
                        scope.values[alias.asname or alias.name] = self.attr(module, alias.name)
            elif isinstance(node, ast.Try):
                try:
                    self.block(node.body, scope)
                except (UnsupportedLanguage, StepLimit):
                    raise
                except Exception as error:
                    for handler in node.handlers:
                        expected = self.expr(handler.type, scope) if handler.type else Exception
                        if isinstance(error, expected):
                            if handler.name:
                                scope.values[handler.name] = error
                            try:
                                self.block(handler.body, scope)
                            finally:
                                if handler.name:
                                    scope.values.pop(handler.name, None)
                            break
                    else:
                        raise
                else:
                    self.block(node.orelse, scope)
                finally:
                    self.block(node.finalbody, scope)
            elif isinstance(node, ast.Raise):
                if node.exc is None:
                    raise UnsupportedLanguage('BARE_RAISE')
                raise self.expr(node.exc, scope)
            elif isinstance(node, ast.Assert):
                if not self.expr(node.test, scope):
                    raise AssertionError('CANDIDATE_ASSERTION')
            elif isinstance(node, ast.Delete):
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        del scope.values[target.id]
                    elif isinstance(target, ast.Subscript):
                        value = self.expr(target.value, scope)
                        if type(value) not in (dict, list):
                            raise UnsupportedLanguage('DELETE_AUTHORITY')
                        del value[self.expr(target.slice, scope)]
                    else:
                        raise UnsupportedLanguage('DELETE_AUTHORITY')
            elif not isinstance(node, ast.Pass):
                raise UnsupportedLanguage('STATEMENT_' + type(node).__name__)


def evaluate(sources, inputs, *, max_steps=MAX_STEPS):
    """Receipts are produced by the interpreter, never a candidate return value."""
    original = copy.deepcopy(inputs)
    engine = Interpreter(sources, max_steps=max_steps)
    handle = engine.attr(engine.module('service'), 'handle')
    outputs = []
    for request in copy.deepcopy(inputs):
        before = canonical(request)
        try:
            result = handle(request)
            row = {'output': json.loads(canonical(result))}
        except (UnsupportedLanguage, StepLimit):
            raise
        except Exception as error:
            row = {'error': type(error).__name__}
        try:
            row['input_preserved'] = canonical(request) == before
        except (TypeError, ValueError, RecursionError):
            row['input_preserved'] = False
        outputs.append(row)
    return {'outputs': outputs, 'verification': {
        'policy': VERSION, 'authority': 'INTERPRETER_OWNED', 'sourceDigest': fingerprint(sources),
        'inputDigest': fingerprint(original), 'outputDigest': fingerprint(outputs),
        'observedInputs': len(inputs), 'preservedInputs': sum(row['input_preserved'] is True for row in outputs),
        'steps': engine.steps, 'languageScope': 'BOUNDED_PYTHON_SUBSET',
    }}
