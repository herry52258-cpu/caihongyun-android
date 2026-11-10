package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.util.UUID

/**
 * 线路刷新。
 *
 * 登录时会把订阅写成本地 File 配置，节点列表之后都读这份缓存，
 * 所以面板上新加的线路要重新登录才看得到。这里用已经保存的登录凭证再拉一次，
 * 覆盖本地配置。正在连接时只重载配置，不停止 VPN；选中的线路还在的话保持选中。
 */
internal object LineRefresh {
    const val AUTO_REFRESH_INTERVAL_MS = 60_000L

    data class Result(val ok: Boolean, val count: Int, val message: String)

    /** 刷新成功后通知界面重画线路列表。自动刷新不弹提示。 */
    @Volatile
    var onLinesUpdated: (() -> Unit)? = null

    /**
     * 仅 debug 包使用：测试时注入订阅正文，避免访问真实面板。
     * release 里 BuildConfig.DEBUG 为 false，这段不会执行。
     */
    @Volatile
    var debugYamlProvider: (() -> String)? = null

    private const val KEY_LAST_AUTO_REFRESH = "last_line_auto_refresh_at"
    private val gate = Any()
    private val mutex = Mutex()
    @Volatile private var lastAutoAt = 0L

    /** 打开 App、从后台回到前台时调用。未登录或 60 秒内刚拉过则跳过，失败不提示。 */
    fun scheduleAuto(context: Context) {
        val app = context.applicationContext
        if (!SetupActivity.isSetupDone(app)) return
        if (!reserve(app, force = false)) return
        Global.launch {
            try {
                refresh(app)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 自动刷新失败保持静默
            }
        }
    }

    /** 刚登录并导入过订阅，短时间内不要再自动拉一次。 */
    fun noteJustFetched(context: Context) {
        reserve(context.applicationContext, force = true)
    }

    suspend fun refresh(context: Context): Result {
        val app = context.applicationContext
        reserve(app, force = true)
        return withContext(Dispatchers.IO) {
            mutex.withLock { refreshLocked(app) }
        }
    }

    suspend fun savedLineNames(context: Context): List<String> {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(5_000) {
                    val active = withProfile { queryActive() } ?: return@withTimeoutOrNull emptyList()
                    val file = app.importedDir.resolve(active.uuid.toString()).resolve("config.yaml")
                    if (!file.isFile) emptyList() else SubscriptionYaml.proxyNames(file.readText())
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun refreshLocked(context: Context): Result {
        val yaml = try {
            loadYaml(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result(false, 0, userFacing(e))
        }
        val count = SubscriptionYaml.countProxies(yaml)
        if (count <= 0) {
            return Result(false, 0, "刷新失败：订阅里没有可用线路，已保留当前线路")
        }
        val installError = try {
            applyYaml(context, yaml)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result(false, 0, userFacing(e))
        }
        if (installError != null) return Result(false, 0, installError)
        withContext(Dispatchers.Main) {
            try {
                onLinesUpdated?.invoke()
            } catch (_: Exception) {
            }
        }
        return Result(true, count, "已更新 $count 条线路")
    }

    private fun loadYaml(context: Context): String {
        if (BuildConfig.DEBUG) {
            debugYamlProvider?.invoke()?.let { return it }
        }
        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(SetupActivity.KEY_AUTH_TOKEN, null)
        var url = prefs.getString(SetupActivity.KEY_SUBSCRIPTION_URL, null)
        if (!token.isNullOrBlank()) {
            try {
                val fresh = fetchSubscribeUrl(token)
                if (!fresh.isNullOrBlank()) {
                    url = fresh
                    prefs.edit().putString(SetupActivity.KEY_SUBSCRIPTION_URL, fresh).apply()
                }
            } catch (_: Exception) {
                // 凭证接口暂时失败时，继续用上次保存的订阅地址
            }
        }
        if (url.isNullOrBlank()) {
            throw IllegalStateException("未登录，请先登录")
        }
        val path = subscriptionPath(url)
        if (path.isBlank()) throw IllegalStateException("没有可用的订阅")
        val (code, content) = SetupActivity.httpApiRequest(
            "GET", path,
            reqHeaders = mapOf("User-Agent" to "ClashMetaForAndroid/2.10.1")
        )
        if (code == 401 || code == 403) {
            throw IllegalStateException("登录已失效或套餐不可用，请重新登录")
        }
        if (code != 200) throw IllegalStateException("获取订阅失败（HTTP $code）")
        if (!content.contains("proxies:") && !content.contains("proxy-providers:")) {
            throw IllegalStateException("订阅格式无法识别")
        }
        return content
    }

    private fun fetchSubscribeUrl(token: String): String? {
        val (code, resp) = SetupActivity.httpApiRequest(
            "GET", "/api/v1/user/getSubscribe",
            reqHeaders = mapOf("Authorization" to token)
        )
        if (code != 200) return null
        val url = JSONObject(resp).optJSONObject("data")?.optString("subscribe_url", "").orEmpty()
        return url.takeIf { it.isNotBlank() }
    }

    private fun subscriptionPath(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val path = uri.rawPath ?: ""
            if (path.isBlank()) "" else path + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        } catch (_: Exception) {
            val tail = url.substringAfterLast("/s/", "")
            if (tail.isBlank()) "" else "/s/$tail"
        }
    }

    /**
     * 写入当前正在使用的那份配置（同一个 uuid），这样原来记住的线路选择还能对上。
     * 不调用停止 VPN 的接口；服务在跑的话，配置变更会在原隧道里重载。
     */
    private suspend fun applyYaml(context: Context, yaml: String): String? {
        val selections = captureSelections()
        if (Remote.broadcasts.clashRunning) {
            for ((group, name) in selections) {
                try {
                    withClash { patchSelector(group, name) }
                } catch (_: Exception) {
                }
            }
        }
        val wasRunning = Remote.broadcasts.clashRunning
        val loaded = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val observer = object : RemoteObserver() {
            override fun onProfileLoaded() {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }

            override fun onStopped(cause: String?) {
                if (!stopped.isCompleted) stopped.complete(Unit)
            }
        }
        if (wasRunning) Remote.broadcasts.addObserver(observer)
        try {
            withContext(NonCancellable) {
                writeProfile(context, yaml)
            }
            if (!wasRunning) return null
            val which = withTimeoutOrNull(8_000) {
                select<String> {
                    loaded.onAwait { "loaded" }
                    stopped.onAwait { "stopped" }
                }
            }
            if (which == "stopped" || !Remote.broadcasts.clashRunning) {
                return "刷新失败：更新配置时连接中断，请重新连接"
            }
            restoreSelections(selections)
            return null
        } finally {
            if (wasRunning) Remote.broadcasts.removeObserver(observer)
        }
    }

    private suspend fun writeProfile(context: Context, yaml: String) {
        val active = try {
            withProfile { queryActive() }
        } catch (_: Exception) {
            null
        }
        if (active != null) {
            try {
                withProfile {
                    patch(active.uuid, active.name, active.source, active.interval, active.ageSecretKey)
                }
                val cfg = context.pendingDir.resolve(active.uuid.toString()).resolve("config.yaml")
                cfg.parentFile?.mkdirs()
                cfg.writeText(yaml)
                withProfile { commit(active.uuid) }
                return
            } catch (_: Exception) {
                // 原地更新失败时再新建一份，仍然不停止 VPN
            }
        }
        val uuid = withProfile {
            create(type = Profile.Type.File, name = "彩虹猫订阅", source = "")
        }
        val cfg = context.pendingDir.resolve(uuid.toString()).resolve("config.yaml")
        cfg.parentFile?.mkdirs()
        cfg.writeText(yaml)
        withProfile {
            commit(uuid)
            queryByUUID(uuid)?.let { setActive(it) }
        }
    }

    private suspend fun captureSelections(): List<Pair<String, String>> {
        if (!Remote.broadcasts.clashRunning) return emptyList()
        return try {
            val names = withClash { queryProxyGroupNames(true) }
            names.mapNotNull { group ->
                val now = withClash { queryProxyGroup(group, ProxySort.Default) }.now
                now.takeIf { it.isNotBlank() }?.let { group to it }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun restoreSelections(selections: List<Pair<String, String>>) {
        if (!Remote.broadcasts.clashRunning) return
        for ((group, name) in selections) {
            try {
                val current = withClash { queryProxyGroup(group, ProxySort.Default) }
                if (current.proxies.any { it.name == name } && current.now != name) {
                    withClash { patchSelector(group, name) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reserve(context: Context, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        synchronized(gate) {
            val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val last = maxOf(lastAutoAt, prefs.getLong(KEY_LAST_AUTO_REFRESH, 0L))
            if (!force && now - last < AUTO_REFRESH_INTERVAL_MS) return false
            lastAutoAt = now
            prefs.edit().putLong(KEY_LAST_AUTO_REFRESH, now).apply()
            return true
        }
    }

    private fun userFacing(t: Throwable): String {
        val raw = t.message?.lineSequence()?.firstOrNull()?.take(80) ?: "请稍后重试"
        val cleaned = raw
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "")
            .trim()
        return if (cleaned.isBlank()) "刷新失败，请稍后重试" else "刷新失败：$cleaned"
    }

    private open class RemoteObserver : Broadcasts.Observer {
        override fun onServiceRecreated() {}
        override fun onStarted() {}
        override fun onStopped(cause: String?) {}
        override fun onProfileChanged() {}
        override fun onProfileUpdateCompleted(uuid: UUID?) {}
        override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {}
        override fun onProfileLoaded() {}
    }
}
