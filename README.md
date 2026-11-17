# VoiceMatch Studio — Android APK Source Project

Dự án Android Native (Kotlin, MinSDK 26 / TargetSDK 35) thực hiện:
1. **Tách âm thanh trực tiếp từ Video Clip giọng nữ** bằng `MediaExtractor` + `MediaCodec` (không cần thư viện ngoài nặng nề) và đo tần số cơ bản `F0` bằng thuật toán Autocorrelation (`VideoPitchExtractor.kt`).
2. **Thu âm giọng nói của bạn** ở định dạng PCM 16-bit 44.1kHz (`AudioRecord`).
3. **Chuyển đổi sang tông giọng nữ của Video** bằng thuật toán **Dual-Head Hann-Windowed Granular Pitch Shifter** kết hợp lọc bỏ âm ngực nam giới (`High-Pass 185Hz`) và kích thích hài âm sắc nữ (`Formant Exciter`) trong `VoiceConverterDsp.kt`.
4. **Phát trực tiếp & xuất file `.wav`** vào bộ nhớ máy.

---

## Cách xuất ra file `.apk` cài đặt ngay

### Cách 1: Build tự động trên đám mây với GitHub Actions (Không cần cài Android Studio)
Dự án đã tích hợp sẵn workflow `.github/workflows/build-apk.yml` (tương tự cấu hình CloudBuild quen thuộc của bạn):
1. Đẩy thư mục này lên một GitHub Repository mới.
2. Vào tab **Actions** -> chọn workflow **Build VoiceMatch Studio APK** -> bấm **Run workflow**.
3. Sau khoảng 1–2 phút, tải file **`app-debug.apk`** đã ký sẵn trong mục **Artifacts** về điện thoại Nothing Phone (2a) Plus hoặc Xiaomi Pad 6 và cài đặt trực tiếp.

### Cách 2: Build trực tiếp trên máy tính của bạn (macOS / Windows / Linux)
Mở Terminal tại thư mục giải nén và chạy:
```bash
gradle assembleDebug
```
File APK đã ký sẵn sẽ nằm tại:
`app/build/outputs/apk/debug/app-debug.apk`

Cài nhanh qua ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
