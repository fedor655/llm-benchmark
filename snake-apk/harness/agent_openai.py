"""Snake-APK benchmark agent, OpenAI chat-completions format (byesu).
Usage: python agent_oai.py <name> <model> <key>
Model can only write/read files inside its project dir and run the cached Gradle.
Gradle runs are serialized across all agents with a lock file (RAM is limited)."""
import json, os, subprocess, sys, time, pathlib, requests

NAME, MODEL, KEY = sys.argv[1], sys.argv[2], sys.argv[3]
API = "https://byesu.com/v1/chat/completions"
BENCH = pathlib.Path(r"C:\Users\semin\snake-bench")
PROJECT = (BENCH / NAME).resolve()
GRADLE = r"C:\Users\semin\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
SDK = r"C:\Users\semin\AppData\Local\Android\Sdk"
JDK = r"C:\Program Files\Android\Android Studio\jbr"
LOCK = BENCH / "gradle.lock"
MAX_TURNS = 40
DEADLINE = 60 * 60  # 1 hour wall clock
LOG = BENCH / f"{NAME}.jsonl"
RESULT = BENCH / f"{NAME}.result.json"

SYSTEM = f"""You are an autonomous Android developer. Build a complete, playable Snake game as an Android APK.
Work fully autonomously - nobody will answer questions. Iterate until `assembleDebug` succeeds.

Environment (Windows 11):
- Project root: {PROJECT} (all paths you use are relative to it)
- Gradle 8.11.1 is invoked for you by the `gradle` tool (there is no gradlew; do not create a wrapper)
- JDK 21, Android SDK at {SDK} with platforms android-34, android-35 and build-tools 34.0.0, 35.0.0
- Internet is available for Maven (google(), mavenCentral()).
- You have NO shell. Tools: write_file, read_file, list_files, gradle.

Requirements: Kotlin, single-module app, package com.example.snake, swipe controls, score,
game over + restart, speed increases over time, looks decent. Keep dependencies minimal.
When the APK is built, call nothing more and reply with a short summary of what you built."""


def fn(name, desc, props, req):
    return {"type": "function", "function": {"name": name, "description": desc,
            "parameters": {"type": "object", "properties": props, "required": req}}}


TOOLS = [
    fn("write_file", "Create/overwrite a text file in the project.",
       {"path": {"type": "string"}, "content": {"type": "string"}}, ["path", "content"]),
    fn("read_file", "Read a text file in the project.", {"path": {"type": "string"}}, ["path"]),
    fn("list_files", "Recursively list project files (build dirs excluded).", {}, []),
    fn("gradle", "Run Gradle in the project root with given args, e.g. 'assembleDebug'. Returns exit code and tail of output.",
       {"args": {"type": "string"}}, ["args"]),
]


def log(msg):
    print(f"[{NAME}] {msg}", flush=True)


def safe(p):
    full = (PROJECT / p).resolve()
    if PROJECT not in full.parents and full != PROJECT:
        raise ValueError("path escapes project root")
    return full


def gradle(args):
    # crude cross-process lock: exclusive create of a lock file
    while True:
        try:
            fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY); os.write(fd, NAME.encode()); os.close(fd); break
        except FileExistsError:
            if time.time() - LOCK.stat().st_mtime > 1500:  # stale lock
                LOCK.unlink(missing_ok=True)
            time.sleep(3)
    try:
        env = dict(os.environ, JAVA_HOME=JDK, ANDROID_HOME=SDK, ANDROID_SDK_ROOT=SDK)
        r = subprocess.run(f'"{GRADLE}" --console=plain {args}', cwd=PROJECT, env=env, shell=True,
                           capture_output=True, text=True, timeout=1200, encoding="utf-8", errors="replace")
        out = (r.stdout + "\n" + r.stderr).strip()
        return f"exit code {r.returncode}\n...{out[-7000:]}"
    finally:
        LOCK.unlink(missing_ok=True)


def run_tool(name, inp):
    try:
        if name == "write_file":
            f = safe(inp["path"]); f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(inp["content"], encoding="utf-8")
            return f"wrote {inp['path']} ({len(inp['content'])} chars)"
        if name == "read_file":
            return safe(inp["path"]).read_text(encoding="utf-8")[:20000]
        if name == "list_files":
            out = [str(p.relative_to(PROJECT)) for p in PROJECT.rglob("*")
                   if p.is_file() and not any(x in p.parts for x in ("build", ".gradle"))]
            return "\n".join(sorted(out)) or "(empty)"
        if name == "gradle":
            return gradle(inp.get("args", "assembleDebug"))
    except Exception as e:
        return f"ERROR: {e}"
    return "unknown tool"


STREAM = os.environ.get("STREAM") == "1"
MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "16000"))


def read_stream(r):
    """Assemble a streamed chat completion into the non-streamed shape."""
    msg = {"role": "assistant", "content": "", "reasoning_content": "", "tool_calls": []}
    usage = {}; finish = None
    for line in r.iter_lines(decode_unicode=True):
        if not line or not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        ev = json.loads(data)
        usage = ev.get("usage") or usage
        for ch in ev.get("choices") or []:
            d = ch.get("delta") or {}
            finish = ch.get("finish_reason") or finish
            msg["content"] += d.get("content") or ""
            msg["reasoning_content"] += d.get("reasoning_content") or ""
            for tc in d.get("tool_calls") or []:
                i = tc.get("index", 0)
                while len(msg["tool_calls"]) <= i:
                    msg["tool_calls"].append({"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                t = msg["tool_calls"][i]
                t["id"] = tc.get("id") or t["id"]
                f = tc.get("function") or {}
                t["function"]["name"] += f.get("name") or ""
                t["function"]["arguments"] += f.get("arguments") or ""
    for k in ("reasoning_content", "tool_calls"):
        if not msg[k]:
            msg.pop(k)
    return {"choices": [{"message": msg, "finish_reason": finish}], "usage": usage}


def call(messages):
    body = {"model": MODEL, "messages": messages, "tools": TOOLS, "max_tokens": MAX_TOKENS}
    if STREAM:
        body.update(stream=True, stream_options={"include_usage": True})
    for attempt in range(6):
        try:
            r = requests.post(API, timeout=900, json=body, headers={"Authorization": f"Bearer {KEY}"}, stream=STREAM)
            if r.status_code == 200:
                d = read_stream(r) if STREAM else r.json()
                if d.get("choices"):
                    return d
                log(f"API empty response: {r.text[:200]}")
            else:
                log(f"API {r.status_code}: {r.text[:200]}")
                # some upstreams reject max_tokens or echoed reasoning fields
                if r.status_code == 400 and "max_tokens" in r.text:
                    body.pop("max_tokens", None); body["max_completion_tokens"] = MAX_TOKENS
                if r.status_code == 400 and "reasoning" in r.text:
                    for m in messages: m.pop("reasoning_content", None)
        except Exception as e:
            log(f"API exception: {e}")
        time.sleep(10 * (attempt + 1))
    return None


def main():
    PROJECT.mkdir(parents=True, exist_ok=True)
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content": "Start. Build the Snake APK."}]
    t0 = time.time(); tin = tout = 0; builds = []; status = "max_turns"
    turn = 0
    for turn in range(1, MAX_TURNS + 1):
        if time.time() - t0 > DEADLINE:
            status = "timeout"; break
        resp = call(messages)
        if resp is None:
            status = "api_failed"; break
        u = resp.get("usage") or {}; tin += u.get("prompt_tokens", 0) or 0; tout += u.get("completion_tokens", 0) or 0
        msg = resp["choices"][0]["message"]
        with open(LOG, "a", encoding="utf-8") as lf:
            lf.write(json.dumps({"turn": turn, "msg": msg, "usage": u}, ensure_ascii=False) + "\n")
        keep = {k: v for k, v in msg.items() if k in ("role", "content", "tool_calls", "reasoning_content") and v is not None}
        keep.setdefault("content", "")
        messages.append(keep)
        if msg.get("content"):
            log(f"{turn}: {str(msg['content']).strip()[:300]}")
        calls = msg.get("tool_calls") or []
        if not calls and resp["choices"][0].get("finish_reason") == "length":
            log(f"{turn}: output cut off by token limit, nudging")
            messages.append({"role": "user", "content": "Your previous response hit the output token limit before any tool call. "
                             "Think less and act: write files via tool calls, a few files per turn."})
            continue
        if not calls:
            status = "finished"; break
        for c in calls:
            name = c["function"]["name"]
            try:
                inp = json.loads(c["function"].get("arguments") or "{}")
            except json.JSONDecodeError as e:
                out = f"ERROR: invalid JSON arguments: {e}"; inp = {}
            else:
                out = run_tool(name, inp)
            if name == "gradle":
                builds.append(out.splitlines()[0])
            arg = inp.get("path") or inp.get("args") or ""
            log(f"{turn}: {name}({arg}) -> {out.splitlines()[0][:120] if out else ''}")
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": out})
    apk = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"
    res = {"name": NAME, "model": MODEL, "status": status, "turns": turn, "seconds": round(time.time() - t0),
           "tokens_in": tin, "tokens_out": tout, "builds": builds, "apk": str(apk) if apk.exists() else None}
    RESULT.write_text(json.dumps(res, indent=1), encoding="utf-8")
    log(f"DONE {json.dumps(res)}")


if __name__ == "__main__":
    main()
