#!/usr/bin/env python3
"""
Minimal Ollama-compatible server for ExactLearner-LLM.
Request that OllamaBridge.ask() sends:

    POST /api/generate
    {"model": "...", "system": "...", "options": {"num_predict": 2},
     "stream": false, "prompt": "..."}

Response it expects:

    {"response":"True"}

Usage
-----
    python3 llm_server.py --model /path/to/checkpoint

"""

import argparse
import faulthandler
import json
import os
import signal
import subprocess
import sys
import threading
import time

os.environ.setdefault("VLLM_WORKER_MULTIPROC_METHOD", "spawn")
os.environ.setdefault("NCCL_P2P_DISABLE", "1")

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from transformers import AutoTokenizer

# torch is deliberately NOT imported here. Nothing in this process needs it --
# vLLM imports it inside its own workers -- and keeping it out guarantees the
# parent never touches a CUDA API before those workers are spawned.

_lock = threading.Lock()
_llm = None
_tokenizer = None
_device = None
_request_count = 0
_truncated_count = 0
_at_cap_count = 0
_unparsed_count = 0
_token_total = 0
_gen_seconds = 0.0
_max_new_tokens = 512
_trace_file = None
_started_at = time.time()
_ready = False          # False until the warmup query has returned
_phase = "starting"
_phase_since = time.time()
_status_file = None
_status_lock = threading.Lock()
_last_error = None


def fmt_elapsed(seconds: float) -> str:
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def gpu_snapshot():
    """
    Per-GPU memory and utilization, via nvidia-smi.

    Deliberately NOT torch.cuda: reading it here would create a CUDA context in
    this parent process, and vLLM's workers must be spawned from a process that
    has none (see the VLLM_WORKER_MULTIPROC_METHOD note at the top). A
    subprocess keeps the parent clean.
    """
    try:
        out = subprocess.run(
            ["nvidia-smi",
             "--query-gpu=index,memory.used,memory.total,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=10, check=True).stdout
    except Exception:  # noqa: BLE001 - no nvidia-smi, no GPU column
        return None
    gpus = []
    for line in out.strip().splitlines():
        try:
            idx, used, total, util = (f.strip() for f in line.split(","))
            gpus.append({"gpu": int(idx), "mem_used_mib": int(used),
                         "mem_total_mib": int(total), "util_pct": int(util)})
        except ValueError:
            continue
    return gpus or None


def status_payload() -> dict:
    now = time.time()
    return {
        "pid": os.getpid(),
        "ready": _ready,
        "phase": _phase,
        "phase_seconds": round(now - _phase_since, 1),
        "uptime_s": round(now - _started_at, 1),
        "requests": _request_count,
        "truncated": _truncated_count,
        "at_cap": _at_cap_count,
        "unparsed": _unparsed_count,
        # Generation-time rate, not wall-clock.
        "tokens_per_s": round(_token_total / max(_gen_seconds, 1e-6), 2),
        "device": str(_device),
        "last_error": _last_error,
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "gpus": gpu_snapshot(),
    }


def write_status() -> None:
    """
    Publishes state to a JSON file on the shared filesystem.

    The HTTP endpoints only answer from inside the job -- a login shell cannot
    reach the compute node's 127.0.0.1. A file both can see is the only way to
    check on a running job without an srun --overlap into it.

    Written via a temp file + rename so a reader never catches a half-written
    document.
    """
    if not _status_file:
        return
    with _status_lock:
        try:
            tmp = f"{_status_file}.tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(status_payload(), fh, indent=2)
            os.replace(tmp, _status_file)
        except OSError as exc:
            print(f"WARNING: could not write status file: {exc}",
                  file=sys.stderr, flush=True)


def set_phase(name: str) -> None:
    global _phase, _phase_since
    _phase = name
    _phase_since = time.time()
    print(f"[t+{fmt_elapsed(time.time() - _started_at)}] phase: {name}",
          flush=True)
    write_status()


def heartbeat_loop(interval: int) -> None:
    """
    One line per interval for as long as the process lives: the cheapest
    possible proof that a long startup, or a long run, is still alive.
    """
    while True:
        time.sleep(interval)
        gpus = gpu_snapshot() or []
        gpu_str = "".join(
            f"  g{g['gpu']}:{g['mem_used_mib'] // 1024}G/{g['util_pct']}%"
            for g in gpus)
        if _ready:
            state = (f"serving  requests={_request_count} "
                     f"{_token_total / max(_gen_seconds, 1e-6):.1f}tok/s "
                     f"{_gen_seconds / max(_request_count, 1):.1f}s/query")
        else:
            state = f"{_phase} for {fmt_elapsed(time.time() - _phase_since)}"
        print(f"[t+{fmt_elapsed(time.time() - _started_at)}] {state}{gpu_str}",
              flush=True)
        write_status()


def _descendants(pid: int) -> list:
    """
    Every descendant PID, read from /proc. No psutil dependency.

    vLLM's tensor-parallel workers are grandchildren (parent -> EngineCore ->
    workers), so killing direct children is not enough.
    """
    if not os.path.isdir("/proc"):
        return []
    children = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/stat", encoding="utf-8") as fh:
                # comm can contain spaces and parentheses; everything after the
                # final ") " is positional, and ppid is the second such field.
                fields = fh.read().rsplit(") ", 1)[-1].split()
            children.setdefault(int(fields[1]), []).append(int(entry))
        except (OSError, IndexError, ValueError):
            continue

    found, stack = [], [pid]
    while stack:
        for child in children.get(stack.pop(), []):
            found.append(child)
            stack.append(child)
    return found


_cleaning_up = False


def cleanup_children(signum=None, frame=None):  # noqa: ARG001
    """
    Takes the vLLM workers down with the server.

    Without this, killing the server leaves its workers spinning on the GPUs:
    they consume GPU cycles, so they distort any measurement taken afterwards,
    and they shrink the free memory vLLM sees at init -- which can turn a
    configuration that would fit into a "No available memory for the cache
    blocks" failure.
    """
    global _cleaning_up
    if _cleaning_up:
        return
    _cleaning_up = True

    kids = _descendants(os.getpid())
    if kids:
        print(f"Shutting down {len(kids)} child process(es): {kids}", flush=True)
        for sig in (signal.SIGTERM, signal.SIGKILL):
            alive = []
            for pid in kids:
                try:
                    os.kill(pid, sig)
                    alive.append(pid)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    print(f"  cannot signal {pid} (permission)", file=sys.stderr)
            if not alive:
                break
            time.sleep(3 if sig == signal.SIGTERM else 0)
            kids = [p for p in alive if os.path.exists(f"/proc/{p}")]
            if not kids:
                break

    if signum is not None:
        # Conventional exit status for a signal, and importantly this runs the
        # atexit handlers rather than dying where the signal landed.
        sys.exit(128 + signum)


def install_debug_handlers() -> None:
    """
    Worker cleanup, plus a way to find out where a stuck process is stuck.
    SIGUSR1 dumps every thread's stack on demand -- run_experiment.sh sends it
    when the readiness budget expires. faulthandler.enable() turns a segfault,
    which a bad CUDA or NCCL interaction can produce, into a traceback instead
    of a silent death.
    """
    faulthandler.enable()
    # Registered before the model loads, so even a Ctrl-C during a 4-minute
    # startup takes the half-initialised workers with it.
    import atexit
    atexit.register(cleanup_children)
    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, cleanup_children)
        except (ValueError, OSError):
            pass  # not the main thread, or unsupported platform
    if hasattr(signal, "SIGUSR1"):
        faulthandler.register(signal.SIGUSR1, all_threads=True, chain=True)


def validate_local_model(model_path: str) -> None:
    """
    Fails fast on a bad checkpoint directory.

    Without this, a typo in the path surfaces either as an opaque transformers
    stack trace or — worse, when the path merely looks like a hub id — as a
    network call that hangs for minutes on a compute node with no internet.
    """
    if not os.path.isdir(model_path):
        sys.exit(f"ERROR: --model path is not a directory: {model_path}")

    entries = os.listdir(model_path)

    if "config.json" not in entries:
        sys.exit(f"ERROR: no config.json in {model_path}\n"
                 f"       found: {sorted(entries)[:20]}")

    if not any(f.endswith((".safetensors", ".bin")) for f in entries):
        sys.exit(f"ERROR: no .safetensors or .bin weight files in {model_path}")

    if not any(f in ("tokenizer.json", "tokenizer.model",
                     "tokenizer_config.json", "vocab.json") for f in entries):
        sys.exit(f"ERROR: no tokenizer files in {model_path}")


def load_vllm(model_path: str, dtype: str, tensor_parallel: int,
              max_model_len: int, gpu_util: float, enforce_eager: bool = False,
              disable_custom_all_reduce: bool = False):
    """
    Loads the engine: tensor parallelism, paged attention, CUDA graphs.

    Constraint: tensor_parallel_size must divide the model's attention head
    count, so in practice it must be a power of two.
    """
    global _llm, _device

    from vllm import LLM

    _llm = LLM(
        model=model_path,
        tensor_parallel_size=tensor_parallel,
        dtype=dtype,
        max_model_len=max_model_len,
        gpu_memory_utilization=gpu_util,
        trust_remote_code=True,
        enforce_eager=enforce_eager,
        disable_custom_all_reduce=disable_custom_all_reduce,
    )
    _device = f"vllm:tp{tensor_parallel}"


def build_inputs(system: str, prompt: str):
    """
    Applies the model's chat template.
    """
    system = (system or "").strip()
    prompt = (prompt or "").strip()
    merged = f"{system}\n\n{prompt}".strip() if system else prompt

    shapes = [[{"role": "user", "content": merged}]]
    if system:
        shapes.insert(0, [{"role": "system", "content": system},
                          {"role": "user", "content": prompt}])

    for messages in shapes:
        try:
            return _tokenizer.apply_chat_template(
                messages, add_generation_prompt=True, tokenize=False)
        except Exception:
            continue
    # No chat template at all (base model): fall back to raw text.
    return merged


THINK_CLOSE = "</think>"

# Tokens allowed for the answer after the reasoning block has been force-closed.
FORCE_ANSWER_TOKENS = 8


def generate_batch(texts: list, max_new_tokens: int):
    """Returns [(decoded_text, n_generated_tokens), ...]."""

    ids = [_tokenizer(t, add_special_tokens=False)["input_ids"] for t in texts]

    from vllm import SamplingParams
    params = SamplingParams(temperature=0.0, max_tokens=max_new_tokens)

    try:
        from vllm import TokensPrompt
        outputs = _llm.generate([TokensPrompt(prompt_token_ids=i) for i in ids],
                                params)
    except ImportError:
        outputs = _llm.generate(prompt_token_ids=ids, sampling_params=params)

    return [(o.outputs[0].text, len(o.outputs[0].token_ids)) for o in outputs]


def extract_answer(tail: str):
    """
    Resolves True/False by FIRST occurrence.

    Cache.getIsStrictlyTrue on the Java side tests contains("true")
    before contains("false"), so a conclusion phrased as
    "the statement is false, not true" would be scored True. 
    Deciding by position, and returning a bare token removes that failure mode.
    """
    low = tail.lower()
    hits = [(low.find(t), t.capitalize()) for t in ("true", "false") if t in low]
    return min(hits)[1] if hits else None


def _result(raw: str, tail: str, tokens: int, truncated: bool,
            at_cap: bool) -> dict:
    return {"raw": raw, "tail": tail, "tokens": tokens,
            "truncated": truncated, "at_cap": at_cap}


def reason_and_answer_batch(system: str, prompts: list, budget: int) -> list:
    """
    Runs the model with a reasoning budget. Returns one _result per prompt.
    """
    texts = [build_inputs(system, p) for p in prompts]
    results = generate_batch(texts, budget)

    out = [None] * len(results)
    forced_idx, forced_texts = [], []
    for i, (raw, n) in enumerate(results):
        if THINK_CLOSE in raw:
            out[i] = _result(raw, raw.split(THINK_CLOSE)[-1], n, False,
                             n >= budget)
        else:
            forced_idx.append(i)
            forced_texts.append(texts[i] + raw + "\n" + THINK_CLOSE + "\n\n")

    # Second pass, also batched, for everything that ran out of budget.
    if forced_texts:
        forced = generate_batch(forced_texts, FORCE_ANSWER_TOKENS)
        for slot, (tail, n_forced) in zip(forced_idx, forced):
            raw, n = results[slot]
            out[slot] = _result(raw, tail, n + n_forced, True, n >= budget)
    return out


def reason_and_answer(system: str, prompt: str, budget: int) -> dict:
    """Single-prompt case, which is what /api/generate and the warmup use."""
    return reason_and_answer_batch(system, [prompt], budget)[0]


def record(result: dict) -> str:
    """Folds one result into the counters and returns its True/False answer."""
    global _token_total, _truncated_count, _at_cap_count, _unparsed_count
    _token_total += result["tokens"]
    _truncated_count += result["truncated"]
    _at_cap_count += result["at_cap"]

    answer = extract_answer(result["tail"])
    if answer is None:
        _unparsed_count += 1
        answer = "False"
    return answer


def sanitize(text: str) -> str:
    """
    A quote in the payload would truncate the Java-side parse, and newlines add
    nothing for a True/False answer.
    """
    return text.replace('"', "").replace("\n", " ").replace("\r", " ").strip()


def encode_json(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":"),
                      ensure_ascii=True).encode("utf-8")


def api_generate(data: dict) -> dict:
    global _request_count, _last_error, _gen_seconds

    prompt = data.get("prompt", "")
    system = data.get("system", "")

    started = time.time()

    try:
        with _lock:
            result = reason_and_answer(system, prompt, _max_new_tokens)
            _request_count += 1
            answer = record(result)
            count = _request_count
    except Exception as exc:  # noqa: BLE001 - surface anything to the log
        _last_error = f"{type(exc).__name__}: {exc}"
        print(f"ERROR during generation: {exc}", file=sys.stderr, flush=True)
        write_status()
        return {"response": "ERROR", "error": str(exc)}

    elapsed = time.time() - started
    _gen_seconds += elapsed

    if _trace_file:
        with open(_trace_file, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({
                "n": count, "prompt": prompt, "raw": result["raw"],
                "tail": result["tail"], "answer": answer,
                "tokens": result["tokens"], "truncated": result["truncated"],
                "at_cap": result["at_cap"], "seconds": round(elapsed, 2),
            }, ensure_ascii=False) + "\n")

    if result["at_cap"]:
        print(f"[{count}] AT CAP: hit --max-new-tokens ({_max_new_tokens}); "
              f"answer {answer!r} is unreliable. Raise the budget.",
              file=sys.stderr, flush=True)

    return {
        "model": data.get("model", ""),
        "response": sanitize(answer),
        "done": True,
        "tokens": result["tokens"],
        "seconds": round(elapsed, 2),
        "truncated": result["truncated"],
        "at_cap": result["at_cap"],
    }


def api_batch(data: dict) -> dict:
    """
    POST /api/batch  {"prompts": [...], "system": "..."}

    Used by BatchPrewarmer to fill the cache for Learner.precomputation(), and
    by benchmark_queries.py. It reports the aggregate rate so a batch can be
    compared directly against sequential /api/generate timings.
    """
    global _request_count, _gen_seconds, _last_error

    prompts = data.get("prompts") or []
    if not isinstance(prompts, list) or not prompts:
        return {"error": "prompts must be a non-empty list"}

    system = data.get("system", "")
    started = time.time()
    try:
        with _lock:
            results = reason_and_answer_batch(system, prompts, _max_new_tokens)
    except Exception as exc:  # noqa: BLE001
        _last_error = f"{type(exc).__name__}: {exc}"
        print(f"ERROR during batch generation: {exc}", file=sys.stderr, flush=True)
        write_status()
        return {"error": str(exc)}

    elapsed = time.time() - started
    answers = [record(r) for r in results]
    tokens = sum(r["tokens"] for r in results)

    _request_count += len(prompts)
    _gen_seconds += elapsed

    return {
        "answers": answers,
        "tokens": [r["tokens"] for r in results],
        "truncated": [r["truncated"] for r in results],
        "at_cap": [r["at_cap"] for r in results],
        "seconds_total": round(elapsed, 2),
        "seconds_per_query": round(elapsed / len(prompts), 3),
        "tokens_per_s": round(tokens / max(elapsed, 1e-6), 1),
        "n": len(prompts),
    }


def health() -> dict:
    payload = status_payload()
    payload["status"] = "ok" if _ready else "loading"
    return payload


class Handler(BaseHTTPRequestHandler):
    """Routes the two endpoints. Everything else is a 404."""

    protocol_version = "HTTP/1.1"

    def _send(self, payload: dict, status: int = 200) -> None:
        body = encode_json(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        route = self.path.rstrip("/")
        if route not in ("/api/generate", "/api/batch"):
            self._send({"error": "not found"}, 404)
            return
        if not _ready:
            self._send({"error": "model still loading", "phase": _phase,
                        "phase_seconds": round(time.time() - _phase_since, 1)},
                       503)
            return
        length = int(self.headers.get("Content-Length", 0))
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError as exc:
            self._send({"error": f"bad json: {exc}"}, 400)
            return
        self._send(api_batch(data) if route == "/api/batch"
                   else api_generate(data))

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") != "/health":
            self._send({"error": "not found"}, 404)
            return
        self._send(health())

    def log_message(self, fmt, *args):
        # Silence per-request access logging; the heartbeat reports progress,
        # and 37k access lines would bury it.
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=os.environ.get("EXACTLEARNER_MODEL_PATH"),
                        help="Checkpoint directory, or $EXACTLEARNER_MODEL_PATH. "
                             "A hub id works only where the hub is reachable.")
    parser.add_argument("--host", default="127.0.0.1",
                        help="Bind address (default: localhost only)")
    parser.add_argument("--port", type=int, default=11434)
    parser.add_argument("--dtype", default="bfloat16",
                        choices=["bfloat16", "float16", "float32"])
    parser.add_argument("--tensor-parallel-size", type=int, default=None,
                        help="Power of two; defaults to the visible GPU count.")
    parser.add_argument("--max-model-len", type=int, default=4096,
                        help="Caps vLLM's KV cache reservation.")
    parser.add_argument("--gpu-util", type=float, default=0.90,
                        help="Fraction of each GPU vLLM may use.")
    parser.add_argument("--enforce-eager", action="store_true",
                        help="Skip torch.compile and CUDA graph capture: much "
                             "faster startup, slower generation.")
    parser.add_argument("--disable-custom-all-reduce", action="store_true",
                        help="Skips the custom all-reduce kernel and its P2P "
                             "probe, which can hang on PCIe cards.")
    parser.add_argument("--max-new-tokens", type=int, default=1024,
                        help="Reasoning budget per query; overrides the client's "
                             "num_predict, which is 2. Generation stops at EOS, "
                             "so headroom is nearly free. Watch at_cap.")
    parser.add_argument("--trace-file", default=None,
                        help="Append full reasoning traces as JSONL.")
    parser.add_argument("--status-file", default=None,
                        help="JSON file, rewritten on every heartbeat, holding "
                             "phase / readiness / GPU state. Readable from a "
                             "login shell, which cannot reach this HTTP port.")
    parser.add_argument("--heartbeat-seconds", type=int, default=30,
                        help="Interval for the progress line and the status "
                             "file. 0 disables both.")
    args = parser.parse_args()

    if not args.model:
        sys.exit("ERROR: no model given. Pass --model /path/to/checkpoint "
                 "or set EXACTLEARNER_MODEL_PATH.")

    global _max_new_tokens, _trace_file, _tokenizer, _status_file, _ready
    _max_new_tokens = args.max_new_tokens
    _trace_file = args.trace_file
    _status_file = args.status_file

    install_debug_handlers()
    if args.heartbeat_seconds > 0:
        threading.Thread(target=heartbeat_loop, args=(args.heartbeat_seconds,),
                         daemon=True).start()

    # Bind and serve BEFORE the model loads. /health then answers from the first
    # second, so the job script can distinguish "still loading, phase X" from
    # "process is gone" instead of getting connection-refused for both.
    # /api/generate stays 503 until warmup finishes (see Handler.do_POST).
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()

    set_phase("loading model")

    is_local = os.path.isdir(args.model)
    if is_local:
        validate_local_model(args.model)
    # The tokenizer is loaded separately from the engine because build_inputs()
    # applies the chat template itself, and generate_batch() tokenizes with
    # add_special_tokens=False to avoid a duplicate BOS.
    _tokenizer = AutoTokenizer.from_pretrained(args.model,
                                               local_files_only=is_local)
    if _tokenizer.pad_token_id is None:
        _tokenizer.pad_token = _tokenizer.eos_token

    tp = args.tensor_parallel_size
    if tp is None:
        # Count GPUs from the environment, or failing that from nvidia-smi.
        # Never from torch.cuda, which would initialize a CUDA context in this
        # parent process before vLLM has spawned its workers.
        visible = os.environ.get("CUDA_VISIBLE_DEVICES")
        tp = (len([d for d in visible.split(",") if d.strip()])
              if visible else len(gpu_snapshot() or []))
    if tp < 1:
        sys.exit("ERROR: no GPUs found. Set CUDA_VISIBLE_DEVICES, or pass "
                 "--tensor-parallel-size explicitly.")
    if tp & (tp - 1) != 0:
        sys.exit(f"ERROR: tensor_parallel_size={tp} is not a power of two. "
                 f"vLLM shards attention heads across GPUs, so it must "
                 f"divide the head count. Request 2 or 4 GPUs (not 3), or "
                 f"pass --tensor-parallel-size explicitly.")
    load_vllm(args.model, args.dtype, tp, args.max_model_len, args.gpu_util,
              args.enforce_eager, args.disable_custom_all_reduce)

    # Warm up so the first real query is not skewed by lazy CUDA init, and so
    # the readiness message genuinely means ready. This also surfaces, before a
    # single experiment query runs, whether the reasoning budget is adequate.
    set_phase("warmup query")
    t0 = time.time()
    warm = reason_and_answer("Answer with only True or False.",
                             "Is a dog an animal?", _max_new_tokens)
    answer = extract_answer(warm["tail"])
    print(f"Warmup: answer={answer!r} tokens={warm['tokens']} "
          f"truncated={warm['truncated']} at_cap={warm['at_cap']} "
          f"seconds={time.time() - t0:.1f}", flush=True)
    if answer is None:
        print("WARNING: could not parse True/False from the warmup answer. "
              "Every query will fall back to False. Check the prompt format "
              "before submitting a long run.", file=sys.stderr, flush=True)
    if warm["at_cap"]:
        # Either the budget ran out mid-reasoning (truncated) or it closed
        # </think> and then had its answer text cut. Both produce answers that
        # get cached and never recomputed.
        print(f"WARNING: warmup consumed the entire {_max_new_tokens}-token "
              "budget, so its answer may be wrong. Raise --max-new-tokens "
              "before running anything long.", file=sys.stderr, flush=True)

    _ready = True
    set_phase("serving")
    print(f"Model ready. Listening on http://{args.host}:{args.port}", flush=True)

    try:
        # The server is already accepting on its own thread; park here so the
        # process stays up and Ctrl-C still lands in the main thread.
        threading.Event().wait()
    except KeyboardInterrupt:
        print("Shutting down.", flush=True)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
