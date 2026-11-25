# 機車超速提醒（Android）

騎機車時用 GPS 測速，**超過限速 + 7 km/h**（限速 50 → 57 開始）時每 0.5 秒嗶一聲，直到速度降下來為止。也會在接近測速照相點時提醒。

## 功能
- **自動限速**：從 OpenStreetMap 下載附近道路（每次約 2.6 km 見方），依位置和行進方向找出目前所在道路的限速；路口會選和你行進方向一致的那條路。有機車專用限速（`maxspeed:motorcycle`）時優先使用。
- **限速判斷順序**：前方 200 m 內的測速照相點 → OSM 道路限速 → 前段道路限速（沿用 30 秒）→ 預設限速（可調，預設 50）。
- **超速警報**：連續 2 筆 GPS 定位都超過門檻才開始嗶（避免 GPS 跳動誤報），每 0.5 秒 2 kHz 嗶聲，一低於門檻就停。
- **測速照相提醒**：內建警政署「測速執法設置點」開放資料（1,909 點），前方 400 m（可調）且方向相符時響三聲提示音，同一個點 5 分鐘內不重複提醒。
- **背景執行**：前景服務 + 喚醒鎖，關螢幕、切到導航也會持續運作；聲音走「導航語音」音訊通道，會從藍牙耳機或安全帽對講機輸出，並暫時降低音樂音量。
- 可調：容許值、預設／手動限速、照相提醒開關與距離、音量。

## 安裝
1. 把 APK 下載到手機並點開安裝（需允許「安裝未知應用程式」）。
2. 打開 App → 按「開始」→ 允許**精確位置**和**通知**權限。
3. 建議按「關閉省電限制」，避免部分手機（小米、OPPO、三星等）在背景把 App 關掉。

## 限制
- OSM 限速資料在台灣主要道路大多有，巷弄常沒有，這時會用預設限速。
- 測速照相資料是 App 內建的快照，**不含行動測速**；要更新請重新產生資料並重新編譯（見下方）。
- GPS 速度通常比機車儀表低幾 km/h（儀表會多報），57 是 GPS 速度。
- 僅供輔助提醒，請以現場標誌為準。

## 編譯
```bash
# 更新測速照相資料（來源 https://data.gov.tw/dataset/7320）
curl -o cams.csv "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/CD32FD37-EA0D-4F76-8C5F-7E2514FA4E1F/download"
python3 tools/build_cameras.py cams.csv app/src/main/assets/cameras.csv

# 單元測試與 APK（需要 Android SDK，在 local.properties 設定 sdk.dir）
./gradlew testDebugUnitTest assembleRelease
# 輸出：app/build/outputs/apk/release/app-release.apk
```

`keystore/speedalert.keystore` 是本專案固定的簽章金鑰（密碼 `speedalert`），讓之後重新編譯的版本可以直接覆蓋安裝。只用於自己安裝，不適合上架 Play 商店。

## 資料來源與授權
- 道路限速：© OpenStreetMap contributors（ODbL），經 Overpass API 取得。
- 測速照相點：內政部警政署「測速執法設置點」，政府資料開放授權條款第 1 版。
