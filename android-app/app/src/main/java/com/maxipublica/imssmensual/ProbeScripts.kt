package com.maxipublica.imssmensual

/** Sondas DOM de SOLO LECTURA (cap. 3). Cada una envía un único mensaje PROBE_* por AppBridge.
 *  No se vuelcan valores de campos (solo longitud) para no exponer CURP/correo (P10). */
object ProbeScripts {
    private const val HEAD = """
function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
function vis(el){ var r=el.getBoundingClientRect(); var cs=getComputedStyle(el); return r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none'; }
function near(inp){
  if(inp.id){ var l=document.querySelector('label[for="'+inp.id+'"]'); if(l) return clean(l.textContent).substring(0,80); }
  var al=inp.closest('label'); if(al){ var t0=clean(al.textContent); if(t0) return t0.substring(0,80); }
  var form=inp.closest('form')||document.body, el=inp;
  for(var u=0;u<6&&el&&el!==form;u++){ var c=el.parentElement; if(!c) break;
    var n=c.querySelectorAll('input,select,textarea').length;
    var cl=c.cloneNode(true); [].slice.call(cl.querySelectorAll('input,select,textarea,script,style')).forEach(function(x){x.remove();});
    var t=clean(cl.textContent);
    if(n<=1 && t.length>1) return t.substring(0,80);
    el=c; }
  return ''; }
"""
    private fun wrap(body: String) = "(function(){$HEAD try{ $body }catch(err){ send({type:'PROBE_ERROR',message:String(err&&err.message||err)}); } true; })();"

    val GENERAL = wrap("""
var inputs=[].slice.call(document.querySelectorAll('input,textarea,select'));
send({type:'PROBE_GENERAL', url:location.href, title:document.title,
  counts:{inputs:document.querySelectorAll('input').length, file:document.querySelectorAll('input[type=file]').length,
    selects:document.querySelectorAll('select').length, textareas:document.querySelectorAll('textarea').length,
    buttons:document.querySelectorAll('button,[role=button],input[type=submit]').length,
    comboboxes:document.querySelectorAll('[role=combobox]').length, iframes:document.querySelectorAll('iframe').length,
    forms:document.querySelectorAll('form').length},
  headings:[].slice.call(document.querySelectorAll('h1,h2,h3,legend')).map(function(h){return clean(h.textContent).substring(0,80);}).slice(0,30),
  captcha:/captcha/i.test(document.documentElement.innerHTML),
  iframeSrcs:[].slice.call(document.querySelectorAll('iframe')).map(function(f){return (f.src||'').substring(0,120);}),
  viewport:{w:innerWidth,h:innerHeight,dpr:devicePixelRatio}});
""")

    val FIELDS = wrap("""
var items=[].slice.call(document.querySelectorAll('input,textarea,select')).filter(function(e){
  var t=(e.getAttribute('type')||'').toLowerCase(); return t!=='hidden'; }).map(function(e){
  var opts=null; if(e.tagName==='SELECT'){ opts=[].slice.call(e.options).slice(0,40).map(function(o){return clean(o.text).substring(0,60);}); }
  return {tag:e.tagName.toLowerCase(), type:e.getAttribute('type')||'', name:e.getAttribute('name')||'', id:e.id||'',
    aria:e.getAttribute('aria-label')||'', placeholder:e.getAttribute('placeholder')||'', label:near(e),
    required:e.required||e.getAttribute('aria-required')==='true', maxlength:e.getAttribute('maxlength')||'',
    pattern:e.getAttribute('pattern')||'', visible:vis(e), disabled:e.disabled, readonly:e.readOnly,
    checked:(e.type==='checkbox'||e.type==='radio')?e.checked:undefined, valueLen:(e.value||'').length,
    optionsCount:opts?e.options.length:undefined, options:opts, y:Math.round(e.getBoundingClientRect().top+scrollY)}; });
items.sort(function(a,b){return a.y-b.y;});
send({type:'PROBE_FIELDS', url:location.href, count:items.length, fields:items});
""")

    val BUTTONS = wrap("""
var items=[].slice.call(document.querySelectorAll('button,[role=button],input[type=submit],input[type=button],a.btn,a[role=button]')).map(function(e){
  return {tag:e.tagName.toLowerCase(), text:clean(e.innerText||e.value||'').substring(0,60), aria:e.getAttribute('aria-label')||'',
    id:e.id||'', disabled:!!e.disabled, visible:vis(e), y:Math.round(e.getBoundingClientRect().top+scrollY)}; });
send({type:'PROBE_BUTTONS', url:location.href, count:items.length, buttons:items.slice(0,60)});
""")

    /** Enlaces/descargas y texto visible (primeros 1500 car.). Las URLs se recortan sin query para no exponer tokens. */
    val LINKS = wrap("""
function strip(u){ return (u||'').split('?')[0].split('#')[0].substring(0,160); }
var a=[].slice.call(document.querySelectorAll('a[href],[onclick],button')).map(function(e){
  return {tag:e.tagName.toLowerCase(), text:clean(e.innerText||e.value||'').substring(0,60), href:strip(e.getAttribute('href')),
    download:e.getAttribute('download'), target:e.getAttribute('target')||'', onclick:(e.getAttribute('onclick')||'').substring(0,80),
    id:e.id||'', visible:vis(e), y:Math.round(e.getBoundingClientRect().top+scrollY)}; })
  .filter(function(x){return x.visible && (x.text||x.href);});
var main=document.querySelector('main')||document.body;
send({type:'PROBE_LINKS', url:location.href, title:document.title, count:a.length, links:a.slice(0,50),
  text:clean(main.innerText).substring(0,1500)});
""")

    /** Celdas de tablas: qué hay DENTRO de cada celda (íconos, enlaces, manejadores). Enmascara argumentos largos de onclick. */
    val CELLS = wrap("""
function strip(u){ return (u||'').split('?')[0].split('#')[0].substring(0,160); }
function mk(s){ return (s||'').replace(/'[^']{8,}'/g,"'…'").substring(0,100); }
function desc(e){ var ds=[]; for(var k in e.dataset) ds.push(k);
  return {tag:e.tagName.toLowerCase(), cls:(typeof e.className==='string'?e.className:'').substring(0,60), id:e.id||'', href:strip(e.getAttribute('href')),
    onclick:mk(e.getAttribute('onclick')), title:e.getAttribute('title')||'', aria:e.getAttribute('aria-label')||'', role:e.getAttribute('role')||'',
    type:e.getAttribute('type')||'', data:ds, src:strip(e.getAttribute('src')), cursor:getComputedStyle(e).cursor}; }
var out=[]; [].slice.call(document.querySelectorAll('table')).slice(0,3).forEach(function(t){
  var hdr=[].slice.call(t.querySelectorAll('th')).map(function(h){return clean(h.innerText).substring(0,30);});
  var rows=[].slice.call(t.querySelectorAll('tr')).slice(0,6).map(function(r){
    return [].slice.call(r.children).slice(0,10).map(function(c){
      var kids=[].slice.call(c.querySelectorAll('*')).slice(0,8).map(desc);
      return {cell:desc(c), text:clean(c.innerText).substring(0,40), kids:kids}; }); });
  out.push({headers:hdr, rows:rows}); });
send({type:'PROBE_CELLS', url:location.href, tables:out});
""")

    val READY = wrap("""
var bt=document.body?(document.body.innerText||''):'';
send({type:'READY_RESULT', ok:document.querySelectorAll('input,select,textarea').length>0, url:location.href, bodyLen:bt.length});
""")

    /** Código fuente (enmascarado) de las funciones del sitio que disparan las descargas. Solo lectura. */
    val FN = wrap("""
function mk(t){ return (t||'').replace(/'[^']{8,}'/g,"'…'").replace(/\s+/g,' ').substring(0,1800); }
var names=['showPDF','showLineCaptura','showModal'];
var src={}; names.forEach(function(n){ try{ src[n]=(typeof window[n]==='function')?mk(window[n].toString()):('tipo:'+typeof window[n]); }catch(e){ src[n]='err'; } });
var globals=Object.keys(window).filter(function(k){ return /^(show|download|print|abrir|get).*/i.test(k) && typeof window[k]==='function'; }).slice(0,30);
send({type:'PROBE_FN', url:location.href, src:src, globals:globals});
""")

    /** Hooks de SOLO OBSERVACIÓN: registran window.open / fetch / XHR / form.submit / iframes nuevos (URL sin query). */
    val HOOK = wrap("""
function strip(u){ return (u||'').split('?')[0].split('#')[0].substring(0,160); }
if(!window.__imssHooked){ window.__imssHooked=1;
  var ow=window.open; window.open=function(u,n){ send({type:'HOOK',kind:'window.open',url:strip(String(u)),target:n||''}); return ow.apply(this,arguments); };
  if(window.fetch){ var of=window.fetch; window.fetch=function(i,o){ send({type:'HOOK',kind:'fetch',method:(o&&o.method)||'GET',url:strip(typeof i==='string'?i:(i&&i.url))}); return of.apply(this,arguments); }; }
  var xo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){ send({type:'HOOK',kind:'xhr',method:m,url:strip(String(u))}); return xo.apply(this,arguments); };
  var fs=HTMLFormElement.prototype.submit; HTMLFormElement.prototype.submit=function(){ send({type:'HOOK',kind:'form.submit',action:strip(this.action),method:this.method,target:this.target}); return fs.apply(this,arguments); };
  new MutationObserver(function(ms){ ms.forEach(function(m){ [].slice.call(m.addedNodes).forEach(function(n){
    if(n.tagName==='IFRAME'||n.tagName==='EMBED'||n.tagName==='OBJECT') send({type:'HOOK',kind:'node:'+n.tagName.toLowerCase(),url:strip(n.src||n.data||'')}); }); }); }).observe(document.documentElement,{childList:true,subtree:true});
}
send({type:'PROBE_HOOK', ok:true, url:location.href});
""")

    /** Captura de blobs de descarga: el sitio crea <a download href=blob:> y le hace click(); lo interceptamos y enviamos el contenido por el puente. */
    val CAPTURE = wrap("""
if(!window.__imssCap){ window.__imssCap=1; window.__blobs={};
  var oc=URL.createObjectURL; URL.createObjectURL=function(b){ var u=oc.call(URL,b); window.__blobs[u]=b; return u; };
  var ac=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){
    var a=this;
    if(a.href && a.href.indexOf('blob:')===0 && a.download){ var b=window.__blobs[a.href];
      if(b){ var fr=new FileReader();
        fr.onload=function(){ var s=String(fr.result); send({type:'DOC',name:a.download,mime:b.type,size:b.size,b64:s.substring(s.indexOf(',')+1)}); };
        fr.onerror=function(){ send({type:'DOC_ERROR',name:a.download,message:'FileReader fallo'}); };
        fr.readAsDataURL(b); return; } }
    return ac.apply(this,arguments); }; }
send({type:'PROBE_CAPTURE', ok:true});
""")

    /** «Pantalla lista» por marcadores: texto esperado Y controles esperados (cap. 2.5/6.3). */
    fun ready(textRegex: String, needs: List<String>): String {
        val rx = org.json.JSONObject.quote(textRegex)
        val sel = needs.joinToString(prefix = "[", postfix = "]") { org.json.JSONObject.quote(it) }
        return wrap("""
var bt=document.body?(document.body.innerText||''):'';
var missing=$sel.filter(function(q){ try{return !document.querySelector(q);}catch(e){return true;} });
var textOk=$rx===''||new RegExp($rx,'i').test(bt);
send({type:'READY_RESULT', ok:textOk&&missing.length===0, textOk:textOk, missing:missing, url:location.href, bodyLen:bt.length});
""")
    }

    val ALL = linkedMapOf(
        "general" to GENERAL, "fields" to FIELDS, "buttons" to BUTTONS, "links" to LINKS, "cells" to CELLS, "fn" to FN, "hook" to HOOK, "capture" to CAPTURE, "ready" to READY,
    )
}
