from flask import Flask, request, jsonify
from linebot.v3.messaging import MessagingApi, Configuration, ApiClient, PushMessageRequest, TextMessage
import os
import json

app = Flask(__name__)

# 初始化 LINE Bot
channel_access_token = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
configuration = Configuration(access_token=channel_access_token)
api_client = ApiClient(configuration)
messaging_api = MessagingApi(api_client)

USER_ID = "你的個人LINE ID"  # 你要自己填自己的 userId
WATCHLIST_FILE = "watchlist.json"


def load_watchlist():
    if not os.path.exists(WATCHLIST_FILE):
        return []
    with open(WATCHLIST_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def save_watchlist(watchlist):
    with open(WATCHLIST_FILE, "w", encoding="utf-8") as f:
        json.dump(watchlist, f, ensure_ascii=False)


@app.route("/message", methods=["POST"])
def receive_message():
    data = request.json
    content = data.get("text", "")

    watchlist = load_watchlist()

    if content == "監聽名單":
        reply_text = "\n".join(watchlist) or "目前沒有監聽名單"
    elif content.startswith("新增:"):
        name = content[3:].strip()
        if name and name not in watchlist:
            watchlist.append(name)
            save_watchlist(watchlist)
        reply_text = f"已新增 {name}"
    elif content.startswith("刪除:"):
        name = content[3:].strip()
        if name in watchlist:
            watchlist.remove(name)
            save_watchlist(watchlist)
            reply_text = f"已刪除 {name}"
        else:
            reply_text = f"{name} 不在監聽名單中"
    else:
        matched = [name for name in watchlist if name in content]
        reply_text = f"發現關鍵字：{', '.join(matched)}" if matched else None

    if reply_text:
        msg = TextMessage(text=reply_text)
        push_request = PushMessageRequest(to=USER_ID, messages=[msg])
        messaging_api.push_message(push_request)

    return jsonify({"status": "ok"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port)
