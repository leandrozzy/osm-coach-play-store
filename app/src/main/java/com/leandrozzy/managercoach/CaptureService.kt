package com.leandrozzy.managercoach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captura da tela pela gravação de tela do Android (MediaProjection), com a permissão que o usuário dá a cada
 * captura. Roda como serviço em primeiro plano com notificação fixa (Encerrar / Abrir o jogo / Abrir o app).
 * No Android 14+ o usuário pode compartilhar só o app do jogo. Não clica nem toca em nada: só lê a imagem.
 */
class CaptureService : Service() {
    companion object {
        @Volatile
        var instance: CaptureService? = null

        private const val ACTION_START = "com.leandrozzy.managercoach.CAPTURE_START"
        private const val ACTION_STOP = "com.leandrozzy.managercoach.CAPTURE_STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val CHANNEL_ALERT = "capture_alert"
        private const val NOTIF_ID = 4242
        private const val NOTICE_ID = 4243

        /** Captura rodando agora (permissão dada e serviço ativo). */
        val running: Boolean get() = instance?.projection != null

        /** Inicia depois do "Permitir" do Android (resultado de createScreenCaptureIntent). */
        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            instance?.finish(processSession = true)
        }

        private fun stopIntent(ctx: Context): PendingIntent = PendingIntent.getService(
            ctx, 1, Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var loop: Job? = null
    @Volatile private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(processSession = true)
                return START_NOT_STICKY
            }
            ACTION_START -> begin(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        ensureChannels()
        // Android 14: o serviço precisa estar em primeiro plano (tipo mediaProjection) antes de pegar a projeção.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification())
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            if (data == null) null else mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            Diag.lastError = "Gravação de tela: " + (e.message ?: e.javaClass.simpleName)
            null
        }
        if (mp == null) {
            finish(processSession = false)
            return
        }
        projection = mp
        // o usuário parou a gravação pelo sistema (ícone de transmissão, tela bloqueada): encerra e processa
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                finish(processSession = true)
            }
        }, Handler(Looper.getMainLooper()))
        val (w, h) = captureSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = try {
            mp.createVirtualDisplay(
                "ManagerCoach", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } catch (e: Exception) {
            Diag.lastError = "Gravação de tela: " + (e.message ?: e.javaClass.simpleName)
            null
        }
        if (display == null) {
            finish(processSession = false)
            return
        }
        instance = this
        Diag.serviceConnected = true
        Health.beat(applicationContext)
        mainHandler.post { showOverlay() }
        startLoop()
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            val pipeline = FramePipeline.get(applicationContext)
            while (isActive) {
                Health.beat(applicationContext)
                val sid = Control.activeSession(applicationContext)
                if (sid == null) {
                    // captura encerrada pelo app: para a gravação também
                    finish(processSession = false)
                    break
                }
                // O próprio app na tela não é o jogo.
                if (AppVisible.resumed) {
                    delay(1200L)
                    continue
                }
                try {
                    // os botões flutuantes somem por um instante para não sair na imagem lida
                    val hid = withContext(Dispatchers.Main) {
                        val b = overlayBox
                        if (b != null && b.alpha > 0f) { b.alpha = 0f; true } else false
                    }
                    if (hid) delay(140L)
                    val bmp = try { grab() } finally {
                        if (hid) withContext(Dispatchers.Main) { overlayBox?.alpha = 0.95f }
                    }
                    if (bmp != null) {
                        try {
                            pipeline.onShot(sid, bmp)
                        } finally {
                            bmp.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Diag.lastError = "Captura: " + (e.message ?: e.javaClass.simpleName)
                }
                delay(pipeline.nextDelayMs())
            }
        }
    }

    /**
     * Tamanho da gravação na orientação ATUAL da tela (o jogo roda deitado): lado menor até 1080 px, o texto continua
     * legível para o OCR e a memória fica menor.
     */
    private fun captureSize(): Pair<Int, Int> {
        val d = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        val short = minOf(p.x, p.y).coerceAtLeast(1)
        val scale = if (short > 1080) 1080f / short else 1f
        return Pair((p.x * scale).toInt(), (p.y * scale).toInt())
    }

    /**
     * Girou a tela (o jogo é deitado, o app é em pé): a gravação acompanha. Sem isso o Android encolhe a imagem
     * deitada dentro de um quadro em pé, com faixas pretas, e nada bate com as posições do jogo.
     */
    private fun followRotation() {
        val r = reader ?: return
        val vd = display ?: return
        val (w, h) = captureSize()
        if (w == r.width && h == r.height) return
        try {
            val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd.resize(w, h, resources.displayMetrics.densityDpi)
            vd.surface = nr.surface
            reader = nr
            r.close()
            Diag.log("Gravação ajustada à tela: ${w}x$h")
        } catch (e: Exception) {
            Diag.lastError = "Gravação (girar): " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Último quadro da gravação como bitmap (sem a sobra de cada linha que o ImageReader acrescenta). */
    private fun grab(): Bitmap? {
        followRotation()
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride
            val pad = plane.rowStride - ps * img.width
            val wide = Bitmap.createBitmap(img.width + pad / ps, img.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            val exact = if (pad == 0) wide else Bitmap.createBitmap(wide, 0, 0, img.width, img.height).also { wide.recycle() }
            return trimBars(exact)
        } finally {
            img.close()
        }
    }

    /**
     * Faixas pretas em volta da imagem (quadro de outra orientação, antes de a gravação acompanhar a rotação):
     * corta para sobrar só o conteúdo.
     */
    private fun trimBars(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        fun darkRow(y: Int): Boolean {
            var i = 0
            while (i < 24) {
                val c = b.getPixel((w * (i + 0.5f) / 24f).toInt(), y)
                if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 36) return false
                i++
            }
            return true
        }
        fun darkCol(x: Int): Boolean {
            var i = 0
            while (i < 24) {
                val c = b.getPixel(x, (h * (i + 0.5f) / 24f).toInt())
                if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 36) return false
                i++
            }
            return true
        }
        var top = 0
        while (top < h / 2 && darkRow(top)) top += 2
        var bottom = h - 1
        while (bottom > h / 2 && darkRow(bottom)) bottom -= 2
        var left = 0
        while (left < w / 2 && darkCol(left)) left += 2
        var right = w - 1
        while (right > w / 2 && darkCol(right)) right -= 2
        val cw = right - left + 1
        val ch = bottom - top + 1
        // só corta faixa de verdade (mais de 4% de cada lado) e nunca a tela inteira escura
        val barV = top > h * 0.04f && (h - 1 - bottom) > h * 0.04f
        val barH = left > w * 0.04f && (w - 1 - right) > w * 0.04f
        if ((!barV && !barH) || cw < w / 4 || ch < h / 4) return b
        val x0 = if (barH) left else 0
        val y0 = if (barV) top else 0
        val out = Bitmap.createBitmap(b, x0, y0, if (barH) cw else w, if (barV) ch else h)
        b.recycle()
        return out
    }

    /** Aviso curto (ex.: tática montada diferente da gerada), em notificação. */
    fun notice(text: String) {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Manager Coach IA")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(20000L)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTICE_ID, n)
        } catch (e: SecurityException) {
            // sem permissão de notificação: o aviso também fica no registro
        }
    }

    /** Chamado pelas telas do app ao abrir/fechar: troca o rótulo do botão de alternar. */
    fun refreshOverlay() {
        mainHandler.post { toggleView?.text = if (AppVisible.resumed) "⚽ Jogo" else "📱 App" }
    }

    // ------------------------------------------------------------ botões flutuantes (permissão "sobrepor a outros apps")

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayBox: LinearLayout? = null
    private var toggleView: TextView? = null
    private var endView: TextView? = null
    private var panelView: TextView? = null
    private var armedAt = 0L

    private fun chip(text: String, color: Int, dp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.WHITE)
        textSize = 12f
        setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 40 * dp
            setColor(color)
        }
    }

    /** Onde os botões estão (0..1): o OCR ignora o que cair embaixo deles. */
    private fun markBounds(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        if (sw <= 0f || sh <= 0f) return
        Overlay.bounds = floatArrayOf(loc[0] / sw, loc[1] / sh, (loc[0] + v.width) / sw, (loc[1] + v.height) / sh)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayBox != null || !android.provider.Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val toggle = chip(if (AppVisible.resumed) "⚽ Jogo" else "📱 App", 0xE61F6BFF.toInt(), dp)
        val tactic = chip("📋 Tática", 0xE6B8860B.toInt(), dp)
        val end = chip("■ Encerrar", 0xE6D32F2F.toInt(), dp)
        val maxW = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val panel = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            maxWidth = maxW
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xF20B1220.toInt())
            }
            visibility = View.GONE
        }
        fun gap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * dp).toInt() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.95f
            addView(toggle)
            addView(tactic, gap())
            addView(end, gap())
            addView(panel, gap())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        }
        fun dragOrTap(v: View, onTap: () -> Unit) {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            v.setOnTouchListener { _: View, e: MotionEvent ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (kotlin.math.abs(dx) > 12 * dp || kotlin.math.abs(dy) > 12 * dp) moved = true
                        if (moved) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try { wm.updateViewLayout(box, lp) } catch (ex: Exception) { }
                            box.post { markBounds(box) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap()
                }
                true
            }
        }
        dragOrTap(toggle) { onToggleTap() }
        dragOrTap(tactic) { onTacticTap() }
        dragOrTap(end) { onEndTap() }
        dragOrTap(panel) { panel.visibility = View.GONE }
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> markBounds(v) }
        try {
            wm.addView(box, lp)
            overlayBox = box
            toggleView = toggle
            endView = end
            panelView = panel
        } catch (e: Exception) {
            Diag.lastError = "Botões flutuantes: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun removeOverlay() {
        val v = overlayBox ?: return
        Overlay.bounds = null
        overlayBox = null
        toggleView = null
        endView = null
        panelView = null
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Exception) { }
    }

    /** No app vai para o jogo; no jogo volta para o app. A captura continua. */
    private fun onToggleTap() {
        try {
            if (AppVisible.resumed) {
                val i = packageManager.getLaunchIntentForPackage(OSM_PACKAGE) ?: return
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        } catch (e: Exception) {
            Diag.lastError = "Alternar app/jogo: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** Encerrar pede um segundo toque (evita encerrar sem querer ao arrastar). */
    private fun onEndTap() {
        val tv = endView ?: return
        val now = System.currentTimeMillis()
        if (armedAt == 0L || now - armedAt > 4000L) {
            armedAt = now
            tv.text = "Toque de novo"
            tv.postDelayed({ if (armedAt != 0L && System.currentTimeMillis() - armedAt >= 4000L) { armedAt = 0L; tv.text = "■ Encerrar" } }, 4100L)
            return
        }
        armedAt = 0L
        android.widget.Toast.makeText(this, "Captura encerrada. Processando…", android.widget.Toast.LENGTH_SHORT).show()
        finish(processSession = true)
    }

    /** Painel com a tática do slot atual, para montar no jogo sem trocar de app. */
    private fun onTacticTap() {
        val pv = panelView ?: return
        if (pv.visibility == View.VISIBLE) {
            pv.visibility = View.GONE
            return
        }
        val app = applicationContext
        scope.launch {
            val text = try {
                val slot = Diag.currentSlot
                if (slot <= 0) "Entre no slot (pré-jogo) para eu saber qual tática mostrar."
                else if (!Premium.allows(slot)) "S$slot faz parte do Premium."
                else {
                    val repo = Repo(app)
                    val p = repo.dao.plan(slot, "tactic")
                    val j = p?.json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    if (p == null || j == null) "S$slot: nenhuma tática gerada. Gere no app (aba Tática)."
                    else {
                        val ok = Director.tacticValid(j, p.at, repo.fieldMap(slot), System.currentTimeMillis())
                        "S$slot • vs ${j.optString("rival")}\n" + (if (ok) "" else "⚠ Tática de outro jogo: gere de novo.\n") + Director.tacticSummary(j)
                    }
                }
            } catch (e: Exception) {
                "Não consegui carregar a tática: " + (e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                panelView?.text = text + "\n(toque para fechar)"
                panelView?.visibility = View.VISIBLE
            }
        }
    }

    @Synchronized
    fun finish(processSession: Boolean) {
        if (finished) return
        finished = true
        loop?.cancel()
        try { display?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        display = null
        reader = null
        projection = null
        instance = null
        mainHandler.post { removeOverlay() }
        Diag.serviceConnected = false
        if (processSession && Control.activeSession(applicationContext) != null) {
            AppScope.scope.launch { Control.end(applicationContext) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) finish(processSession = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Captura do jogo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aparece enquanto o app lê a tela do jogo"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Avisos durante a captura", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Lendo o jogo")
            .setContentText("Navegue pelas telas do jogo. Toque em Encerrar quando terminar.")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "■ Encerrar", stopIntent(this)).build())
            .addAction(Notification.Action.Builder(null, "📱 App", openApp).build())
        packageManager.getLaunchIntentForPackage(OSM_PACKAGE)?.let { game ->
            val pi = PendingIntent.getActivity(this, 3, game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "⚽ Jogo", pi).build())
        }
        return b.build()
    }
}
