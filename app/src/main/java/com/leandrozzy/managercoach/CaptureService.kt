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
import android.view.WindowManager
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
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        // largura de até 1080 px: o texto continua legível para o OCR e a memória fica menor
        val scale = if (bounds.width() > 1080) 1080f / bounds.width() else 1f
        val w = (bounds.width() * scale).toInt()
        val h = (bounds.height() * scale).toInt()
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
                    val bmp = grab()
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

    /** Último quadro da gravação como bitmap (sem a sobra de cada linha que o ImageReader acrescenta). */
    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride
            val pad = plane.rowStride - ps * img.width
            val wide = Bitmap.createBitmap(img.width + pad / ps, img.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            if (pad == 0) return wide
            val exact = Bitmap.createBitmap(wide, 0, 0, img.width, img.height)
            wide.recycle()
            return exact
        } finally {
            img.close()
        }
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

    /** Mantido para as telas do app (antes trocava o rótulo do botão flutuante). */
    fun refreshOverlay() {}

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
