"""Shared fixtures. Tests never call a real AI model and never touch the network."""
import os, sys
from pathlib import Path
import pytest

import tempfile
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
_SESSION = tempfile.mkdtemp(prefix="iris-tests-")
os.environ["IRIS_HOME"] = os.path.join(_SESSION, "home")            # tests never touch real user data
os.environ["IRIS_WORKSPACE"] = os.path.join(_SESSION, "workspace")
FAKE_KEY = "sk-ant-api03-TESTKEY-not-real-0123456789abcdef"
os.environ["ANTHROPIC_API_KEY"] = FAKE_KEY
os.environ["AUTO_UPDATE"] = "off"


@pytest.fixture(autouse=True)
def _isolate(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)            # nothing a test does lands in the project folder
    import events
    events.LOGS.clear()
    yield


class Block:
    def __init__(self, type, **kw):
        self.type = type
        self.__dict__.update(kw)


class Resp:
    def __init__(self, blocks, stop="end_turn"):
        self.content, self.stop_reason = blocks, stop
        self.usage = Block("usage", input_tokens=10, output_tokens=5)


class FakeClient:
    """Plays back scripted model replies and records every request."""
    def __init__(self, replies):
        self.replies, self.calls = list(replies), []
        self.messages = self

    def create(self, **kw):
        self.calls.append(kw)
        return self.replies.pop(0)


def text(t):
    return Resp([Block("text", text=t)])


def tool_call(name, inp, id="t1"):
    return Resp([Block("tool_use", id=id, name=name, input=inp)], stop="tool_use")
