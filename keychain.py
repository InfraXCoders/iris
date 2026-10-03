"""API keys from the macOS Keychain, so they never sit in a plain-text file next to the code.

Order: a value already in the environment (or .env, for backward compatibility) wins,
otherwise the Keychain item  service='iris'  account=<NAME>  is used.
Store a key (asks for it hidden, never shown in the process list):
    security add-generic-password -U -s iris -a ANTHROPIC_API_KEY -w
"""
import os, platform, subprocess

SERVICE = "iris"
KEYS = ("ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "OMNIROUTE_API_KEY", "GITHUB_TOKEN", "YOUTUBE_API_KEY")


def read(name):
    if platform.system() != "Darwin":
        return None
    try:
        r = subprocess.run(["/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", name, "-w"],
                           capture_output=True, text=True, timeout=10, shell=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    v = r.stdout.strip()
    return v if r.returncode == 0 and v else None


def load_into_env():
    """Fills os.environ for keys that are not set yet. Returns the names that came from the Keychain."""
    found = []
    for k in KEYS:
        if not os.getenv(k):
            v = read(k)
            if v:
                os.environ[k] = v
                found.append(k)
    return found
