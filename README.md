# MHXX RNG Tool (Android)

モンスターハンターダブルクロス（MHXX）向けの **護石乱数・鑑定・調合位置特定** ネイティブ Android アプリです。

鑑定認識は [mhxx-renkin-reader](https://github.com/skst328/mhxx-renkin-reader)（錬金リーダー）と同じ **テンプレート照合** を使います（OCR による文字読み取りは使いません）。

| 項目 | 内容 |
|------|------|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 最小 SDK | Android 7.0 (API 24) |
| ターゲット SDK | API 32 |
| 鑑定 | 錬金リーダー方式（OpenCV テンプレート照合 + 公式 WASM 保険） |
| 乱数 | MHXX XorShift 系エンジン（種類: 風化 / 古び / 光る / なぞ） |

---

## 機能タブ

| タブ | 内容 |
|------|------|
| **検索** | スキル・ポイント・スロット条件でお守りを検索。結果タップでスナイプ時間をタイマーへ反映 |
| **周辺** | 指定フレーム周辺のお守り一覧 |
| **調合** | 調合カウンター動画から数値変化を読み取り、現在フレームを推定 |
| **位置** | フレーム位置まわりの補助表示 |
| **狙い目** | クエスト報酬・マカ錬金まわりの有効フレーム計算 |
| **鑑定** | ギャラリー／カメラ画像から護石を読み取り → 検索条件に反映。完全一致フレームがあれば表示 |
| **タイマー** | 通常スナイプ待機時間・コンテニュー連打法のカウント／メトロノーム |

種類チップ（風化・古び・光る・なぞ）は全タブ共通です。

---

## 鑑定について（重要）

### 認識方式

1. 画像を **1280×720** 相当に位置合わせ（reference 画像／フォールバックで拡縮）
2. トップハット前処理 + **NCC テンプレート照合**
3. スキル名・ポイント・スロットを見本 PNG と照合
4. ローカル照合が空のときのみ **錬金リーダー互換 WASM** を保険実行

### 対応スキル

- **マカフシギ錬金で出る公式 134 スキル**（サイト版錬金リーダーと同じ範囲）
- 見本: `app/src/main/assets/renkin/skills/{スキルID}.png`
- スロット: `○` / `---` の見本照合（0〜3）

サイト版で読めないスキル（例: 精密射撃・採取など公式134外）は、本アプリでも対象外です。

### 推奨入力

| 入力 | 推奨度 |
|------|--------|
| Switch 本体スクショ（1280×720 前後・16:9） | ◎ |
| 録画からの切り出し（画角が公式に近い） | ○ |
| スマホでテレビ画面を斜め撮影 | △（位置合わせに依存） |

写真は端末内のみで処理し、外部送信しません。

---

## スナイプ時間の目安

検索結果のお守りをタップすると、タイマーに自動入力されます。

- **通常スナイプ**: 残りフレーム ÷ 30 秒前後の待機
- **コンテニュー連打法**: 長い待ちを連打回数 + 余り待機に分解

（実装・表示はアプリ内タイマー／検索連携を参照）

---

## GitHub Actions で APK を入手

`main` に push すると Debug APK が自動ビルドされます。

1. GitHub リポジトリの **Actions** → **Build Debug APK**
2. 最新の成功ワークフローを開く
3. **Artifacts** から `mhxx-rng-tool-debug-…` をダウンロード
4. 中の `app-debug.apk` を端末に入れてインストール

タグ（例: `v1.0.0`）を push すると Release 用 APK も生成される構成です。

---

## ローカルビルド

### 必要環境

- JDK 17+
- Android SDK（API 32 以上のビルドツール）

### ビルド

```bash
./gradlew assembleDebug
```

出力: `app/build/outputs/apk/debug/app-debug.apk`

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## プロジェクト構成（抜粋）

```
app/src/main/
├── assets/renkin/
│   ├── skills/          # 公式 134 スキル見本
│   ├── digits/          # ポイント数字見本
│   ├── slots/           # ○ / --- 見本
│   ├── label.png        # パネル位置合わせ用
│   └── reference.png    # 画面位置合わせ用
├── assets/renkin_reader/  # WASM（保険）
└── kotlin/org/mhxxtools/mhxxrngtool/
    ├── MainActivity.kt
    ├── rng/               # 乱数エンジン・スキル名・検索
    ├── ocr/
    │   ├── RenkinSwitchReader.kt   # テンプレ照合（本体）
    │   ├── HybridCharmReader.kt    # ローカル → WASM
    │   ├── RenkinWasmBridge.kt
    │   └── OpenCvBridge.kt
    └── ui/
        ├── MainScreen.kt
        ├── search / around / combo / reward / aimpoint
        ├── ocr/           # 鑑定画面
        └── timer/
```

---

## 関連リンク

- 鑑定エンジン参考: [mhxx-renkin-reader](https://github.com/skst328/mhxx-renkin-reader)  
  サイト: https://skst328.github.io/mhxx-renkin-reader/
- 本リポジトリ: https://github.com/touyahoko/toa

---

## 注意

- 本ツールは非公式の有志ツールです。ゲームの利用規約の範囲で自己責任で使用してください。
- 鑑定の精度は画角・解像度・圧縮に左右されます。公式に近い Switch スクショを推奨します。
- 映像キャプチャ専用タブや Arduino コントローラ UI は、現行ビルドでは含みません。

---

## ライセンス・第三者

OpenCV 等の第三者ライセンスは、錬金リーダー側の表記およびプロジェクト内のライセンス表記に従ってください。
