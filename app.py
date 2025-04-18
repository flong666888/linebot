from flask import Flask, request, abort
from linebot.v3.webhooks import MessageEvent, TextMessageContent, WebhookParser
from linebot.v3.messaging import (
    Configuration, MessagingApi, ApiClient,
    ReplyMessageRequest, TextMessage
)
import os
import json

app = Flask(__name__)

# 設定環境變數
channel_access_token = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
channel_secret = os.getenv("LINE_CHANNEL_SECRET")

configuration = Configuration(access_token=channel_access_token)
api_client = ApiClient(configuration)
messaging_api = MessagingApi(api_client)
parser = WebhookParser(channel_secret)

WATCHLIST_FILE = "watchlist.json"

def load_watchlist():
    if not os.path.exists(WATCHLIST_FILE):
        return []
    with open(WATCHLIST_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_watchlist(watchlist):
    with open(WATCHLIST_FILE, "w", encoding="utf-8") as f:
        json.dump(watchlist, f, ensure_ascii=False)

@app.route("/callback", methods=['POST'])
def callback():
    signature = request.headers.get('X-Line-Signature')
    body = request.get_data(as_text=True)

    try:
        events = parser.parse(body, signature)
    except Exception as e:
        print("Webhook 解析失敗:", e)
        abort(400)

    for event in events:
        if isinstance(event, MessageEvent) and isinstance(event.message, TextMessageContent):
            user_id = event.source.user_id
            user_text = event.message.text
            reply_token = event.reply_token
            watchlist = load_watchlist()

            if user_text == "監聽名單":
                reply_text = "\n".join(watchlist) or "目前沒有監聽名單"
            elif user_text.startswith("新增:"):
                name = user_text[3:].strip()
                if name and name not in watchlist:
                    watchlist.append(name)
                    save_watchlist(watchlist)
                reply_text = f"已新增 {name}"
            elif user_text.startswith("刪除:"):
                name = user_text[3:].strip()
                if name in watchlist:
                    watchlist.remove(name)
                    save_watchlist(watchlist)
                    reply_text = f"已刪除 {name}"
                else:
                    reply_text = f"{name} 不在監聽名單中"
            else:
                matched = [name for name in watchlist if name in user_text]
                reply_text = f"發現關鍵字：{', '.join(matched)}" if matched else None

            if reply_text:
                message = TextMessage(text=reply_text)
                reply = ReplyMessageRequest(reply_token=reply_token, messages=[message])
                messaging_api.reply_message(reply)

    return "OK"

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port)
