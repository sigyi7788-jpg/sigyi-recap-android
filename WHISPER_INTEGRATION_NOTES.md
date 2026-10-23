# Whisper.cpp integration notes

- Official repository: https://github.com/ggml-org/whisper.cpp
- Official Android sample: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- Official WASM example: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.wasm
- Official Java binding notes: https://github.com/ggml-org/whisper.cpp/blob/master/bindings/java/README.md

The official C API exposes token timestamps through `whisper_full_get_token_t0`, `whisper_full_get_token_t1`, and `whisper_full_get_token_text`. The official CLI documents experimental word-level output by setting `max_len=1` (`-ml 1`) and `split_on_word=true`. The Android sample builds whisper.cpp through an Android library module with CMake/NDK and loads a GGML model from assets.

This project uses the same native approach, but keeps the multilingual model outside the APK and downloads it on first use to avoid inflating the release APK.
