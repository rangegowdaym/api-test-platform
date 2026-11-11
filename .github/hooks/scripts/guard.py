#!/usr/bin/env python3
"""
GitHub Copilot preToolUse guard for api-test-platform (registered in .github/hooks/guard.json).
Runs in Copilot CLI and Copilot cloud agent (VS Code hooks are in preview). Must be on the default branch
for the cloud agent.

Input (stdin): either the Copilot format {"toolName": "...", "toolArgs": "<json string or object>", ...}
or the VS Code-compatible format {"tool_name": "...", "tool_input": {...}, ...}.
Deny: print {"permissionDecision":"deny","permissionDecisionReason":...} and exit 2 (non-zero = deny).
Allow: exit 0 with no output (normal permission flow continues).

Enforces the hard rules in .github/copilot-instructions.md:
  1. agents never approve test cases and never edit testcases/ directly (only testcases/_proposed/)
  2. generated feature files, <Tag>Api and <Tag>Steps classes are never hand-edited
  3. no literal secrets in config/env/*.properties
"""
import json
import re
import sys

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)                      # not a payload we understand: do not block

tool = str(data.get("toolName") or data.get("tool_name") or "")
args = data.get("toolArgs", data.get("tool_input", {}))
if isinstance(args, str):
    try:
        args = json.loads(args)
    except Exception:
        args = {"command": args}


def deny(msg: str) -> None:
    reason = f"Blocked by .github/hooks/scripts/guard.py: {msg}"
    print(json.dumps({"permissionDecision": "deny", "permissionDecisionReason": reason}))
    print(reason, file=sys.stderr)
    sys.exit(2)


def strings(obj, key=""):
    """All (key, string) pairs in the arguments, however the tool nests them."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from strings(v, k)
    elif isinstance(obj, list):
        for v in obj:
            yield from strings(v, key)
    elif isinstance(obj, str):
        yield key, obj


pairs = list(strings(args))
paths = [v for k, v in pairs if re.search(r"path|file|uri", k, re.I)]
text = "\n".join(v for k, v in pairs)
# apply_patch carries paths inside the patch text
paths += re.findall(r"\*\*\* (?:Add|Update|Delete) File: (.+)", text)
paths = [p.replace("\\", "/") for p in paths]

t = tool.lower()
SHELL = t in ("bash", "powershell", "shell", "runinterminal", "run_in_terminal") or "terminal" in t
EDIT = any(x in t for x in ("edit", "create", "write", "str_replace", "apply_patch", "multiedit", "insert"))

APPROVED = re.compile(r'"status"\s*:\s*"APPROVED"')
SECRET_LINE = re.compile(r'^\s*[\w.-]*(secret|password|token|apikey|api_key|key)\s*=\s*(?!\$\{)(\S+)', re.I | re.M)

if EDIT and not SHELL:
    for p in paths:
        if re.search(r"(^|/)src/test/resources/features/generated/", p):
            deny("generated feature files are rebuilt from approved test cases. "
                 "Run: java -jar target/api-test-platform.jar features")
        if re.search(r"(^|/)target/rca/rca\.json$", p) or re.search(r"(^|/)reports/index\.html$", p):
            deny("rca.json and the report are written by the platform. Answer target/ai/rca-narrative/requests.md "
                 "and run: java -jar target/api-test-platform.jar ai rca-narrative --apply")
        if re.search(r"(^|/)src/test/java/com/qa/automation/(api|steps)/generated/[^/]+\.java$", p) \
                and not p.endswith("package-info.java"):
            deny("generated Api and Steps classes are rebuilt from the spec. "
                 "Run: java -jar target/api-test-platform.jar services --spec specs/<spec>")
        if re.search(r"(^|/)testcases/", p) and not re.search(r"(^|/)testcases/_proposed/", p):
            deny("test cases change only through the approval UI. Write proposals to testcases/_proposed/ "
                 "and run: java -jar target/api-test-platform.jar propose --file <file>")
        if re.search(r"(^|/)testcases/", p) and APPROVED.search(text):
            deny('a proposal cannot be "APPROVED". Use "PENDING_REVIEW"; a human approves in the UI.')
        if re.search(r"(^|/)config/env/[^/]+\.properties$", p):
            m = SECRET_LINE.search(text)
            if m and m.group(2) not in ("", "none"):
                deny(f"literal secret in {p}. Use ${{ENV_VAR}} instead.")

if SHELL:
    cmd = text
    if re.search(r"/api/testcases/[^ ]*/approve|/api/testcases/bulk", cmd) or re.search(r'"action"\s*:\s*"APPROVE"', cmd):
        deny("approving test cases is a human action in the approval UI.")
    if re.search(r"(sed|perl|awk)\b[^|;]*-i[^|;]*testcases/", cmd) or re.search(r">\s*testcases/(?!_proposed/)", cmd):
        deny("do not rewrite testcases/ from the shell. Use the approval UI or the propose command.")
    if re.search(r"features/generated/", cmd) and re.search(r"(sed -i|>\s|tee )", cmd):
        deny("do not edit generated features. Run: java -jar target/api-test-platform.jar features")
    if re.search(r"\bgit\s+push\b", cmd):
        deny("pushing is done by a person (or by the cloud agent's own PR flow), not from the shell.")

sys.exit(0)
