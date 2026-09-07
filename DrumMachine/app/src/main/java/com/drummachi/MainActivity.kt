package com.drummachi

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Padrões de um ritmo: VELOCITY (0-127) por semicolcheia — 16 posições por peça. */
class Patterns(
    val kick: IntArray, val snare: IntArray, val hat: IntArray,
    val tomFt: IntArray, val tomMt: IntArray, val tomHt: IntArray,
    val crash: IntArray
)

/** Um ritmo carregado dos JSONs em assets/styles. */
data class Rhythm(
    val nome: String,
    val descricao: String,
    val bpm: Int,
    val compasso: String = "4/4", // v5.0: fórmula de compasso (ex.: "6/8")
    val verso: Patterns,
    val refrao: Patterns,
    val fill: Patterns
)

/** Grupo de ritmos por estilo (Rock, Pop, Blues, Latino). */
data class StyleGroup(val estilo: String, val ritmos: List<Rhythm>)

/**
 * MainActivity - Interface de performance do Drummachi v4.4.
 *
 * Mudanças v4.4 (feedback do Felipe sobre o v4.3):
 *  1. Contador 1-4 virou MOSTRADOR: acompanha o compasso via onBeat (sem clique).
 *  2. Menu funcional: 12 ritmos do Betão (assets/styles) agrupados por estilo;
 *     selecionar aplica label "Style / <ritmo>", BPM e padrões via JNI.
 *  3. Motor Oboe: padrões dinâmicos (nativeSetPattern) + callback de FILL.
 *  4. Horizontal line on the VERSE card removed.
 *  5. Card FILL acende em azul claro quando o fill está ativo (callback nativo).
 *  6. Slider CONTÍNUO de BPM no header (40-240, sem travas; convive com TAP TEMPO).
 *
 * Motor Oboe (v4.2) preservado: o sequenciador continua dentro do callback de
 * áudio; DrumEngine/BeatSequencer só ganharam setPattern/setFillListener.
 */
class MainActivity : AppCompatActivity(),
    BeatSequencer.BeatCallback, BeatSequencer.FillCallback {

    // Estado
    private var isPlaying = false
    private var currentPart = "VERSE" // "VERSE" or "CHORUS"
    private var currentBpm = 120
    private var lastTapTime: Long = 0
    private val tapIntervals = mutableListOf<Long>()

    // v5.x: preferência de tema (shared prefs)
    private lateinit var themePrefs: android.content.SharedPreferences

    private lateinit var drumEngine: DrumEngine
    private lateinit var beatSequencer: BeatSequencer

    private var styleGroups: List<StyleGroup> = emptyList()

    // Views
    private lateinit var bpmLabel: TextView
    private lateinit var bpmSlider: BpmSlider
    private lateinit var styleValue: TextView
    private lateinit var playButton: View
    private lateinit var playFlash: View
    private lateinit var playText: TextView
    private lateinit var songPartButton: View
    private lateinit var songPartText: TextView
    private lateinit var fillGlow: View
    private lateinit var variationBar: android.widget.LinearLayout
    private var variationSegments: List<TextView> = emptyList()
    private var currentBeatsPerBar = 4 // v5.0: compasso ativo (4/4 → 4, 6/8 → 6)

    // v4.7: mixer (painel lateral + sliders por peça)
    private lateinit var mixerPanel: View
    private val mixerRows = mutableListOf<MixerRow>()
    private var mixerTouchStartX = 0f
    private var mixerTouchStartY = 0f
    private var mixerTwoFinger = false
    private var mixerOpen = false

    // v5.x: favoritos (biblioteca de loops) + gesto de swipe
    private lateinit var favButton: ImageView
    private lateinit var favoritesPrefs: android.content.SharedPreferences
    private val favoriteKeys = mutableListOf<String>() // chaves "estilo|nome", ordenadas
    private val rhythmByKey = LinkedHashMap<String, Rhythm>()
    private var currentRhythm: Rhythm? = null
    private var currentStyleKey: String? = null

    // v5.x: swipe de favoritos (região dos 5 botões principais)
    private lateinit var gestureDetector: GestureDetector
    private var headerEndPx = 0f
    private var footerPx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- v5.x: aplica o tema escolhido ANTES de inflar a view ----
        themePrefs = getSharedPreferences("drummachi_theme", Context.MODE_PRIVATE)
        applySavedTheme()

        // ---- Diagnóstico v4.2: captura qualquer erro e mostra na tela ----
        val crashFile = File(filesDir, "drummachi_crash.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile.appendText(
                    "[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] " +
                        "${thread.name}: ${Log.getStackTraceString(throwable)}\n"
                )
            } catch (_: Exception) {}
        }
        if (crashFile.exists() && crashFile.length() > 0) {
            val prev = crashFile.readText().takeLast(2000)
            AlertDialog.Builder(this)
                .setTitle("Previous crash log")
                .setMessage(prev)
                .setPositiveButton("OK", null)
                .show()
        }

        setContentView(R.layout.activity_main)

        // Mantém a tela acesa durante a performance
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        // Motor de áudio + sequenciador (API estável + v4.4: fill callback)
        try {
            drumEngine = DrumEngine()
            drumEngine.setCrashLogPath(crashFile.absolutePath)
            beatSequencer = BeatSequencer(drumEngine)
            beatSequencer.setBeatCallback(this)
            beatSequencer.setFillCallback(this)
        } catch (t: Throwable) {
            val msg = "AUDIO INIT FAILURE:\n${Log.getStackTraceString(t)}"
            try { crashFile.appendText(msg + "\n") } catch (_: Exception) {}
            AlertDialog.Builder(this)
                .setTitle("Native error")
                .setMessage(msg.takeLast(3000))
                .setPositiveButton("OK", null)
                .show()
        }

        // v4.6: abre o stream e carrega o kit de samples REAIS (assets/kit)
        try {
            if (drumEngine.open()) loadKitSamples()
        } catch (t: Throwable) {
            Log.e("Drummachi", "Falha ao abrir stream / carregar kit", t)
        }

        // Views
        bpmLabel = findViewById(R.id.bpmLabel)
        bpmSlider = findViewById(R.id.bpmSlider)
        styleValue = findViewById(R.id.styleValue)
        favButton = findViewById(R.id.favButton)
        playButton = findViewById(R.id.playButton)
        playFlash = findViewById(R.id.playFlash)
        playText = findViewById(R.id.playText)
        songPartButton = findViewById(R.id.songPartButton)
        songPartText = findViewById(R.id.songPartText)
        fillGlow = findViewById(R.id.fillGlow)
        variationBar = findViewById(R.id.variationBar)

        // Slider contínuo do BPM (v4.4): arrastar atualiza label + engine.
        // O TAP TEMPO continua funcionando e também sincroniza o slider.
        bpmSlider.onBpmChange = { bpm ->
            currentBpm = bpm
            beatSequencer.setBpm(bpm)
            updateBpmLabel()
        }

        // Listeners
        findViewById<View>(R.id.btnMenu).setOnClickListener { onMenuClicked() }
        findViewById<View>(R.id.tapTempoButton).setOnClickListener { onTapTempoClicked() }
        // v5.x: botão CRASH — toca o crash mesmo com a máquina parada
        findViewById<View>(R.id.crashButton).setOnClickListener { onCrashClicked() }
        songPartButton.setOnClickListener { onSongPartClicked() }
        playButton.setOnClickListener { onPlayClicked() }
        findViewById<View>(R.id.fillButton).setOnClickListener { onFillClicked() }
        favButton.setOnClickListener { onFavClicked() }
        // v4.4: o contador 1-4 NÃO é clicável — é um mostrador (sem listeners).

        // Carrega os 12 ritmos (assets/styles) e aplica o padrão inicial
        styleGroups = loadStyles()
        styleGroups.firstOrNull()?.ritmos?.firstOrNull()?.let { applyStyle(it) }

        // v5.x: favoritos (índice + persistência) e gesto de swipe
        buildRhythmIndex()
        favoritesPrefs = getSharedPreferences("drummachi_favorites", Context.MODE_PRIVATE)
        loadFavorites()
        initGesture()

        // v4.7: mixer de volume/tone por peça (painel lateral, gesto de 2 dedos)
        setupMixer()

        // Textos iniciais
        updateBpmLabel()
        updatePartLabels()
        buildCounter(4) // mostrador 1-4 padrão (4/4)
    }

    // ---------- Ações ----------

    /**
     * Menu hambúrguer (v4.4): Styles (12 ritmos do Betão), Import MIDI (em
     * breve), Sobre e Fechar.
     */
    private fun onMenuClicked() {
        val items = arrayOf(
            getString(R.string.menu_styles),
            getString(R.string.menu_favorites),
            getString(R.string.menu_import_midi),
            getString(R.string.menu_theme),
            getString(R.string.menu_about),
            getString(R.string.menu_close)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showStylesDialog()
                    1 -> showFavoritesDialog()
                    2 -> Toast.makeText(this, R.string.import_midi_soon, Toast.LENGTH_SHORT).show()
                    3 -> showThemeDialog()
                    4 -> showAboutDialog()
                }
            }
            .show()
    }

    /** Submenu Tema: Claro / Escuro / Automático (segue o sistema). */
    private fun showThemeDialog() {
        val items = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setItems(items) { _, which ->
                val mode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO      // Claro
                    1 -> AppCompatDelegate.MODE_NIGHT_YES     // Escuro
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM // Automático
                }
                themePrefs.edit().putInt("theme_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
            }
            .show()
    }

    /** Aplica o modo de tema salvo (default: DARK, como antes da v5). */
    private fun applySavedTheme() {
        val saved = themePrefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(saved)
    }

    /** Submenu Styles: 12 ritmos agrupados por estilo (Rock/Pop/Blues/Latino). */
    private fun showStylesDialog() {
        if (styleGroups.isEmpty()) {
            Toast.makeText(this, "Styles not found (assets/styles)", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = mutableListOf<String>()
        val refs = mutableListOf<Rhythm?>() // null = cabeçalho do estilo
        for (g in styleGroups) {
            labels += g.estilo.uppercase()
            refs += null
            for (r in g.ritmos) {
                labels += "   ${r.nome}  ·  ${r.bpm} BPM"
                refs += r
            }
        }
        val density = resources.displayMetrics.density
        val adapter = object : BaseAdapter() {
            override fun getCount() = labels.size
            override fun getItem(position: Int) = labels[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setPadding(
                        (24 * density).toInt(), (12 * density).toInt(),
                        (24 * density).toInt(), (12 * density).toInt()
                    )
                }
                val isHeader = refs[position] == null
                tv.text = labels[position]
                tv.textSize = if (isHeader) 13f else 17f
                tv.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isHeader) R.color.text_secondary else R.color.text_primary
                    )
                )
                tv.setTypeface(null, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
                return tv
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_styles)
            .setAdapter(adapter) { _, which ->
                refs[which]?.let { applyStyle(it) }
            }
            .setNegativeButton(R.string.menu_close, null)
            .show()
    }

    private fun showAboutDialog() {
        // v5.1: About reescrito — corpo HTML com link GoFundMe clicável,
        // chave Pix com botão COPIAR e QR code Pix (BrCode) escaneável.
        val body = getString(
            R.string.about_text,
            getString(R.string.donate_link),
            getString(R.string.donate_pix)
        )
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)

        // Corpo do texto (HTML) — link de doação clicável (abre o navegador).
        val bodyView = view.findViewById<TextView>(R.id.aboutBody)
        bodyView.text = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
        bodyView.movementMethod = LinkMovementMethod.getInstance()

        // Copiar chave Pix para a área de transferência.
        view.findViewById<Button>(R.id.aboutPixCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Pix", getString(R.string.donate_pix)))
            Toast.makeText(this, R.string.about_pix_copied, Toast.LENGTH_SHORT).show()
        }

        // v5.1: botão dedicado de doação — abre o navegador direto no GoFundMe.
        view.findViewById<Button>(R.id.aboutDonate).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.donate_link))))
            } catch (t: Throwable) {
                Toast.makeText(this, "Sem navegador disponível", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Aplica um ritmo: label do header, BPM (engine + slider) e os três
     * padrões (verso/refrão/fill) via JNI setPattern.
     */
    private fun applyStyle(r: Rhythm) {
        styleValue.text = r.nome
        currentBpm = r.bpm
        beatSequencer.setBpm(currentBpm)
        updateBpmLabel()
        bpmSlider.bpm = currentBpm
        drumEngine.setPatterns(r.verso, r.refrao, r.fill)
        // v5.0: compasso do ritmo → engine (passos/batidas) + mostrador dinâmico
        val (steps, beats) = parseCompasso(r.compasso)
        drumEngine.setTimeSignature(steps, beats)
        buildCounter(beats)
        // v5.x: guarda o ritmo ativo para a funcionalidade de favoritos
        currentRhythm = r
        currentStyleKey = null
        for (g in styleGroups) for (rr in g.ritmos) {
            if (rr === r) currentStyleKey = "${g.estilo}|${rr.nome}"
        }
        updateFavButton()
    }

    /** v5.0: "6/8" → (12 passos, 6 batidas); "4/4" → (16, 4); default 4/4. */
    private fun parseCompasso(compasso: String): Pair<Int, Int> {
        val parts = compasso.split("/")
        val num = parts.getOrNull(0)?.toIntOrNull() ?: 4
        val den = parts.getOrNull(1)?.toIntOrNull() ?: 4
        // semicolcheias por compasso = num * (4/den) * 4 → num*16/den
        val steps = (num * 16) / den
        // batidas por compasso = numerador (6/8 → 6 colcheias, 4/4 → 4)
        val beats = num
        return steps to beats
    }

    /**
     * v4.6: carrega os 6 samples do kit (assets/kit) no engine. Chamar após open().
     * Os WAVs foram convertidos para PCM16 mono 44.1kHz (ffmpeg).
     */
    private fun loadKitSamples() {
        val kit = listOf(
            DrumEngine.SOUND_KICK to "kit/KICK.wav",
            DrumEngine.SOUND_SNARE to "kit/SNARE.wav",
            DrumEngine.SOUND_HAT to "kit/HAT.wav",
            DrumEngine.SOUND_TOM_FT to "kit/TOM_FT.wav",
            DrumEngine.SOUND_TOM_MT to "kit/TOM_MT.wav",
            DrumEngine.SOUND_TOM_HT to "kit/TOM_HT.wav",
            DrumEngine.SOUND_CRASH to "kit/CRASH.wav" // v4.8
        )
        for ((soundId, path) in kit) {
            try {
                val wav = assets.open(path).use { it.readBytes() }
                val (pcm16, sampleRate) = parseWavPcm16(wav)
                drumEngine.loadSample(soundId, pcm16, sampleRate)
            } catch (t: Throwable) {
                Log.e("Drummachi", "Falha ao carregar $path", t)
            }
        }
    }

    /** Extrai o payload PCM16 (mono) de um WAV padrão (RIFF). */
    private fun parseWavPcm16(wav: ByteArray): Pair<ByteArray, Int> {
        var offset = 12 // pula "RIFF" + tamanho + "WAVE"
        var sampleRate = 44100
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = (wav[offset + 4].toInt() and 0xFF) or
                ((wav[offset + 5].toInt() and 0xFF) shl 8) or
                ((wav[offset + 6].toInt() and 0xFF) shl 16) or
                ((wav[offset + 7].toInt() and 0xFF) shl 24)
            if (id == "fmt ") {
                sampleRate = (wav[offset + 12].toInt() and 0xFF) or
                    ((wav[offset + 13].toInt() and 0xFF) shl 8) or
                    ((wav[offset + 14].toInt() and 0xFF) shl 16) or
                    ((wav[offset + 15].toInt() and 0xFF) shl 24)
            } else if (id == "data") {
                val dataStart = offset + 8
                val dataLen = minOf(size, wav.size - dataStart)
                return Pair(wav.copyOfRange(dataStart, dataStart + dataLen), sampleRate)
            }
            offset += 8 + size + (size and 1)
        }
        return Pair(ByteArray(0), sampleRate)
    }

    /**
     * v5.4: reabre o stream (se necessário) e recarrega os samples WAV antes de
     * iniciar o loop. Uma troca de dispositivo de áudio (ex.: desconectar um
     * fone Bluetooth) faz o Oboe fechar o stream e, ao reabri-lo, o engine
     * regenera os sons sintéticos (renderSounds) no lugar dos WAV — que só são
     * recarregados aqui. Chamar com o stream PARADO (não há callback lendo os
     * samples durante o carregamento), evitando corrida.
     */
    private fun ensureSamplesLoaded(): Boolean {
        if (!drumEngine.open()) return false
        loadKitSamples()
        return true
    }

    private fun onPlayClicked() {
        if (!isPlaying) {
            try {
                if (!ensureSamplesLoaded()) {
                    Toast.makeText(this, "Failed to open audio (native stream)", Toast.LENGTH_LONG).show()
                    return
                }
                beatSequencer.start()
                if (!beatSequencer.isRunning()) {
                    Toast.makeText(this, "Failed to start audio (native stream)", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                return
            }
            playText.text = getString(R.string.stop)
            isPlaying = true
        } else {
            beatSequencer.stop()
            playText.text = getString(R.string.play)
            isPlaying = false
            setFillGlow(false) // garante que o glow do fill apaga ao parar
        }
    }

    private fun onFillClicked() {
        if (isPlaying) {
            beatSequencer.queueFill()
            setFillGlow(true) // feedback imediato; o callback nativo apaga no fim
        } else {
            // v5.x: máquina parada — FILL inicia a música: faz o fill e entra
            // no loop normal (play) em seguida.
            try {
                if (!ensureSamplesLoaded()) {
                    Toast.makeText(this, "Failed to open audio (native stream)", Toast.LENGTH_LONG).show()
                    return
                }
                beatSequencer.start()
                if (!beatSequencer.isRunning()) {
                    Toast.makeText(this, "Failed to start audio (native stream)", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                return
            }
            playText.text = getString(R.string.stop)
            isPlaying = true
            beatSequencer.queueFill()
            setFillGlow(true)
        }
    }

    private fun onTapTempoClicked() {
        val now = System.currentTimeMillis()
        if (lastTapTime != 0L) {
            val delta = now - lastTapTime
            if (delta > 2000) {
                // Ritmo perdido: reinicia a contagem
                tapIntervals.clear()
            } else {
                tapIntervals.add(delta)
            }
        }
        lastTapTime = now

        // Precisa de pelo menos 4 toques (3 intervalos) para calcular
        if (tapIntervals.size >= 3) {
            val avg = tapIntervals.average()
            currentBpm = (60000.0 / avg).toInt().coerceIn(40, 240)
            beatSequencer.setBpm(currentBpm)
            updateBpmLabel()
            bpmSlider.bpm = currentBpm // sincroniza o slider com o tap tempo
        }
    }

    private fun onSongPartClicked() {
        currentPart = if (currentPart == "VERSE") "CHORUS" else "VERSE"
        beatSequencer.setPart(currentPart)
        updatePartLabels()
        // Transição suave com um fill
        if (isPlaying) beatSequencer.queueFill()
    }

    // ---------- UI ----------

    private fun updateBpmLabel() {
        bpmLabel.text = getString(R.string.bpm_format, currentBpm)
    }

    private fun updatePartLabels() {
        songPartText.text = currentPart
    }

    /**
     * v5.0: reconstrói a barra segmentada do compasso (4/4 → 1-4, 6/8 → 1-6).
     * Cria N TextViews com divisores, no mesmo estilo do layout antigo.
     */
    private fun buildCounter(beats: Int) {
        currentBeatsPerBar = beats
        variationBar.removeAllViews()
        val density = resources.displayMetrics.density
        val segments = mutableListOf<TextView>()
        for (i in 1..beats) {
            if (i > 1) {
                val divider = View(this)
                divider.layoutParams = android.widget.LinearLayout.LayoutParams(
                    (1 * density).toInt(),
                    (20 * density).toInt()
                ).apply {
                    topMargin = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
                divider.setBackgroundColor(ContextCompat.getColor(this, R.color.segment_divider))
                variationBar.addView(divider)
            }
            val tv = TextView(this)
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(
                (42 * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            tv.gravity = android.view.Gravity.CENTER
            tv.text = i.toString()
            tv.textSize = 15f
            tv.setTypeface(null, Typeface.BOLD)
            tv.isClickable = false
            tv.isFocusable = false
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_inactive))
            segments += tv
            variationBar.addView(tv)
        }
        variationSegments = segments
        updateCounter(1)
    }

    /**
     * Contador (MOSTRADOR): destaca o segmento da batida atual.
     * Funciona para N segmentos (4/4 e 6/8) — módulo pelo tamanho atual.
     */
    private fun updateCounter(beatNumber: Int) {
        if (variationSegments.isEmpty()) return
        val n = variationSegments.size
        val idx = ((beatNumber - 1) % n + n) % n
        variationSegments.forEachIndexed { index, segment ->
            val active = index == idx
            segment.setBackgroundResource(
                if (active) R.drawable.bg_segment_active else 0
            )
            segment.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.text_primary else R.color.text_inactive
                )
            )
        }
    }

    /** Acende/apaga o glow azul claro do card FILL. */
    private fun setFillGlow(on: Boolean) {
        fillGlow.animate().alpha(if (on) 1f else 0f).setDuration(150).start()
    }

    // ---------- Callbacks do sequenciador (já na main thread) ----------

    override fun onBeat(beatNumber: Int) {
        // Pulso visual no card PLAY a cada batida (scale + flash de brilho)
        val scaleX = ObjectAnimator.ofFloat(playButton, View.SCALE_X, 1f, 1.045f, 1f).setDuration(200)
        val scaleY = ObjectAnimator.ofFloat(playButton, View.SCALE_Y, 1f, 1.045f, 1f).setDuration(200)
        scaleX.interpolator = DecelerateInterpolator()
        scaleY.interpolator = DecelerateInterpolator()
        scaleX.start()
        scaleY.start()

        val flash = ObjectAnimator.ofFloat(playFlash, View.ALPHA, 0f, 0.30f, 0f).setDuration(200)
        flash.start()

        // v4.4: contador acompanha o compasso
        updateCounter(beatNumber)
    }

    /** v4.4: fill começou (true) / terminou (false) — controla o glow do card. */
    override fun onFillChanged(active: Boolean) {
        setFillGlow(active)
    }

    // ---------- Carregamento dos ritmos (assets/styles) ----------

    private fun loadStyles(): List<StyleGroup> {
        val groups = mutableListOf<StyleGroup>()
        // v4.9: todos os .json em assets/styles (16 famílias do dmp_midi)
        val files = assets.list("styles")?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") }
            ?: emptyList()
        for (file in files.sorted()) {
            try {
                val text = assets.open("styles/$file.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val estilo = root.getString("estilo")
                val ritmos = root.getJSONArray("ritmos")
                val list = mutableListOf<Rhythm>()
                for (i in 0 until ritmos.length()) {
                    val r = ritmos.getJSONObject(i)
                    list += Rhythm(
                        nome = r.getString("nome"),
                        descricao = r.optString("descricao"),
                        bpm = r.getInt("bpm"),
                        compasso = r.optString("compasso", "4/4"), // v5.0
                        verso = parsePatterns(r.getJSONObject("verso")),
                        refrao = parsePatterns(r.getJSONObject("refrao")),
                        fill = parsePatterns(r.getJSONObject("fill"))
                    )
                }
                groups += StyleGroup(estilo, list)
            } catch (t: Throwable) {
                Log.e("Drummachi", "Falha ao carregar styles/$file.json", t)
            }
        }
        return groups
    }

    private fun parsePatterns(obj: JSONObject): Patterns = Patterns(
        velocityArray(obj.getJSONArray("kick")),
        velocityArray(obj.getJSONArray("snare")),
        velocityArray(obj.getJSONArray("hat")),
        velocityArray(obj.optJSONArray("tom_ft")),
        velocityArray(obj.optJSONArray("tom_mt")),
        velocityArray(obj.optJSONArray("tom_ht")),
        velocityArray(obj.optJSONArray("crash"))
    )

    /** Converte a grade de 16 posições em array de VELOCITY (0-127). */
    private fun velocityArray(arr: JSONArray?): IntArray {
        if (arr == null) return IntArray(16) // silêncio
        val out = IntArray(16)
        for (i in 0 until minOf(arr.length(), 16)) {
            out[i] = arr.optInt(i).coerceIn(0, 127)
        }
        return out
    }

    // ---------- Modo imersivo ----------

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            // v5.x: limites da região dos 5 botões para o swipe de favoritos
            val root = findViewById<View>(android.R.id.content)
            headerEndPx = root.height * 0.18f
            footerPx = root.height * 0.94f
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::beatSequencer.isInitialized) beatSequencer.stop()
        if (::drumEngine.isInitialized) drumEngine.release()
    }

    // ========== v4.7: MIXER (volume/tone por peça, gesto de 2 dedos) ==========

    /** Dados + views de uma peça do mixer. */
    private class MixerRow(
        val soundId: Int,
        val name: String,
        val volume: SeekBar,
        val tone: SeekBar
    )

    private fun setupMixer() {
        mixerPanel = findViewById(R.id.mixerPanel)
        val defs = listOf(
            R.id.mixerKick to (DrumEngine.SOUND_KICK to "KICK"),
            R.id.mixerSnare to (DrumEngine.SOUND_SNARE to "SNARE"),
            R.id.mixerHat to (DrumEngine.SOUND_HAT to "HAT"),
            R.id.mixerTomFt to (DrumEngine.SOUND_TOM_FT to "TOM FT"),
            R.id.mixerTomMt to (DrumEngine.SOUND_TOM_MT to "TOM MT"),
            R.id.mixerTomHt to (DrumEngine.SOUND_TOM_HT to "TOM HT"),
            R.id.mixerCrash to (DrumEngine.SOUND_CRASH to "CRASH") // v4.8
        )
        val prefs = getSharedPreferences("drummachi_mixer", Context.MODE_PRIVATE)
        mixerRows.clear()
        for ((rowId, pair) in defs) {
            val row = findViewById<View>(rowId)
            val nameTv = row.findViewById<TextView>(R.id.mixerName)
            val volSb = row.findViewById<SeekBar>(R.id.mixerVolume)
            val toneSb = row.findViewById<SeekBar>(R.id.mixerTone)
            nameTv.text = pair.second

            // Carrega valores salvos (default: vol 100%, tone 0)
            val vol = prefs.getInt("vol_${pair.first}", 100)
            val tone = prefs.getInt("tone_${pair.first}", 0)
            volSb.progress = vol
            toneSb.progress = tone

            volSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    // SeekBar 0..200 → ganho 0.0..2.0
                    drumEngine.setGain(pair.first, p / 100f)
                    prefs.edit().putInt("vol_${pair.first}", p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            toneSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    drumEngine.setTone(pair.first, p / 100f)
                    prefs.edit().putInt("tone_${pair.first}", p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            mixerRows += MixerRow(pair.first, pair.second, volSb, toneSb)
        }

        // v5.5: REVERB global — LEVEL (dry/wet) + TIME (decay), persistidos em drummachi_mixer
        val reverbRow = findViewById<View>(R.id.mixerReverb)
        val rvLevelSb = reverbRow.findViewById<SeekBar>(R.id.mixerReverbLevel)
        val rvTimeSb = reverbRow.findViewById<SeekBar>(R.id.mixerReverbTime)
        val rvLevel = prefs.getInt("reverb_level", 0)
        val rvTime = prefs.getInt("reverb_time", 75)
        rvLevelSb.progress = rvLevel
        rvTimeSb.progress = rvTime
        drumEngine.setReverbLevel(rvLevel / 100f)
        drumEngine.setReverbTime(rvTime / 100f)
        rvLevelSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drumEngine.setReverbLevel(p / 100f)
                prefs.edit().putInt("reverb_level", p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        rvTimeSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                drumEngine.setReverbTime(p / 100f)
                prefs.edit().putInt("reverb_time", p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Painel começa fechado
        mixerPanel.visibility = View.GONE
    }

    /** Abre/fecha o painel do mixer com animação de deslize. */
    private fun setMixerOpen(open: Boolean) {
        mixerOpen = open
        if (open) {
            mixerPanel.visibility = View.VISIBLE
            mixerPanel.animate().translationX(0f).setDuration(180).start()
        } else {
            mixerPanel.animate().translationX(mixerPanel.width.toFloat())
                .setDuration(180)
                .withEndAction { mixerPanel.visibility = View.GONE }
                .start()
        }
    }

    /** Gesto: 2 dedos deslizando para a ESQUERDA abre o mixer; para a DIREITA fecha. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // v5.x: alimenta o detector de swipe de favoritos (observa, não consome)
        if (::gestureDetector.isInitialized) gestureDetector.onTouchEvent(ev)
        if (::mixerPanel.isInitialized) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2) {
                        mixerTwoFinger = true
                        mixerTouchStartX = ev.getX(1)
                        mixerTouchStartY = ev.getY(1)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mixerTwoFinger && ev.pointerCount >= 2) {
                        val dx = ev.getX(1) - mixerTouchStartX
                        if (Math.abs(dx) > 140f) {
                            mixerTwoFinger = false
                            setMixerOpen(dx < 0f) // esquerda = abre, direita = fecha
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mixerTwoFinger = false
                }
            }
            // Toque simples fora do painel fecha o mixer
            if (mixerOpen && ev.actionMasked == MotionEvent.ACTION_DOWN && ev.pointerCount == 1) {
                val x = ev.x
                val loc = IntArray(2)
                mixerPanel.getLocationOnScreen(loc)
                if (x < loc[0]) {
                    setMixerOpen(false)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ========== v5.x: FAVORITOS (biblioteca de loops) ==========

    /** Índice "estilo|nome" → Rhythm, para resolver favoritos rapidamente. */
    private fun buildRhythmIndex() {
        rhythmByKey.clear()
        for (g in styleGroups) for (r in g.ritmos) {
            rhythmByKey["${g.estilo}|${r.nome}"] = r
        }
    }

    private fun loadFavorites() {
        favoriteKeys.clear()
        try {
            val arr = JSONArray(favoritesPrefs.getString("favorites", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (k.isNotBlank() && rhythmByKey.containsKey(k)) favoriteKeys += k
            }
        } catch (_: Exception) {
        }
        favoriteKeys.sort()
    }

    private fun saveFavorites() {
        val arr = JSONArray()
        favoriteKeys.forEach { arr.put(it) }
        favoritesPrefs.edit().putString("favorites", arr.toString()).apply()
    }

    private fun initGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) < Math.abs(dy) * 1.2f) return false // exige dominância horizontal
                if (Math.abs(vx) < 600f) return false
                if (mixerTwoFinger || mixerOpen) return false
                if (headerEndPx > 0f && (e1.y < headerEndPx || e1.y > footerPx)) return false
                if (dx > 0) nextFavorite() else previousFavorite()
                return true
            }
        })
    }

    private fun onFavClicked() {
        val key = currentStyleKey ?: return
        if (key in favoriteKeys) {
            favoriteKeys.remove(key)
            saveFavorites()
            Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show()
        } else {
            favoriteKeys += key
            favoriteKeys.sort()
            saveFavorites()
            Toast.makeText(this, R.string.fav_added, Toast.LENGTH_SHORT).show()
        }
        updateFavButton()
    }

    private fun updateFavButton() {
        if (!::favButton.isInitialized) return
        val key = currentStyleKey
        val isFav = key != null && key in favoriteKeys
        if (isFav) {
            favButton.setImageResource(R.drawable.ic_star_filled)
            favButton.setColorFilter(ContextCompat.getColor(this, R.color.fav_active))
        } else {
            favButton.setImageResource(R.drawable.ic_star_outline)
            favButton.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun showFavoritesDialog() {
        if (favoriteKeys.isEmpty()) {
            Toast.makeText(this, R.string.fav_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = favoriteKeys.map { key ->
            val r = rhythmByKey[key]!!
            "${key.substringBefore("|")} — ${r.nome} · ${r.bpm} BPM"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_favorites)
            .setItems(labels.toTypedArray()) { _, which ->
                rhythmByKey[favoriteKeys[which]]?.let { applyStyle(it) }
            }
            .setNegativeButton(R.string.menu_close, null)
            .show()
    }

    private fun nextFavorite() = cycleFavorite(1)
    private fun previousFavorite() = cycleFavorite(-1)

    private fun cycleFavorite(dir: Int) {
        if (favoriteKeys.isEmpty()) {
            Toast.makeText(this, R.string.fav_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val n = favoriteKeys.size
        val idx = currentStyleKey?.let { favoriteKeys.indexOf(it) } ?: -1
        val newIdx = when {
            idx < 0 -> if (dir > 0) 0 else n - 1
            else -> (idx + dir + n) % n
        }
        val r = rhythmByKey[favoriteKeys[newIdx]] ?: return
        applyStyle(r)
        Toast.makeText(
            this,
            getString(R.string.fav_count, newIdx + 1, n, r.nome),
            Toast.LENGTH_SHORT
        ).show()
    }

    // v5.3: o crash toca imediatamente (one-shot). Só funciona com o stream
    // rodando (máquina tocando) — o engine nativo é que controla isso.
    private fun onCrashClicked() {
        drumEngine.playOneShot(DrumEngine.SOUND_CRASH)
    }
}
