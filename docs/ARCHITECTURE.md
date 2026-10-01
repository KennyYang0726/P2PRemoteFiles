# v26.10.01 架構

## 首頁與Session

```text
APP啟動
  ↓
Home
  ├─自己的Device ID
  ├─輸入遠端Device ID
  ├─LAN裝置區塊
  ├─最近連線
  └─Loopback測試
        ↓
Remote Session
        ↓
Dual Pane Remote File Manager
```

正式版本中，任何手機都能成為Controller或Target；角色只存在於當次Session。

## Filesystem

```text
DualPane UI
   ↓
RemoteFileClient
   ↓
LoopbackRemoteFileClient（v0.1）
   ↓
HybridFileSystemBackend
   ├─DirectFileBackend
   └─ShizukuFileBackend
        ↓
   Shizuku UserService
```

Direct File永遠是一般路徑的第一選擇。對Android 11+已知會隱藏成「不存在」的`Android/data`／`Android/obb`路徑，在Direct File失敗後允許NOT_FOUND也觸發Shizuku後備。

## 雙欄狀態

左右Pane各自保有：

- Volume ID／Volume root
- Current path
- Entries
- Selected paths
- Search query
- Filter
- Sort field／direction

`activePane`記錄最後操作欄。Drawer切換SD卡／USB時只改變`activePane`。

## ITEM手勢

```text
Folder tap        →進入目錄
File tap          →無動作
Long press        →ITEM附近Context Menu
Horizontal drag   →進入Selection
Horizontal+Y drag →同欄Range Selection
```

Selection Mode中，單擊ITEM只Toggle選取，不進入Folder。

## Server Endpoint設定

Android Client不在原始碼或Gradle腳本中寫死正式Server網域。專案根目錄使用兩份設定檔：

```text
p2premotefiles.conf          # 本機私人設定，Git忽略
p2premotefiles.conf.example  # 可提交的公開範本
```

Gradle在Build時優先讀取`p2premotefiles.conf`；若私人設定不存在，則讀取`.example`，再產生`BuildConfig.VPS_SIGNALING_URL`與`BuildConfig.VPS_RELAY_URL`。

這樣Fork或自行部署Server時只需要更換CONF，不需要修改App原始碼，也不會把個人VPS端點跟著Push。

## 正式連線預定

```text
輸入Device ID
  ↓
Cached LAN endpoint
  ↓
LAN Discovery
  ↓ 找不到
VPS Lookup／Signaling
  ↓
ICE Direct P2P
  ↓ 失敗
Relay fallback
```

檔案內容不預設經過VPS；VPS只負責跨網路尋址、Signaling與無法P2P時的Relay。
