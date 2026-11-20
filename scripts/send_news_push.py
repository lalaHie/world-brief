"""Send a data-only FCM message for one newly published headline per update."""
import hashlib
import json
import os
import subprocess
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, messaging


def stories(document):
    return [story for edition in document.get("editions", {}).values()
            for story in edition.get("stories", []) if isinstance(story, dict)]


def identity(story):
    return str(story.get("url") or story.get("title") or "").strip()


def payload(story):
    url = str(story.get("url", ""))
    if not url.startswith("https://"):
        url = "https://lalahie.github.io/world-brief/"
    image = story.get("image") or {}
    if not isinstance(image, dict):
        image = {}
    image_url = str(image.get("url", ""))
    return {
        "id": hashlib.sha256(identity(story).encode()).hexdigest()[:32],
        "title": str(story.get("title") or "世界晨報")[:120],
        "summary": str(story.get("summary") or "")[:160],
        "url": url[:900],
        "image": image_url[:900] if image_url.startswith("https://") else "",
        "category": str(story.get("category") or "其他")[:50],
        "type": "breaking" if story.get("breaking") is True and story.get("verification") == "已核對"
            and bool(story.get("verificationEvidence")) else "hourly",
    }


def main():
    if not os.environ.get("FCM_SERVICE_ACCOUNT"):
        print("Firebase not configured; no messages sent.")
        return
    manual = os.environ.get("EVENT_NAME") == "workflow_dispatch"
    if manual:
        data = {
            "id": "world-brief-fcm-test",
            "title": "世界晨報｜推播測試",
            "summary": "如果你收到這則通知，代表 Firebase 推播連線成功。",
            "url": "https://lalahie.github.io/world-brief/",
            "image": "",
            "category": "台灣",
            "type": "hourly",
        }
    else:
        before = os.environ.get("BEFORE_SHA", "")
        if not before or set(before) == {"0"}:
            print("No previous edition; no historical notifications sent.")
            return
        try:
            old = json.loads(subprocess.check_output(
                ["git", "show", before + ":news.json"], text=True))
        except (subprocess.CalledProcessError, ValueError):
            print("Cannot compare previous edition; no message sent.")
            return
        new = json.loads(Path("news.json").read_text(encoding="utf-8"))
        seen = {identity(s) for s in stories(old)}
        fresh = [s for s in stories(new) if identity(s) and identity(s) not in seen]
        if not fresh:
            print("No newly published stories.")
            return
        chosen = max(fresh, key=lambda s: str(s.get("published", "")))
        data = payload(chosen)
    firebase_admin.initialize_app(credentials.Certificate(json.loads(os.environ["FCM_SERVICE_ACCOUNT"])))
    message = messaging.Message(
        data=data, topic="world-brief-news",
        android=messaging.AndroidConfig(priority="high"),
    )
    messaging.send(message)
    print("FCM message accepted.")


if __name__ == "__main__":
    main()
