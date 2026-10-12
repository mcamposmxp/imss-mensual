package com.maxipublica.imssmensual

import org.json.JSONObject

/** Recetas JS de llenado (cap. 4). Cada script responde UNA vez: FILL_RESULT o FILL_ERROR. */
object FillScripts {
    private const val HELPERS = """
function send(p){ try{ window.AppBridge.postMessage(JSON.stringify(p)); }catch(e){} }
function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
function norm(s){ return clean(s).replace(/[*:]/g,'').trim().toLowerCase(); }
function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
// Localiza el input por TEXTO de etiqueta (P3); el id del DOM solo es respaldo.
function findInput(labels, domId){
  var wanted=labels.map(norm);
  var ls=document.querySelectorAll('label');
  for(var i=0;i<ls.length;i++){ if(wanted.indexOf(norm(ls[i].textContent))===-1) continue;
    var el=ls[i].htmlFor?document.getElementById(ls[i].htmlFor):ls[i].querySelector('input,textarea');
    if(el) return {el:el,via:'label'}; }
  var all=document.querySelectorAll('span,div,strong,p');
  for(var j=0;j<all.length;j++){ var n=all[j]; if(n.children.length!==0) continue;
    if(wanted.indexOf(norm(n.textContent))===-1) continue;
    var c=n; for(var u=0;u<5&&c;u++){ c=c.parentElement; if(!c) break;
      var inp=c.querySelector('input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=file]):not([type=button]),textarea');
      if(inp) return {el:inp,via:'texto'}; } }
  if(domId){ var d=document.getElementById(domId); if(d) return {el:d,via:'id'}; }
  return null;
}
async function typeReact(el, val){
  el.focus(); await sleep(80);
  var proto = el.tagName==='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
  setter.call(el,''); el.dispatchEvent(new Event('input',{bubbles:true})); await sleep(50);
  for (var i=0;i<val.length;i++){
    setter.call(el, val.substring(0,i+1));
    el.dispatchEvent(new InputEvent('input',{bubbles:true,data:val[i],inputType:'insertText'}));
    if (i%10===0) await sleep(10);
  }
  el.dispatchEvent(new Event('change',{bubbles:true})); await sleep(50);
  el.dispatchEvent(new Event('blur',{bubbles:true}));
}
"""
    private fun wrap(body: String) =
        "(async function(){$HELPERS try{ $body }catch(err){ send({type:'FILL_ERROR',ok:false,message:String(err&&err.message||err)}); } })();true;"

    /** Texto: escribe y VERIFICA releyendo el valor aplicado (P4). No se registra el valor, solo longitudes. */
    fun text(key: String, labels: List<String>, domId: String, value: String): String {
        val l = labels.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }
        return wrap("""
var f=findInput($l, ${JSONObject.quote(domId)});
if(!f){ send({type:'FILL_RESULT',field:${JSONObject.quote(key)},ok:false,reason:'etiqueta_no_encontrada',message:'etiqueta_no_encontrada'}); return; }
var val=${JSONObject.quote(value)};
f.el.scrollIntoView({block:'center'}); await sleep(200);
await typeReact(f.el,val); await sleep(150);
var ok=(f.el.value===val);
send({type:'FILL_RESULT',field:${JSONObject.quote(key)},ok:ok,reason:ok?'':'valor_no_aplicado',via:f.via,
  message:ok?('valor verificado len='+f.el.value.length+' via='+f.via):('valor leido difiere len='+f.el.value.length+' esperado='+val.length)});
""")
    }

    /** Clic en el ícono/control de la fila de datos bajo el encabezado de texto [header] (P3: por texto, no por clase).
     *  Es la acción de DESCARGA/consulta del Paso 5; no envía ni paga nada. */
    fun clickColumn(header: String): String = wrap("""
function fullClick(el){ var r=el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
  var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
  ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){ var Ev=t.indexOf('pointer')===0?PointerEvent:MouseEvent; try{ el.dispatchEvent(new Ev(t,o)); }catch(e){} }); }
var hdr=${JSONObject.quote(header)}.toLowerCase(), th=null;
var tds=document.querySelectorAll('td,th');
for(var i=0;i<tds.length;i++){ if(clean(tds[i].textContent).toLowerCase()===hdr){ th=tds[i]; break; } }
if(!th){ send({type:'FILL_RESULT',field:'click',ok:false,reason:'etiqueta_no_encontrada',message:'encabezado no encontrado'}); return; }
var idx=[].indexOf.call(th.parentElement.children, th), row=th.parentElement.nextElementSibling, target=null;
while(row && !target){ var cell=row.children[idx]; if(cell){ target=cell.querySelector('[onclick],a[href],button,input[type=button],.glyphicon,img'); } row=row.nextElementSibling; }
if(!target){ send({type:'FILL_RESULT',field:'click',ok:false,reason:'etiqueta_no_encontrada',message:'sin control bajo el encabezado'}); return; }
target.scrollIntoView({block:'center'}); await sleep(300);
fullClick(target);
send({type:'FILL_RESULT',field:'click',ok:true,message:'clic en '+target.tagName.toLowerCase()+' bajo "'+hdr+'"'});
""")

    /** Extrae la imagen del captcha YA MOSTRADA (canvas.toDataURL). No se vuelve a pedir la URL: podría generar otro captcha distinto. */
    fun captchaImage(labels: List<String>, domId: String): String {
        val l = labels.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }
        return wrap("""
var f=findInput($l, ${JSONObject.quote(domId)});
if(!f){ send({type:'CAPTCHA_IMG',ok:false,reason:'etiqueta_no_encontrada'}); return; }
var img=null, c=f.el;
for(var u=0;u<6&&c&&!img;u++){ c=c.parentElement; if(!c) break;
  var im=c.querySelectorAll('img');
  for(var k=0;k<im.length;k++){ var r=im[k].getBoundingClientRect();
    if(im[k].complete&&im[k].naturalWidth>0&&r.width>=40&&r.width<=500&&r.height>=15&&r.height<=200){ img=im[k]; break; } } }
if(!img){ send({type:'CAPTCHA_IMG',ok:false,reason:'imagen_no_encontrada'}); return; }
try{
  var cv=document.createElement('canvas'); cv.width=img.naturalWidth; cv.height=img.naturalHeight;
  cv.getContext('2d').drawImage(img,0,0);
  var d=cv.toDataURL('image/png');
  send({type:'CAPTCHA_IMG',ok:true,w:cv.width,h:cv.height,b64:d.substring(d.indexOf(',')+1)});
}catch(e){ send({type:'CAPTCHA_IMG',ok:false,reason:'canvas_no_legible',message:String(e&&e.message||e)}); }
""")
    }
}
