'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/second/risedie/challengeapp/ui/ChallengeWebViewActivity.kt'),'utf8');
const intent=source.slice(source.indexOf('override fun onNewIntent'),source.indexOf('private fun navigateInternalOrLoad'));
assert.ok(intent.includes('sanitizePushRoute'));assert.ok(intent.includes('navigateInternalOrLoad(route)'));assert.ok(!intent.includes('webView.loadUrl'));
const nav=source.slice(source.indexOf('private fun navigateInternalOrLoad'),source.indexOf('private fun createNotificationChannel'));
for(const text of ['sanitizePushRoute(rawRoute)','JSONObject.quote(route)','JSONObject.quote(url)','!trustedDocumentReady','isAllowedInternalUrl(Uri.parse(current))','webView.evaluateJavascript','result != "true"','fallback()','!webSessionRestored','internalNavigationSequence'])assert.ok(nav.includes(text),text);
assert.ok(source.indexOf('restoreWebSession()')<source.indexOf('webView.loadUrl(resolvedLaunchUrl)'));
assert.ok(source.includes('cacheMode = WebSettings.LOAD_DEFAULT'));assert.ok(!source.includes('LOAD_CACHE_ELSE_NETWORK'));assert.ok(!source.includes('clearCache(true)'));
const script=nav.split('val script = """')[1].split('""".trimIndent()')[0];
const route='/web/challenges/show?id=7&text="\\\n雪';
const execute=(navigate,ready='complete')=>{
 const calls=[],assigns=[],window={location:{assign:value=>assigns.push(value)}};
 if(navigate)window.GrafitNavigateInternal=(...args)=>{calls.push(args);return navigate(...args);};
 const js=script.replace('$quotedRoute',JSON.stringify(route)).replace('$quotedUrl',JSON.stringify('https://grafit.test'+route));
 return {accepted:vm.runInNewContext(js,{window,document:{readyState:ready}}),calls,assigns};
};
(async()=>{
 let f=execute(()=>Promise.resolve(true));assert.equal(f.accepted,true);assert.equal(f.calls[0][0],route);assert.equal(f.calls[0][1].fallback,true);assert.deepEqual(f.assigns,[]);
 f=execute(null);assert.equal(f.accepted,false);
 f=execute(()=>Promise.resolve(true),'loading');assert.equal(f.accepted,false);assert.equal(f.calls.length,0);
 f=execute(()=>{throw new Error('not ready');});assert.equal(f.accepted,false);
 f=execute(()=>Promise.reject(new Error('async failure')));await new Promise(r=>setImmediate(r));assert.equal(f.assigns.length,1);
 console.log('OK: Android cold/auth/trusted-origin/cache contracts and 5 actual injected-JS navigation scenarios');
})().catch(e=>{console.error(e);process.exitCode=1;});
