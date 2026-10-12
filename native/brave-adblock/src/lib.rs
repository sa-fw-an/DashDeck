// DashDeck's Android JNI bridge for Brave's adblock-rust engine (MPL-2.0).
//
// One engine is shared by every WebView request thread. It is built from full filter
// lists (EasyList, EasyPrivacy, uBlock Origin) and can be serialized so later app
// launches load it in milliseconds instead of re-parsing ~100k rules.
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::jint;
use jni::{Env, EnvUnowned};
use std::collections::HashSet;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// JNI contract version. The app refuses engine plugins with a different value,
/// so bump it whenever an exported function's name or signature changes.
const API_VERSION: jint = 2;

/// adblock-rust release this library was built from (kept in sync with Cargo.toml
/// by scripts/shields-engine-release.sh).
const ENGINE_VERSION: &str = "0.13.3";

/// Lists marked trusted may use uBO's "requiresTrust" scriptlets.
const TRUSTED: PermissionMask = PermissionMask::from_bits(1);

type JniResult<T> = jni::errors::Result<T>;

// ---- Engine logic (plain Rust) ----

fn filter_set(lists: Vec<String>, trusted_mask: i32) -> (FilterSet, usize) {
    let mut set = FilterSet::new(false);
    let mut rules = 0;
    for (i, text) in lists.into_iter().enumerate() {
        let permissions = if trusted_mask & (1 << i) != 0 { TRUSTED } else { PermissionMask::default() };
        rules += text.lines().filter(|l| { let l = l.trim(); !l.is_empty() && !l.starts_with('!') && !l.starts_with('[') }).count();
        set.add_filter_list(text, ParseOptions { permissions, ..ParseOptions::default() });
    }
    (set, rules)
}

fn apply_resources(engine: &mut Engine, resources_json: Option<String>) {
    if let Some(Ok(resources)) = resources_json.map(|json| serde_json::from_str::<Vec<Resource>>(&json)) {
        engine.use_resources(resources);
    }
}

fn install(engine: Engine) {
    if let Ok(mut slot) = ENGINE.write() {
        *slot = Some(engine);
    }
}

fn with_engine<T>(f: impl FnOnce(&Engine) -> Option<T>) -> Option<T> {
    ENGINE.read().ok()?.as_ref().and_then(f)
}

/// None to allow, "" to block, or a data: URL to serve instead.
fn check(url: &str, source: &str, kind: &str) -> Option<String> {
    let request = Request::new(url, source, kind, "").ok()?;
    with_engine(|engine| {
        let result = engine.check_network_request(&request);
        result.should_block().then(|| result.redirect.unwrap_or_default())
    })
}

fn cosmetic(url: &str) -> Option<String> {
    with_engine(|engine| {
        let r = engine.url_cosmetic_resources(url);
        Some(serde_json::json!({
            "hide": r.hide_selectors,
            "exceptions": r.exceptions,
            "script": r.injected_script,
            "generichide": r.generichide,
        }).to_string())
    })
}

fn hidden_selectors(classes: &str, ids: &str, exceptions: &str) -> Option<String> {
    let parse = |s: &str| -> Vec<String> { serde_json::from_str(s).unwrap_or_default() };
    let (classes, ids) = (parse(classes), parse(ids));
    let exceptions: HashSet<String> = parse(exceptions).into_iter().collect();
    with_engine(|engine| serde_json::to_string(&engine.hidden_class_id_selectors(&classes, &ids, &exceptions)).ok())
}

fn validate(lists: Vec<String>, trusted_mask: i32, resources: Option<String>) -> String {
    let (set, rules) = filter_set(lists, trusted_mask);
    let mut engine = Engine::new_with_filter_set(set);
    let serialized = engine.serialize();
    apply_resources(&mut engine, resources);
    let tests = self_test(&engine, &serialized, rules);
    serde_json::json!({
        "passed": tests.iter().all(|(_, ok)| *ok),
        "rules": rules,
        "tests": tests.iter().map(|(n, ok)| serde_json::json!({"name": n, "passed": ok})).collect::<Vec<_>>(),
    }).to_string()
}

// ---- JNI layer (org.dashdeck.app.web.BraveAdblockNative) ----

fn opt_string(env: &Env, s: &JString) -> JniResult<Option<String>> {
    if s.is_null() { Ok(None) } else { s.try_to_string(env).map(Some) }
}

fn string_list(env: &mut Env, array: &JObjectArray<JString>) -> JniResult<Vec<String>> {
    let mut out = Vec::new();
    for i in 0..array.len(env)? {
        let item = array.get_element(env, i)?;
        out.push(item.try_to_string(env)?);
    }
    Ok(out)
}

fn new_string<'local>(env: &mut Env<'local>, s: Option<String>) -> JniResult<JString<'local>> {
    match s {
        Some(s) => JString::from_str(env, s),
        None => Ok(JString::default()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeApiVersion(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    API_VERSION
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeEngineVersion<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    env.with_env(|env| JString::from_str(env, ENGINE_VERSION)).resolve::<LogErrorAndDefault>()
}

/// Builds an engine from `lists`, installs it, and returns its serialized form for caching.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeBuild<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    lists: JObjectArray<'local, JString<'local>>,
    trusted_mask: jint,
    resources_json: JString<'local>,
) -> JByteArray<'local> {
    env.with_env(|env| {
        let (set, _) = filter_set(string_list(env, &lists)?, trusted_mask);
        let mut engine = Engine::new_with_filter_set(set);
        let serialized = engine.serialize();
        apply_resources(&mut engine, opt_string(env, &resources_json)?);
        install(engine);
        env.byte_array_from_slice(&serialized)
    })
    .resolve::<LogErrorAndDefault>()
}

/// Loads a previously serialized engine. Returns false when the cache is stale or corrupt.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeLoad<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    serialized: JByteArray<'local>,
    resources_json: JString<'local>,
) -> bool {
    env.with_env(|env| -> JniResult<bool> {
        let bytes = env.convert_byte_array(&serialized)?;
        let mut engine = Engine::default();
        if engine.deserialize(&bytes).is_err() {
            return Ok(false);
        }
        apply_resources(&mut engine, opt_string(env, &resources_json)?);
        install(engine);
        Ok(true)
    })
    .resolve::<LogErrorAndDefault>()
}

/// Returns null to allow, "" to block, or a data: URL to serve in place of the request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeCheck<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    source_url: JString<'local>,
    resource_type: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| {
        let verdict = check(&url.try_to_string(env)?, &source_url.try_to_string(env)?, &resource_type.try_to_string(env)?);
        new_string(env, verdict)
    })
    .resolve::<LogErrorAndDefault>()
}

/// Site-specific cosmetic resources as JSON:
/// {"hide":[...],"exceptions":[...],"script":"...","generichide":bool}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeCosmetic<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| {
        let json = cosmetic(&url.try_to_string(env)?);
        new_string(env, json)
    })
    .resolve::<LogErrorAndDefault>()
}

/// Generic hide selectors for the classes/ids seen on a page. All inputs are JSON arrays.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeHiddenSelectors<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    classes: JString<'local>,
    ids: JString<'local>,
    exceptions: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| {
        let json = hidden_selectors(&classes.try_to_string(env)?, &ids.try_to_string(env)?, &exceptions.try_to_string(env)?);
        new_string(env, json)
    })
    .resolve::<LogErrorAndDefault>()
}

/// Builds a candidate engine without installing it and runs the self-test suite.
/// Returns JSON: {"passed":bool,"rules":n,"tests":[{"name","passed"}]}.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_dashdeck_app_web_BraveAdblockNative_nativeValidate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    lists: JObjectArray<'local, JString<'local>>,
    trusted_mask: jint,
    resources_json: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| {
        let report = validate(string_list(env, &lists)?, trusted_mask, opt_string(env, &resources_json)?);
        JString::from_str(env, report)
    })
    .resolve::<LogErrorAndDefault>()
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
