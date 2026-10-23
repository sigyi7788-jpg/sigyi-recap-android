SIGYI AI Recap Studio — Enhanced 0.2.0
======================================

ဤ Project တွင် Edge TTS ကို Android ထဲက native Myanmar TTS အစား အသုံးပြုရန် companion HTTPS service ပါဝင်သည်။ APK က Edge TTS service ကိုခေါ်သုံးရန် `app/build.gradle.kts` ထဲက `EDGE_TTS_BASE_URL` ကို ကိုယ်တိုင် deploy လုပ်ထားသော HTTPS URL ဖြင့် ပြင်ပြီး APK ကို rebuild လုပ်ရမည်။ Setup အဆင့်များကို `EDGE_TTS_SETUP_MY.md` တွင်ကြည့်ပါ။

Render profile: H.264 CRF 18, ultrafast preset, input frame rate ကိုထိန်းရန် ကြိုးစားခြင်း၊ AAC 192 kbps / 48 kHz။ Output size 45 MB ကန့်သတ်ချက်မရှိတော့ပါ။ Video ကို 0.88x အတင်းနှေးမထားဘဲ 1.0x သဘာဝ speed သုံးထားသည်။ FFmpeg.wasm/WebView RAM ကန့်သတ်ချက်ကြောင့် ဖိုင်အလွန်ကြီးလျှင် render မအောင်မြင်နိုင်သေးသည်။

Edge TTS သည် အင်တာနက်လိုအပ်သော unofficial client ဖြစ်ပြီး service availability အာမခံချက်မရှိပါ။ APK build သို့မဟုတ် ဖုန်းပေါ် real render ကို မစမ်းသပ်ရသေးပါ။

SIGYI Recap Android — စမ်းသပ်ဗားရှင်း 0.1

ဒီဟာက စတင်စမ်းသပ်ဖို့ Android project source ဖြစ်ပြီး အပြီးသတ် Recap App မဟုတ်သေးပါ။

ပါဝင်တာ:
- ဖုန်းထဲက video ရွေးနိုင်မယ်။
- Android Media3 Transformer နဲ့ ဖုန်းထဲမှာ MP4 export လုပ်မယ်။
- Movies/SIGYI folder ထဲသိမ်းမယ်။
- Subtitle မထည့်ထားပါ။
- Video ကို server ဆီ upload မလုပ်ပါ။

မပါသေးတာ:
- Gemini script/translation
- Burmese TTS
- Original audio ကို narration နဲ့အစားထိုးခြင်း၊ scene sync
- 0.88x video / 1.18x narration settings
- သင့်ဖုန်းပေါ်မှာ လက်တွေ့စမ်းသပ်အတည်ပြုခြင်း

ပြင်ဆင်ထားတာ:
- MainActivity.kt ရဲ့ syntax/brace ပြဿနာကို ပြန်စီပြီး compile-safe ဖြစ်အောင် ပြင်ထားပါတယ်။
- `registerForActivityResult` အတွက် `MainActivity` ကို `ComponentActivity` မှ inherit လုပ်အောင် ပြင်ထားပါတယ်။
- User ပေးထားတဲ့ Kotlin plugin version 2.2.10 သို့ update လုပ်ထားပါတယ်။
- Render ပြီးနောက် temporary cache file ကို Gallery ထဲသို့ copy လုပ်ပြီး cleanup လုပ်ပါတယ်။
- Gallery save မအောင်မြင်လျှင် MediaStore partial entry ကို ဖျက်ပေးပါတယ်။
- Render error/cancel အခြေအနေတွေမှာ button/progress state ကို ပြန်ထားပေးပါတယ်။
- `release` build type ကို ထည့်ထားပြီး `isMinifyEnabled=false` ဖြင့် test release APK ထုတ်ပေးပါတယ်။
- Test release APK ကို CI runner ရဲ့ debug keystore ဖြင့် sign လုပ်ထားသောကြောင့် ဖုန်းမှာ တိုက်ရိုက် install စမ်းနိုင်ပါတယ်။
- ဒီ debug signing key သည် Play Store production release အတွက် မသုံးရပါ။ Production အတွက် private upload keystore နှင့် GitHub Secrets ထည့်ရန်လိုပါတယ်။

ဖုန်းကနေ APK build လုပ်ရန်:
1. ဖုန်း browser မှ GitHub account ဖွင့်ပါ။
2. sigyi-recap-android ဆိုတဲ့ repository အသစ်ဖန်တီးပါ။
3. ဒီ ZIP ထဲက ဖိုင်/ဖိုလ်ဒါအားလုံးကို repository root ထဲ upload လုပ်ပါ။
4. Actions tab ကိုဖွင့်ပြီး Actions ကို enable လုပ်ပါ။
5. Build SIGYI APK workflow ကို Run workflow လုပ်ပါ (သို့) main branch ထဲ push လုပ်ပါ။
6. Workflow run အောင်မြင်ရင် Artifacts အောက်က `sigyi-recap-release-apk` ကို download လုပ်ပါ။
7. Download လုပ်ထားတဲ့ ZIP ကို extract လုပ်ပြီး `app-release.apk` ကို install လုပ်ပါ။ Debug version လိုလျှင် `sigyi-recap-debug-apk` artifact ကို သုံးနိုင်ပါတယ်။

GitHub က APK ကို compile လုပ်ဖို့သာ အသုံးပြုပါတယ်။ Video render ကို ဖုန်းပေါ်မှာပဲ လုပ်ပါတယ်။ API key/password မပို့ပါနဲ့။


Whisper.cpp native integration:
- `whisper/` module ထဲမှာ official whisper.cpp source, ggml CPU backend, Android NDK/CMake build နှင့် JNI bridge ပါပါတယ်။
- `app` module က `implementation(project(":whisper"))` ဖြင့် native library ကို ချိတ်ထားပါတယ်။
- GitHub Actions က Android SDK, `ndk;27.0.12077973` နှင့် `cmake;3.22.1` ကို install ပြီး native APK build လုပ်ပါတယ်။
- `ggml-tiny.bin` multilingual model ကို APK ထဲမထည့်ဘဲ ပထမဆုံး render လုပ်ချိန် `filesDir/whisper-models` ထဲ download လုပ်ပါတယ်။ Model download အတွက် internet လိုပါတယ်။
- JNI bridge က `max_len=1` နှင့် `split_on_word=true` သုံးပြီး `whisper_full_get_token_t0/t1` မှ exact word timestamps ထုတ်ပါတယ်။
- Model file မရလျှင် render ကို fallback duration split မလုပ်ဘဲ Whisper error ပြပြီး ရပ်ပါတယ်။


FFmpeg.wasm subtitle/audio mux implementation:
- Kotlin typed request ကို `FfmpegRenderOptions` ဖြင့် သတ်မှတ်ထားပါတယ်။
- `subtitleCheck` ဖြင့် exact word subtitle burn-in ကို ဖွင့်/ပိတ်နိုင်ပါတယ်။
- Original movie audio ကို အမြဲ mute လုပ်ပြီး Myanmar TTS narration track တစ်ခုတည်းကိုသာ output MP4 ထဲ map လုပ်ပါတယ်။
- `app/src/main/assets/ffmpeg_engine.ts` သည် typed TypeScript source ဖြစ်ပြီး Android WebView runtime အတွက် browser-ready equivalent ကို `ffmpeg_engine.html` ထဲမှာ ထည့်ထားပါတယ်။
- FFmpeg command က multiple WAV segment များကို concat လုပ်ပြီး `-filter_complex`, `-map`, optional `subtitles` filter, `-shortest` နှင့် AAC audio encode ကို အသုံးပြုပါတယ်။


Gemini movie recap module:
- Native Kotlin side မှာ `GeminiClient` က Gemini Files API သို့ video upload, processing state စောင့်ခြင်း၊ typed Burmese TikTok prompt နှင့် JSON recap segments parse လုပ်ခြင်းကို ဆောင်ရွက်ပါတယ်။
- Prompt rules က Myanmar Unicode voice-over, chronological accuracy, no invented details, short spoken segments နှင့် no long copyrighted dialogue ကို ထိန်းထားပါတယ်။
- `app/src/main/assets/gemini_recap.ts` မှာ browser/WebView အတွက် typed Gemini upload, polling, prompt builder နှင့် segment parser ပါပါတယ်။
- API key ကို log မထုတ်ဘဲ device-side request ထဲမှာပဲ သုံးထားပါတယ်။


Security and runtime hardening update:
- Gemini API key ကို Android Keystore-backed `EncryptedSharedPreferences` ထဲမှာ သိမ်းထားပြီး normal plaintext preferences မသုံးတော့ပါ။
- Manifest မှာ `android:allowBackup="false"` နှင့် `android:usesCleartextTraffic="false"` ထည့်ထားပါတယ်။
- WebView မှာ content access, file-to-file universal access, mixed content navigation ကို ပိတ်ထားပြီး asset page အပြင် navigation ကို block လုပ်ပါတယ်။
- Whisper `ggml-tiny.bin` model ကို official SHA-256 checksum verify ပြီးမှ rename/use လုပ်ပါတယ်။ Corrupt model ဖြစ်ရင် delete ပြီး download ပြန်လုပ်ပါတယ်။
- FFmpeg `amix` filter မှာ `duration=shortest` နှင့် `normalize=0` သုံးထားပါတယ်။
- Video input, WAV input နှင့် output MP4 ကို JavaScript bridge မှာ 512 KB chunks နဲ့ native cache files အဖြစ် ဖတ်/ရေးပါတယ်။ Bulk `evaluateJavascript()` Base64 video transfer မသုံးတော့ပါ။
