"""Run ONCE on your own Mac:  python gen_keys.py
- writes the PUBLIC key to update_pubkey.txt (ships with the app; every install uses it to verify updates)
- prints the PRIVATE key: save it ONLY as GitHub secret UPDATE_SIGNING_KEY, then delete it from the screen/clipboard."""
import base64
from pathlib import Path
from cryptography.hazmat.primitives import serialization as S
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
k = Ed25519PrivateKey.generate()
raw = lambda x, **kw: base64.b64encode(x).decode()
priv = k.private_bytes(S.Encoding.Raw, S.PrivateFormat.Raw, S.NoEncryption())
pub = k.public_key().public_bytes(S.Encoding.Raw, S.PublicFormat.Raw)
Path(__file__).with_name("update_pubkey.txt").write_text(raw(pub) + "\n")
print("Public key saved to update_pubkey.txt")
print("\nPRIVATE KEY (GitHub secret UPDATE_SIGNING_KEY - never share, never commit):\n" + raw(priv))
