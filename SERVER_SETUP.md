# P2PRemoteFiles Server架設指南

這份文件說明如何從零開始架設自己的P2PRemoteFiles Signaling / Presence / Relay Server。

本Server的用途是：

- 註冊Device ID與裝置公鑰資訊。
- 維護在線狀態。
- 讓不同網路的兩台Android裝置找到彼此。
- 傳遞連線要求與接受／拒絕結果。
- 在無法LAN直連時提供WebSocket Relay。

Server **不會把傳輸中的檔案寫成暫存檔保存於VPS**。Relay是即時轉送二進位資料，因此主要成本是**頻寬與流量**，不是磁碟容量。

---

## 1. 建議環境

以下環境最接近目前專案實際使用與測試的組合：

- Ubuntu 22.04 LTS或相近版本
- 1 vCPU以上
- 1 GB RAM以上
- 建議至少1～2 GB Swap（低記憶體VPS）
- 一個Public IPv4
- 一個可控制DNS的網域名稱
- Nginx
- Go 1.18以上
- Certbot / Let's Encrypt

若只是少量朋友與私人裝置使用，Signaling本身的資源需求很低；真正會消耗資源的是大量Relay傳輸。

---

## 2. 先準備這些欄位

以下教學會使用這些範例值。請替換成自己的環境。

| 欄位 | 範例 | 說明 |
|---|---|---|
| `<DOMAIN>` | `remote.example.com` | Android App要連線的網域 |
| `<PUBLIC_IPV4>` | `203.0.113.10` | VPS對外IPv4 |
| `<SERVER_SOURCE>` | `/home/ubuntu/server` | 上傳到VPS的Server原始碼位置 |
| `<SERVICE_USER>` | `p2premotefiles` | 執行Go Server的系統帳號 |
| `<APP_DIR>` | `/opt/p2premotefiles` | Server執行檔安裝位置 |
| `<DATA_DIR>` | `/var/lib/p2premotefiles` | Device Registry保存位置 |
| `<BACKEND_ADDR>` | `127.0.0.1:18080` | Go Server監聽位址 |

後面的指令若沒有特殊需求，可以直接沿用：

```text
SERVICE_USER = p2premotefiles
APP_DIR      = /opt/p2premotefiles
DATA_DIR     = /var/lib/p2premotefiles
BACKEND_ADDR = 127.0.0.1:18080
```

通常真正需要替換的只有：

```text
<DOMAIN>
<PUBLIC_IPV4>
<SERVER_SOURCE>
```

---

## 3. DNS

在DNS服務商新增A Record：

```text
Type: A
Name: 例如 remote
Value: <PUBLIC_IPV4>
```

最終例如：

```text
remote.example.com → 203.0.113.10
```

如果使用Cloudflare，第一次部署建議先使用 **DNS only**，不要開啟Proxy。等整套HTTPS / WSS都正常後，再自行評估是否需要Proxy。

確認：

```bash
nslookup <DOMAIN>
```

或：

```bash
dig +short <DOMAIN>
```

應該看到自己的Public IPv4。

> `ping` 沒有回應不代表服務有問題。Ping使用ICMP，而本專案真正需要的是TCP 80與TCP 443。

---

## 4. 雲端防火牆與主機防火牆

需要對外開放：

```text
TCP 80
TCP 443
```

用途：

- 80：Let's Encrypt HTTP-01驗證與HTTP → HTTPS重新導向
- 443：Android HTTPS / WSS Signaling與Relay

**不要把18080對外開放。**

Go Server應只監聽：

```text
127.0.0.1:18080
```

### Cloud Provider Firewall

如果是Oracle Cloud、AWS、GCP、Azure或其他VPS平台，請在Security List / Security Group / NSG等介面加入：

```text
Source: 0.0.0.0/0
Protocol: TCP
Port: 80

Source: 0.0.0.0/0
Protocol: TCP
Port: 443
```

### UFW

先確認：

```bash
sudo ufw status verbose
```

若UFW有啟用：

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status verbose
```

### iptables

即使UFW顯示inactive，某些雲端Ubuntu映像仍可能存在iptables REJECT規則。

檢查：

```bash
sudo iptables -L INPUT -n -v --line-numbers
```

如果前面只有SSH 22被允許，後面直接有REJECT all，則80/443仍會被擋住。

修改防火牆前請先確認SSH規則，避免把自己鎖在VPS外。

修改後若使用 `iptables-persistent`：

```bash
sudo apt install -y iptables-persistent
sudo netfilter-persistent save
```

---

## 5. 確認TCP 80能從外部連入

如果VPS已安裝Nginx：

```bash
sudo systemctl status nginx --no-pager
sudo ss -lntp | grep -E ':(80|443|18080)\b'
```

在VPS本機：

```bash
curl -v --connect-timeout 5 http://127.0.0.1/
```

再從另一台電腦測：

```bash
curl -v --connect-timeout 5 http://<DOMAIN>/
```

Windows PowerShell也可以：

```powershell
Test-NetConnection <DOMAIN> -Port 80
```

若停在：

```text
Trying <PUBLIC_IPV4>:80...
```

而沒有建立TCP連線，先處理Cloud Firewall / UFW / iptables，不要先除錯Android或Go Server。

---

## 6. 安裝必要套件

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx golang-go
```

確認：

```bash
nginx -v
go version
certbot --version
```

---

## 7. 上傳Server原始碼

將專案中的Server目錄放到VPS，例如：

```text
/home/ubuntu/server
```

它至少應包含：

```text
server/
├── cmd/server/main.go
├── internal/
├── deploy/nginx/
├── deploy/systemd/
└── go.mod
```

進入：

```bash
cd <SERVER_SOURCE>
```

---

## 8. 建立Nginx Bootstrap設定

在取得Let's Encrypt憑證前，先建立純HTTP站台。

建立：

```bash
sudo nano /etc/nginx/sites-available/p2premotefiles
```

內容：

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name <DOMAIN>;

    root /var/www/html;

    location /.well-known/acme-challenge/ {
        try_files $uri =404;
    }

    location / {
        return 200 "P2PRemoteFiles TLS bootstrap\n";
        add_header Content-Type text/plain;
    }
}
```

啟用：

```bash
sudo ln -sf /etc/nginx/sites-available/p2premotefiles /etc/nginx/sites-enabled/p2premotefiles
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

外部測試：

```bash
curl http://<DOMAIN>/
```

應看到：

```text
P2PRemoteFiles TLS bootstrap
```

---

## 9. 取得Let's Encrypt憑證

```bash
sudo certbot certonly --nginx -d <DOMAIN>
```

成功後憑證通常位於：

```text
/etc/letsencrypt/live/<DOMAIN>/fullchain.pem
/etc/letsencrypt/live/<DOMAIN>/privkey.pem
```

確認：

```bash
sudo certbot certificates
```

---

## 10. Build Go Server

```bash
cd <SERVER_SOURCE>
go mod download
go build -trimpath -ldflags='-s -w' -o p2premotefiles-server ./cmd/server
```

測試是否產生：

```bash
ls -lh p2premotefiles-server
```

目前Server使用的Go Module：

```text
p2premotefiles/server
```

Go Server預設不需要PostgreSQL、MySQL或Redis。

Device Registry目前使用JSON檔案持久化。

---

## 11. 建立Server系統帳號與目錄

```bash
sudo useradd --system \
  --home /var/lib/p2premotefiles \
  --shell /usr/sbin/nologin \
  p2premotefiles 2>/dev/null || true
```

建立資料目錄：

```bash
sudo install -d \
  -o p2premotefiles \
  -g p2premotefiles \
  -m 0750 \
  /var/lib/p2premotefiles
```

建立程式目錄：

```bash
sudo install -d \
  -o root \
  -g root \
  -m 0755 \
  /opt/p2premotefiles
```

安裝執行檔：

```bash
sudo install \
  -o root \
  -g root \
  -m 0755 \
  ./p2premotefiles-server \
  /opt/p2premotefiles/p2premotefiles-server
```

---

## 12. 建立systemd Service

建立：

```bash
sudo nano /etc/systemd/system/p2premotefiles.service
```

內容：

```ini
[Unit]
Description=P2PRemoteFiles signaling and relay server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=p2premotefiles
Group=p2premotefiles
WorkingDirectory=/var/lib/p2premotefiles
Environment=LISTEN_ADDR=127.0.0.1:18080
Environment=REGISTRY_PATH=/var/lib/p2premotefiles/registry.json
ExecStart=/opt/p2premotefiles/p2premotefiles-server
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/p2premotefiles

[Install]
WantedBy=multi-user.target
```

啟用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now p2premotefiles
```

確認：

```bash
sudo systemctl status p2premotefiles --no-pager
```

應看到：

```text
Active: active (running)
```

Server Log：

```bash
sudo journalctl -u p2premotefiles -f
```

---

## 13. 先直接測Go Server

```bash
curl -i http://127.0.0.1:18080/healthz
```

應得到：

```json
{"status":"ok"}
```

確認18080只監聽localhost：

```bash
sudo ss -lntp | grep 18080
```

正常應接近：

```text
127.0.0.1:18080
```

不應是：

```text
0.0.0.0:18080
```

---

## 14. 正式Nginx HTTPS / WSS設定

把 `/etc/nginx/sites-available/p2premotefiles` 改成：

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name <DOMAIN>;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name <DOMAIN>;

    ssl_certificate /etc/letsencrypt/live/<DOMAIN>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<DOMAIN>/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    location = /healthz {
        proxy_pass http://127.0.0.1:18080/healthz;
        proxy_set_header Host $host;
    }

    location = /ws {
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;

        proxy_pass http://127.0.0.1:18080;
    }

    location = /relay {
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 24h;
        proxy_send_timeout 24h;

        proxy_pass http://127.0.0.1:18080;
    }

    location / {
        return 404;
    }
}
```

套用：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

---

## 15. 驗證HTTPS

VPS本機：

```bash
curl -ik https://127.0.0.1/healthz -H 'Host: <DOMAIN>'
```

外部電腦：

```bash
curl -v https://<DOMAIN>/healthz
```

應得到：

```json
{"status":"ok"}
```

Windows：

```powershell
Test-NetConnection <DOMAIN> -Port 443
```

---

## 16. Android App要填什麼？

Android專案不需要直接修改Gradle來填入VPS網域。連線端點統一由專案根目錄的`p2premotefiles.conf`提供。

Repository內會附一份可公開的範本：

```text
p2premotefiles.conf.example
```

先複製成自己的私人設定檔：

```bash
cp p2premotefiles.conf.example p2premotefiles.conf
```

Windows PowerShell可使用：

```powershell
Copy-Item p2premotefiles.conf.example p2premotefiles.conf
```

然後編輯`p2premotefiles.conf`：

```properties
VPS_SIGNALING_URL=wss://<DOMAIN>/ws
VPS_RELAY_URL=wss://<DOMAIN>/relay
```

例如自架網域是`remote.example.com`：

```properties
VPS_SIGNALING_URL=wss://remote.example.com/ws
VPS_RELAY_URL=wss://remote.example.com/relay
```

`app/build.gradle.kts`會在Build時讀取這份設定，並產生：

```text
BuildConfig.VPS_SIGNALING_URL
BuildConfig.VPS_RELAY_URL
```

因此每個自行架設Server的人只需要維護自己的`p2premotefiles.conf`，不需要修改Android原始碼。

### Git行為

真正的`p2premotefiles.conf`已列入`.gitignore`，應保持在本機，不要提交到公開Repository。可公開的只有：

```text
p2premotefiles.conf.example
```

如果Clone後尚未建立`p2premotefiles.conf`，Gradle會退回讀取`p2premotefiles.conf.example`，讓專案仍能正常Build；但範例使用的是保留的無效網域，不會連到任何正式P2PRemoteFiles Server。

正式環境請保持：

```text
wss://
```

不要改成未加密的`ws://`，也不要關閉TLS憑證驗證。

---

## 17. Let's Encrypt自動續期

Ubuntu套件安裝的Certbot通常會建立systemd timer，但仍應主動確認。

```bash
sudo systemctl status certbot.timer
sudo systemctl list-timers --all | grep certbot
```

若未啟用：

```bash
sudo systemctl enable --now certbot.timer
```

測試續期：

```bash
sudo certbot renew --dry-run
```

若所有模擬續期成功，就代表自動續期流程正常。

### 建議加入Nginx Deploy Hook

```bash
sudo mkdir -p /etc/letsencrypt/renewal-hooks/deploy
sudo nano /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
```

內容：

```sh
#!/bin/sh
/usr/sbin/nginx -t && /bin/systemctl reload nginx
```

給執行權限：

```bash
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
```

再測：

```bash
sudo certbot renew --dry-run
```

---

## 18. 低記憶體VPS建議加入Swap

如果VPS只有約1 GB RAM，建議額外建立1～2 GB Swap。

例如2 GB：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

確認：

```bash
swapon --show
free -h
```

加入 `/etc/fstab`：

```bash
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## 19. Registry備份

Server的裝置註冊資料預設位於：

```text
/var/lib/p2premotefiles/registry.json
```

內容包含Device ID與裝置公開身分資訊。

建議定期備份：

```bash
sudo cp \
  /var/lib/p2premotefiles/registry.json \
  /var/lib/p2premotefiles/registry.json.backup
```

或定期複製到其他安全位置。

### 重要

Relay中的檔案資料不會保存到 `registry.json`，也不會在VPS產生大型傳輸暫存檔。

---

## 20. 更新Server程式

更新原始碼後：

```bash
cd <SERVER_SOURCE>

go mod download

go build -trimpath -ldflags='-s -w' \
  -o p2premotefiles-server ./cmd/server
```

覆蓋執行檔：

```bash
sudo install -m 0755 \
  ./p2premotefiles-server \
  /opt/p2premotefiles/p2premotefiles-server
```

重新啟動：

```bash
sudo systemctl restart p2premotefiles
sudo systemctl status p2premotefiles --no-pager
```

如果Nginx設定也有變更：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

最後：

```bash
curl https://<DOMAIN>/healthz
```

---

## 21. 常用管理指令

查看狀態：

```bash
sudo systemctl status p2premotefiles --no-pager
```

重新啟動：

```bash
sudo systemctl restart p2premotefiles
```

即時Log：

```bash
sudo journalctl -u p2premotefiles -f
```

最近200行Log：

```bash
sudo journalctl -u p2premotefiles -n 200 --no-pager
```

Nginx設定檢查：

```bash
sudo nginx -t
```

Nginx Error Log：

```bash
sudo tail -n 100 /var/log/nginx/error.log
```

Nginx Access Log：

```bash
sudo tail -n 100 /var/log/nginx/access.log
```

目前監聽的Port：

```bash
sudo ss -lntp | grep -E ':(80|443|18080)\b'
```

---

## 22. 常見問題

### 外部連80 / 443一直Timeout

如果：

```text
Trying <PUBLIC_IPV4>:443...
```

之後一直沒有反應，通常表示TCP本身沒有進到Nginx。

依序檢查：

1. DNS是否解析到正確Public IP。
2. Cloud Provider Firewall / Security List / NSG。
3. UFW。
4. iptables / nftables。
5. Nginx是否真的listen 80 / 443。

### `/healthz` 回502 Bad Gateway

代表：

```text
Internet → Nginx
```

已經正常，但：

```text
Nginx → 127.0.0.1:18080
```

失敗。

檢查：

```bash
sudo systemctl status p2premotefiles --no-pager
curl http://127.0.0.1:18080/healthz
```

### APP一直顯示VPS連線中斷

先確認：

```bash
curl https://<DOMAIN>/healthz
```

接著：

```bash
sudo journalctl -u p2premotefiles -f
sudo tail -f /var/log/nginx/access.log /var/log/nginx/error.log
```

`/ws`正常建立WebSocket時，Nginx Access Log通常會看到：

```text
GET /ws HTTP/1.1" 101
```

HTTP `101`代表WebSocket升級成功。

### Nginx Error Log出現陌生IP的TLS錯誤

公開的TCP 443很常被Internet掃描器碰到。

若錯誤來源IP不是自己的裝置，而且正常Android Client仍能建立WSS，通常不代表Server本身故障。

---

## 23. 最後驗收

### Server

```bash
curl https://<DOMAIN>/healthz
```

應得到：

```json
{"status":"ok"}
```

### App

準備兩支手機：

```text
A：Wi-Fi
B：4G / 5G
```

兩台都開啟P2PRemoteFiles：

1. 首頁應顯示VPS已連線。
2. A輸入B的Device ID。
3. B收到連線要求。
4. B接受。
5. A進入雙欄遠端檔案管理器。
6. 連線模式顯示VPS加密中繼。
7. 可以正常列出目錄與進行遠端檔案操作。

再將A與B連到同一個區域網路測一次，應優先使用LAN直接連線。

---

## 24. 架構摘要

```text
Android A
    │
    │ WSS :443
    ▼
┌─────────────────────────┐
│ Nginx                   │
│ TLS / Let's Encrypt     │
└────────────┬────────────┘
             │
             │ localhost:18080
             ▼
┌─────────────────────────┐
│ P2PRemoteFiles Server   │
│                         │
│ Device Registry         │
│ Presence                │
│ Signaling               │
│ Relay                   │
└────────────┬────────────┘
             │
             │ WSS :443
             ▼
         Android B
```

真正的Relay檔案內容是即時轉送；VPS主要保存的是Device Registry，而不是使用者傳輸的檔案。
