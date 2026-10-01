"""PreToolUse guard for the byesu Claude Code profile.

Blocks (exit 2) any tool call that:
  - writes/edits a file outside WORKSPACE
  - reads/searches outside WORKSPACE (except read-only tool dirs)
  - runs a shell command with destructive/system-level patterns
  - runs a shell command touching paths outside WORKSPACE
  - runs a script file whose contents do any of the above
Fails closed: any internal error blocks the call.
"""
import json, os, re, sys, datetime
from pathlib import PureWindowsPath

HOME = r"C:\Users\semin"
WORKSPACE = os.path.normcase(os.path.realpath(r"C:\Users\semin\byesu-claude\workspace"))
# Outside dirs the agent may READ / execute tools from, but never modify.
READONLY_DIRS = [os.path.normcase(os.path.realpath(p)) for p in [
    r"C:\Program Files", r"C:\Program Files (x86)",
    HOME + r"\AppData\Local\Android\Sdk", HOME + r"\.gradle",
    HOME + r"\AppData\Roaming\npm", HOME + r"\AppData\Local\Programs\Python",
]]
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "guard.log")

DANGEROUS = [
    # mass / recursive deletion
    r"\brm\s+(-[a-zA-Z]*[rRf]|--recursive|--force|--no-preserve-root)",
    r"\b(rmdir|rd)\b", r"\b(del|erase)\s", r"\bshred\b", r"\bfind\b.*\s-delete\b",
    r"Remove-Item\b[^|;&]*-(Recurse|Force)", r"\bri\s.*-r", r"\bClear-(Content|Disk|RecycleBin)\b",
    r"shutil\.rmtree", r"\bgit\s+clean\s+-[a-z]*[fdx]",
    # disks, boot, system config
    r"\bformat(\.com)?\s+[a-z]:", r"\bdiskpart\b", r"\bbcdedit\b", r"\bvssadmin\b", r"\bcipher\s+/w",
    r"\b(Format|Clear|Initialize)-(Volume|Disk)\b", r"\bmkfs\b", r"\bdd\s+if=",
    r"\breg(\.exe)?\s+(add|delete|import|load|restore|copy)\b", r"\bregedit\b",
    r"(HKLM|HKCU|HKEY_)", r"\b(Set|New|Remove)-ItemProperty\b",
    r"\b(shutdown|Restart-Computer|Stop-Computer|logoff)\b",
    r"\b(takeown|icacls|cacls|attrib)\b", r"\bSet-(Acl|ExecutionPolicy|MpPreference)\b",
    r"\bsc(\.exe)?\s+(stop|delete|config|create)\b", r"\b(Stop|Remove|New|Set)-Service\b",
    r"\bschtasks\b", r"\b(Register|Unregister)-ScheduledTask\b", r"\bnetsh\b", r"\bwmic\b",
    r"\b(taskkill|Stop-Process|kill\s+-9|pkill|killall)\b",
    r"\bDisable-|Enable-WindowsOptionalFeature|\bdism\b|\bsfc\b",
    r"\bbitsadmin\b", r"\bcertutil\b",
    # privilege escalation
    r"\b(sudo|runas)\b", r"-Verb\s+RunAs",
    # obfuscated / remote code execution
    r"-e(nc(odedcommand)?)?\s+[A-Za-z0-9+/=]{16,}", r"FromBase64String",
    r"\b(Invoke-Expression|iex)\b", r"(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod)\b[^|]*\|\s*(ba|z|da)?sh\b",
    r"(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod)\b[^|]*\|\s*(iex|python|node|powershell|pwsh)\b",
    # global environment changes
    r"\bnpm\s+(i|install|uninstall|rm|update)\b[^;&|]*\s(-g|--global)\b",
    r"(^|[;&|(]\s*)(pip3?|python3?\s+-m\s+pip|py\s+-m\s+pip)\s+(install|uninstall)\b",
    r"\bsetx\b", r"\[Environment\]::SetEnvironmentVariable",
    r"\bgit\s+push\b[^;&|]*(--force|-f\b)", r"\bgit\s+config\s+--(global|system)\b",
    # tampering with the guard itself
    r"byesu-claude[\\/](config|guard\.py|guard\.log|byesu-claude\.cmd)", r"CLAUDE_CONFIG_DIR",
]
DANGEROUS_RE = [re.compile(p, re.I) for p in DANGEROUS]

ENV_PATH_RE = re.compile(
    r"(^|[\s'\"=(])~(?=[\\/\s'\"]|$)|\$HOME\b|\$\{HOME\}|\$USERPROFILE|\$env:|%(USERPROFILE|APPDATA|LOCALAPPDATA|"
    r"HOMEPATH|HOMEDRIVE|SystemRoot|windir|ProgramData|ProgramFiles|ALLUSERSPROFILE|PUBLIC|TEMP|TMP)%", re.I)
QUOTED_WIN_RE = re.compile(r"[\"'](?<![A-Za-z0-9])([A-Za-z]):[\\/]([^\"'\r\n]*)[\"']")
QUOTED_BASH_RE = re.compile(r"[\"']/([A-Za-z])/([^\"'\r\n]*)[\"']")
WIN_PATH_RE = re.compile(r"(?<![A-Za-z0-9])([A-Za-z]):[\\/]([^\s'\"|;&<>`]*)")
BASH_DRIVE_RE = re.compile(r"(?<![\w.:/~\\-])/([A-Za-z])/([^\s'\"|;&<>`]*)")
UNIX_ROOT_RE = re.compile(r"(?<![\w.:/~\\-])/(usr|etc|bin|home|mnt|proc|root|var|opt|sys|boot|lib|sbin|srv|cygdrive)(?=[/\s'\"]|$)", re.I)
UNC_RE = re.compile(r"\\\\[A-Za-z0-9.$]")
DOTDOT_RE = re.compile(r"[^\s'\"|;&<>`]*\.\.[\\/]?[^\s'\"|;&<>`]*")
MUTATING_RE = re.compile(
    r"\b(rm|mv|cp|del|mkdir|touch|tee|chmod|chown|ln|install|Remove-Item|Move-Item|Copy-Item|New-Item|"
    r"Set-Content|Add-Content|Out-File|Rename-Item|Expand-Archive|unzip|tar)\b|\bsed\s+-i|(?<![0-9&])>(?!\s*(&1|/dev/null|nul\b))", re.I)
SCRIPT_EXT = (".py", ".js", ".mjs", ".cjs", ".ts", ".sh", ".bash", ".ps1", ".bat", ".cmd", ".rb", ".pl", ".kts", ".gradle")


def norm(p):
    return os.path.normcase(os.path.realpath(p))


def under(p, root):
    return p == root or p.startswith(root.rstrip("\\") + "\\")


def in_workspace(p):
    return under(norm(p), WORKSPACE)


def in_readonly(p):
    n = norm(p)
    return any(under(n, r) for r in READONLY_DIRS)


def block(reason):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S} BLOCKED: {reason}\n")
    print(f"[guard] BLOCKED: {reason}. Work only inside {WORKSPACE}; destructive/system commands are forbidden.", file=sys.stderr)
    sys.exit(2)


def check_text(text, cwd, source):
    """Check a shell command or script body. Returns None if ok, else reason."""
    for rx in DANGEROUS_RE:
        m = rx.search(text)
        if m:
            return f"{source}: dangerous pattern '{m.group(0).strip()}'"
    m = ENV_PATH_RE.search(text)
    if m:
        return f"{source}: home/env-variable path '{m.group(0).strip()}' (use paths inside the workspace)"
    if UNC_RE.search(text):
        return f"{source}: network (UNC) path"
    m = UNIX_ROOT_RE.search(text)
    if m:
        return f"{source}: system path '{m.group(0)}'"
    # quoted paths may contain spaces: take them whole, then scan the rest unquoted
    paths = [f"{d}:\\{rest}" for d, rest in QUOTED_WIN_RE.findall(text)]
    paths += [f"{d}:\\{rest}" for d, rest in QUOTED_BASH_RE.findall(text)]
    rest_text = QUOTED_BASH_RE.sub(" ", QUOTED_WIN_RE.sub(" ", text))
    paths += [f"{d}:\\{rest}" for d, rest in WIN_PATH_RE.findall(rest_text)]
    paths += [f"{d}:\\{rest}" for d, rest in BASH_DRIVE_RE.findall(rest_text)]
    mutating = bool(MUTATING_RE.search(text))
    for p in paths:
        p = p.replace("/", "\\").rstrip("\\,.)")
        if in_workspace(p):
            continue
        if in_readonly(p) and not mutating:
            continue
        return f"{source}: path outside workspace '{p}'"
    for tok in DOTDOT_RE.findall(text):
        if ".." in tok and not re.fullmatch(r"\.\.\.+", tok):
            cand = tok.split("=")[-1]
            if not in_workspace(os.path.join(cwd, cand)):
                return f"{source}: '..' escapes the workspace ('{tok}')"
    return None


def check_scripts(cmd, cwd):
    """Also inspect script files referenced by the command (python x.py, node y.js, ...)."""
    for tok in re.findall(r"[^\s'\"|;&<>`]+", cmd):
        if tok.lower().endswith(SCRIPT_EXT):
            f = tok if os.path.isabs(tok) else os.path.join(cwd, tok)
            if os.path.isfile(f) and in_workspace(f):
                try:
                    body = open(f, encoding="utf-8", errors="replace").read(400_000)
                except OSError:
                    continue
                r = check_text(body, os.path.dirname(f), f"script {tok}")
                if r:
                    return r
    return None


def main():
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    inp = data.get("tool_input", {}) or {}
    cwd = data.get("cwd") or os.getcwd()

    if not in_workspace(cwd):
        block(f"session cwd {cwd} is outside the workspace")

    if tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        p = inp.get("file_path") or inp.get("notebook_path") or ""
        if not p or not in_workspace(p if os.path.isabs(p) else os.path.join(cwd, p)):
            block(f"{tool} outside workspace: {p}")

    elif tool in ("Read", "Glob", "Grep"):
        p = inp.get("file_path") or inp.get("path") or cwd
        full = p if os.path.isabs(p) else os.path.join(cwd, p)
        if not (in_workspace(full) or in_readonly(full)):
            block(f"{tool} outside workspace: {p}")
        pat = inp.get("pattern", "") if tool == "Glob" else ""
        if pat and (os.path.isabs(pat) or ".." in pat):
            block(f"Glob pattern escapes workspace: {pat}")

    elif tool in ("Bash", "PowerShell"):
        cmd = inp.get("command", "")
        r = check_text(cmd, cwd, "command") or check_scripts(cmd, cwd)
        if r:
            block(r)

    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:  # fail closed
        block(f"guard internal error: {e!r}")
