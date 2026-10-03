"""Allowlisted read projection of Desktop's default-profile UI mirror (v3)."""

import math
import re
import unicodedata

from hermes_mobile.services.redaction import REDACTED, scrub_hub_text


STORE_KEY = "hermes-bots-groups"
ROOM_ID = re.compile(r"[A-Za-z0-9_][A-Za-z0-9._:-]{0,199}")


def safe_text(value: object) -> str:
    if not isinstance(value, str):
        return ""
    value = "".join(c for c in value if c == "\n" or unicodedata.category(c) not in {"Cc", "Cf", "Cs"})
    value = re.sub(r"(?<![\w])(?:[A-Za-z]:/|\\\\)[^\s\"']*", REDACTED, value)
    return scrub_hub_text(value).encode("utf-8")[:16384].decode("utf-8", errors="ignore")


def valid_room_id(value: object) -> bool:
    return isinstance(value, str) and ROOM_ID.fullmatch(value) is not None and safe_text(value) == value


def _number(value: object) -> bool:
    return type(value) in (int, float) and 0 <= value <= 1e308 and math.isfinite(value)


def _count(value: object) -> int:
    return value if type(value) is int and value >= 0 else 0


def _messages(value: object) -> list[dict]:
    messages = []
    for entry in value if isinstance(value, list) else []:
        if not isinstance(entry, dict):
            continue
        author = entry.get("from")
        if (not isinstance(author, dict) or not isinstance(author.get("name"), str)
                or not isinstance(entry.get("text"), str) or not _number(entry.get("at"))):
            continue
        kind = author.get("kind")
        text = safe_text(entry["text"])
        messages.append({
            "id": safe_text(entry.get("id")),
            "from_kind": kind if kind in ("user", "member") else "other",
            "from_name": safe_text(author["name"]), "text": text,
            "at": entry["at"] / 1000.0,
            "thread": safe_text(entry.get("thread", "legacy")),
            "truncated": entry.get("truncated") is True or len(entry["text"].encode("utf-8", errors="ignore")) > 16384,
            "has_attachments": any(isinstance(entry.get(key), list) and bool(entry[key])
                                   for key in ("images", "attachments")),
        })
    return sorted(messages, key=lambda entry: (entry["at"], entry["id"]))


def parse_bot_groups(result: object) -> dict:
    if not isinstance(result, dict) or not isinstance(result.get("profiles"), list):
        raise ValueError("Invalid profiles result")
    # Desktop group-chat.ts:1014-1024 uses .find(name === 'default'), NOT
    # a revision contest across profile rows. Ignore every other profile.
    profile = next((row for row in result["profiles"]
                    if isinstance(row, dict) and row.get("name") == "default"), {})
    meta = profile.get("ui_meta")
    store = meta.get(STORE_KEY) if isinstance(meta, dict) else None
    if not isinstance(store, dict):
        return {"rooms": [], "updated_at": 0.0}
    output = {"rooms": [], "updated_at": store["updatedAt"] / 1000.0
              if _number(store.get("updatedAt")) else 0.0}
    if type(store.get("version")) is not int or store["version"] != 3:
        output["format_warning"] = True
    rooms = store.get("rooms")
    deleted = store.get("deleted")
    deleted = deleted if isinstance(deleted, dict) else {}
    for key, room in rooms.items() if isinstance(rooms, dict) else []:
        if not isinstance(key, str) or not isinstance(room, dict) or not isinstance(room.get("log"), list):
            continue
        room_id = room.get("roomId", key[3:] if key.startswith("id:") else key)
        if (not valid_room_id(room_id) or key in deleted or "id:" + room_id in deleted
                or (key.startswith("id:") and key != "id:" + room_id)):
            continue
        members = []
        for member in room.get("members", []) if isinstance(room.get("members", []), list) else []:
            if not isinstance(member, dict) or not isinstance(member.get("name"), str):
                continue
            members.append({"name": safe_text(member["name"]), "handle": safe_text(member.get("handle")),
                            "local": member.get("connectionKind") == "local"})
        messages = _messages(room["log"])
        output["rooms"].append({
            "room_id": room_id, "name": safe_text(room.get("name", room_id)), "members": members,
            "omitted": _count(room.get("omitted")), "revision": _count(room.get("revision")),
            "messages": messages, "message_count": len(messages),
            "last_at": messages[-1]["at"] if messages else 0.0,
        })
    output["rooms"].sort(key=lambda room: (-room["last_at"], room["room_id"]))
    return output
