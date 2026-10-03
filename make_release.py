"""Publish a new version:  python make_release.py 2.1.0
Creates dist/iris.zip + dist/version.json (+ .sig when UPDATE_SIGNING_KEY is set). Upload them to a GitHub
Release and set UPDATE_URL to the version.json link on every Mac."""
import base64, hashlib, json, os, sys, zipfile
from pathlib import Path
ROOT, ver = Path(__file__).parent, sys.argv[1]
SKIP = {".venv", ".git", ".pytest_cache", "workspace", "backups", "dist", "__pycache__", "brain_memory.db", ".env", ".env.offline", ".brain_state.json"}
(ROOT / "VERSION").write_text(ver)
out = ROOT / "dist"; out.mkdir(exist_ok=True)
zp = out / "iris.zip"
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    for f in ROOT.rglob("*"):
        rel = f.relative_to(ROOT)
        if f.is_file() and not (set(rel.parts) & SKIP) and f.suffix != ".pyc":
            z.write(f, "iris/" + rel.as_posix())
(out / "version.json").write_text(json.dumps(
    {"version": ver, "zip": "iris.zip", "sha256": hashlib.sha256(zp.read_bytes()).hexdigest()}, indent=1))
man = out / "version.json"
if os.getenv("UPDATE_SIGNING_KEY"):               # Ed25519 signature: Macs only accept updates signed by YOUR key
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    k = Ed25519PrivateKey.from_private_bytes(base64.b64decode(os.environ["UPDATE_SIGNING_KEY"]))
    (out / "version.json.sig").write_bytes(base64.b64encode(k.sign(man.read_bytes())))
    print("signed")
else:
    print("WARNING: UPDATE_SIGNING_KEY not set - release is UNSIGNED and every Mac will refuse it")
print("Release", ver, "ready in dist/")
