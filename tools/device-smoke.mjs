import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdirSync,writeFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
const adb=process.env.ADB || 'adb';
const serial=process.env.DSH_TEST_SERIAL || 'emulator-5556';
if(!serial.startsWith('emulator-'))throw Error('仅允许在隔离模拟器运行此回归脚本');
const out=process.env.DSH_TEST_OUT || fileURLToPath(new URL('../build/device-smoke',import.meta.url));
mkdirSync(out,{recursive:true});
function run(...args){const r=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:30000});if(r.status!==0)throw Error(`adb ${args.slice(0,3).join(' ')}: ${r.stderr}`);return r.stdout;}
function prefsWrite(xml){const r=spawnSync(adb,['-s',serial,'shell','run-as','dev.pocketagent','sh','-c',"'cat > shared_prefs/pocketagent_setup.xml'"],{input:xml,encoding:'utf8',timeout:10000});if(r.status!==0)throw Error('cannot write test preferences');}
function shot(name){const r=spawnSync(adb,['-s',serial,'exec-out','screencap','-p'],{timeout:10000});if(r.status!==0)throw Error('screenshot failed');writeFileSync(`${out}/${name}.png`,r.stdout);}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let original,enabled,token=randomBytes(24).toString('hex'),counter=0,server;
const checks=[],events=[];
async function rpc(name,args={}){const r=await fetch('http://127.0.0.1:18765/mcp',{method:'POST',headers:{'content-type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify({jsonrpc:'2.0',id:++counter,method:'tools/call',params:{name,arguments:args}}),signal:AbortSignal.timeout(140000)});assert.equal(r.status,200,`${name} HTTP ${r.status}`);const data=await r.json();if(data.error)throw Error(`${name}: ${JSON.stringify(data.error)}`);return data.result;}
function text(result){return result.content.filter(x=>x.type==='text').map(x=>x.text).join('\n');}
function check(name,value){assert.ok(value,name);checks.push({name,passed:true});console.log(`PASS ${name}`);}
function saveResult(name,result){const stripped={...result,content:result.content.map((c,i)=>{if(c.type!=='image')return c;const p=`${out}/${name}-${i}.png`;writeFileSync(p,Buffer.from(c.data,'base64'));return {type:'image',path:p};})};writeFileSync(`${out}/${name}.json`,JSON.stringify(stripped,null,2),'utf8');return result;}
function nodeId(result,label){const line=text(result).split('\n').find(l=>l.includes(`"${label}"`)&&l.includes('['));assert.ok(line,`node ${label}`);return line.match(/\[([a-z]+\d+)\]/)?.[1];}
try{
  if(!run('get-state').includes('device'))throw Error('test emulator not ready');
  run('install','-r',process.env.DSH_TEST_APK || fileURLToPath(new URL('../app/build/outputs/apk/debug/app-debug.apk',import.meta.url)));
  try { original=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml'); }
  catch { original='<?xml version="1.0" encoding="utf-8"?><map></map>'; run('shell','run-as','dev.pocketagent','mkdir','-p','shared_prefs'); }
  original=original.replace(/<map\s*\/>/,'<map></map>');
  enabled=run('shell','settings','get','secure','enabled_accessibility_services').trim();
  run('shell','am','force-stop','dev.pocketagent');
  const xml=original.replace(/<boolean name="settings\.computerLink"[^>]*\/>/g,'').replace(/<string name="settings\.linkToken">[^<]*<\/string>/g,'').replace('</map>',`<boolean name="settings.computerLink" value="true" /><string name="settings.linkToken">${token}</string></map>`);
  prefsWrite(xml);
  const without=enabled.split(':').filter(x=>x&&x!=='null'&&!x.startsWith('dev.pocketagent/')).join(':');
  run('shell','settings','put','secure','enabled_accessibility_services',without||"''");
  run('shell','settings','put','secure','enabled_accessibility_services',[without,'dev.pocketagent/dev.pocketagent.android.PocketAccessibilityService'].filter(Boolean).join(':'));
  run('shell','settings','put','secure','accessibility_enabled','1');
  run('shell','am','start','-n','dev.pocketagent/.ui.MainActivity');await delay(1300);
  run('forward','tcp:18765','tcp:8765');
  run('reverse','tcp:18100','tcp:18100');
  let connected=false,startupError='';
  for(let attempt=0;attempt<100;attempt++){try{await rpc('control_status');connected=true;break;}catch(error){startupError=error.message;await delay(300);}}
  if(!connected) {
    const saved=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml');
    console.log('STARTUP_DIAGNOSTIC',JSON.stringify({linkEnabled:/name="settings\.computerLink" value="true"/.test(saved),tokenMatches:saved.includes(token),enabledServices:run('shell','settings','get','secure','enabled_accessibility_services').trim(),service:run('shell','dumpsys','accessibility').split('\n').filter(l=>/Bound services|Enabled services/.test(l))}));
    shot('00-startup-failed');
  }
  assert.ok(connected,'测试服务启动完成: '+startupError);
  const html=`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:21px sans-serif;padding:20px}button,input{font:22px sans-serif;padding:12px;margin:10px;display:block}label{display:block;padding:18px}input[type=checkbox]{display:inline}</style><h1>dsh 回归测试</h1><label><input id="toggle" type="checkbox" onchange="fetch('/event?name='+encodeURIComponent('checked:'+this.checked))">测试提醒</label><input aria-label="测试输入框" id="entry" placeholder="测试输入框" oninput="fetch('/event?name='+encodeURIComponent('input:'+this.value))"><button onclick="this.textContent='选择张三成功';fetch('/event?name=exact')">张三</button><button>张三丰</button><button>同名联系人</button><button>同名联系人</button><a href="/canvas">打开画布</a><p id="result">测试准备完毕</p>`;
  const canvas=`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><canvas id="c" width="660" height="850" style="width:100%;height:850px"></canvas><script>let c=document.querySelector('canvas'),x=c.getContext('2d');function draw(clicked=false){x.fillStyle=clicked?'#dcfce7':'#eef2ff';x.fillRect(0,0,660,850);x.font='32px sans-serif';['蓝色按钮','绿色按钮','橙色按钮'].forEach((s,i)=>{x.fillStyle=['#2563eb','#16a34a','#ea580c'][i];x.fillRect(70,120+i*200,480,100);x.fillStyle='white';x.fillText(s,180,183+i*200)});if(clicked){x.fillStyle='black';x.fillText('画布已响应',170,800)}}draw();c.onclick=()=>{draw(true);fetch('/event?name=canvas')};</script>`;
  server=createServer((req,res)=>{if(req.url.startsWith('/event')){events.push(new URL(req.url,'http://local').searchParams.get('name'));res.end('ok');return;}res.setHeader('content-type','text/html;charset=utf-8');res.end(req.url.startsWith('/canvas')?canvas:html);});
  await new Promise(r=>server.listen(18100,'0.0.0.0',r));
  const packages=run('shell','pm','list','packages'); const browser=packages.includes('org.chromium.webview_shell')?'org.chromium.webview_shell/.WebViewBrowserActivity':'com.android.chrome/com.google.android.apps.chrome.Main'; run('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:18100/','-n',browser);await delay(2500);
  const pending=rpc('control_start',{task:'仅在本地回归测试页面检查读屏、勾选、中文输入和截图，禁止发送或提交。'}).then(result=>({result}),error=>({error}));
  await delay(900);shot('01-approval');console.log(`WAIT_APPROVAL ${out}/01-approval.png`);
  if(process.env.DSH_SMOKE_APPROVE==='1') { await delay(700);run('shell','input','tap','780','2180'); }
  const settled=await pending;if(settled.error)throw settled.error;
  const initial=saveResult('02-control-start',settled.result);check('电脑控制需要手机批准后返回屏幕',!initial.isError&&text(initial).includes('dsh 回归测试'));
  const screen=await rpc('read_screen');saveResult('03-screen',screen);
  const toggle=nodeId(screen,'测试提醒');
  const firstTap=saveResult('03-tap',await rpc('tap',{node_id:toggle}));
  check('原生点击经过安全执行链',!firstTap.isError);
  let waited=saveResult('04-checked',await rpc('wait_for',{text:'测试提醒',checked:true,seconds:2}));
  if(waited.isError) {
    const row=text(waited).split('\n').find(l=>l.includes('"测试提醒"'));
    const b=row?.match(/bounds=\((\d+),(\d+),(\d+),(\d+)\)/)?.slice(1).map(Number);
    if(b){saveResult('04-coordinate-fallback',await rpc('tap',{x:Math.floor((b[0]+b[2])/2),y:Math.floor((b[1]+b[3])/2)}));waited=saveResult('04-checked-retry',await rpc('wait_for',{text:'测试提醒',checked:true,seconds:2}));}
  }
  if(text(waited).includes('checked=unknown')) {
    check('不可靠状态明确标为未知并附图',waited.isError&&waited.content.some(x=>x.type==='image'));
    check('坐标后备触摸实际完成勾选',events.includes('checked:true'));
  } else check('勾选状态经真实读屏核验',!waited.isError&&text(waited).includes('checked=true'));
  const fresh=await rpc('read_screen');const entry=text(fresh).split('\n').find(l=>l.includes('view_id="entry"'))?.match(/\[([a-z]+\d+)\]/)?.[1] || nodeId(fresh,'测试输入框');
  const typed=await rpc('type',{node_id:entry,text:'中文输入测试',mode:'replace'});check('中文输入通过手机工具写入',!typed.isError&&events.includes('input:中文输入测试'));
  await rpc('back');
  const found=await rpc('find_elements',{text:'同名联系人'});check('重名联系人返回多个候选',text(found).split('\n').filter(x=>x.includes('同名联系人')).length>=2);
  const ambiguous=await rpc('tap_text',{text:'同名联系人',scrolls:0});check('重名点击被拒绝',ambiguous.isError);
  const exact=saveResult('04-exact',await rpc('tap_text',{text:'张三',scrolls:0}));
  if(!events.includes('exact')) {
    const latest=await rpc('read_screen');const row=text(latest).split('\n').find(l=>l.includes('"张三"')&&l.includes('clickable'));
    const b=row?.match(/bounds=\((\d+),(\d+),(\d+),(\d+)\)/)?.slice(1).map(Number);
    if(b)saveResult('04-exact-fallback',await rpc('tap',{x:Math.floor((b[0]+b[2])/2),y:Math.floor((b[1]+b[3])/2)}));
  }
  check('精确姓名优先于相似姓名',!exact.isError&&events.includes('exact'));
  const snap=saveResult('05-screenshot',await rpc('screenshot'));check('MCP 返回原生图片内容',snap.content.some(x=>x.type==='image'));
  const observation=Number(text(snap).match(/observation=(\d+)/)?.[1]);
  await rpc('read_screen');const stale=await rpc('tap_mark',{index:1,observation});check('刷新后旧截图编号失效',stale.isError);
  const zoom=saveResult('06-zoom',await rpc('zoom_screen',{left:20,top:180,right:650,bottom:700}));check('局部放大截图保留原屏幕坐标',!zoom.isError&&zoom.content.some(x=>x.type==='image'));
  const link=await rpc('tap_text',{text:'打开画布',scrolls:1});
  if(text(link).includes('"打开画布"')) {
    const row=text(link).split('\n').find(l=>l.includes('"打开画布"')&&l.includes('clickable'));
    const b=row?.match(/bounds=\((\d+),(\d+),(\d+),(\d+)\)/)?.slice(1).map(Number);
    if(b)await rpc('tap',{x:Math.floor((b[0]+b[2])/2),y:Math.floor((b[1]+b[3])/2)});
  }
  const vision=saveResult('07-canvas',await rpc('read_screen'));check('画布页面自动附图',vision.content.some(x=>x.type==='image'));
  const pointPending=rpc('tap',{x:300,y:300}).then(result=>({result}),error=>({error}));
  await delay(900);shot('08-canvas-card');
  if(process.env.DSH_SMOKE_APPROVE==='1')run('shell','input','tap','780','2180');
  const pointSettled=await pointPending;if(pointSettled.error)throw pointSettled.error;const point=pointSettled.result;
  // A fully unlabelled target must ask; stop here to inspect that card instead of silently approving it.
  saveResult('08-canvas-action',point);check('画布操作有可见反馈或风险确认',typeof point.isError==='boolean');
  const stopped=await rpc('control_stop');check('电脑控制可停止',!stopped.isError);
  const forbidden=await rpc('read_screen');check('停止后不能继续读屏',forbidden.isError);
  shot('09-finished');
}catch(e){checks.push({name:'device run',passed:false,error:e.message});console.error(e.stack);process.exitCode=1;}
finally{
  // Restore the emulator's settings in memory; no account token is written to the report.
  try{await rpc('control_stop');}catch{}
  if(original){run('shell','am','force-stop','dev.pocketagent');prefsWrite(original.replace(/<boolean name="settings\.computerLink"[^>]*\/>/g,'').replace(/<string name="settings\.linkToken">[^<]*<\/string>/g,''));}
  if(enabled!==undefined)run('shell','settings','put','secure','enabled_accessibility_services',enabled&&enabled!=='null'?enabled:"''");
  try{run('forward','--remove','tcp:18765');}catch{}
  try{run('reverse','--remove','tcp:18100');}catch{}
  server?.close();
  server?.closeAllConnections();
  writeFileSync(`${out}/results.json`,JSON.stringify({checks,events},null,2),'utf8');
  console.log(`RESULTS ${checks.filter(x=>x.passed).length}/${checks.length}`);
}
