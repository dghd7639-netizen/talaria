"""Defense in depth for allowlisted metadata, never a license to store bodies."""

import re


_SENSITIVE = re.compile(r"token|secret|password|api[_-]?key|authorization|prompt", re.I)
_CREDENTIAL_TEXT = re.compile(
    r"(?:token|secret|password|api[_-]?key|authorization|prompt)\s*[:=]|\bbearer\s+", re.I
)
REDACTED = "[REDACTED]"


def redact(value: object) -> object:
    """Copy JSON metadata, removing sensitive keys and whole long text values."""
    if isinstance(value, dict):
        return {
            key: redact(item) for key, item in value.items()
            if isinstance(key, str) and len(key) <= 64 and not _SENSITIVE.search(key)
        }
    if isinstance(value, (list, tuple)):
        return [redact(item) for item in value]
    if isinstance(value, str):
        if len(value) > 200 or _CREDENTIAL_TEXT.search(value) or any(ord(c) < 32 for c in value):
            return REDACTED
        return value
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return REDACTED


# Free-form external Hub text needs multiline rendering, unlike audit metadata.
# Redact whole credential-bearing lines BEFORE truncation to avoid cut-off secrets.
_HUB_SECRET = re.compile(
    r"(?:token|secret|password|passwd|api[_-]?key|authorization)[\w.-]*[\"\x27]?\s*[:=]"
    r"|\b(?:bearer|basic)\s+|[a-z][a-z0-9+.-]*://[^\s/]*@|\bfile://"
    r"|\b(?:sk-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+"
    r"|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)", re.I
)
_HUB_PATH = re.compile(r"(?<![\w:/])(?:~?/|[A-Za-z]:\\)[^\s\"\x27<>]*")


def scrub_hub_text(value: str) -> str:
    lines = []
    in_private_key = False
    for line in value.splitlines():
        if re.search(r"-----BEGIN .*PRIVATE KEY-----", line):
            in_private_key = True
        hidden = in_private_key or bool(_HUB_SECRET.search(line))
        if re.search(r"-----END .*PRIVATE KEY-----", line):
            in_private_key = False
        clean = REDACTED if hidden else _HUB_PATH.sub(REDACTED, line)
        lines.append("".join(c for c in clean if ord(c) >= 32 and not 127 <= ord(c) <= 159))
    return "\n".join(lines)
