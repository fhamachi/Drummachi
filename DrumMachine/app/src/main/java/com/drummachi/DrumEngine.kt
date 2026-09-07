package com.drummachi

/**
 * DrumEngine - Motor de áudio do Drummachi baseado em Oboe (C++).
 *
 * v4: stream de saída com PerformanceMode.LOW_LATENCY + SharingMode.EXCLUSIVE.
 * O sequenciador roda DENTRO do callback de áudio nativo, agendando as
 * batidas por posição absoluta de frame (precisão de 1 amostra, sem jitter
 * de thread). Esta classe é apenas a fachada JNI — a lógica vive em
 * `cpp/engine.cpp` (libdrummachi.so).
 */
class DrumEngine {

    /**
     * Recebe a batida (1..4) na thread de áudio nativa.
     * Quem mexe em UI deve postar para a main thread (ver BeatSequencer).
     */
    interface BeatListener {
        fun onBeat(beat: Int)
    }

    /**
     * v4.4: recebe a mudança de estado do FILL na thread de áudio nativa.
     * true = fill começou, false = fill terminou. Postar p/ main thread.
     */
    interface FillListener {
        fun onFillChanged(active: Boolean)
    }

    // v4.4: constantes das partes (mesmos valores usados no engine.cpp)
    companion object {
        const val PART_VERSE = 0
        const val PART_CHORUS = 1
        const val PART_FILL = 2

        // v4.6: slots do kit (mesmos índices do engine.cpp)
        const val SOUND_KICK = 0
        const val SOUND_SNARE = 1
        const val SOUND_HAT = 2
        const val SOUND_TOM_FT = 3
        const val SOUND_TOM_MT = 4
        const val SOUND_TOM_HT = 5
        const val SOUND_CRASH = 6 // v4.8: crash cymbal (one-shot)
    }

    init {
        System.loadLibrary("drummachi")
    }

    /** Abre o stream Oboe (low-latency). Idempotente: chamar antes de start(). */
    fun open(): Boolean = nativeOpen()

    fun start() = nativeStart()
    fun stop() = nativeStop()
    fun release() = nativeRelease()

    /** BPM 40-240. */
    fun setBpm(bpm: Int) = nativeSetBpm(bpm.coerceIn(40, 240))

    /**
     * v5.0: define a fórmula de compasso do ritmo ativo.
     * @param stepsPerBar semicolcheias por compasso (4/4→16, 6/8→12)
     * @param beatsPerBar batidas por compasso (4/4→4, 6/8→6)
     */
    fun setTimeSignature(stepsPerBar: Int, beatsPerBar: Int) =
        nativeSetTimeSignature(stepsPerBar, beatsPerBar)

    /** 0 = VERSE, 1 = CHORUS. */
    fun setPart(partId: Int) = nativeSetPart(partId)

    /** Agenda virada (fill) de 1 compasso. */
    fun queueFill() = nativeQueueFill()

    /**
     * v4.4/v4.9: envia os padrões de um ritmo (VELOCITY 0-127 por semicolcheia).
     * @param part 0=VERSE, 1=CHORUS, 2=FILL
     * Cada array tem 16 valores (0-127).
     */
    fun setPattern(part: Int, kick: IntArray, snare: IntArray, hat: IntArray,
                   tomFt: IntArray, tomMt: IntArray, tomHt: IntArray,
                   crash: IntArray) =
        nativeSetPattern(part, kick, snare, hat, tomFt, tomMt, tomHt, crash)

    /** v4.4: registra o listener de mudança de estado do fill. */
    fun setFillListener(listener: FillListener?) = nativeSetFillListener(listener)

    /** v4.4/v4.9: aplica os três padrões (verse/chorus/fill) de uma vez. */
    fun setPatterns(verse: Patterns, chorus: Patterns, fill: Patterns) {
        nativeSetPattern(PART_VERSE, verse.kick, verse.snare, verse.hat,
                         verse.tomFt, verse.tomMt, verse.tomHt, verse.crash)
        nativeSetPattern(PART_CHORUS, chorus.kick, chorus.snare, chorus.hat,
                         chorus.tomFt, chorus.tomMt, chorus.tomHt, chorus.crash)
        nativeSetPattern(PART_FILL, fill.kick, fill.snare, fill.hat,
                         fill.tomFt, fill.tomMt, fill.tomHt, fill.crash)
    }

    /**
     * v4.6: carrega um sample PCM16 (mono) num slot do kit.
     * soundId: SOUND_KICK..SOUND_TOM_HT. sampleRate: taxa do WAV (ex.: 44100).
     */
    fun loadSample(soundId: Int, pcm16: ByteArray, sampleRate: Int) =
        nativeLoadSample(soundId, pcm16, sampleRate)

    /**
     * v4.7: ganho individual da peça (mixer). 0.0..2.0, default 1.0.
     */
    fun setGain(soundId: Int, gain: Float) = nativeSetGain(soundId, gain)

    /**
     * v4.7: TONE da peça. 0.0 = natural, 1.0 = bem mais agudo.
     */
    fun setTone(soundId: Int, tone: Float) = nativeSetTone(soundId, tone)

    /**
     * v5.5: REVERB master — nível de mix dry/wet. 0.0 = só seco (bypass), 1.0 = cheio.
     */
    fun setReverbLevel(level: Float) = nativeSetReverbLevel(level.coerceIn(0f, 1f))

    /**
     * v5.5: REVERB master — tempo de cauda (decay). 0.0 = curto, 1.0 = longo. Default 0.75.
     */
    fun setReverbTime(time: Float) = nativeSetReverbTime(time.coerceIn(0f, 1f))

    /**
     * v4.8: dispara um som imediatamente (one-shot, ex.: crash cymbal).
     */
    fun playOneShot(soundId: Int) = nativePlayOneShot(soundId)

    /** Caminho para o log de crash nativo (filesDir). Instala handlers de sinal. */
    fun setCrashLogPath(path: String?) = nativeSetCrashLogPath(path)

    fun setBeatListener(listener: BeatListener?) = nativeSetBeatListener(listener)

    private external fun nativeOpen(): Boolean
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeRelease()
    private external fun nativeSetBpm(bpm: Int)
    private external fun nativeSetTimeSignature(stepsPerBar: Int, beatsPerBar: Int)
    private external fun nativeSetPart(partId: Int)
    private external fun nativeQueueFill()
    private external fun nativeSetPattern(part: Int, kick: IntArray, snare: IntArray, hat: IntArray,
                                          tomFt: IntArray, tomMt: IntArray, tomHt: IntArray,
                                          crash: IntArray)
    private external fun nativeLoadSample(soundId: Int, pcm16: ByteArray, sampleRate: Int)
    private external fun nativeSetGain(soundId: Int, gain: Float)
    private external fun nativeSetTone(soundId: Int, tone: Float)
    private external fun nativeSetReverbLevel(level: Float)
    private external fun nativeSetReverbTime(time: Float)
    private external fun nativePlayOneShot(soundId: Int)
    private external fun nativeSetFillListener(listener: FillListener?)
    private external fun nativeSetCrashLogPath(path: String?)
    private external fun nativeSetBeatListener(listener: BeatListener?)
}
