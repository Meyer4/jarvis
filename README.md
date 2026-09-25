# JarvisAI

**An on-device-first, always-available personal assistant for Android.** JarvisAI keeps speech recognition, wake-word detection, conversation memory, and optional GGUF inference on the phone. Cloud inference is opt-in and only used when a provider key is configured and a validated internet connection is available.

> **Model weights are not checked into this repository.** They are large, separately licensed files. The app detects missing models, explains what is needed, and includes an in-app importer that stores files in app-private storage.

## Features

- **Wake word:** streaming openWakeWord ONNX pipeline (`melspectrogram.onnx` → `embedding_model.onnx` → Hey Jarvis classifier) on a dedicated, low-priority inference thread.
- **Speech recognition:** Sherpa-ONNX SenseVoice or Whisper, with 16 kHz mono capture, pre-roll, and automatic end-of-speech silence detection.
- **Speech output:** Android System TextToSpeech with an adjustable speech-rate control.
- **Hybrid LLM routing:** try a local llama.cpp GGUF first; when unavailable or inference fails, use configured OpenAI or Anthropic APIs if online.
- **Private memory:** Room/SQLite keeps recent conversation turns on device.
- **Air Touch:** CameraX front camera + MediaPipe Hand Landmarker, low-pass filtered index-finger-tip cursor, pinch-to-talk, fist-to-hide, and open-palm-to-show shortcuts.
- **System tools:** allowlisted app launch, clock alarms and timers, battery status, media volume, flashlight, and web search. The assistant never runs shell commands or executes arbitrary model-generated code.
- **Background operation:** microphone and camera foreground services, persistent notifications, a boot receiver, and a user-triggered battery-optimization exemption flow.
- **Model setup UI:** imports `.onnx`, `.task`, `.gguf`, tokenizer, and related model files into private app storage and reports readiness for each engine.

## Architecture

```text
┌──────────────────────────────────────────────────────────────────────┐
│ MainActivity: permissions · model importer · status · speed control │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ explicit service actions
          ┌───────────────────┴────────────────────┐
          ▼                                        ▼
┌──────────────────────┐                ┌─────────────────────────────┐
│ JarvisForeground     │                │ AirTouchService             │
│ Service (mic FGS)    │                │ (camera FGS + overlay)      │
└──────────┬───────────┘                └─────────────┬───────────────┘
           │                                          │ pinch-to-talk
           ▼                                          ▼
┌───────────────────┐   ┌──────────────────────┐   ┌───────────────────┐
│ WakeWordEngine    │──▶│ SpeechToTextEngine   │──▶│ ToolRegistry      │
│ openWakeWord/ORT  │   │ Sherpa-ONNX         │   │ safe native tools │
└───────────────────┘   └──────────┬───────────┘   └─────────┬─────────┘
                                   │                         │ if no tool
                                   ▼                         ▼
                         ┌─────────────────────────────────────────┐
                         │ LLMRouter                               │
                         │ llama.cpp GGUF → OpenAI/Anthropic       │
                         └────────────────┬────────────────────────┘
                                          │
                            ┌─────────────┴────────────┐
                            ▼                          ▼
                     Room / SQLite                 TTSEngine
                   local conversation memory      Android System TTS
```

## Requirements

- Android Studio with JDK 17, Android SDK platform 36 (the app targets API 35), and Gradle 8.13 (the wrapper is included).
- Android 8.0 / API 26 or newer. Native inference dependencies currently target `arm64-v8a` and `x86_64`.
- For local GGUF inference, use a device with ample free memory. Quantized 1B–3B models can still take hundreds of MB to multiple GB of RAM and storage; performance depends on the device.
- A supported Android TTS voice for spoken replies.

## Build and run

```bash
git clone https://github.com/Meyer4/jarvis.git
cd jarvis
cp local.properties.example local.properties
# Add sdk.dir if Android Studio has not already created it.
# Optional: add OPENAI_API_KEY and/or ANTHROPIC_API_KEY to local.properties.
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Open the root folder in Android Studio to run or debug it. Gradle downloads the pinned Android libraries, Sherpa-ONNX AAR from JitPack, and llama.cpp Android binding from Maven Central. CI builds the debug APK on pushes and pull requests.

API keys can instead be supplied as `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` environment variables. **Never commit `local.properties`, API keys, signing keys, model files, or generated APKs.** `local.properties` is git-ignored and `local.properties.example` is safe to copy. Keys supplied this way are compiled into the APK and can be extracted; use blank keys for public builds and avoid shipping an APK that embeds a personal key.

## Download and install models

Models may be installed either by selecting files in **Import models** inside JarvisAI, or by placing them flat in `app/src/main/assets/` or `app/src/main/assets/models/` before building. Bundled assets are copied to the app's private `files/models/` directory on first run. For most users, in-app import is recommended. Files remain local and are not uploaded by the app.

### 1. openWakeWord

Use the official [openWakeWord project](https://github.com/dscripka/openWakeWord) and its model downloader to obtain the shared feature models and a Hey Jarvis model. The upstream classifier is commonly named `hey_jarvis_v0.1.onnx`; JarvisAI recognizes that name and stores it as `jarvis_wake_word.onnx`.

```bash
python -m pip install openwakeword
python - <<'PY'
from openwakeword.utils import download_models
download_models(model_names=["hey_jarvis_v0.1"])
print("Models downloaded. Locate melspectrogram.onnx, embedding_model.onnx, and hey_jarvis_v0.1.onnx in the openwakeword package model directory.")
PY
```

Import these **three** files:

- `melspectrogram.onnx`
- `embedding_model.onnx`
- `hey_jarvis_v0.1.onnx` (or rename to `jarvis_wake_word.onnx`)

All three must come from compatible openWakeWord releases. This is an openWakeWord model pipeline, not a single raw-waveform classifier.

### 2. Sherpa-ONNX speech recognition

Download an English-capable SenseVoice int8 model and its `tokens.txt` from the [Sherpa-ONNX model documentation](https://k2-fsa.github.io/sherpa/onnx/). Import `model.int8.onnx` and `tokens.txt` together. SenseVoice is the recommended compact setup.

Whisper is also supported. Import a matching encoder, decoder, and tokenizer (for example `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, and `small-tokens.txt`). Keep the same filename prefix so JarvisAI can pair the files. Do not mix files from different model releases.

### 3. Local GGUF model

Download a quantized `.gguf` compatible with llama.cpp. For example, select an appropriate Q4 quantization of Llama 3.2 3B Instruct or Phi-3 Mini from a trusted publisher such as [Hugging Face](https://huggingface.co/models?search=GGUF). Model licenses and size vary; check each model card. Import one GGUF at a time. A 2K token context is used to limit memory use on mobile.

### 4. MediaPipe hand landmarker

Download the official `hand_landmarker.task` model from Google's [MediaPipe model page](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker). Import it to enable Air Touch. The app uses only the front camera, locally, while the Air Touch foreground service is active.

### Model diagnostics

The dashboard shows whether wake word, STT, local GGUF, and gesture models are ready. Missing weights do not crash the app. If no wake model is installed, **Talk now** still starts a manually requested utterance after STT files are installed. If no GGUF is installed, online fallback is available only when an API key is configured and Android reports validated internet.

## Permissions and privacy walkthrough

1. **Microphone:** requested when you start always-on listening or tap **Talk now**. Android shows a persistent foreground notification while listening. Microphone capture pauses while a command is processed and spoken.
2. **Notifications:** requested on Android 13 and later so the foreground service can remain visible. Denying notification permission does not grant the app permission to hide Android's foreground-service disclosure.
3. **Camera:** requested only when Air Touch is enabled. Frames are analyzed on device and are not saved or sent to a server.
4. **Display over other apps:** requested separately by Android so the cursor can float above apps. The cursor overlay is non-touchable and does not inject system taps. **Pinch-to-talk** is the supported global shortcut; Android protects cross-app click injection behind Accessibility APIs, which JarvisAI does not request.
5. **Battery optimization:** optional and user initiated. Continuous microphone work can increase battery use; the exemption is not enabled silently.
6. **Network:** only used for configured cloud APIs or web searches. API keys are build-time `BuildConfig` values from environment variables or ignored `local.properties`; the app contains no default keys.

### Android 14/15 background restrictions

Android restricts starting microphone foreground services from a boot receiver, and Android 15 enforces this for apps targeting API 35. On those versions, boot completion posts a **Tap to resume** notification instead of trying an illegal background microphone start. On older Android versions, the receiver starts listening when the user previously enabled it and microphone permission remains granted. Always-on listening must be started from the visible app UI after permission approval.

## Screenshots

Screenshots are welcome in `docs/screenshots/`. Add them here when captured on a real device or emulator.

| Dashboard | Air Touch | Voice interaction |
| --- | --- | --- |
| _Coming soon_ | _Coming soon_ | _Coming soon_ |

## Development notes

- Kotlin 2.x, AGP 8.x, target SDK 35, min SDK 26.
- Engines and services are separated by package; microphone, model loading, database work, network calls, camera analysis, and inference run away from the main thread.
- System actions are allowlisted and triggered only by explicit recognized user phrases. Cloud models cannot directly invoke Android actions.
- Model and API service failures surface as dashboard/service status instead of terminating the app.
- Unit tests cover timer/alarm parsing. Run them with `./gradlew testDebugUnitTest`.

## License

JarvisAI source code is licensed under the [MIT License](LICENSE). Model weights and third-party libraries retain their own licenses; see their respective model cards and project licenses before redistribution.
