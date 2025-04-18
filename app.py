from flask import Flask, request, abort
from linebot.v3.webhook import WebhookHandler
from linebot.v3.messaging import MessagingApi, Configuration, ApiClient, ReplyMessageRequest, TextMessage
from linebot.v3.webhooks import MessageEvent, TextMessageContent
import os

app = Flask(__name__)

# 使用環境變數取得 Token
channel_access_token = os.getenv("LINE_CHANNEL_ACCESS_TOKEN")
channel_secret = os.getenv("LINE_CHANNEL_SECRET")

# 初始化 WebhookHandler 與 Messaging API
handler = WebhookHandler(channel_secret)
configuration = Configuration(access_token=channel_access_token)
api_client = ApiClient(configuration)
messaging_api = MessagingApi(api_client)

@app.route("/callback", methods=['POST'])
def callback():
    signature = request.headers['X-Line-Signature']
    body = request.get_data(as_text=True)

    try:
        handler.handle(body, signature)
    except Exception as e:
        print("Webhook Error:", e)
        abort(400)

    return 'OK'

@handler.add(MessageEvent, message=TextMessageContent)
def handle_message(event):
    user_text = event.message.text
    reply_token = event.reply_token

    # 回傳一樣的訊息
    message = TextMessage(text=user_text)
    reply = ReplyMessageRequest(reply_token=reply_token, messages=[message])
    messaging_api.reply_message(reply)

if __name__ == "__main__":
    app.run(debug=True)
