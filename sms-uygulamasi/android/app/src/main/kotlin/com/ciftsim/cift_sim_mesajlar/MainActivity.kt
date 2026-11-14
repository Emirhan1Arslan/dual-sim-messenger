package com.ciftsim.cift_sim_mesajlar

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Flutter arayüzü ile Android SMS/SIM katmanı arasındaki köprü. */
class MainActivity : FlutterActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var events: EventChannel.EventSink? = null
    private var pendingRole: MethodChannel.Result? = null
    private var pendingPermissions: MethodChannel.Result? = null
    private var initialAction: Map<String, Any?>? = null
    private var observer: ContentObserver? = null

    private val emitChanged = Runnable { events?.success(mapOf("event" to "changed")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) initialAction = parseIntent(intent)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, "ciftsim/sms").setMethodCallHandler(::onCall)
        EventChannel(messenger, "ciftsim/events").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                    events = sink
                }

                override fun onCancel(arguments: Any?) {
                    events = null
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val action = parseIntent(intent) ?: return
        val sink = events
        if (sink != null) sink.success(mapOf("event" to "action") + action)
        else initialAction = action
    }

    override fun onResume() {
        super.onResume()
        AppState.resumed = true
        SmsStore.clearNameCache()
    }

    override fun onPause() {
        AppState.resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        main.removeCallbacks(emitChanged)
        io.shutdown()
        super.onDestroy()
    }

    // ---- Kanal ----

    private fun onCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = applicationContext
        when (call.method) {
            "getStatus" -> result.success(
                mapOf("isDefault" to isDefaultSmsApp(), "permissionsOk" to hasRequiredPermissions())
            )
            "requestRole" -> requestRole(result)
            "requestPermissions" -> requestAllPermissions(result)
            "openAppSettings" -> {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
                result.success(null)
            }
            "observe" -> {
                registerObserver()
                result.success(null)
            }
            "getSeedColor" -> result.success(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    getColor(android.R.color.system_accent1_600)
                else null
            )
            "getInitialAction" -> {
                result.success(initialAction)
                initialAction = null
            }
            "setActive" -> {
                AppState.activeThread = call.argument<Number>("threadId")?.toLong() ?: -1L
                AppState.activeSub = call.argument<Number>("subId")?.toInt() ?: -1
                result.success(null)
            }
            "getSims" -> result.success(Sims.active(ctx))
            "getDefaultSubId" -> result.success(SmsManager.getDefaultSmsSubscriptionId())
            "getConversations" -> background(result) { SmsStore.conversations(ctx) }
            "getMessages" -> {
                val threadId = call.argument<Number>("threadId")!!.toLong()
                val subId = call.argument<Number>("subId")!!.toInt()
                background(result) { SmsStore.messages(ctx, threadId, subId) }
            }
            "sendSms" -> {
                val address = call.argument<String>("address")!!
                val body = call.argument<String>("body")!!
                val subId = call.argument<Number>("subId")?.toInt() ?: -1
                background(result) {
                    val (threadId, usedSubId) = SmsStore.send(ctx, address, body, subId)
                    mapOf("threadId" to threadId, "subId" to usedSubId)
                }
            }
            "markRead" -> {
                val threadId = call.argument<Number>("threadId")!!.toLong()
                val subId = call.argument<Number>("subId")!!.toInt()
                background(result) {
                    SmsStore.markRead(ctx, threadId, subId)
                    null
                }
            }
            "deleteConversation" -> {
                val threadId = call.argument<Number>("threadId")!!.toLong()
                val subId = call.argument<Number>("subId")!!.toInt()
                background(result) {
                    SmsStore.deleteConversation(ctx, threadId, subId)
                    null
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun background(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            try {
                val value = block()
                main.post { result.success(value) }
            } catch (e: Exception) {
                main.post { result.error("hata", e.message ?: e.javaClass.simpleName, null) }
            }
        }
    }

    // ---- content://sms gözlemcisi ----

    private fun registerObserver() {
        if (observer != null) return
        val created = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // Art arda gelen değişiklikleri tek olayda topla.
                main.removeCallbacks(emitChanged)
                main.postDelayed(emitChanged, 250)
            }
        }
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, created)
            observer = created
        } catch (_: SecurityException) {
        }
    }

    // ---- Rol ve izinler ----

    private fun isDefaultSmsApp(): Boolean =
        getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    private fun allPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    /** Uygulamanın çalışması için şart olanlar; rehber ve bildirim izni isteğe bağlıdır. */
    private fun hasRequiredPermissions(): Boolean = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
    ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestRole(result: MethodChannel.Result) {
        val roles = getSystemService(RoleManager::class.java)
        when {
            roles == null || !roles.isRoleAvailable(RoleManager.ROLE_SMS) -> result.success(false)
            roles.isRoleHeld(RoleManager.ROLE_SMS) -> result.success(true)
            pendingRole != null -> result.success(false)
            else -> {
                pendingRole = result
                @Suppress("DEPRECATION")
                startActivityForResult(
                    roles.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_ROLE
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) {
            pendingRole?.success(isDefaultSmsApp())
            pendingRole = null
        }
    }

    private fun requestAllPermissions(result: MethodChannel.Result) {
        val missing = allPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            missing.isEmpty() -> result.success(true)
            pendingPermissions != null -> result.success(false)
            else -> {
                pendingPermissions = result
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            pendingPermissions?.success(hasRequiredPermissions())
            pendingPermissions = null
        }
    }

    // ---- Intent'ler: bildirime dokunma ve sms:/smsto: bağlantıları ----

    private fun parseIntent(intent: Intent?): Map<String, Any?>? {
        intent ?: return null
        when (intent.action) {
            ACTION_OPEN_CONVERSATION -> return mapOf(
                "type" to "conversation",
                "threadId" to intent.getLongExtra(EXTRA_THREAD_ID, -1L),
                "subId" to intent.getIntExtra(EXTRA_SUB_ID, -1),
                "address" to intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
            )
            Intent.ACTION_SENDTO, Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme !in SMS_SCHEMES) return null
                val specific = data.schemeSpecificPart.orEmpty()
                val address = specific.substringBefore('?').trim()
                var body = intent.getStringExtra("sms_body")
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (body == null && specific.contains('?')) {
                    body = try {
                        Uri.parse("x://x?" + data.encodedSchemeSpecificPart.substringAfter('?'))
                            .getQueryParameter("body")
                    } catch (_: Exception) {
                        null
                    }
                }
                return mapOf("type" to "compose", "address" to address, "body" to body.orEmpty())
            }
        }
        return null
    }

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.ciftsim.cift_sim_mesajlar.OPEN_CONVERSATION"
        const val EXTRA_THREAD_ID = "threadId"
        const val EXTRA_SUB_ID = "subId"
        const val EXTRA_ADDRESS = "address"
        private const val REQUEST_ROLE = 4101
        private const val REQUEST_PERMISSIONS = 4102
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
    }
}
