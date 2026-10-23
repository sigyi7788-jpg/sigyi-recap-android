# SIGYI AI Recap Studio

## 1. Project overview

SIGYI AI Recap Studio သည် Android native app တစ်ခုဖြစ်ပြီး user ရွေးထားသော movie video ကို Gemini ဖြင့် ခွဲခြမ်းကာ မြန်မာ TikTok recap script ထုတ်ပေးသည်။ Script ကို Android Myanmar TTS ဖြင့် segment တစ်ခုချင်း audio ထုတ်ပြီး Whisper.cpp ဖြင့် exact word timestamp ရှာသည်။ နောက်ဆုံးတွင် WebView ထဲရှိ FFmpeg.wasm က video, Myanmar narration တစ်ခုတည်းနှင့် optional word-level subtitle ကို MP4 အဖြစ် render လုပ်ပြီး `Movies/SIGYI` ထဲသိမ်းသည်။ Original movie audio ကို output ထဲသို့ မmap လုပ်ဘဲ အမြဲ mute ထားသည်။

အဓိက processing ကို device ပေါ်မှာလုပ်သည်။ Gemini video analysis အတွက်သာ selected video ကို Gemini Files API သို့ upload လုပ်သည်။ Gemini API key ကို Android Keystore-backed `EncryptedSharedPreferences` ထဲတွင် သိမ်းထားပြီး source log များထဲသို့ မရေးပါ။

## 2. Repository structure

```text
SigyiRecap/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/sigyi/recap/
│       │   ├── MainActivity.kt
│       │   ├── GeminiClient.kt
│       │   ├── GeminiRecapModels.kt
│       │   ├── RecapModels.kt
│       │   ├── MyanmarTtsEngine.kt
│       │   ├── FfmpegRenderModels.kt
│       │   ├── FfmpegWasmRenderer.kt
│       │   └── ...
│       ├── res/layout/activity_main.xml
│       └── assets/
│           ├── gemini_recap.ts
│           ├── ffmpeg_engine.ts
│           └── ffmpeg_engine.html
├── whisper/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/sigyi/recap/whisper/
│       │   ├── WhisperNative.kt
│       │   ├── WhisperModelManager.kt
│       │   └── WhisperWordAligner.kt
│       └── jni/whisper/
│           ├── CMakeLists.txt
│           ├── whisper.cpp
│           ├── whisper.h
│           ├── whisper_jni.cpp
│           └── ggml/
├── .github/workflows/build-apk.yml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README-MY.txt
```

`README-MY.txt` ၏ အစပိုင်းသည် starter project မှတ်တမ်းဟောင်းဖြစ်ပြီး နောက်ဆုံး section များတွင် Gemini, Whisper, TTS နှင့် FFmpeg implementation အခြေအနေကို update လုပ်ထားသည်။ အပြည့်အစုံ architecture ကို ဒီ document ကို source of truth အဖြစ်သုံးပါ။

## 3. Gradle modules

### `:app`

`app` သည် install လုပ်မည့် Android application module ဖြစ်သည်။ Application id သည် `com.sigyi.recap` ဖြစ်ပြီး `minSdk = 26`, `targetSdk = 35`, `compileSdk = 36` သတ်မှတ်ထားသည်။ Java/Kotlin target သည် 17 ဖြစ်သည်။ `:whisper` ကို project dependency အဖြစ်သုံးပြီး AndroidX Activity/Core နှင့် Media3 Transformer dependencies ပါသည်။

### `:whisper`

`whisper` သည် Android library module ဖြစ်သည်။ Official whisper.cpp နှင့် ggml CPU backend ကို local native source အဖြစ် compile လုပ်သည်။ Native build သည် Android NDK `27.0.12077973` နှင့် CMake `3.22.1` သုံးသည်။ ABI များမှာ `arm64-v8a`, `armeabi-v7a`, နှင့် `x86_64` ဖြစ်သည်။

JNI bridge သည် `whisper_full()` ကို `max_len = 1` နှင့် `split_on_word = true` ဖြင့် run ပြီး `whisper_full_get_token_t0()`, `whisper_full_get_token_t1()`, `whisper_full_get_token_text()` မှ word/token timestamps ပြန်ပေးသည်။

## 4. End-to-end data flow

### Step A — Video import

`MainActivity` က `ActivityResultContracts.OpenDocument()` ဖြင့် `video/*` URI ရယူသည်။ Persistable read permission ရရှိပါက device restart ပြီးနောက်လည်း URI ကို ဖတ်နိုင်သည်။

### Step B — Gemini script generation

`GeminiClient` သည် selected URI ကို temporary file အဖြစ်သိမ်းပြီး Gemini Files API သို့ multipart upload လုပ်သည်။ Upload ပြီးနောက် file state ကို poll လုပ်ပြီး `ACTIVE` ဖြစ်မှ `generateContent` ကိုခေါ်သည်။

`GeminiRecapPrompt` သည် အောက်ပါ output contract ကိုတောင်းသည်။

```json
[
  {
    "text": "မြန်မာ narration segment",
    "source_start_ms": 0,
    "source_end_ms": 5000
  }
]
```

Response ထဲတွင် markdown fence ပါလာလျှင် JSON array အစနှင့်အဆုံးကိုရှာပြီး parse လုပ်သည်။ Empty segment သို့မဟုတ် invalid JSON ဖြစ်လျှင် render မဆက်ဘဲ error ပြသည်။

### Step C — Myanmar TTS

`MyanmarTtsEngine` သည် Android `TextToSpeech` engine ကို `Locale("my", "MM")` ဖြင့် configure လုပ်သည်။ UI ရှိ speed slider နှင့် pitch slider တန်ဖိုးများကို `setSpeechRate()` နှင့် `setPitch()` သို့ ပြောင်းပေးသည်။ Segment တစ်ခုချင်း WAV file ထုတ်ပြီး WAV header မှ audio duration တွက်သည်။ ထို duration များဖြင့် narration timeline ကို တည်ဆောက်သည်။

### Step D — Whisper word alignment

`WhisperModelManager` သည် `ggml-tiny.bin` multilingual model ကို APK ထဲမထည့်ဘဲ first use တွင် download လုပ်သည်။ File ကို `filesDir/whisper-models/ggml-tiny.bin` ထဲ atomic temporary-file rename ဖြင့်သိမ်းပြီး official file SHA-256 checksum ကို verify လုပ်မှသာ အသုံးပြုသည်။

`WhisperWordAligner` သည် TTS WAV ကို mono 16 kHz Float32 PCM သို့ convert ပြီး native Whisper context ထဲပို့သည်။ Whisper က ပြန်ပေးသော relative timestamps များကို `NarrationSegment.startMs` နှင့်ပေါင်းကာ video-global word timestamps ပြောင်းသည်။

### Step E — FFmpeg.wasm render

`FfmpegWasmRenderer` သည် video/audio files ကို app cache ထဲ staging လုပ်ပြီး WebView JavaScript bridge မှ 512 KB chunks နဲ့ ဖတ်ခိုင်းသည်။ Subtitle metadata သာ JSON အဖြစ်ပို့ပြီး video/audio binary အကြီးစား Base64 string ကို `evaluateJavascript()` ထဲ မထည့်တော့ပါ။ WebView က CDN မှ FFmpeg.wasm core ကို load ပြီး virtual filesystem ထဲ file များရေးသည်။ Output MP4 ကိုလည်း 512 KB chunks နဲ့ native cache output file ထဲပြန်ရေးသည်။

Audio segment များကို `filter_complex concat` ဖြင့် narration track တစ်ခုအဖြစ်ပေါင်းသည်။ Subtitle checkbox ဖွင့်ထားပါက Whisper word timestamps ဖြင့် SRT ပြုလုပ်ပြီး `subtitles=subtitle.srt` video filter ဖြင့် burn-in လုပ်သည်။ FFmpeg command သည် `-map 0:v:0` နှင့် `[narration]` ကိုသာ map လုပ်သောကြောင့် original movie audio မပါဝင်ပါ။

### Step F — Save output

FFmpeg output MP4 ကို WebView bridge မှ 512 KB chunks ဖြင့် native cache output file ထဲရေးပြီး `MediaStore` မှတစ်ဆင့် `Movies/SIGYI/SIGYI_<timestamp>.mp4` သို့ stream copy လုပ်သည်။ `IS_PENDING = 0` ဖြင့် Gallery တွင် visible ဖြစ်စေပြီး save fail ဖြစ်လျှင် partial MediaStore row ကို ဖျက်သည်။

## 5. Local build prerequisites

Local Android build အတွက် အောက်ပါတွေလိုသည်။

```text
JDK 17
Android SDK platform 36
Android build-tools 35.0.0
Android NDK 27.0.12077973
CMake 3.22.1
Gradle 8.11.1 or compatible Gradle wrapper
```

Environment example:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Android SDK packages install example:

```bash
sdkmanager --licenses
sdkmanager \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "ndk;27.0.12077973" \
  "cmake;3.22.1"
```

## 6. Local Gradle commands

Project root မှ run ပါ။

```bash
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:assembleRelease
```

Whisper module ကို သီးခြားစစ်ရန်:

```bash
gradle --no-daemon :whisper:assembleDebug
gradle --no-daemon :whisper:externalNativeBuildDebug
```

APK output paths:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

လက်ရှိ release build သည် installable test release ဖြစ်ပြီး debug signing key ဖြင့် sign လုပ်ထားသည်။ Play Store production အတွက် private upload keystore နှင့် release signing configuration သီးခြားထည့်ရမည်။

## 7. GitHub Actions build

`.github/workflows/build-apk.yml` သည် `main` branch push သို့မဟုတ် manual `workflow_dispatch` ဖြင့် run သည်။ Workflow အဆင့်များမှာ JDK 17 setup, Android SDK setup, SDK license acceptance, Android platform/build-tools/NDK/CMake install, Gradle setup နှင့် debug/release assemble ဖြစ်သည်။

Repository upload လုပ်ပြီးနောက်:

1. GitHub repository root တွင် ZIP အတွင်းရှိ `sigyi` folder contents အားလုံးကို upload လုပ်ပါ။
2. `Actions` tab ထဲဝင်ပါ။
3. `Build SIGYI APKs` workflow ကို `Run workflow` လုပ်ပါ သို့မဟုတ် `main` သို့ push လုပ်ပါ။
4. Run ပြီးလျှင် `sigyi-recap-release-apk` artifact ကို download လုပ်ပါ။
5. ZIP ကို extract လုပ်ပြီး `app-release.apk` install လုပ်ပါ။

Debug artifact သည် `sigyi-recap-debug-apk` ဖြစ်သည်။ Native build သည် NDK/CMake ကြောင့် pure Kotlin app ထက် အချိန်ပိုယူနိုင်သည်။

## 8. First-run requirements

ပထမဆုံးအသုံးပြုချိန်တွင် Gemini API key ထည့်ရန်လိုသည်။ Gemini script generation အတွက် internet လိုသည်။ FFmpeg.wasm core ကို CDN မှ load လုပ်သောကြောင့် render engine ပထမဆုံးအသုံးပြုချိန်တွင် internet လိုသည်။ Whisper tiny model ကိုလည်း first alignment တွင် download လုပ်သောကြောင့် model download ပြီးသည်အထိ app ကို မပိတ်သင့်ပါ။

Android device တွင် Burmese TTS language data မရှိပါက `Myanmar TTS voice မရှိသေးပါ` error ပြမည်။ Device ရှိ TTS engine ထဲတွင် Burmese voice install လုပ်ပြီးမှ render လုပ်ရမည်။

Video input ကို native cache file သို့ copy လုပ်ပြီး chunked bridge သုံးသောကြောင့် bulk JavaScript string limit ကို ရှောင်ထားသည်။ လက်ရှိ staging safety limit သည် 512 MB ဖြစ်ပြီး low-memory devices တွင် real-device စမ်းသပ်ရန်လိုသည်။

## 9. Troubleshooting

### `SDK location not found`

`ANDROID_HOME` သတ်မှတ်ထားခြင်းနှင့် `sdkmanager` မှ platform 36, build-tools 35.0.0, NDK 27.0.12077973, CMake 3.22.1 install ဖြစ်ခြင်းကိုစစ်ပါ။ GitHub Actions တွင် setup step ရှိပြီးသားဖြစ်သည်။

### `CMake not found` သို့မဟုတ် `NDK not found`

Android Studio SDK Manager ထဲတွင် SDK Tools tab မှ NDK side by side နှင့် CMake ကို install လုပ်ပြီး Gradle sync ပြန်လုပ်ပါ။ NDK version သည် `whisper/build.gradle.kts` ထဲက version နှင့် တူရမည်။

### `FFmpeg.wasm engine မ ready`

Internet connection, WebView JavaScript setting, CDN access ကိုစစ်ပါ။ `ffmpeg_engine.html` သည် `@ffmpeg/ffmpeg`, `@ffmpeg/util`, နှင့် `@ffmpeg/core` CDN assets သုံးထားသည်။

### `Whisper model download failed`

Model download အတွက် internet နှင့် device storage ကိုစစ်ပါ။ `filesDir/whisper-models/ggml-tiny.bin.part` ဖိုင်က download ပျက်ပြီးကျန်နေပါက app data ထဲမှ temporary file ဖျက်ပြီး render ပြန်လုပ်ပါ။

### `Myanmar TTS voice မရပါ`

Android Text-to-Speech settings ထဲတွင် Burmese language data ရှိသည့် engine ကိုရွေးပါ။ TTS engine မထောက်ပံ့ပါက app သည် exact alignment မလုပ်ဘဲ မရပ်ဘဲ error ပြမည်။

### Original audio မပါဘဲ TTS voice-only output

လက်ရှိ design တွင် original audio ကို မည်သည့်အခြေအနေမှာမျှ map မလုပ်ပါ။ FFmpeg output သည် video stream နှင့် concatenated Myanmar narration stream တစ်ခုတည်းဖြစ်သည်။ ဒါကြောင့် `amix`/original-audio-track မရှိခြင်းသည် error မဟုတ်ပါ။

## 10. Production hardening checklist

Production မထုတ်မီ release keystore ထည့်ရန်၊ API key အတွက် Keystore-backed encrypted storage ထည့်ပြီးဖြစ်သော်လည်း backend proxy သုံးသင့်/မသုံးသင့်ကို ပြန်စဉ်းစားရန်နှင့် FFmpeg CDN assets ကို local bundle/cache လုပ်ရန်လိုသည်။ Whisper model checksum verification, chunked native file bridge, TTS-only audio mapping နှင့် cleartext/backup hardening ကို ထည့်ပြီးဖြစ်သည်။

လက်ရှိ project သည် working prototype architecture ဖြစ်ပြီး real device matrix, low-memory devices, long videos, background cancellation နှင့် Play Store policy review များကို ထပ်မံစမ်းသပ်ရန်လိုသည်။ Chunked bridge သည် evaluateJavascript bulk payload ပြဿနာကို ဖြေရှင်းပေးသော်လည်း FFmpeg.wasm ၏ in-memory `writeFile/readFile` ကြောင့် very large video အတွက် native Media3/FFmpeg pipeline ကို နောက်တစ်ဆင့်စဉ်းစားသင့်သည်။
