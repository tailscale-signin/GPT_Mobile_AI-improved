#!/usr/bin/env python3
"""Validate public score submissions and recompute portable scores (no model output)."""
import json
import math
import re

PROVIDERS = {"OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OPENROUTER", "OLLAMA", "CUSTOM", "LLAMA", "FREE", "NVIDIA"}
DOMAINS = {"Math", "Logic", "Reading", "Planning", "Code reasoning", "Knowledge"}
OUTCOMES = {"PASSED", "FAILED", "ERROR", "TIMED_OUT"}
ROOT_KEYS = {"schemaVersion", "id", "kind", "plan", "suiteVersion", "startedAt", "provider", "model", "primaryProvider", "primaryModel", "reviewerProvider", "reviewerModel", "trials", "issueNumber"}
TRIAL_DEFAULTS = dict(firstTextMs=None, speed=None, inputTokens=0, outputTokens=0, estimatedTokens=False, toolCalls=0, successfulToolCalls=0, intelligence={}, reviewerScore=None, workerCalls=0, searches=0, pagesRead=0, handoffCharacters=0, primaryInputTokens=0, primaryOutputTokens=0, reviewerCalls=0, reviewerInputTokens=0, reviewerOutputTokens=0, outputCapViolations=0)
FAMILIES = ["delegation-compact", "delegation-tools", "delegation-research", "delegation-chunks", "delegation-handoff", "delegation-stopping", "delegation-ownership", "delegation-progress"]


def manifest(kind, plan):
    if kind == "DELEGATION":
        pairs = [(family, 0) for family in FAMILIES[:5]] if plan == "QUICK" else [(FAMILIES[i % 8], i // 8) for i in range(50)]
        return {(family if seed == 0 else f"{family}-seed-{seed}"): ("delegation", family) for family, seed in pairs}
    if plan == "QUICK":
        return {"speed-0": ("speed", "short"), "instruction": ("task", "instruction"), "json": ("json", "structured"), "tool": ("tools", "tools"), "intelligence-batch-0": ("intelligence", "Six domains")}
    result = {f"speed-{band}-{seed}": ("speed", band) for band in ("short", "medium", "long") for seed in range(6)}
    result.update({key: ("task", family) for key, family in [("instruction-0", "instruction"), ("logic-0", "logic"), ("context", "recall"), ("extract-0", "extraction"), ("abstain-0", "abstention"), ("instruction-1", "instruction"), ("logic-1", "logic"), ("recall-1", "recall")]})
    result.update({f"json-{i}": ("json", "structured") for i in range(6)})
    result.update({f"tool-{i}": ("tools", "tools") for i in range(12)})
    result.update({f"intelligence-batch-{i}": ("intelligence", "Six domains") for i in range(6)})
    return result


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low=0, high=100_000_000):
    return type(value) is int and low <= value <= high


def model(value):
    return isinstance(value, str) and 1 <= len(value) <= 200 and value == value.strip() and "://" not in value and not value.startswith("/") and "\\" not in value and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)


def validate(data, *, submitted=False):
    require(type(data) is dict and not data.keys() - ROOT_KEYS, "Unknown or private fields in submission.")
    data = dict(data)
    data.setdefault("schemaVersion", 1)
    require(type(data["schemaVersion"]) is int and data["schemaVersion"] == 1, "Unsupported scoreboard version.")
    require(data.get("kind") in {"MODEL", "DELEGATION"} and data.get("plan") in {"QUICK", "FULL"}, "Unsupported test plan.")
    delegation = data["kind"] == "DELEGATION"
    require(type(data.get("suiteVersion")) is int and data["suiteVersion"] == (1 if delegation else 6), "Unsupported suite version.")
    require(isinstance(data.get("id"), str) and re.fullmatch(r"[A-Za-z0-9-]{1,80}", data["id"]), "Invalid run ID.")
    require(integer(data.get("startedAt"), 0, 253402300799999), "Invalid run timestamp.")
    require(data.get("provider") in PROVIDERS and model(data.get("model")), "Only remote provider/model identities are accepted.")
    if data.get("issueNumber") is not None:
        require(not submitted and integer(data["issueNumber"], 1), "Issue provenance is assigned by the server.")
    for role in ("primary", "reviewer"):
        if delegation:
            require(data.get(role + "Provider") in PROVIDERS | {"LITERT_LM"} and model(data.get(role + "Model")), "Missing primary or reviewer model identity.")
        else:
            require(data.get(role + "Provider") is None and data.get(role + "Model") is None, "Unexpected delegation identity.")
    if delegation:
        require(data["model"].lower() != data["reviewerModel"].lower(), "Reviewer must use a different model.")
    expected = manifest(data["kind"], data["plan"])
    trials = data.get("trials")
    require(type(trials) is list and len(trials) == len(expected), "Incomplete test count.")
    require(all(type(t) is dict for t in trials), "Invalid trial.")
    require(all(type(t.get("id")) is str for t in trials), "Missing trial ID.")
    require({t["id"] for t in trials} == set(expected), "Duplicate, missing or unknown tests.")
    normalized = []
    for trial in trials:
        require(not trial.keys() - (TRIAL_DEFAULTS.keys() | {"id", "outcome", "durationMs"}), "Unknown or private trial fields.")
        t = TRIAL_DEFAULTS | trial
        require(t.get("outcome") in OUTCOMES and integer(t.get("durationMs"), 0, 3_600_000), "Unfinished or invalid trial.")
        require(t["firstTextMs"] is None or integer(t["firstTextMs"], 0, t["durationMs"]), "Invalid first-text time.")
        require(t["speed"] is None or (type(t["speed"]) in (float, int) and math.isfinite(t["speed"]) and 0 < t["speed"] <= 1_000_000), "Invalid speed.")
        for key in ("inputTokens", "outputTokens", "primaryInputTokens", "primaryOutputTokens", "reviewerInputTokens", "reviewerOutputTokens"):
            require(integer(t[key]), "Invalid token count.")
        for key in ("toolCalls", "successfulToolCalls", "workerCalls", "searches", "pagesRead", "reviewerCalls", "outputCapViolations"):
            require(integer(t[key], 0, 10_000), "Invalid activity count.")
        require(integer(t["handoffCharacters"], 0, 1_000_000) and t["successfulToolCalls"] <= t["toolCalls"], "Invalid tool or handoff count.")
        require(type(t["estimatedTokens"]) is bool, "Invalid token provenance.")
        require(t["reviewerScore"] is None or integer(t["reviewerScore"], 0, 100), "Invalid reviewer score.")
        if delegation and t["outcome"] in {"PASSED", "FAILED"}:
            require(t["reviewerScore"] is not None and t["reviewerCalls"] > 0, "Missing independent reviewer assessment.")
        answers = t["intelligence"]
        require(type(answers) is dict and all(type(v) is bool for v in answers.values()), "Invalid intelligence answers.")
        require(set(answers) == (DOMAINS if expected[t["id"]][0] == "intelligence" else set()), "Incomplete intelligence coverage.")
        normalized.append(t)
    # Omit default trial fields in the public index; the Android decoder shares these defaults.
    data["trials"] = [{key: value for key, value in t.items() if key not in TRIAL_DEFAULTS or value != TRIAL_DEFAULTS[key]} for t in normalized]
    return data


def score(data):
    data = validate(data)
    trials = [TRIAL_DEFAULTS | t for t in data["trials"]]
    expected = manifest(data["kind"], data["plan"])
    def passed(items):
        return 100 * sum(t["outcome"] == "PASSED" for t in items) / len(items)
    completion = 100 * sum(t["outcome"] in {"PASSED", "FAILED"} for t in trials) / len(trials)
    if data["kind"] == "DELEGATION":
        accuracy = passed(trials)
        calls = sum(t["toolCalls"] for t in trials)
        tools = 100 * sum(t["successfulToolCalls"] for t in trials) / calls if calls else 0
        research = passed([t for t in trials if expected[t["id"]][1] in {"delegation-research", "delegation-handoff", "delegation-stopping", "delegation-progress"}])
        reviewer = sum(t["reviewerScore"] or 0 for t in trials) / len(trials)
        return min(accuracy, .50 * accuracy + .15 * tools + .15 * research + .20 * reviewer)
    category = {name: [t for t in trials if expected[t["id"]][0] == name] for name in ("task", "json", "tools", "intelligence")}
    intelligence = 100 * sum(sum(t["intelligence"].values()) for t in category["intelligence"]) / (len(category["intelligence"]) * 6)
    return min(completion, .35 * passed(category["task"]) + .20 * passed(category["json"]) + .20 * passed(category["tools"]) + .15 * intelligence + .10 * completion)


def identity(data):
    return (data["kind"], data["plan"], data["suiteVersion"], data["provider"], data["model"])


def best(entries):
    valid = [validate(entry) for entry in entries]
    ordered = sorted(valid, key=lambda entry: (-score(entry), -entry["startedAt"], entry["id"]))
    chosen = {}
    for entry in ordered:
        chosen.setdefault(identity(entry), entry)
    return list(chosen.values())


def parse_issue(body):
    require(isinstance(body, str) and len(body.encode()) <= 60_000, "Submission exceeds the size limit.")
    match = re.search(r"(?:^|\n)```json\n([^`]+)\n```(?:\n|$)", body)
    require(match is not None and body.count("```") == 2, "Expected one JSON submission block.")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON keys.")
            result[key] = value
        return result
    try:
        data = json.loads(match.group(1), object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite number.")))
    except (ValueError, RecursionError) as error:
        raise ValueError("Invalid submission JSON.") from error
    return validate(data, submitted=True)
