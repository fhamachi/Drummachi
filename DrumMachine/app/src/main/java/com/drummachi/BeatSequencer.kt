package com.drummachi

import android.os.Handler
import android.os.Looper

/**
 * BeatSequencer - Controlador do sequenciador (v4/Oboe).
 *
 * Desde a v4 o agendamento rítmico é feito DENTRO do callback de áudio do
 * Oboe, com precisão de amostra (não existe mais thread com Thread.sleep).
 * Esta classe virou uma fachada: mantém a mesma API usada pela MainActivity
 * (setBeatCallback, start/stop, setBpm, setPart, queueFill) e repassa os
 * eventos de batida para a main thread.
 */
class BeatSequencer(private val drumEngine: DrumEngine) {

    /** Callback notificado a cada batida forte (1 a 4), sempre na main thread. */
    interface BeatCallback {
        fun onBeat(beatNumber: Int)
    }

    /** v4.4: callback de estado do fill (true = começou, false = terminou). */
    interface FillCallback {
        fun onFillChanged(active: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var callback: BeatCallback? = null
    private var beatListener: DrumEngine.BeatListener? = null
    private var fillCallback: FillCallback? = null
    private var fillListener: DrumEngine.FillListener? = null

    @Volatile
    private var isRunning = false

    fun setBeatCallback(cb: BeatCallback) {
        callback = cb
        if (beatListener == null) {
            beatListener = object : DrumEngine.BeatListener {
                override fun onBeat(beat: Int) {
                    handler.post { callback?.onBeat(beat) }
                }
            }
            drumEngine.setBeatListener(beatListener)
        }
    }

    /** v4.4: registra o callback de fill (acender/apagar o card FILL). */
    fun setFillCallback(cb: FillCallback) {
        fillCallback = cb
        if (fillListener == null) {
            fillListener = object : DrumEngine.FillListener {
                override fun onFillChanged(active: Boolean) {
                    handler.post { fillCallback?.onFillChanged(active) }
                }
            }
            drumEngine.setFillListener(fillListener)
        }
    }

    fun setBpm(newBpm: Int) {
        drumEngine.setBpm(newBpm)
    }

    fun setPart(part: String) {
        drumEngine.setPart(if (part == "CHORUS") 1 else 0)
    }

    fun isRunning(): Boolean = isRunning

    /** Inicia o loop rítmico (abre o stream Oboe na primeira vez). */
    fun start() {
        if (isRunning) return
        if (!drumEngine.open()) return // stream falhou; não inicia
        drumEngine.start()
        isRunning = true
    }

    /** Interrompe o loop e silencia os sons. */
    fun stop() {
        if (!isRunning) return
        drumEngine.stop()
        isRunning = false
    }

    /**
     * v5.1: agenda um fill que entra no PRÓXIMO BEAT e dura até o fim do
     * compasso (estilo Beat Buddy) — 2, 3 ou 4 batidas em 4/4, conforme o
     * momento do toque.
     */
    fun queueFill() {
        if (isRunning) drumEngine.queueFill()
    }
}
