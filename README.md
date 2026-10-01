<div align="right">
<strong>繁體中文</strong> ·
<a href="README_en.md">English</a>
</div>

<div align="center">

<h1>
  <img
    src="docs/assets/app_icon.png"
    width="40"
    align="absmiddle"
  />
  P2P遠端檔案
</h1>

**Android對Android的遠端雙欄檔案管理與資料救援工具**

![Kotlin](https://img.shields.io/badge/Kotlin-Project-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/Min_SDK-24-34A853)
![Target SDK](https://img.shields.io/badge/Target_SDK-37-34A853)
![Version](https://img.shields.io/badge/Version-26.10.01-181717)

</div>

---

## 這是什麼？

P2PRemoteFiles是一個以 **Android手機互相協助、遠端管理與資料救援** 為目的的檔案管理工具。

它不是遠端桌面，也不是一般的雲端硬碟。每一台安裝App的Android裝置都有自己的唯一ID，可以主動控制其他裝置，也可以成為被控端。

最主要的操作介面是一個類似MT管理器的**雙欄遠端檔案管理器**：左右兩欄預設都代表被控端的檔案系統，因此可以直接在被控端內部進行複製、移動、重新命名、刪除與壓縮，而不需要先把檔案下載到主控端再傳回去。

例如：

```text
左欄：B 手機 /Android/data/com.example/
右欄：B 手機 /USB/Backup/

             複製 →

實際發生的是：
B 手機內部儲存空間 → B 手機 USB
```

主控端只送出操作指令，實際檔案內容不會因為這種「被控端內部操作」而經過網路或VPS。

---

## 適合什麼情境？

P2PRemoteFiles特別適合以下情境：

- 幫家人或朋友遠端整理Android手機檔案。
- 將被控端資料直接複製到其SD卡或USB外接儲存裝置。
- 手機螢幕或觸控故障，但系統仍正常運作時，從另一台Android裝置救援資料。
- 遠端備份 `Android/data` 等一般檔案管理器難以處理的受限位置。
- 在不同網路下，透過自己的VPS建立連線，不依賴第三方遠端服務。

> 此專案的重點是「管理與搬移檔案」，**刻意不提供檔案開啟、圖片檢視、影片播放、文字編輯或開啟方式等功能**。

---

## 主要功能

### AnyDesk式裝置連線

每台裝置都有唯一Device ID。

首頁可以：

- 查看自己的ID。
- 輸入對方ID發起連線。
- 自動搜尋同一區域網路中的裝置。
- 查看最近連線。
- 管理受信任裝置與無人值守權限。

同一區域網路時會優先直接連線；不同網路時則透過自架VPS進行尋址與加密中繼。

### 雙欄遠端檔案管理

連線成功後，左右兩欄都是被控端的檔案系統，可以獨立瀏覽不同位置。

支援：

- 複製
- 移動
- 重新命名
- 刪除
- 建立資料夾
- ZIP壓縮
  - 僅儲存
  - 一般壓縮
- 檔案／目錄屬性
- 權限資訊與修改（能力允許時）
- 水平拖曳多選與範圍選取
- 下拉重新整理
- 直接輸入路徑轉址

### 上傳與下載

除了被控端內部操作，也能進行真正的跨裝置傳輸：

```text
主控端 A → 被控端 B：上傳
被控端 B → 主控端 A：下載／備份
```

同一區域網路時直接由A與B傳輸；不同網路時目前會經過自己的VPS Relay即時轉送。

VPS **不會把傳輸檔案存成暫存檔**，但跨網路Relay會消耗VPS的進站與出站流量。

### SD卡與USB外接儲存裝置

被控端插入SD卡或USB儲存裝置後，可以從儲存空間抽屜直接切換目前操作欄位。

例如左欄正在操作時選擇USB，只有左欄切換到USB根目錄，右欄維持原本位置。

### 可選圖片縮圖

圖片檔可以顯示縮圖；若縮圖產生失敗，會自動退回預設檔案類型圖示。

縮圖功能可完全關閉，適合低速網路、低效能裝置或資料救援情境。

---

## 檔案存取方式

P2PRemoteFiles會依裝置目前具備的能力選擇可用的檔案存取方式。

一般情況優先使用Android原生檔案存取；若裝置具備更高權限，則可以處理更多受限制的位置。

目前設計的能力優先順序為：

```text
Root Shizuku / Sui
        ↓
SU RootService
        ↓
File API
        ↓
Shell Shizuku
```

如果需要透過Shizuku存取被控端的受限目錄，**Shizuku必須存在於被控端**。主控端自己的Shizuku無法替另一台手機取得檔案權限。

---

## 無人值守與鎖定狀態存取

預設情況下，被控端必須有人確認連線。

使用者可以針對特定受信任裝置額外開啟：

- 無人值守存取
- 鎖定螢幕時允許存取
- 瀏覽
- 下載
- 上傳
- 檔案管理
- 刪除

這些權限是以裝置為單位個別授權，並不是知道Device ID就能直接進入。

無人值守開啟後，App可以使用前景服務維持被連線能力，因此在切到背景或螢幕關閉時仍可接受已授權裝置的連線。

### 無法支援的情況

如果手機**重新開機後尚未完成第一次解鎖**，Android的Credential Encrypted資料仍不可用。

P2PRemoteFiles不會也不打算繞過這個限制。這種狀態直接視為無法進行遠端檔案救援。

---

## 連線方式

P2PRemoteFiles不要求每一次連線都經過VPS。

```text
同一區域網路
    ↓
LAN 直接連線

不同網路
    ↓
VPS 尋址與 Relay
```

檔案系統RPC與傳輸Session會在裝置之間再建立加密層，因此VPS的角色主要是尋址與轉送。

如果想架設自己的Server，請參考：

**[SERVER_SETUP.md](SERVER_SETUP.md)**

### 從原始碼建置時設定自己的Server

Repository不會寫死任何人的正式VPS網域。Android App的連線端點放在專案根目錄的`p2premotefiles.conf`。

第一次Clone後：

```bash
cp p2premotefiles.conf.example p2premotefiles.conf
```

接著編輯`p2premotefiles.conf`：

```properties
VPS_SIGNALING_URL=wss://你的網域/ws
VPS_RELAY_URL=wss://你的網域/relay
```

Gradle在Build時會自動讀取這兩個值並產生對應的`BuildConfig`欄位，不需要修改`app/build.gradle.kts`。

`p2premotefiles.conf`已加入`.gitignore`，所以可以保存自己的實際Server設定而不會被正常的Git提交帶上去；`p2premotefiles.conf.example`則保留在Repository作為範本。若尚未建立私人設定檔，專案仍可使用範本值完成Build，但範例網域不提供實際服務。

---

## 權限與安全性

這個專案本身就是高權限工具，部分能力需要：

- 所有檔案存取權
- Shizuku／Sui
- Root（選用）
- 前景服務
- 區域網路存取
- 通知權限

「無人值守」與「鎖定狀態存取」都是高風險功能，因此預設關閉，必須由被控端使用者明確授權。

知道Device ID只代表可以發起連線要求，不代表具有存取權限。

---

## 專案定位

P2PRemoteFiles目前主要為私人、朋友與少量裝置之間使用而設計，沒有以大型公開服務或應用程式商店上架為目標。

它比較接近：

> **Android對Android的遠端雙欄檔案管理器 + 自架連線服務 + 資料救援工具。**

而不是傳統的遠端桌面或單純檔案傳輸App。

---

## 版本

目前版本：**26.10.01**  
Version Code：**1**
