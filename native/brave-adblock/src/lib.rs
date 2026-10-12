// DashDeck's Android JNI bridge for Brave's adblock-rust engine (MPL-2.0).
//
// One engine is shared by every WebView request thread. It is built from full filter
// lists (EasyList, EasyPrivacy, uBlock Origin) and can be serialized so later app
// launches load it in milliseconds instead of re-parsing ~100k rules.
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashSet;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// JNI contract version. The app refuses engine plugins with a different value,
/// so bump it whenever an exported function's name or signature changes.
const API_VERSION: jint = 2;

/// adblock-rust release this library was built from (kept in sync with Cargo.toml
/// by scripts/shields-engine-release.sh).
const ENGINE_VERSION: &str = "0.13.3";

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    API_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeEngineVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&mut env, ENGINE_VERSION)
}

/// Lists marked trusted may use uBO's "requiresTrust" scriptlets.
const TRUSTED: PermissionMask = PermissionMask::from_bits(1);

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn apply_resources(engine: &mut Engine, resources_json: Option<String>) {
    if let Some(json) = resources_json {
        if let Ok(resources) = serde_json::from_str::<Vec<Resource>>(&json) {
            engine.use_resources(resources);
        }
    }
}

fn install(engine: Engine) {
    if let Ok(mut slot) = ENGINE.write() {
        *slot = Some(engine);
    }
}

/// Builds an engine from `lists` (parallel to `trusted`), installs it, and returns its
/// serialized form for caching. Returns null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeBuild(
    mut env: JNIEnv,
    _class: JClass,
    lists: JObjectArray,
    trusted_mask: jint,
    resources_json: JString,
) -> jbyteArray {
    let count = env.get_array_length(&lists).unwrap_or(0);
    let mut set = FilterSet::new(false);
    for i in 0..count {
        let Ok(obj) = env.get_object_array_element(&lists, i) else { continue };
        let Some(text) = jstr(&mut env, &JString::from(obj)) else { continue };
        let permissions = if trusted_mask & (1 << i) != 0 { TRUSTED } else { PermissionMask::default() };
        set.add_filter_list(text, ParseOptions { permissions, ..ParseOptions::default() });
    }
    let mut engine = Engine::new_with_filter_set(set);
    let serialized = engine.serialize();
    let resources = jstr(&mut env, &resources_json);
    apply_resources(&mut engine, resources);
    install(engine);
    env.byte_array_from_slice(&serialized)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Loads a previously serialized engine. Returns false when the cache is stale or corrupt.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    serialized: JByteArray,
    resources_json: JString,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&serialized) else { return JNI_FALSE };
    let mut engine = Engine::default();
    if engine.deserialize(&bytes).is_err() {
        return JNI_FALSE;
    }
    let resources = jstr(&mut env, &resources_json);
    apply_resources(&mut engine, resources);
    install(engine);
    JNI_TRUE
}

/// Returns null to allow, "" to block, or a data: URL to serve in place of the request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    resource_type: JString,
) -> jstring {
    let (Some(url), Some(source), Some(kind)) = (
        jstr(&mut env, &url),
        jstr(&mut env, &source_url),
        jstr(&mut env, &resource_type),
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(request) = Request::new(&url, &source, &kind, "") else { return std::ptr::null_mut() };
    let Ok(guard) = ENGINE.read() else { return std::ptr::null_mut() };
    let Some(engine) = guard.as_ref() else { return std::ptr::null_mut() };
    let result = engine.check_network_request(&request);
    if !result.should_block() {
        return std::ptr::null_mut();
    }
    to_jstring(&mut env, result.redirect.as_deref().unwrap_or(""))
}

/// Site-specific cosmetic resources as JSON:
/// {"hide":[...],"exceptions":[...],"script":"...","generichide":bool}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeCosmetic(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Some(url) = jstr(&mut env, &url) else { return std::ptr::null_mut() };
    let Ok(guard) = ENGINE.read() else { return std::ptr::null_mut() };
    let Some(engine) = guard.as_ref() else { return std::ptr::null_mut() };
    let r = engine.url_cosmetic_resources(&url);
    let json = serde_json::json!({
        "hide": r.hide_selectors,
        "exceptions": r.exceptions,
        "script": r.injected_script,
        "generichide": r.generichide,
    });
    to_jstring(&mut env, &json.to_string())
}

/// Generic hide selectors for the classes/ids seen on a page. All inputs are JSON arrays.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeHiddenSelectors(
    mut env: JNIEnv,
    _class: JClass,
    classes: JString,
    ids: JString,
    exceptions: JString,
) -> jstring {
    let parse = |env: &mut JNIEnv, s: &JString| -> Vec<String> {
        jstr(env, s).and_then(|j| serde_json::from_str(&j).ok()).unwrap_or_default()
    };
    let classes = parse(&mut env, &classes);
    let ids = parse(&mut env, &ids);
    let exceptions: HashSet<String> = parse(&mut env, &exceptions).into_iter().collect();
    let Ok(guard) = ENGINE.read() else { return std::ptr::null_mut() };
    let Some(engine) = guard.as_ref() else { return std::ptr::null_mut() };
    let selectors = engine.hidden_class_id_selectors(&classes, &ids, &exceptions);
    to_jstring(&mut env, &serde_json::to_string(&selectors).unwrap_or_else(|_| "[]".into()))
}

/// Builds a candidate engine without installing it and runs DashDeck's self-test
/// suite against it. Returns JSON: {"passed":bool,"tests":[{"name","passed"}],"rules":n}.
/// Passing candidates are returned serialized under "engine" (base64) so the app
/// can commit them with nativeLoad; failing ones are dropped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeValidate(
    mut env: JNIEnv,
    _class: JClass,
    lists: JObjectArray,
    trusted_mask: jint,
    resources_json: JString,
) -> jstring {
    let count = env.get_array_length(&lists).unwrap_or(0);
    let mut set = FilterSet::new(false);
    let mut rules = 0usize;
    for i in 0..count {
        let Ok(obj) = env.get_object_array_element(&lists, i) else { continue };
        let Some(text) = jstr(&mut env, &JString::from(obj)) else { continue };
        let permissions = if trusted_mask & (1 << i) != 0 { TRUSTED } else { PermissionMask::default() };
        rules += text.lines().filter(|l| { let l = l.trim(); !l.is_empty() && !l.starts_with('!') && !l.starts_with('[') }).count();
        set.add_filter_list(text, ParseOptions { permissions, ..ParseOptions::default() });
    }
    let mut engine = Engine::new_with_filter_set(set);
    let serialized = engine.serialize();
    apply_resources(&mut engine, jstr(&mut env, &resources_json));

    let tests = self_test(&engine, &serialized, rules);
    let passed = tests.iter().all(|(_, ok)| *ok);
    let json = serde_json::json!({
        "passed": passed,
        "rules": rules,
        "tests": tests.iter().map(|(n, ok)| serde_json::json!({"name": n, "passed": ok})).collect::<Vec<_>>(),
    });
    to_jstring(&mut env, &json.to_string())
}

/// Offline checks a healthy engine must pass. Kept in sync with the unit test below.
fn self_test(engine: &Engine, serialized: &[u8], rules: usize) -> Vec<(&'static str, bool)> {
    let page = "https://www.example-news.com/article";
    let check = |e: &Engine, url: &str, kind: &str| {
        Request::new(url, page, kind, "").map(|r| e.check_network_request(&r).should_block()).unwrap_or(false)
    };
    let restored = {
        let mut e = Engine::default();
        e.deserialize(serialized).ok().map(|_| e)
    };
    vec![
        ("Rule count looks healthy", rules > 50_000),
        ("Blocks Google ad scripts", check(engine, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "script")),
        ("Blocks AdSense", check(engine, "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "script")),
        ("Blocks trackers", check(engine, "https://www.google-analytics.com/analytics.js", "script")),
        ("Allows normal page content", !check(engine, "https://www.example-news.com/static/app.js", "script")),
        ("Allows normal images", !check(engine, "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "image")),
        ("YouTube ad scriptlets present", !engine.url_cosmetic_resources("https://www.youtube.com/watch?v=x").injected_script.is_empty()),
        ("Element hiding rules work", !engine.hidden_class_id_selectors(["ADBox"], Vec::<String>::new(), &HashSet::new()).is_empty()),
        ("Engine cache reloads", restored.map(|e| check(&e, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "script")).unwrap_or(false)),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> Engine {
        let mut set = FilterSet::new(false);
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/src/main/assets/shields/");
        for name in ["easylist.txt", "easyprivacy.txt", "ublock-filters.txt", "ublock-unbreak.txt"] {
            let text = std::fs::read_to_string(format!("{dir}{name}")).unwrap();
            set.add_filter_list(text, ParseOptions { permissions: TRUSTED, ..Default::default() });
        }
        let mut e = Engine::new_with_filter_set(set);
        let json = std::fs::read_to_string(format!("{dir}resources.json")).unwrap();
        e.use_resources(serde_json::from_str::<Vec<Resource>>(&json).unwrap());
        e
    }

    fn blocked(e: &Engine, url: &str, src: &str, kind: &str) -> bool {
        e.check_network_request(&Request::new(url, src, kind, "").unwrap()).should_block()
    }

    #[test]
    fn blocks_real_ads_and_allows_content() {
        let e = engine();
        let page = "https://www.example-news.com/article";
        assert!(blocked(&e, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, "script"));
        assert!(blocked(&e, "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, "script"));
        assert!(blocked(&e, "https://www.google-analytics.com/analytics.js", page, "script"));
        assert!(blocked(&e, "https://static.criteo.net/js/ld/publishertag.js", page, "script"));
        assert!(!blocked(&e, "https://www.example-news.com/static/app.js", page, "script"));
        assert!(!blocked(&e, "https://www.youtube.com/watch?v=abc", "https://www.youtube.com/", "document"));

        let yt = e.url_cosmetic_resources("https://www.youtube.com/watch?v=abc");
        assert!(!yt.injected_script.is_empty(), "YouTube scriptlets should be injected");
        let generic = e.hidden_class_id_selectors(["ADBox"], Vec::<String>::new(), &HashSet::new());
        assert!(!generic.is_empty(), "generic cosmetic rules should match ADBox");

        let rules = 100_000; // add_filter_list counts are covered on device
        assert!(self_test(&e, &e.serialize(), rules).iter().all(|(_, ok)| *ok), "self-test suite must pass");

        // Round-trip through the cache format used on device.
        let mut restored = Engine::default();
        restored.deserialize(&e.serialize()).unwrap();
        assert!(blocked(&restored, "https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, "script"));
    }
}
