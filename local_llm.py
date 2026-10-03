"""AirLLM backend: runs big open models on small GPUs by streaming layers from disk (slow but offline)."""
import os, threading

_model, _lock = None, threading.Lock()
MODEL = os.getenv("AIRLLM_MODEL", "Qwen/Qwen2.5-Coder-32B-Instruct")
CTX = int(os.getenv("AIRLLM_CTX", "4096"))
NEW = int(os.getenv("AIRLLM_MAX_NEW", "1500"))


def _load():
    global _model
    if _model is None:
        from airllm import AutoModel
        kw = {}
        if os.getenv("AIRLLM_COMPRESSION"):          # "4bit" or "8bit": faster loading
            kw["compression"] = os.environ["AIRLLM_COMPRESSION"]
        print(f"[AirLLM] loading {MODEL} (first run downloads the weights)...", flush=True)
        _model = AutoModel.from_pretrained(MODEL, **kw)
        _model.tokenizer.truncation_side = "left"     # keep the end of the prompt
    return _model


def _prompt(tok, messages):
    try:
        return tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    except Exception:
        text = "".join(f"<|{m['role']}|>\n{m['content']}\n" for m in messages)
        return text + "<|assistant|>\n"


def chat(messages):
    """messages: [{'role': 'system'|'user'|'assistant', 'content': str}] -> reply text."""
    with _lock:                                       # one model, layers streamed from disk: no parallel calls
        m = _load()
        import torch
        ids = m.tokenizer(_prompt(m.tokenizer, messages), return_tensors="pt", return_attention_mask=False,
                          truncation=True, max_length=CTX, padding=False)["input_ids"]
        if torch.cuda.is_available():
            ids = ids.cuda()
        out = m.generate(ids, max_new_tokens=NEW, use_cache=True, return_dict_in_generate=True)
        return m.tokenizer.decode(out.sequences[0][ids.shape[1]:], skip_special_tokens=True).strip()
