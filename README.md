# MHXX RNG Tool (Kotlin / Android)

**Python/Kivy 版 `touyo` を Kotlin/Jetpack Compose へ移植したネイティブ Android アプリ**  
**HTML 統合ツール (`snipe_modified.html`) のコア機能を上書き統合済み**

| 項目 | 内容 |
|------|------|
| 言語 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 |
| 最小 SDK | Android 7.0 (API 24) |
| ターゲット SDK | Android 14 (API 34) |
| OCR | ML Kit (日本語テキスト認識・端末内処理) + HTML準拠スキル辞書/エイリアス |
| カメラ | CameraX (UVC キャプチャーカード対応) |
| ハードウェア | Arduino Leonardo (USB CDC) ※コントローラUIは未実装 |

---

## HTML統合ツールからの上書き内容

- **正式スキル名** 205種（略称パディング廃止 → HTML `SKILL` 配列準拠）
- **検索モード**: スキルから検索 / **フレームから検索**（指定Fのお守り即時表示）
- **OCRスキルエイリアス** HTML `SKILL_ALIASES` 相当を維持・拡張
- **乱数エンジン・KIND_TABLES** HTML `KIND_DATA` と整合
- コントローラ操作UIは実装していません（Arduinoオートは既存のまま任意）

---

## 機能タブ一覧

| タブ | 機能 |
|------|------|
| 検索 | スキル・スロット検索 (完全一致/以上) + **フレームから検索** |
| 周辺 | 特定フレーム周辺のお守り一覧表示 |
| 調合 | 調合数値列からのフレーム特定 + **mhxx-combo-scan 準拠テンプレート照合**による動画解析 |
| 狙い目 | クエスト / マカフシギ / 天運の有効フレーム計算 |
| 鑑定読取 | カメラ/ギャラリー写真から ML Kit OCR でスキル認識 → 検索タブへ反映 |
| 映像 | UVC キャプチャーカード映像のリアルタイム表示 |
| オート | Arduino Leonardo 連携による自動鑑定ループ（任意） |

---

## GitHub Actions での自動ビルド

`main` へプッシュするだけで APK が自動ビルドされます。

1. **Actions タブ** → `Build Debug APK` を選択
2. 最新のワークフロー実行を開く
3. **Artifacts** → `mhxx-rng-tool-debug-<番号>` をダウンロード
4. `app-debug.apk` を端末に転送してインストール

> **タグ**（例：`v1.0.0`）を push すると Release APK も生成され、  
> GitHub Releases に自動で添付されます。

---

## ローカルビルド手順

### 必要ツール
- JDK 17+
- Android SDK (API 34)

### ビルド
```bash
# Unix / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

出力先: `app/build/outputs/apk/debug/app-debug.apk`

### ADB でインストール
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## ハードウェア構成

```
Android スマートフォン (USB-C)
  └─ OTG アダプター
       └─ ANYOYO USB ハブ (または類似 OTG ハブ)
            ├─ Arduino Leonardo ← USB CDC Serial で接続
            └─ HDMI キャプチャーカード (UVC) ← Switch 映像入力
                 └─ Nintendo Switch (HDMI)
```

### Arduino スケッチ要件
Arduino Leonardo に以下のコマンドを処理するスケッチを書き込んでください：

| コマンド (UART 受信) | 動作 | 応答 |
|---|---|---|
| `READY` | 初期確認 | `OK` |
| `SEQ:APPRAISE` | Switchで鑑定シーケンス実行 | `DONE` |
| `SEQ:BACK` | 不一致時の戻りシーケンス | `DONE` |

通信設定: **115200 bps / 8N1**  
Arduino Leonardo は USB HID + CDC の複合デバイスとして動作します。

---

## プロジェクト構成

```
app/src/main/kotlin/org/mhxxtools/mhxxrngtool/
├── MainActivity.kt             # エントリーポイント
├── AppState.kt                 # グローバル状態 (種類/FPS)
├── rng/
│   ├── MHXXEngine.kt           # XorShift128 RNG エンジン (Python版の完全移植)
│   ├── CharmData.kt            # SKILL_NAMES / KIND_TABLES (全4種)
│   └── RngTypes.kt             # データクラス (Charm, CharmResult, SearchTarget, …)
├── ocr/
│   ├── AndroidOcr.kt           # ML Kit 日本語 OCR ラッパー
│   └── SkillMatcher.kt         # テキスト → スキル名マッチング
├── hardware/
│   ├── ArduinoBridge.kt        # USB CDC シリアル通信
│   ├── CharmDetector.kt        # OCR + 目標照合
│   └── UsbVideoHelper.kt       # カメラ列挙 / JPEG クロップ
└── ui/
    ├── MainScreen.kt           # Scaffold + TabRow ナビゲーション
    ├── theme/Theme.kt          # Material3 ダークテーマ
    ├── search/                 # 検索タブ
    ├── around/                 # 周辺確認タブ
    ├── combo/                  # 調合スナイプタブ
    ├── aimpoint/               # 狙い目タブ
    ├── ocr/                    # 鑑定読取タブ
    ├── stream/                 # 映像タブ
    └── auto/                   # オートタブ
```

---

## 元のPython版との対応

| Python モジュール | Kotlin 移植先 |
|---|---|
| `mhxx_rng.py` (XorShift128 / jump / charm生成) | `rng/MHXXEngine.kt` |
| `screens/search_screen.py` | `ui/search/` |
| `screens/around_screen.py` | `ui/around/` |
| `screens/combo_screen.py` | `ui/combo/` |
| `screens/aimpoint_screen.py` | `ui/aimpoint/` |
| `screens/ocr_screen.py` | `ui/ocr/` |
| `screens/stream_screen.py` (Kivy Camera) | `ui/stream/` (CameraX) |
| `screens/auto_screen.py` | `ui/auto/` |
| `hardware/arduino_bridge.py` (pyserial) | `hardware/ArduinoBridge.kt` (USB Host API) |
| `hardware/charm_detector.py` | `hardware/CharmDetector.kt` |
| `hardware/usb_video.py` (OpenCV UVC) | `hardware/UsbVideoHelper.kt` (CameraX) |
| `ocr/android_ocr.py` (Pyjnius ML Kit) | `ocr/AndroidOcr.kt` (ネイティブ ML Kit) |
| `ocr/skill_matcher.py` | `ocr/SkillMatcher.kt` |

---

## ライセンス

元プロジェクト (`touyo`) に準じます。
