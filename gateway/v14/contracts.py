"""Bounded, dialect-aware validation for catalog-admitted MCP tool arguments."""
from __future__ import annotations

import math
import re


class SchemaValidationError(ValueError):
    """A schema or value is outside the deliberately small supported dialect."""


_TYPES = {"object", "array", "string", "number", "integer", "boolean", "null"}
_SCHEMA_KEYS = {
    "type", "properties", "required", "additionalProperties", "items", "enum",
    "minLength", "maxLength", "pattern", "minimum", "maximum", "minItems",
    "maxItems", "uniqueItems",
}


def validate_schema(schema: object, *, depth: int = 0) -> None:
    """Reject unsupported or unbounded schemas before they enter the tool catalog."""
    if depth > 12 or not isinstance(schema, dict):
        raise SchemaValidationError("Tool schema must be a bounded object")
    if set(schema) - _SCHEMA_KEYS:
        raise SchemaValidationError("Tool schema uses unsupported keywords")
    kind = schema.get("type", "object")
    if kind not in _TYPES:
        raise SchemaValidationError("Tool schema declares an unsupported type")
    enum = schema.get("enum")
    if enum is not None and (not isinstance(enum, list) or not 1 <= len(enum) <= 100):
        raise SchemaValidationError("Tool enum must contain between 1 and 100 values")
    if kind == "object":
        properties = schema.get("properties", {})
        required = schema.get("required", [])
        if not isinstance(properties, dict) or len(properties) > 100:
            raise SchemaValidationError("Tool object properties exceed the limit")
        if not all(isinstance(key, str) and 1 <= len(key) <= 128 for key in properties):
            raise SchemaValidationError("Tool property name is invalid")
        if not isinstance(required, list) or len(required) > 100 or not all(isinstance(key, str) for key in required):
            raise SchemaValidationError("Tool required list is invalid")
        if not set(required) <= properties.keys():
            raise SchemaValidationError("Required tool property is not declared")
        additional = schema.get("additionalProperties", False)
        if not isinstance(additional, bool):
            raise SchemaValidationError("Schema-valued additionalProperties is unsupported")
        for child in properties.values():
            validate_schema(child, depth=depth + 1)
    elif kind == "array":
        if "items" not in schema:
            raise SchemaValidationError("Tool arrays require an item schema")
        validate_schema(schema["items"], depth=depth + 1)
        _bounded_integer(schema, "minItems", 0, 10_000)
        _bounded_integer(schema, "maxItems", 0, 10_000)
        if schema.get("uniqueItems", False) not in (True, False):
            raise SchemaValidationError("uniqueItems must be boolean")
    elif kind == "string":
        _bounded_integer(schema, "minLength", 0, 100_000)
        _bounded_integer(schema, "maxLength", 0, 100_000)
        pattern = schema.get("pattern")
        if pattern is not None:
            if not isinstance(pattern, str) or len(pattern) > 256:
                raise SchemaValidationError("Tool string pattern exceeds the limit")
            # Python's backtracking regex engine has no per-match timeout. Keep the
            # accepted subset deliberately small: literals, anchors, character
            # classes and simple quantifiers. Grouping/lookarounds/backrefs are out.
            if re.search(r"[()|\\]|\{\d", pattern) or re.search(r"(?:\*|\+|\?){2}", pattern):
                raise SchemaValidationError("Tool string pattern exceeds the safe subset")
            try:
                re.compile(pattern)
            except re.error as exc:
                raise SchemaValidationError("Tool string pattern is invalid") from exc
    elif kind in {"integer", "number"}:
        for key in ("minimum", "maximum"):
            value = schema.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)):
                raise SchemaValidationError("Tool numeric bound is invalid")
    for lower, upper in (("minLength", "maxLength"), ("minItems", "maxItems"), ("minimum", "maximum")):
        if lower in schema and upper in schema and schema[lower] > schema[upper]:
            raise SchemaValidationError("Tool schema has an inverted range")


def validate_arguments(schema: dict, arguments: object, *, max_nodes: int = 2_000) -> None:
    """Validate a JSON value against the supported schema subset without dispatch."""
    validate_schema(schema)
    if not isinstance(arguments, dict):
        raise SchemaValidationError("Tool arguments must be a JSON object")
    budget = [max_nodes]

    def visit(spec: dict, value: object, path: str, depth: int) -> None:
        budget[0] -= 1
        if budget[0] < 0 or depth > 12:
            raise SchemaValidationError("Tool arguments exceed the validation limit")
        kind = spec.get("type", "object")
        valid = {
            "object": lambda item: isinstance(item, dict),
            "array": lambda item: isinstance(item, list),
            "string": lambda item: isinstance(item, str),
            "number": lambda item: isinstance(item, (int, float)) and not isinstance(item, bool) and math.isfinite(item),
            "integer": lambda item: isinstance(item, int) and not isinstance(item, bool),
            "boolean": lambda item: isinstance(item, bool),
            "null": lambda item: item is None,
        }[kind](value)
        if not valid:
            raise SchemaValidationError(f"{path} has the wrong type")
        if "enum" in spec and value not in spec["enum"]:
            raise SchemaValidationError(f"{path} is not an allowed value")
        if kind == "object":
            props = spec.get("properties", {})
            required = spec.get("required", [])
            missing = [name for name in required if name not in value]
            if missing:
                raise SchemaValidationError(f"{path} is missing a required value")
            if spec.get("additionalProperties", False) is False and set(value) - props.keys():
                raise SchemaValidationError(f"{path} contains an undeclared value")
            for name, child in value.items():
                if name in props:
                    visit(props[name], child, f"{path}.{name}", depth + 1)
                else:
                    # Even schema-permitted extra values consume the same bounded
                    # traversal budget; otherwise additionalProperties=true could
                    # smuggle an arbitrarily deep/large JSON tree past admission.
                    visit_untyped(child, f"{path}.{name}", depth + 1)
        elif kind == "array":
            if len(value) < spec.get("minItems", 0) or len(value) > spec.get("maxItems", 10_000):
                raise SchemaValidationError(f"{path} has an invalid number of items")
            if spec.get("uniqueItems") and len({repr(item) for item in value}) != len(value):
                raise SchemaValidationError(f"{path} contains duplicate items")
            for index, item in enumerate(value):
                visit(spec["items"], item, f"{path}[{index}]", depth + 1)
        elif kind == "string":
            if len(value) < spec.get("minLength", 0) or len(value) > spec.get("maxLength", 100_000):
                raise SchemaValidationError(f"{path} has an invalid length")
            if "pattern" in spec and re.search(spec["pattern"], value) is None:
                raise SchemaValidationError(f"{path} does not match the allowed format")
        elif kind in {"integer", "number"}:
            if "minimum" in spec and value < spec["minimum"] or "maximum" in spec and value > spec["maximum"]:
                raise SchemaValidationError(f"{path} is outside the allowed range")

    def visit_untyped(value: object, path: str, depth: int) -> None:
        budget[0] -= 1
        if budget[0] < 0 or depth > 12:
            raise SchemaValidationError("Tool arguments exceed the validation limit")
        if isinstance(value, dict):
            if len(value) > 100:
                raise SchemaValidationError(f"{path} contains too many properties")
            for key, child in value.items():
                if not isinstance(key, str) or len(key) > 128:
                    raise SchemaValidationError(f"{path} contains an invalid property name")
                visit_untyped(child, f"{path}.{key}", depth + 1)
        elif isinstance(value, list):
            if len(value) > 10_000:
                raise SchemaValidationError(f"{path} contains too many items")
            for index, child in enumerate(value):
                visit_untyped(child, f"{path}[{index}]", depth + 1)
        elif isinstance(value, str) and len(value) > 100_000:
            raise SchemaValidationError(f"{path} exceeds the string limit")

    visit(schema, arguments, "$", 0)


def _bounded_integer(schema: dict, key: str, lower: int, upper: int) -> None:
    value = schema.get(key)
    if value is not None and (isinstance(value, bool) or not isinstance(value, int) or not lower <= value <= upper):
        raise SchemaValidationError(f"Tool schema {key} is invalid")
