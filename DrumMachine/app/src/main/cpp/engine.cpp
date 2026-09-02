/*
 * engine.cpp - Motor de áudio do Drummachi baseado em Oboe (C++).
 *
 * Arquitetura (v4):
 *  - Stream de saída com PerformanceMode::LowLatency + SharingMode::Exclusive
 *    (fallback automático para configuração padrão se o dispositivo não
 *    suportar baixa latência).
 *  - O sequenciador roda DENTRO do callback de áudio (onAudioReady): as
 *    batidas são agendadas por posição ABSOLUTA de frame, o que dá precisão
 *    de 1 amostra (~20µs a 48kHz) — sem thread, sem Thread.sleep, sem jitter.
 *  - Mixer com pool de 8 vozes (retrigger e sobreposição do mesmo som).
 *  - Sons sintetizados proceduralmente na taxa de amostragem do stream.
 *
 * Exposed via JNI para com.drummachi.DrumEngine (Kotlin).
 */

#include <oboe/Oboe.h>
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <random>
#include <vector>

#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>

#define LOG_TAG "Drummachi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace oboe;

// ---------------------------------------------------------------------------
// Sons sintetizados (float, [-1, 1])
// ---------------------------------------------------------------------------
static int g_sampleRate = 48000;
static std::vector<float> g_sounds[7]; // 0=kick 1=snare 2=hat 3=tom_ft 4=tom_mt 5=tom_ht 6=crash

// v4.7: ganho individual por peça (mixer) — default 1.0, range 0.0..2.0
static std::atomic<float> g_gains[7] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
// v4.7: TONE por peça — 0.0 (natural) .. 1.0 (mais agudo). Filtro passa-alta 1ª ordem.
static std::atomic<float> g_tones[7] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

static void renderKick() {
    const double startFreq = 150.0, endFreq = 50.0;
    const double sweepSamples = g_sampleRate * 0.1;
    const int n = static_cast<int>(g_sampleRate * 0.3);
    auto &out = g_sounds[0];
    out.resize(n);
    double phase = 0.0;
    for (int i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / g_sampleRate;
        const double freq = (i < sweepSamples)
            ? startFreq + (endFreq - startFreq) * (i / sweepSamples)
            : endFreq;
        phase += 2.0 * M_PI * freq / g_sampleRate;
        const double env = exp(-t / 0.08);
        out[i] = static_cast<float>(sin(phase) * env);
    }
}

static void renderSnare() {
    const int n = static_cast<int>(g_sampleRate * 0.25);
    auto &out = g_sounds[1];
    out.resize(n);
    std::mt19937 rng(12345);
    std::uniform_real_distribution<double> uni(-1.0, 1.0);
    double phase = 0.0;
    for (int i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / g_sampleRate;
        const double noise = uni(rng);
        phase += 2.0 * M_PI * 200.0 / g_sampleRate;
        const double tone = sin(phase);
        const double envN = exp(-t / 0.12);
        const double envT = exp(-t / 0.15);
        out[i] = static_cast<float>(noise * envN * 0.7 + tone * envT * 0.3);
    }
}

static void renderHat() {
    const int n = static_cast<int>(g_sampleRate * 0.05);
    auto &out = g_sounds[2];
    out.resize(n);
    std::mt19937 rng(67890);
    std::uniform_real_distribution<double> uni(-1.0, 1.0);
    double prev = 0.0;
    for (int i = 0; i < n; i++) {
        const double t = static_cast<double>(i) / g_sampleRate;
        const double noise = uni(rng);
        const double hp = noise - prev; // passa-alta de 1ª ordem
        prev = noise;
        const double env = exp(-t / 0.02);
        out[i] = static_cast<float>(hp * env);
    }
}

static void renderSounds() {
    renderKick();
    renderSnare();
    renderHat();
}

// ---------------------------------------------------------------------------
// Vozes (pool de 8 para retrigger + sobreposição)
// ---------------------------------------------------------------------------
struct Voice {
    int sound = -1;
    float pos = 0.0f;   // posição em frames; começa negativa se agendada no futuro
    float len = 0.0f;
    bool active = false;
    // v4.7: estado do filtro de TONE (passa-alta 1ª ordem) por voz
    float prevIn = 0.0f;
    float prevOut = 0.0f;
    // v4.9: velocity (humanização) — ganho aplicado no mix
    float gain = 1.0f;
};
static Voice g_voices[16]; // v5.0: pool maior p/ não roubar crash no fim do fill

// v4.7: converte tone (0..1) em coeficiente alpha do passa-alta.
// fc vai de ~60Hz (natural) a ~3.8kHz (bem agudo).
static float toneAlpha(float tone, int sampleRate) {
    if (tone <= 0.0f) return 0.0f; // bypass
    const double fc = 60.0 * pow(10.0, tone * 1.8);
    return static_cast<float>(1.0 / (1.0 + 2.0 * M_PI * fc / sampleRate));
}

static void triggerVoice(int sound, int offsetFrames, float gain = 1.0f) {
    for (auto &v : g_voices) {
        if (!v.active) {
            v.sound = sound;
            v.pos = static_cast<float>(-offsetFrames);
            v.len = static_cast<float>(g_sounds[sound].size());
            v.prevIn = 0.0f;   // v4.7: zera estado do filtro no retrigger
            v.prevOut = 0.0f;
            v.gain = gain;      // v4.9: velocity (humanização)
            v.active = true;
            return;
        }
    }
    // Sem voz livre (v5.0): rouba a voz que falta MENOS para terminar
    // (menor len - pos) e, de preferência, NÃO a do crash (6) — assim o
    // crash disparado no fim de um fill denso não é cortado na próxima nota.
    Voice *best = nullptr;
    for (auto &v : g_voices) {
        if (v.sound == 6) continue; // nunca rouba o crash se houver alternativa
        if (!best || (v.len - v.pos) < (best->len - best->pos)) best = &v;
    }
    if (!best) { // todas as vozes ativas são crash: rouba a mais antiga
        best = &g_voices[0];
    }
    auto &v = *best;
    v.sound = sound;
    v.pos = static_cast<float>(-offsetFrames);
    v.len = static_cast<float>(g_sounds[sound].size());
    v.prevIn = 0.0f;
    v.prevOut = 0.0f;
    v.gain = gain;
    v.active = true;
}

static void clearVoices() {
    for (auto &v : g_voices) v.active = false;
}

// ---------------------------------------------------------------------------
// Estado do sequenciador (lido/escrito na thread de áudio; parâmetros via atomic)
// ---------------------------------------------------------------------------
static std::atomic<int> g_bpm{120};
static std::atomic<int> g_part{0};              // 0 = VERSO, 1 = REFRÃO (parte ATIVA)
static std::atomic<int> g_partPending{-1};      // v4.5: troca de parte agendada p/ próximo compasso (-1 = nenhuma)
static std::atomic<bool> g_fillPending{false};
static std::atomic<bool> g_running{false};
static std::atomic<int> g_oneShotPending{-1}; // v4.8: crash/one-shot imediato

// v5.0: fórmula de compasso. Padrão 4/4 (16 semicolcheias, 4 batidas).
// Estilos 6/8 (blues/shuffle/swing): 12 semicolcheias, 6 batidas (colcheias).
static std::atomic<int> g_stepsPerBar{16};  // semicolcheias por compasso
static std::atomic<int> g_beatsPerBar{4};   // batidas (postBeat) por compasso

// v4.9: padrões com VELOCITY (0-127 por semicolcheia). 3 partes × 7 peças × 16 passos.
// [part][sound][step] — 0 = silêncio, 1-127 = velocity.
static std::atomic<uint8_t> g_vel[3][7][16];

// v4.9: inicializa os padrões com os grooves originais (todos com velocity 127).
static void initPatterns() {
    // verse: kick 0,8 | snare 4,12 | hat colcheias
    static const uint8_t vK[16] = {127,0,0,0,0,0,0,0,127,0,0,0,0,0,0,0};
    static const uint8_t vS[16] = {0,0,0,0,127,0,0,0,0,0,0,0,127,0,0,0};
    static const uint8_t vH[16] = {127,0,127,0,127,0,127,0,127,0,127,0,127,0,127,0};
    // chorus: kick 0,8,12 | snare 4,12 | hat colcheias
    static const uint8_t cK[16] = {127,0,0,0,0,0,0,0,127,0,0,0,127,0,0,0};
    // fill: kick 0 | snare 16x | hat colcheias
    static const uint8_t fK[16] = {127,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    static const uint8_t fS[16] = {127,127,127,127,127,127,127,127,127,127,127,127,127,127,127,127};
    for (int i = 0; i < 16; i++) {
        g_vel[0][0][i].store(vK[i]); g_vel[0][1][i].store(vS[i]); g_vel[0][2][i].store(vH[i]);
        g_vel[1][0][i].store(cK[i]); g_vel[1][1][i].store(vS[i]); g_vel[1][2][i].store(vH[i]);
        g_vel[2][0][i].store(fK[i]); g_vel[2][1][i].store(fS[i]); g_vel[2][2][i].store(vH[i]);
    }
}

struct SchedState {
    int64_t nextStepFrame = 0; // posição absoluta da próxima semicolcheia
    int stepInBar = 0;         // 0..15
    int beatCount = 1;         // 1..4
    bool fillBar = false;
    bool crashOnDownbeat = false; // v5.1: crash+bumbo no 1º tempo do compasso pós-fill
};
static SchedState g_sched;
static int64_t g_framesWritten = 0; // frames já entregues ao stream (nosso relógio)
static int g_framesPerBurst = 192;

// ---------------------------------------------------------------------------
// JNI listener de batida (chamado na thread de áudio)
// ---------------------------------------------------------------------------
static JavaVM *g_jvm = nullptr;
static jobject g_listener = nullptr;
static jmethodID g_onBeatMethod = nullptr;
static std::mutex g_listenerMutex;

static void postBeat(int beat) {
    std::lock_guard<std::mutex> lock(g_listenerMutex);
    if (!g_jvm || !g_listener || !g_onBeatMethod) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (env) {
        env->CallVoidMethod(g_listener, g_onBeatMethod, static_cast<jint>(beat));
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) g_jvm->DetachCurrentThread();
    }
}

// ---------------------------------------------------------------------------
// JNI listener de FILL (v4.4): onFillChanged(boolean active) — chamado na
// thread de áudio quando o fill começa (true) e quando termina (false).
// Mesmo padrão do postBeat (AttachCurrentThread + CallVoidMethod).
// ---------------------------------------------------------------------------
static jobject g_fillListener = nullptr;
static jmethodID g_onFillChangedMethod = nullptr;
static std::mutex g_fillMutex;

static void postFillChanged(bool active) {
    std::lock_guard<std::mutex> lock(g_fillMutex);
    if (!g_jvm || !g_fillListener || !g_onFillChangedMethod) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (env) {
        env->CallVoidMethod(g_fillListener, g_onFillChangedMethod,
                            static_cast<jboolean>(active));
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) g_jvm->DetachCurrentThread();
    }
}

// ---------------------------------------------------------------------------
// Stream
// ---------------------------------------------------------------------------
static std::mutex g_streamMutex;
static std::shared_ptr<AudioStream> g_stream;
static bool g_streamFailed = false;

// ---------------------------------------------------------------------------
// Callbacks do Oboe
// ---------------------------------------------------------------------------
class EngineCallback : public AudioStreamDataCallback {
public:
    DataCallbackResult onAudioReady(AudioStream * /*stream*/, void *audioData,
                                    int32_t numFrames) override {
        float *out = static_cast<float *>(audioData);

        if (!g_running.load()) {
            std::memset(out, 0, sizeof(float) * numFrames);
            g_framesWritten += numFrames;
            return DataCallbackResult::Continue;
        }

        // v4.8: dispara one-shot pendente (crash) no início deste buffer
        {
            const int os = g_oneShotPending.exchange(-1);
            if (os >= 0 && os < 7) triggerVoice(os, 0);
        }

        const int64_t bufferStart = g_framesWritten;
        const int64_t bufferEnd = bufferStart + numFrames;

        // v5.0: compasso ativo (lido uma vez por callback)
        const int stepsPerBar = g_stepsPerBar.load();
        const int beatsPerBar = g_beatsPerBar.load();
        const int stepsPerBeat = stepsPerBar / beatsPerBar; // 4/4→4, 6/8→2

        // ---- agendamento sample-accurate das semicolcheias ----
        const double stepFrames = 60.0 * g_sampleRate / static_cast<double>(g_bpm.load()) / 4.0;
        if (g_sched.nextStepFrame < bufferStart) g_sched.nextStepFrame = bufferStart;

        while (g_sched.nextStepFrame < bufferEnd) {
            const int step = g_sched.stepInBar;
            const bool fill = g_sched.fillBar;
            const int offset = static_cast<int>(g_sched.nextStepFrame - bufferStart);
            if (offset >= 0 && offset < numFrames) {
                // v5.1: CRASH + BUMBO juntos no downbeat (step 0) do compasso
                // que sucede o fill — o "retorno ao groove". A flag é setada na
                // virada em que o fill termina e consumida aqui, no step 0.
                if (step == 0 && g_sched.crashOnDownbeat) {
                    g_sched.crashOnDownbeat = false;
                    triggerVoice(0, offset); // 0 = kick (bumbo)
                    triggerVoice(6, offset); // 6 = crash
                }
                const int part = g_part.load();
                // v4.9: lê VELOCITY (0-127) por peça/passo em vez de máscara binária
                const int partIdx = fill ? 2 : (part == 1 ? 1 : 0);
                for (int snd = 0; snd < 7; snd++) {
                    const uint8_t vel = g_vel[partIdx][snd][step].load();
                    if (vel > 0) triggerVoice(snd, offset, vel / 127.0f);
                }
            }
            if ((step % stepsPerBeat) == 0) { // início de batida forte (4/4: 1-4; 6/8: 1-6)
                // v5.1: FILL entra no PRÓXIMO BEAT (estilo Beat Buddy) — o
                // pendente é consumido aqui, no início da batida, em vez de
                // esperar a virada do compasso. O fill dura só até o fim do
                // compasso: 2, 3 ou 4 batidas em 4/4 (3-6 em 6/8), conforme
                // o momento do toque. Se já estiver em fill, o toque é ignorado.
                const bool wantFill = g_fillPending.exchange(false);
                if (wantFill && !g_sched.fillBar) {
                    g_sched.fillBar = true;
                    postFillChanged(true);
                }
                // v5.1: troca de parte TAMBÉM entra no próximo beat (mesma
                // mecânica do fill). Se um fill estiver ativo, a mudança fica
                // inaudível até o retorno — o crash+bumbo do downbeat marca a
                // transição já na parte nova.
                const int pendingPart = g_partPending.exchange(-1);
                if (pendingPart != -1) {
                    g_part.store(pendingPart);
                }
                postBeat(g_sched.beatCount);
                g_sched.beatCount = (g_sched.beatCount >= beatsPerBar) ? 1 : g_sched.beatCount + 1;
            }
            g_sched.stepInBar = (step + 1) % stepsPerBar;
            if (g_sched.stepInBar == 0) {
                // v5.1: encerra o fill na virada do compasso (duração parcial),
                // notifica o listener (apagar o card FILL) e agenda CRASH+BUMBO
                // no downbeat do compasso seguinte (retorno ao groove).
                if (g_sched.fillBar) {
                    g_sched.fillBar = false;
                    postFillChanged(false);
                    g_sched.crashOnDownbeat = true;
                }
            }
            g_sched.nextStepFrame = static_cast<int64_t>(
                static_cast<double>(g_sched.nextStepFrame) + stepFrames);
        }

        // ---- mixagem ----
        std::memset(out, 0, sizeof(float) * numFrames);
        // v4.7: coeficientes do filtro de TONE (lidos uma vez por callback)
        float alpha[7];
        for (int s = 0; s < 7; s++) alpha[s] = toneAlpha(g_tones[s].load(), g_sampleRate);
        for (int i = 0; i < numFrames; i++) {
            float sample = 0.0f;
            for (auto &v : g_voices) {
                if (!v.active) continue;
                if (v.pos >= 0.0f) {
                    const int idx = static_cast<int>(v.pos);
                    if (idx < static_cast<int>(v.len)) {
                        float s = g_sounds[v.sound][idx];
                        const float a = alpha[v.sound];
                        if (a > 0.0f) {
                            // passa-alta 1ª ordem: y = a*(y_prev + x - x_prev)
                            const float hp = a * (v.prevOut + s - v.prevIn);
                            v.prevIn = s;
                            v.prevOut = hp;
                            s = hp;
                        } else {
                            v.prevIn = s;
                            v.prevOut = s;
                        }
                        // v4.7: ganho individual da peça (mixer) + v4.9 velocity da batida
                        sample += s * g_gains[v.sound].load() * v.gain;
                    }
                }
                v.pos += 1.0f;
                if (v.pos >= v.len) v.active = false;
            }
            if (sample > 1.0f) sample = 1.0f;
            else if (sample < -1.0f) sample = -1.0f;
            out[i] = sample * 0.9f; // ganho mestre
        }

        g_framesWritten = bufferEnd;
        return DataCallbackResult::Continue;
    }
};

class EngineErrorCallback : public AudioStreamErrorCallback {
public:
    void onErrorAfterClose(AudioStream *stream, Result error) override {
        LOGE("Oboe error after close: %s", convertToText(error));
        std::lock_guard<std::mutex> lock(g_streamMutex);
        g_streamFailed = true;
        g_running = false;
    }
};

static EngineCallback g_dataCallback;
static EngineErrorCallback g_errorCallback;

static bool openStreamLocked() {
    if (g_stream && !g_streamFailed) return true;
    g_stream.reset();
    g_streamFailed = false;

    AudioStreamBuilder builder;
    builder.setDirection(Direction::Output)
        ->setPerformanceMode(PerformanceMode::LowLatency)
        ->setSharingMode(SharingMode::Exclusive)
        ->setFormat(AudioFormat::Float)
        ->setChannelCount(1) // taxa de amostragem: nativa do dispositivo
        ->setDataCallback(&g_dataCallback)
        ->setErrorCallback(&g_errorCallback);

    Result r = builder.openStream(g_stream);
    if (r != Result::OK) {
        LOGE("Low-latency openStream falhou (%s); tentando configuração padrão",
             convertToText(r));
        g_stream.reset();
        AudioStreamBuilder fb;
        fb.setDirection(Direction::Output)
            ->setFormat(AudioFormat::Float)
            ->setChannelCount(1)
            ->setDataCallback(&g_dataCallback)
            ->setErrorCallback(&g_errorCallback);
        r = fb.openStream(g_stream);
        if (r != Result::OK) {
            LOGE("openStream padrão falhou: %s", convertToText(r));
            g_stream.reset();
            return false;
        }
    }

    g_sampleRate = g_stream->getSampleRate();
    g_framesPerBurst = g_stream->getFramesPerBurst();
    if (g_framesPerBurst <= 0) g_framesPerBurst = 192;
    renderSounds();       // renderiza na taxa real do stream
    initPatterns();       // v4.9: garante padrões iniciais (velocity arrays)

    LOGI("Oboe OK: rate=%d ch=%d burst=%d perf=%d sharing=%d",
         g_sampleRate, g_stream->getChannelCount(), g_framesPerBurst,
         static_cast<int>(g_stream->getPerformanceMode()),
         static_cast<int>(g_stream->getSharingMode()));
    return true;
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
static char g_crashPath[512] = {0};

static void nativeCrashHandler(int sig) {
    int fd = open(g_crashPath, O_CREAT | O_WRONLY | O_APPEND, 0644);
    if (fd >= 0) {
        char buf[192];
        int n = snprintf(buf, sizeof(buf),
                         "\n=== NATIVE CRASH signal %d (%s) ===\n",
                         sig, strsignal(sig));
        write(fd, buf, n);
        close(fd);
    }
    _exit(128 + sig);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetCrashLogPath(JNIEnv *env, jobject,
                                                    jstring path) {
    const char *p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (p) {
        strncpy(g_crashPath, p, sizeof(g_crashPath) - 1);
        g_crashPath[sizeof(g_crashPath) - 1] = 0;
        env->ReleaseStringUTFChars(path, p);
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = nativeCrashHandler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_drummachi_DrumEngine_nativeOpen(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_streamMutex);
    return openStreamLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeStart(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_streamMutex);
    if (!openStreamLocked()) return;
    clearVoices();
    g_sched = SchedState();
    const int64_t preRoll = static_cast<int64_t>(g_framesPerBurst) * 2;
    g_sched.nextStepFrame = g_framesWritten + preRoll;
    g_running = true;
    g_stream->requestStart();
    LOGI("start: rate=%d bpm=%d preRoll=%lld", g_sampleRate, g_bpm.load(),
         static_cast<long long>(preRoll));
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeStop(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_streamMutex);
    g_running = false;
    if (g_stream) g_stream->requestStop();
    clearVoices();
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeRelease(JNIEnv *env, jobject) {
    {
        std::lock_guard<std::mutex> lock(g_streamMutex);
        g_running = false;
        if (g_stream) {
            g_stream->requestStop();
            g_stream->close();
            g_stream.reset();
        }
    }
    std::lock_guard<std::mutex> lock(g_listenerMutex);
    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
        g_onBeatMethod = nullptr;
    }
    std::lock_guard<std::mutex> lock2(g_fillMutex);
    if (g_fillListener) {
        env->DeleteGlobalRef(g_fillListener);
        g_fillListener = nullptr;
        g_onFillChangedMethod = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetBpm(JNIEnv *, jobject, jint bpm) {
    int v = bpm;
    if (v < 40) v = 40;
    if (v > 240) v = 240;
    g_bpm.store(v);
}

// v5.0: define a fórmula de compasso (semicolcheias por compasso + batidas).
// Ex.: 4/4 → 16 passos / 4 batidas; 6/8 → 12 passos / 6 batidas.
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetTimeSignature(JNIEnv *, jobject,
                                                     jint stepsPerBar, jint beatsPerBar) {
    int s = stepsPerBar;
    if (s < 4) s = 4;
    if (s > 32) s = 32;
    int b = beatsPerBar;
    if (b < 1) b = 1;
    if (b > s) b = s;
    g_stepsPerBar.store(s);
    g_beatsPerBar.store(b);
    LOGI("time signature: %d passos / %d batidas", s, b);
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetPart(JNIEnv *, jobject, jint partId) {
    // v5.1: NÃO aplica na hora — agenda para o PRÓXIMO BEAT (mesma mecânica
    // do fill). O scheduler consome g_partPending no início de cada batida.
    g_partPending.store(partId == 1 ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeQueueFill(JNIEnv *, jobject) {
    g_fillPending.store(true);
}

JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetBeatListener(JNIEnv *env, jobject,
                                                    jobject listener) {
    std::lock_guard<std::mutex> lock(g_listenerMutex);
    if (g_listener) {
        env->DeleteGlobalRef(g_listener);
        g_listener = nullptr;
        g_onBeatMethod = nullptr;
    }
    if (listener) {
        g_listener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(listener);
        g_onBeatMethod = env->GetMethodID(cls, "onBeat", "(I)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

// v4.9: envia os padrões de um ritmo ao engine com VELOCITY (0-127 por semicolcheia).
// part: 0=VERSE, 1=CHORUS, 2=FILL. Cada array tem 16 valores (0-127).
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetPattern(JNIEnv *env, jobject, jint part,
                                               jintArray kick, jintArray snare, jintArray hat,
                                               jintArray tomFt, jintArray tomMt, jintArray tomHt,
                                               jintArray crash) {
    if (part < 0 || part > 2) return;
    jintArray arrs[7] = {kick, snare, hat, tomFt, tomMt, tomHt, crash};
    for (int snd = 0; snd < 7; snd++) {
        if (arrs[snd] == nullptr) continue;
        jsize len = env->GetArrayLength(arrs[snd]);
        if (len < 16) continue;
        jint *vals = env->GetIntArrayElements(arrs[snd], nullptr);
        for (int i = 0; i < 16; i++) {
            int v = vals[i];
            if (v < 0) v = 0;
            if (v > 127) v = 127;
            g_vel[part][snd][i].store(static_cast<uint8_t>(v));
        }
        env->ReleaseIntArrayElements(arrs[snd], vals, JNI_ABORT);
    }
}

// v4.6: carrega um sample PCM16 (mono) em um dos 6 slots do kit.
// soundId: 0=kick 1=snare 2=hat 3=tom_ft 4=tom_mt 5=tom_ht
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeLoadSample(JNIEnv *env, jobject,
                                               jint soundId, jbyteArray pcm,
                                               jint sampleRate) {
    if (soundId < 0 || soundId >= 7) return;
    jsize len = env->GetArrayLength(pcm);
    if (len < 2) return;
    jbyte *buf = env->GetByteArrayElements(pcm, nullptr);
    const int16_t *s16 = reinterpret_cast<const int16_t *>(buf);
    int n = static_cast<int>(len / 2);

    std::vector<float> out;
    if (sampleRate == g_sampleRate) {
        out.resize(n);
        for (int i = 0; i < n; i++) out[i] = s16[i] / 32768.0f;
    } else {
        // Resample linear simples de sampleRate -> g_sampleRate
        double ratio = static_cast<double>(g_sampleRate) / sampleRate;
        int outN = static_cast<int>(n * ratio);
        out.resize(outN);
        for (int i = 0; i < outN; i++) {
            double pos = i / ratio;
            int i0 = static_cast<int>(pos);
            int i1 = (i0 + 1 < n) ? i0 + 1 : i0;
            double frac = pos - i0;
            out[i] = static_cast<float>(
                (s16[i0] * (1.0 - frac) + s16[i1] * frac) / 32768.0);
        }
    }
    env->ReleaseByteArrayElements(pcm, buf, JNI_ABORT);
    g_sounds[soundId] = std::move(out);
    LOGI("sample %d carregado: %d frames (%d Hz -> %d Hz)", soundId,
         static_cast<int>(g_sounds[soundId].size()), sampleRate, g_sampleRate);
}

// v4.8: dispara um som imediatamente (ex.: crash) — consumido no próximo callback
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativePlayOneShot(JNIEnv *, jobject, jint soundId) {
    if (soundId < 0 || soundId >= 7) return;
    g_oneShotPending.store(soundId);
}

// v4.7: ganho individual da peça (0.0 .. 2.0, default 1.0)
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetGain(JNIEnv *, jobject, jint soundId,
                                            jfloat gain) {
    if (soundId < 0 || soundId >= 7) return;
    float g = gain;
    if (g < 0.0f) g = 0.0f;
    if (g > 2.0f) g = 2.0f;
    g_gains[soundId].store(g);
}

// v4.7: TONE da peça (0.0 natural .. 1.0 mais agudo)
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetTone(JNIEnv *, jobject, jint soundId,
                                            jfloat tone) {
    if (soundId < 0 || soundId >= 7) return;
    float t = tone;
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    g_tones[soundId].store(t);
}

// v4.4: registra o listener de fill (Kotlin: onFillChanged(boolean)).
JNIEXPORT void JNICALL
Java_com_drummachi_DrumEngine_nativeSetFillListener(JNIEnv *env, jobject,
                                                    jobject listener) {
    std::lock_guard<std::mutex> lock(g_fillMutex);
    if (g_fillListener) {
        env->DeleteGlobalRef(g_fillListener);
        g_fillListener = nullptr;
        g_onFillChangedMethod = nullptr;
    }
    if (listener) {
        g_fillListener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(listener);
        g_onFillChangedMethod = env->GetMethodID(cls, "onFillChanged", "(Z)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

} // extern "C"
