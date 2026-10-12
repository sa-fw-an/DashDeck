/*
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package org.dashdeck.app.web

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.dashdeck.app.data.BrowserPreferences
import org.json.JSONObject

/**
 * Cosmetic filtering for DashDeck Shields: element hiding and uBlock Origin
 * scriptlets (which is what removes YouTube's ads), driven by adblock-rust.
 */
object ShieldsCosmetics {
    private const val BRIDGE = "__dashdeckShields"

    fun install(webView: WebView) {
        val appContext = webView.context.applicationContext
        webView.addJavascriptInterface(Bridge(appContext), BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DOCUMENT_START_JS, setOf("*"))
        }
    }

    /**
     * Fallback for pages whose CSP or Trusted Types policy blocked the scriptlets
     * at document start. evaluateJavascript is not subject to page CSP.
     */
    fun injectPendingScriptlets(webView: WebView, pageUrl: String?) {
        if (pageUrl == null || !AdBlocker.isEnabledFor(webView.context, pageUrl)) return
        val script = BraveAdblockNative.cosmeticResources(pageUrl)
            ?.let { runCatching { JSONObject(it).optString("script") }.getOrNull() }
            ?.takeIf { it.isNotBlank() } ?: return
        webView.evaluateJavascript(
            "(function(){if(window.__ddScriptletsDone)return;window.__ddScriptletsDone=true;\n$script\n})();",
            null
        )
    }

    private class Bridge(private val context: Context) {
        @JavascriptInterface
        fun resources(url: String?): String? {
            if (url.isNullOrBlank() || !AdBlocker.isEnabledFor(context, url)) return null
            BraveAdblockNative.warmUp(context)
            return BraveAdblockNative.cosmeticResources(url)
        }

        @JavascriptInterface
        fun hidden(classes: String?, ids: String?, exceptions: String?): String? {
            if (!BrowserPreferences.isShieldsEnabled(context)) return null
            return BraveAdblockNative.hiddenSelectors(classes ?: "[]", ids ?: "[]", exceptions ?: "[]")
        }
    }

    private val DOCUMENT_START_JS = """
(function () {
  if (window.__ddShields) return;
  window.__ddShields = true;
  var bridge = window.$BRIDGE;
  if (!bridge) return;
  var raw = null;
  try { raw = bridge.resources(location.href); } catch (e) {}
  if (!raw) return;
  var res;
  try { res = JSON.parse(raw); } catch (e) { return; }

  // 1. Scriptlets (run before page scripts). Pages with strict CSP / Trusted
  //    Types reject this; DashDeck then injects them natively on page start.
  if (res.script) {
    try {
      var src = res.script;
      if (window.trustedTypes && trustedTypes.createPolicy) {
        try { src = trustedTypes.createPolicy('dashdeck-shields', { createScript: function (s) { return s; } }).createScript(src); } catch (e) {}
      }
      (0, eval)(src);
      window.__ddScriptletsDone = true;
    } catch (e) {}
  }

  // 2. Element hiding. Constructable stylesheets are not subject to style-src CSP.
  var sheet = null, styleEl = null, rules = [];
  function addRules(selectors) {
    var fresh = [];
    for (var i = 0; i < selectors.length; i++) {
      var s = selectors[i];
      if (s && rules.indexOf(s) < 0) { rules.push(s); fresh.push(s); }
    }
    if (!fresh.length) return;
    var css = '';
    for (var j = 0; j < fresh.length; j++) css += fresh[j] + '{display:none!important}\n';
    try {
      if (!sheet && 'adoptedStyleSheets' in document) {
        sheet = new CSSStyleSheet();
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      }
      if (sheet) {
        for (var k = 0; k < fresh.length; k++) {
          try { sheet.insertRule(fresh[k] + '{display:none!important}', sheet.cssRules.length); } catch (e) {}
        }
        return;
      }
    } catch (e) {}
    if (!styleEl) {
      styleEl = document.createElement('style');
      (document.head || document.documentElement).appendChild(styleEl);
    }
    styleEl.textContent += css;
  }
  if (res.hide && res.hide.length) addRules(res.hide);
  if (res.generichide) return;

  // 3. Generic rules keyed by class/id, looked up as new elements appear.
  var exceptions = JSON.stringify(res.exceptions || []);
  var seenClasses = Object.create(null), seenIds = Object.create(null);
  var pendingClasses = [], pendingIds = [], scheduled = false;
  function collect(el) {
    if (!el || el.nodeType !== 1) return;
    var id = el.id;
    if (id && typeof id === 'string' && !seenIds[id]) { seenIds[id] = 1; pendingIds.push(id); }
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) {
      var c = cl[i];
      if (!seenClasses[c]) { seenClasses[c] = 1; pendingClasses.push(c); }
    }
  }
  function flush() {
    scheduled = false;
    if (!pendingClasses.length && !pendingIds.length) return;
    var classes = pendingClasses.splice(0, 2000), ids = pendingIds.splice(0, 2000);
    var out = null;
    try { out = bridge.hidden(JSON.stringify(classes), JSON.stringify(ids), exceptions); } catch (e) {}
    if (out) { try { addRules(JSON.parse(out)); } catch (e) {} }
    if (pendingClasses.length || pendingIds.length) schedule();
  }
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(flush, 120);
  }
  function scan(root) {
    collect(root);
    if (root.querySelectorAll) {
      var all = root.querySelectorAll('[id],[class]');
      for (var i = 0; i < all.length; i++) collect(all[i]);
    }
    schedule();
  }
  function start() {
    scan(document.documentElement);
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === 'attributes') { collect(r.target); continue; }
        for (var j = 0; j < r.addedNodes.length; j++) {
          var n = r.addedNodes[j];
          if (n.nodeType === 1) scan(n);
        }
      }
      schedule();
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
  }
  if (document.documentElement) start();
  else document.addEventListener('readystatechange', start, { once: true });
})();
""".trimIndent()
}
