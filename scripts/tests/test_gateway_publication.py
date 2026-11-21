"""Exercise recovery and public bytes through the Windows publisher's real shell entry point."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]

GH = '''#!/usr/bin/env python3
import hashlib, json, os, shutil, subprocess, sys
from pathlib import Path
root = Path(os.environ["FIXTURE"])
version = "17.0.2" if Path.cwd().name == "v17-transition" else "18.0.0"
state = root / ("remote-" + version + ".json")
listing_delay = root / ("listing-delay-" + version)
uploads = root / "uploads" / version
uploads.mkdir(exist_ok=True)
r = json.loads(state.read_text()) if state.exists() else None
args = sys.argv[1:]
if args[:2] == ["attestation", "verify"]:
    assert Path(args[2]).is_file() and Path(args[2]).suffix in (".zip", ".exe")
    assert args[args.index("--repo")+1] == "owner/repo"
    assert args[args.index("--signer-workflow")+1] == "owner/repo/.github/workflows/gateway-windows-installer.yml"
    assert args[args.index("--source-digest")+1] == args[args.index("--signer-digest")+1] == os.environ["GITHUB_SHA"]
    assert "--deny-self-hosted-runners" in args
    public_file = Path(args[2]).is_absolute()
    if public_file and os.environ.get("FIXTURE_REJECT_PUBLIC_PROVENANCE"):
        sys.exit(1)
    if Path(args[2]).suffix == ".exe" and os.environ.get("FIXTURE_UNSIGNED_INSTALLER"):
        sys.exit(1)
elif args[:3] == ["api", "--paginate", "--slurp"]:
    remaining = int(listing_delay.read_text()) if listing_delay.exists() else 0
    if remaining:
        listing_delay.write_text(str(remaining - 1))
        r = None
    print(json.dumps([[r] if r else []]))
elif args[:3] == ["api", "--method", "POST"]:
    assert r is None
    assert "tag_name=gateway-v" + version in args
    r = dict(id=7, tag_name="gateway-v" + version, target_commitish=os.environ["GITHUB_SHA"],
             draft=True, prerelease=False, assets=[], html_url="fixture")
    state.write_text(json.dumps(r))
    listing_delay.write_text(os.environ.get("FIXTURE_LIST_DELAY", "1"))
    sys.exit(1 if version == "17.0.2" else 0)  # The bridge create reply was lost.
elif args == ["api", "repos/owner/repo/releases/7"]:
    print(json.dumps(r))
elif args[:2] == ["release", "upload"]:
    name = args[3]
    assert not any(item["name"] == name for item in r["assets"])
    assert r["draft"]
    path = Path(name)
    shutil.copyfile(path, uploads / name)
    r["assets"].append(dict(name=name, state="uploaded", size=path.stat().st_size,
                            digest="sha256:"+hashlib.sha256(path.read_bytes()).hexdigest()))
    state.write_text(json.dumps(r))
    sys.exit(1)
elif args[:3] == ["api", "--method", "PATCH"]:
    assert len(r["assets"]) == 4 and r["draft"]
    r.update(draft=False, published_at="2026-11-19")
    subprocess.run(["git","tag","-a",r["tag_name"],"-m","Verified fixture",r["target_commitish"]], cwd=root,check=True)
    subprocess.run(["git","push","-q","origin",r["tag_name"]],cwd=root,check=True)
    state.write_text(json.dumps(r))
    sys.exit(1)
elif args[:2] == ["release", "download"]:
    assert not r["draft"]
    dest = Path(args[args.index("--dir")+1])
    for item in r["assets"]:
        shutil.copyfile(uploads / item["name"], dest / item["name"])
else:
    raise ValueError(args)
'''


@unittest.skipUnless(all(shutil.which(tool) for tool in ("bash", "jq", "git", "unzip", "sha256sum")), "Linux release tools required")
class WindowsPublicationTest(unittest.TestCase):
    def test_lost_replies_annotated_tag_public_download_and_immutable_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()
            git("init", "-q")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Fixture")
            (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_text('versionName = "0.9.66.8"\nversionCode = 142\n')
            v14 = root / "gateway/v14"
            v14.mkdir(parents=True)
            (v14 / "__init__.py").write_text('VERSION = "18.0.0"\n')
            git("add", "app", "gateway")
            git("commit", "-qm", "fixture")
            sha = git("rev-parse", "HEAD")
            git("init", "-q", "--bare", "origin.git")
            git("remote", "add", "origin", str(root / "origin.git"))
            (root / "scripts").mkdir()
            shutil.copyfile(ROOT / "scripts/publish_gateway_windows.sh", root / "scripts/publish_gateway_windows.sh")
            for version, payload in (("18.0.0", root / "dist/windows"), ("17.0.2", root / "dist/windows/v17-transition")):
                payload.mkdir(parents=True)
                archive = payload / f"Gateway-{version}-windows-x64.zip"
                with zipfile.ZipFile(archive, "w") as z:
                    z.writestr("release.json", json.dumps(dict(version=version, sourceCommit=sha)))
                installer = payload / f"Gateway-{version}-windows-x64-setup.exe"
                installer.write_bytes(b"fixture installer")
                (payload / "SHA256SUMS.txt").write_bytes("".join(
                    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\r\n" for path in (archive, installer)).encode())
                (payload / "smoke-results.json").write_text(json.dumps(dict(version=version, passed=["installed"])))
            (root / "uploads").mkdir()
            gh = root / "gh"
            gh.write_text(GH)
            gh.chmod(0o700)
            sleep = root / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o700)
            env = dict(os.environ, FIXTURE=str(root), GITHUB_SHA=sha, GITHUB_REF="refs/heads/main",
                       GITHUB_REPOSITORY="owner/repo", PATH=str(root)+os.pathsep+os.environ["PATH"])
            def publish():
                return subprocess.run(["bash", str(ROOT / "scripts/publish_gateway_windows.sh")],
                                      cwd=root, env=env, text=True, capture_output=True)
            env["FIXTURE_UNSIGNED_INSTALLER"] = "1"
            self.assertNotEqual(0, publish().returncode)
            self.assertFalse((root / "remote-18.0.0.json").exists())
            del env["FIXTURE_UNSIGNED_INSTALLER"]
            env["FIXTURE_LIST_DELAY"] = "100"
            delayed = publish()
            self.assertNotEqual(0, delayed.returncode)
            self.assertIn("gateway-v18.0.0", delayed.stderr)
            pending = json.loads((root / "remote-18.0.0.json").read_text())
            self.assertTrue(pending["draft"])
            self.assertEqual([], pending["assets"])
            (root / "listing-delay-18.0.0").unlink()
            del env["FIXTURE_LIST_DELAY"]
            for attempt in range(2):
                result = publish()
                self.assertEqual(0, result.returncode, (attempt, result.stderr))
            for version in ("18.0.0", "17.0.2"):
                self.assertFalse(json.loads((root / f"remote-{version}.json").read_text())["draft"])
            env["FIXTURE_REJECT_PUBLIC_PROVENANCE"] = "1"
            self.assertNotEqual(0, publish().returncode)
            del env["FIXTURE_REJECT_PUBLIC_PROVENANCE"]
            (root / "uploads/17.0.2" / installer.name).write_bytes(b"changed remote bytes")
            self.assertNotEqual(0, publish().returncode)
