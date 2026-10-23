# SIGYI Edge TTS setup (Myanmar)

ဒီ Android app က Edge TTS ကို Python companion HTTPS service ကတစ်ဆင့် ခေါ်သုံးပါတယ်။ Edge TTS က Microsoft ရဲ့ online service ကိုသုံးတာဖြစ်လို့ internet လိုအပ်ပြီး unofficial community client ဖြစ်တာကြောင့် availability ပြောင်းလဲနိုင်ပါတယ်။

## Deploy service
1. Project ကို GitHub repository အသစ်ထဲ push လုပ်ပါ။
2. Render dashboard မှာ **New → Blueprint** ကိုရွေးပြီး repository ကိုချိတ်ပါ။ `render.yaml` ကိုဖတ်ပြီး `sigyi-edge-tts` service တည်ဆောက်ပါ။
3. Deploy ပြီးရင် service URL ဥပမာ `https://sigyi-edge-tts.onrender.com` ကိုယူပါ။
4. Browser မှာ `https://YOUR-SERVICE/health` ကိုဖွင့်ပြီး `{"ok":true,...}` ရမရ စစ်ပါ။
5. `app/build.gradle.kts` ထဲက `EDGE_TTS_BASE_URL` မှာ အဲဒီ HTTPS URL ကိုထည့်ပါ။ ဥပမာ:
   `buildConfigField("String", "EDGE_TTS_BASE_URL", "\"https://sigyi-edge-tts.onrender.com\"")`
6. GitHub ကို commit/push လုပ်ပြီး APK ကို build ပြန်လုပ်ပါ။ URL ကို `http://` မသုံးပါနဲ့။

## Voice / render notes
- Default Myanmar voice: `my-MM-NilarNeural`.
- Voice service request rate is bounded to 70–130%; default UI value controls speech rate. Pitch range is bounded.
- Edge TTS output is normalized to mono 24 kHz PCM WAV for predictable duration and Whisper alignment.
- FFmpeg.wasm render currently uses H.264 CRF 18, ultrafast preset (speed-first), AAC 192 kbps / 48 kHz, and preserves input frame rate. Very large files can still fail because WebView/FFmpeg.wasm require substantial RAM; removing the file-size cap does not remove device-memory limits.

## Build APK
Android Studio: Open the `sigyi` folder, allow Gradle sync, then Build → Build Bundle(s) / APK(s) → Build APK(s).
Command line from project root: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Troubleshooting
- `/health` fails: check service deployment/logs.
- TTS returns 502: inspect service logs, internet access, Edge TTS availability and FFmpeg install.
- App says service URL not configured: edit `EDGE_TTS_BASE_URL`, sync and rebuild.
- Render OOM: test a shorter/smaller video; FFmpeg.wasm on Android WebView is memory intensive even with chunked file transfer.
