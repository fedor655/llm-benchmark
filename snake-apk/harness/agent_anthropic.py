"""Minimal autonomous coding agent: model talks via byesu (Anthropic format),
can only write/read files inside PROJECT and run the cached Gradle."""
import json, os, subprocess, sys, time, pathlib, requests

API = "https://byesu.com/v1/messages"
KEY = os.environ["BYESU_KEY"]
MODEL = os.environ.get("MODEL", "claude-opus-5-5")
PROJECT = pathlib.Path(r"C:\Users\semin\snake-agent").resolve()
GRADLE = r"C:\Users\semin\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
SDK = r"C:\Users\semin\AppData\Local\Android\Sdk"
JDK = r"C:\Program Files\Android\Android Studio\jbr"
MAX_TURNS = 40
LOG = PROJECT.parent / "snake-agent-log.jsonl"

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

TOOLS = [
    {"name": "write_file", "description": "Create/overwrite a text file in the project.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
    {"name": "read_file", "description": "Read a text file in the project.",
     "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
    {"name": "list_files", "description": "Recursively list project files (build dirs excluded).",
     "input_schema": {"type": "object", "properties": {}}},
    {"name": "gradle", "description": "Run Gradle in the project root with given args, e.g. 'assembleDebug'. Returns exit code and tail of output.",
     "input_schema": {"type": "object", "properties": {"args": {"type": "string"}}, "required": ["args"]}},
]


def safe(p):
    full = (PROJECT / p).resolve()
    if PROJECT not in full.parents and full != PROJECT:
        raise ValueError("path escapes project root")
    return full


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
            env = dict(os.environ, JAVA_HOME=JDK, ANDROID_HOME=SDK, ANDROID_SDK_ROOT=SDK)
            r = subprocess.run(f'"{GRADLE}" --console=plain {inp["args"]}', cwd=PROJECT, env=env,
                               shell=True, capture_output=True, text=True, timeout=1200,
                               encoding="utf-8", errors="replace")
            out = (r.stdout + "\n" + r.stderr).strip()
            return f"exit code {r.returncode}\n...{out[-7000:]}"
    except Exception as e:
        return f"ERROR: {e}"
    return "unknown tool"


def call(messages):
    for attempt in range(5):
        try:
            r = requests.post(API, timeout=600, headers={
                "x-api-key": KEY, "anthropic-version": "2023-06-01", "content-type": "application/json"},
                json={"model": MODEL, "max_tokens": 32000, "system": SYSTEM, "tools": TOOLS, "messages": messages})
            if r.status_code == 200:
                return r.json()
            print(f"  API {r.status_code}: {r.text[:300]}", flush=True)
        except Exception as e:
            print(f"  API exception: {e}", flush=True)
        time.sleep(5 * (attempt + 1))
    sys.exit("API failed")


def main():
    PROJECT.mkdir(parents=True, exist_ok=True)
    messages = [{"role": "user", "content": "Start. Build the Snake APK."}]
    t0 = time.time(); tot_in = tot_out = 0
    for turn in range(1, MAX_TURNS + 1):
        resp = call(messages)
        u = resp.get("usage", {}); tot_in += u.get("input_tokens", 0); tot_out += u.get("output_tokens", 0)
        messages.append({"role": "assistant", "content": resp["content"]})
        with open(LOG, "a", encoding="utf-8") as lf:
            lf.write(json.dumps({"turn": turn, "resp": resp}, ensure_ascii=False) + "\n")
        results = []
        for b in resp["content"]:
            if b["type"] == "text" and b["text"].strip():
                print(f"[{turn}] MODEL: {b['text'].strip()[:600]}", flush=True)
            if b["type"] == "tool_use":
                arg = b["input"].get("path") or b["input"].get("args") or ""
                out = run_tool(b["name"], b["input"])
                first = out.splitlines()[0] if out else ""
                print(f"[{turn}] {b['name']}({arg}) -> {first[:150]}", flush=True)
                results.append({"type": "tool_result", "tool_use_id": b["id"], "content": out})
        if not results:
            break
        messages.append({"role": "user", "content": results})
    print(f"\nDONE: {turn} turns, {time.time()-t0:.0f}s, tokens in={tot_in} out={tot_out}", flush=True)


if __name__ == "__main__":
    main()
