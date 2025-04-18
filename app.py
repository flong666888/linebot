from flask import Flask, request, abort
from linebot import LineBotApi, WebhookHandler
from linebot.exceptions import InvalidSignatureError
from linebot.models import MessageEvent, TextMessage, TextSendMessage
import os
import json

app = Flask(__name__)

# LINE Bot 的 Channel Access Token 與 Secret（請設在環境變數或直接填）
channel_access_token = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
channel_secret = os.getenv("LINE_CHANNEL_SECRET")

line_bot_api = LineBotApi(channel_access_token)
handler = WebhookHandler(channel_secret)

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
    signature = request.headers.get("X-Line-Signature", "")
    body = request.get_data(as_text=True)

    try:
        handler.handle(body, signature)
    except InvalidSignatureError:
        abort(400)

    return 'OK'


@handler.add(MessageEvent, message=TextMessage)
def handle_message(event):
    user_text = event.message.text
    user_id = event.source.user_id

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
        line_bot_api.reply_message(
            event.reply_token,
            TextSendMessage(text=reply_text)
        )


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port)
