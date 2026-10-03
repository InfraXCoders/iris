"""Self-update must only accept releases signed with YOUR key, and must never leave a broken install."""
import base64, hashlib, io, json, shutil, stat, zipfile
from pathlib import Path
import pytest
from cryptography.hazmat.primitives import serialization as S
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import updater

PROJECT = Path(updater.__file__).parent
SKIP = {".git", ".venv", "workspace", "backups", "dist", "__pycache__", ".pytest_cache", "tests"}


def keypair():
    k = Ed25519PrivateKey.generate()
    pub = k.public_key().public_bytes(S.Encoding.Raw, S.PublicFormat.Raw)
    return k, base64.b64encode(pub).decode()


@pytest.fixture
def install(tmp_path, monkeypatch):
    """A throwaway copy of the app at v1.0.0 that the updater operates on."""
    root = tmp_path / "app"
    shutil.copytree(PROJECT, root, ignore=lambda d, names: [n for n in names if n in SKIP or n.endswith(".db")])
    (root / "VERSION").write_text("1.0.0")
    (root / ".env").write_text("ANTHROPIC_API_KEY=user-secret\n")
    key, pub = keypair()
    (root / "update_pubkey.txt").write_text(pub + "\n")
    monkeypatch.setattr(updater, "ROOT", root)
    monkeypatch.setattr(updater, "STATE_FILE", root / ".brain_state.json")
    monkeypatch.setattr(updater, "BACKUPS", root / "backups")
    monkeypatch.setenv("ALLOW_FILE_UPDATES", "1")          # tests serve releases from disk instead of https
    monkeypatch.delenv("ALLOW_UNSIGNED_UPDATES", raising=False)
    return root, key


def release(tmp_path, version="1.1.0", key=None, mutate=None, extra=None, sign=True, bad_sha=False):
    """Builds dist/version.json (+ .sig) and dist/app.zip like make_release.py. Returns the manifest URL."""
    dist = tmp_path / f"dist_{version}_{id(mutate)}_{id(extra)}"; dist.mkdir()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for f in PROJECT.rglob("*"):
            rel = f.relative_to(PROJECT)
            if f.is_file() and not (set(rel.parts) & SKIP) and not f.name.endswith((".db", ".pyc")):
                data = f.read_bytes()
                if rel.as_posix() == "VERSION":
                    data = version.encode()
                if mutate:
                    data = mutate(rel.as_posix(), data)
                z.writestr("ai_dev_brain/" + rel.as_posix(), data)
        for name, data, attr in (extra or []):
            info = zipfile.ZipInfo(name); info.external_attr = attr
            z.writestr(info, data)
    raw_zip = buf.getvalue(); (dist / "app.zip").write_bytes(raw_zip)
    sha = "0" * 64 if bad_sha else hashlib.sha256(raw_zip).hexdigest()
    man = json.dumps({"version": version, "zip": "app.zip", "sha256": sha}).encode()
    (dist / "version.json").write_bytes(man)
    if sign and key:
        (dist / "version.json.sig").write_bytes(base64.b64encode(key.sign(man)))
    return (dist / "version.json").as_uri()


def ver(root):
    return (root / "VERSION").read_text().strip()


def test_signed_update_installs_and_keeps_user_data(install, tmp_path, monkeypatch):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key))
    assert "updated v1.0.0 -> v1.1.0" in updater.update_code(apply=True)
    assert ver(root) == "1.1.0"
    assert (root / ".env").read_text() == "ANTHROPIC_API_KEY=user-secret\n"     # never overwritten
    assert any((root / "backups").iterdir())


def test_rollback_restores_previous_version(install, tmp_path, monkeypatch):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key))
    updater.update_code(apply=True)
    assert "Rolled back to v1.0.0" in updater.rollback() and ver(root) == "1.0.0"


def test_forged_signature_rejected(install, tmp_path, monkeypatch):
    root, _ = install
    attacker, _ = keypair()
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=attacker))
    with pytest.raises(Exception):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0"


def test_unsigned_update_rejected(install, tmp_path, monkeypatch):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key, sign=False))
    with pytest.raises(Exception):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0"


def test_no_public_key_means_no_code_updates(install, tmp_path, monkeypatch):
    root, key = install
    (root / "update_pubkey.txt").write_text("")
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key))
    with pytest.raises(ValueError, match="no signing key"):
        updater.update_code(apply=True)


def test_checksum_mismatch_rejected(install, tmp_path, monkeypatch):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key, bad_sha=True))
    with pytest.raises(ValueError, match="checksum"):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0"


@pytest.mark.parametrize("name,attr,match", [
    ("ai_dev_brain/../../evil.py", 0, "unsafe path"),
    ("ai_dev_brain/payload.exe", 0, "not allowed"),
    ("ai_dev_brain/run.sh_link", (stat.S_IFLNK | 0o777) << 16, "symlink"),
])
def test_malicious_zip_contents_rejected(install, tmp_path, monkeypatch, name, attr, match):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key, extra=[(name, b"x", attr)]))
    with pytest.raises(ValueError, match=match):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0" and not (tmp_path / "evil.py").exists()


def test_untrusted_new_dependency_rejected(install, tmp_path, monkeypatch):
    root, key = install
    add_dep = lambda rel, data: data + b"\nevil-package==1.0\n" if rel == "requirements.txt" else data
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key, mutate=add_dep))
    with pytest.raises(ValueError, match="evil-package"):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0"


def test_release_that_fails_selftest_is_not_installed(install, tmp_path, monkeypatch):
    root, key = install
    breaks = lambda rel, data: b"raise SystemExit(1)\n" if rel == "selftest.py" else data
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, key=key, mutate=breaks))
    with pytest.raises(ValueError, match="selftest"):
        updater.update_code(apply=True)
    assert ver(root) == "1.0.0"


def test_plain_http_update_url_rejected(install, monkeypatch):
    monkeypatch.setenv("UPDATE_URL", "http://example.com/version.json")
    with pytest.raises(ValueError, match="https"):
        updater.update_code(apply=True)


def test_older_or_same_version_is_not_installed(install, tmp_path, monkeypatch):
    root, key = install
    monkeypatch.setenv("UPDATE_URL", release(tmp_path, version="1.0.0", key=key))
    assert "up to date" in updater.update_code(apply=True)
