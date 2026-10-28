package com.shiyi.app.ocr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.shiyi.app.diagnostics.CrashLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 在独立进程（:ocr）中运行 ML Kit。
 * 原生识别库若崩溃或内存不足，只会结束这个进程，主界面不会闪退。
 */
class OcrService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val engine by lazy { OcrEngine(applicationContext) }
    private val barcode by lazy { BarcodeEngine() }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_RECOGNIZE && msg.what != MSG_BARCODE) return
            val what = msg.what
            val path = msg.data?.getString(KEY_PATH)
            val reply = msg.replyTo ?: return
            scope.launch {
                val data = Bundle()
                lock.withLock {
                    runCatching {
                        val f = File(path ?: error("缺少文件路径"))
                        if (what == MSG_BARCODE) barcode.scan(f) else engine.recognize(f)
                    }
                        .onSuccess { data.putStringArrayList(KEY_LINES, ArrayList(it)) }
                        .onFailure {
                            CrashLog.append(applicationContext, "【识别失败】" + it.stackTraceToString().take(3000))
                            data.putString(KEY_ERROR, it.javaClass.simpleName + ": " + (it.message ?: ""))
                        }
                }
                runCatching { reply.send(Message.obtain(null, MSG_RESULT).apply { this.data = data }) }
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        // ML Kit 的初始化 Provider 只在主进程运行，这里手动初始化
        MlKitContext.initializeIfNeeded(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MSG_RECOGNIZE = 1
        const val MSG_RESULT = 2
        const val MSG_BARCODE = 3
        const val KEY_PATH = "path"
        const val KEY_LINES = "lines"
        const val KEY_ERROR = "error"
    }
}

class OcrProcessDiedException : Exception("识别进程意外退出")

/** 主进程侧：绑定 [OcrService]，逐张发送识别请求。 */
class OcrClient(private val context: Context) {

    /**
     * 识别多张图片。返回 null 表示无法连接识别服务（调用方可回退为进程内识别）。
     */
    suspend fun recognizeAll(files: List<File>, onProgress: (Int) -> Unit): List<Result<List<String>>>? {
        val conn = connect() ?: return null
        try {
            return files.mapIndexed { i, f ->
                onProgress(i)
                if (conn.dead) Result.failure(OcrProcessDiedException()) else conn.request(f)
            }
        } finally {
            conn.close()
        }
    }

    /** 识别一张照片中的条码；返回 null 表示无法连接识别服务。 */
    suspend fun scanBarcode(file: File): Result<List<String>>? {
        val conn = connect() ?: return null
        try { return conn.request(file, OcrService.MSG_BARCODE) } finally { conn.close() }
    }

    private suspend fun connect(): Conn? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont ->
            var conn: Conn? = null
            val sc = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) { if (cont.isActive) cont.resume(null); return }
                    conn = Conn(context, this, Messenger(binder))
                    if (cont.isActive) cont.resume(conn) else conn?.close()
                }
                override fun onServiceDisconnected(name: ComponentName?) { conn?.onDied() }
                override fun onBindingDied(name: ComponentName?) {
                    conn?.onDied()
                    if (cont.isActive) cont.resume(null)
                }
                override fun onNullBinding(name: ComponentName?) { if (cont.isActive) cont.resume(null) }
            }
            val ok = runCatching { context.bindService(Intent(context, OcrService::class.java), sc, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!ok) {
                runCatching { context.unbindService(sc) }
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { if (conn == null) runCatching { context.unbindService(sc) } }
        }
    }

    private class Conn(val context: Context, val sc: ServiceConnection, val service: Messenger) {
        @Volatile var dead = false
            private set
        @Volatile private var pending: CancellableContinuation<Result<List<String>>>? = null
        private var closed = false

        fun onDied() {
            dead = true
            pending?.let { p -> pending = null; if (p.isActive) p.resume(Result.failure(OcrProcessDiedException())) }
        }

        suspend fun request(file: File, what: Int = OcrService.MSG_RECOGNIZE): Result<List<String>> =
            withTimeoutOrNull(90_000) {
                suspendCancellableCoroutine { cont ->
                    pending = cont
                    val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
                        if (msg.what == OcrService.MSG_RESULT && pending === cont) {
                            pending = null
                            val lines = msg.data?.getStringArrayList(OcrService.KEY_LINES)
                            val err = msg.data?.getString(OcrService.KEY_ERROR)
                            if (cont.isActive) cont.resume(
                                if (lines != null) Result.success(lines.toList()) else Result.failure(Exception(err ?: "识别失败"))
                            )
                        }
                        true
                    })
                    try {
                        service.send(Message.obtain(null, what).apply {
                            data = Bundle().apply { putString(OcrService.KEY_PATH, file.absolutePath) }
                            replyTo = reply
                        })
                    } catch (e: RemoteException) {
                        pending = null
                        dead = true
                        if (cont.isActive) cont.resume(Result.failure(OcrProcessDiedException()))
                    }
                    cont.invokeOnCancellation { if (pending === cont) pending = null }
                }
            } ?: Result.failure(java.util.concurrent.TimeoutException("识别超时"))

        fun close() {
            if (closed) return
            closed = true
            runCatching { context.unbindService(sc) }
        }
    }
}
