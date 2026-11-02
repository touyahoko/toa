Bluetooth HID Gamepad (実験) パッチ

【できること】
- 設定画面でスイッチをオン → BluetoothHidDevice で汎用 Gamepad として registerApp
- オフ → unregister（接続機能停止）
- 仮想ボタンで HID 入力レポート送信

【できないこと / 制限】
- アプリから Bluetooth Class of Device を任意変更する公式手段はない
- Switch が汎用HIDゲームパッドをコントローラとして認識する保証はない
- 多くの環境では Switch に繋がらない

【上書きファイル】
- app/src/main/AndroidManifest.xml
- app/src/main/kotlin/.../hid/BluetoothHidGamepad.kt （新規）
- app/src/main/kotlin/.../ui/hid/HidControllerSection.kt （新規）
- app/src/main/kotlin/.../ui/settings/SettingsScreen.kt

【手順】
1. 本ZIPを toa リポジトリに展開上書き
2. git commit & push → Actions で APK ビルド
3. 設定タブ → 「Bluetooth HID コントローラ（実験）」をオン
4. 端末のBluetooth設定でペアリング可能なホストと接続を試行

Switch 向けに確実な操作が必要なら USB の NX Macro 互換基板を推奨。
