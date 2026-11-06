# 遙控器焦點導覽（車機 Android 用）

讓 USB 遙控器的 **方向鍵 / 確定鍵** 像 Google TV 一樣操作：畫面上出現白色焦點框，
方向鍵移到該方向最近的按鈕，按確定就點選，到邊緣再按會自動捲動。

## 按鍵對照

| 遙控器按鍵 | 動作 |
|---|---|
| 上／下／左／右 | 移動焦點框（第一下會先叫出框） |
| 確定（OK / Enter） | 點選；按住 0.6 秒以上＝長按 |
| 邊緣再按方向鍵 | 捲動清單 |
| ESC / 返回（若送出 ESC） | 返回 |
| PageUp / PageDown | 整頁捲動 |

框會在 8 秒沒操作後自動隱藏，你用手指觸控時也不會一直擋著。

## 重要：遙控器要送出「方向鍵」

這個 App 接收的是**按鍵事件**。很多飛鼠遙控器有兩種模式：滑鼠模式（只送游標移動）和鍵盤／方向鍵模式。
請先把遙控器切到會送方向鍵的模式，再打開 App，按遙控器任一鍵，
首頁的「最後按鍵」會顯示它送出什麼。方向鍵按了沒反應 → 還在滑鼠模式。

## 一、用 GitHub 線上打包 APK（不需要 Android Studio）

1. 到 github.com 註冊／登入，右上角 **＋ → New repository**，名稱隨意（例如 CarRemoteNav），按 Create。
2. 在新倉庫頁面點 **uploading an existing file**，把這個資料夾**裡面所有內容**拖進去
   （包含隱藏的 `.github` 資料夾），按 **Commit changes**。
   - 若 `.github` 沒被上傳：點 **Add file → Create new file**，檔名輸入 `.github/workflows/build.yml`，
     貼上本專案同名檔案的內容後 Commit。
3. 點上方 **Actions** 分頁，等 **Build APK** 跑完（約 3～5 分鐘，打勾＝成功）。
   沒自動開始就點 Build APK → **Run workflow**。
4. 點進那次執行，頁面下方 **Artifacts** 下載 `CarRemoteNav-apk`，解壓縮得到 `app-debug.apk`。

## 二、安裝到車機

1. 把 `app-debug.apk` 複製到隨身碟，插車機，用檔案管理員點開安裝（需允許「安裝未知來源 App」）。
2. 打開「遙控器焦點導覽」，按 **開啟無障礙設定**，找到「遙控器焦點導覽」→ 開啟 → 同意。
   - Android 13 以上若開不了：到 設定 → 應用程式 → 遙控器焦點導覽 → 右上角 ⋮ → **允許受限制的設定**，再回去開啟。
3. 回到 App，首頁顯示「服務狀態：運作中 ✅」即完成。

### 車機沒有無障礙設定頁時（用電腦 adb）

```
adb shell settings put secure enabled_accessibility_services com.carremote.focusnav/com.carremote.focusnav.RemoteNavService
adb shell settings put secure accessibility_enabled 1
```

## 疑難排解

- **方向鍵沒反應**：看首頁「最後按鍵」。沒有變化＝遙控器在滑鼠模式；有顯示但沒移動＝某些 App 沒公開按鈕資訊（見下）。
- **框框位置怪怪的**：少數 App（地圖、遊戲、自繪介面）不公開按鈕位置，框會找不到目標；這類畫面仍可用觸控。
- **想暫時關掉**：首頁關掉「啟用遙控器焦點導覽」開關，方向鍵就恢復原本行為。
- 最低支援 Android 7.0（API 24）。

## 專案結構

- `RemoteNavService.kt`：無障礙服務，攔截按鍵、找可點項目、移動焦點、點選、捲動
- `FocusOverlayView.kt`：畫焦點框的透明圖層（不攔截觸控）
- `MainActivity.kt`：狀態、按鍵測試、開關
- `.github/workflows/build.yml`：GitHub 線上打包
