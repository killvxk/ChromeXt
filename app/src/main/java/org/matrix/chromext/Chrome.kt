package org.matrix.chromext

import android.app.Application
import android.content.Context
import android.os.Handler
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.devtools.getInspectPages
import org.matrix.chromext.devtools.hitDevTools
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.hook.WebViewHook
import org.matrix.chromext.proxy.UserScriptProxy
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.invokeMethod

object Chrome {
  private var mContext: WeakReference<Context>? = null
  private var currentTab: WeakReference<Any>? = null
  private var devToolsReady = false

  var isDev = false
  var isEdge = false
  var isVivaldi = false
  var isBrave = false

  fun init(ctx: Context, packageName: String? = null) {
    val initialized = mContext != null
    mContext = WeakReference(ctx)

    if (initialized || packageName == null) return

    if (ctx is Application) {
      Log.d("Started a WebView based browser")
    }
    isEdge = packageName.startsWith("com.microsoft.emmx")
    isVivaldi = packageName == "com.vivaldi.browser"
    isBrave = packageName.startsWith("com.brave.browser")
    isDev = packageName.endsWith("canary") || packageName.endsWith("dev")
    @Suppress("DEPRECATION")
    val packageInfo = ctx.getPackageManager()?.getPackageInfo(packageName, 0)
    Log.i("Package: ${packageName}, v${packageInfo?.versionName}")
  }

  fun wakeUpDevTools(limit: Int = 10) {
    var waited = 0
    while (!devToolsReady && waited < limit) {
      runCatching {
            hitDevTools().close()
            devToolsReady = true
            Log.i("DevTools woke up")
          }
          .onFailure { Log.d("Waking up DevTools") }
      if (!devToolsReady) Thread.sleep(500)
      waited += 1
    }
  }

  fun getContext(): Context {
    return mContext!!.get()!!
  }

  fun load(className: String): Class<*> {
    return getContext().getClassLoader().loadClass(className)
  }

  fun getTab(): Any? {
    return currentTab?.get()
  }

  fun refreshTab(tab: Any?) {
    if (tab != null) currentTab = WeakReference(tab)
  }

  private fun evaluateJavascript(codes: List<String>, tabId: String) {
    wakeUpDevTools()
    var client = DevToolClient(tabId)
    if (client.isClosed()) {
      hitDevTools().close()
      client = DevToolClient(tabId)
    }
    // client.command(null, "Page.setBypassCSP", JSONObject().put("enabled", true))
    codes.forEach { client.evaluateJavascript(it) }
    client.close()
  }

  fun evaluateJavascript(codes: List<String>) {
    if (codes.size == 0) return

    Handler(getContext().getMainLooper()).post {
      if (WebViewHook.isInit) {
        codes.forEach { WebViewHook.evaluateJavascript(it) }
      } else if (UserScriptHook.isInit) {
        val failed = codes.filter { !UserScriptProxy.evaluateJavascript(it) }
        if (failed.size > 0) {
          thread {
            evaluateJavascript(failed, getTab()!!.invokeMethod() { name == "getId" }.toString())
          }
        }
      }
    }
  }

  fun broadcast(
      event: String,
      data: String,
      excludeSelf: Boolean = true,
      matching: (String) -> Boolean
  ) {
    val code = "window.dispatchEvent(new CustomEvent('${event}', ${data}));"
    Log.d("broadcasting ${event}")
    wakeUpDevTools()
    val pages = getInspectPages()!!
    val tabs = mutableListOf<String>()
    for (i in 0 until pages.length()) {
      val tab = pages.getJSONObject(i)
      if (tab.optString("type") == "page" && matching(tab.optString("url"))) {
        if (tab.optString("description") == "" ||
            !JSONObject(tab.getString("description")).optBoolean("never_attached")) {
          tabs.add(tab.getString("id"))
        }
      }
    }
    if (tabs.size > 1 || !excludeSelf) {
      tabs.forEach { evaluateJavascript(listOf(code), it) }
    }
  }
}
