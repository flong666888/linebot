from flask import Flask, request, abort, jsonify
from linebot.v3.messaging import MessagingApi, Configuration, ApiClient, ReplyMessageRequest, TextMessage
from linebot.v3.webhooks import WebhookHandler, MessageEvent, TextMessageContent
import os
import json

app = Flask(__name__)

# 使用環境變數取得 Token 和 Secret
channel_access_token = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
channel_secret = os.getenv("LINE_CHANNEL_SECRET")

# 初始化 WebhookHandler 和 Messaging API
handler = WebhookHandler(channel_secret)
configuration = Configuration(access_token=channel_access_token)
api_client = ApiClient(configuration)
messaging_api = MessagingApi(api_client)

USER_ID = os.getenv("LINE_USER_ID")  # 從環境變數讀取個人 LINE ID
WATCHLIST_FILE = "watchlist.json"

# 載入監聽名單
def load_watchlist():
    if not os.path.exists(WATCHLIST_FILE):
        return []
    with open(WATCHLIST_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

# 儲存監聽名單
def save_watchlist(watchlist):
    with open(WATCHLIST_FILE, "w", encoding="utf-8") as f:
        json.dump(watchlist, f, ensure_ascii=False)

@app.route("/callback", methods=['POST'])
def callback():
    signature = request.headers['X-Line-Signature']
    body = request.get_data(as_text=True)

    try:
        # 使用 WebhookHandler 來處理訊息和驗證簽名
        handler.handle(body, signature)
    except Exception as e:
        print("Webhook Error:", e)
        abort(400)

    return 'OK'

# 設定收到訊息時的回應行為
@handler.add(MessageEvent, message=TextMessageContent)
def handle_message(event):
    user_text = event.message.text
    reply_token = event.reply_token

    watchlist = load_watchlist()

    # 根據用戶的訊息回應
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

    # 回應訊息
    if reply_text:
        message = TextMessage(text=reply_text)
        reply_request = ReplyMessageRequest(reply_token=reply_token, messages=[message])
        messaging_api.reply_message(reply_request)

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port)
