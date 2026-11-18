import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdirSync,writeFileSync} from 'node:fs';
import {randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
import {lockPhone} from './phone-test-lock.mjs';
const adb=process.env.ADB || 'adb';
const serial=process.env.DSH_TEST_SERIAL || 'emulator-5556';
if(!serial.startsWith('emulator-'))throw Error('仅允许在隔离模拟器运行此回归脚本');
const release=lockPhone(serial);
const out=process.env.DSH_TEST_OUT || fileURLToPath(new URL('../build/device-smoke',import.meta.url));
mkdirSync(out,{recursive:true});
function run(...args){const r=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:30000});if(r.status!==0)throw Error(`adb ${args.slice(0,3).join(' ')}: ${r.stderr}`);return r.stdout;}
function prefsWrite(xml,file='pocketagent_setup'){assert.ok(['pocketagent_setup','dsh_progress','dsh_chat'].includes(file));run('shell','run-as','dev.pocketagent','rm','-f',`shared_prefs/${file}.xml.bak`);const r=spawnSync(adb,['-s',serial,'shell','run-as','dev.pocketagent','sh','-c',`'cat > shared_prefs/${file}.xml'`],{input:xml,encoding:'utf8',timeout:10000});if(r.status!==0)throw Error('cannot write test preferences');}
function shot(name){const r=spawnSync(adb,['-s',serial,'exec-out','screencap','-p'],{timeout:10000});if(r.status!==0)throw Error('screenshot failed');writeFileSync(`${out}/${name}.png`,r.stdout);}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
let original,progressBefore,chatBefore,enabled,proxy,token=randomBytes(24).toString('hex'),counter=0,server,modelRequests=0,failModel=false,modelResponder=null;
const checks=[],events=[],timings=[];
async function rpc(name,args={}){const started=performance.now();const r=await fetch('http://127.0.0.1:18765/mcp',{method:'POST',headers:{'content-type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify({jsonrpc:'2.0',id:++counter,method:'tools/call',params:{name,arguments:args}}),signal:AbortSignal.timeout(140000)});assert.equal(r.status,200,`${name} HTTP ${r.status}`);const data=await r.json();if(data.error)throw Error(`${name}: ${JSON.stringify(data.error)}`);timings.push({name,ms:Math.round(performance.now()-started),interactive:name==='control_start'||name==='run_task'||(name==='tap_text'&&args.text==='发送')});return data.result;}
function text(result){return result.content.filter(x=>x.type==='text').map(x=>x.text).join('\n');}
function check(name,value){assert.ok(value,name);checks.push({name,passed:true});console.log(`PASS ${name}`);}
function saveResult(name,result){const stripped={...result,content:result.content.map((c,i)=>{if(c.type!=='image')return c;const p=`${out}/${name}-${i}.png`;writeFileSync(p,Buffer.from(c.data,'base64'));return {type:'image',path:p};})};writeFileSync(`${out}/${name}.json`,JSON.stringify(stripped,null,2),'utf8');return result;}
function nodeId(result,label){const line=text(result).split('\n').find(l=>l.includes(`"${label}"`)&&l.includes('['));assert.ok(line,`node ${label}`);return line.match(/\[([a-z]+\d+)\]/)?.[1];}
try{
  if(!run('get-state').includes('device'))throw Error('test emulator not ready');
  let booted=false;
  for(let attempt=0;attempt<100;attempt++){if(run('shell','getprop','sys.boot_completed').trim()==='1'){booted=true;break;}await delay(500);}
  assert.ok(booted,'模拟器启动超时，尚未进入可安装应用的状态');
  run('install','-r',process.env.DSH_TEST_APK || fileURLToPath(new URL('../app/build/outputs/apk/debug/app-debug.apk',import.meta.url)));
  await delay(1800); // Let package replacement finish before changing accessibility settings.
  enabled=run('shell','settings','get','secure','enabled_accessibility_services').trim();
  const without=enabled.split(':').filter(x=>x&&x!=='null'&&!x.startsWith('dev.pocketagent/')).join(':');
  run('shell','settings','put','secure','enabled_accessibility_services',without||"''");await delay(450);
  run('shell','am','force-stop','dev.pocketagent');
  try{progressBefore=run('shell','run-as','dev.pocketagent','cat','shared_prefs/dsh_progress.xml')}catch{progressBefore=null;}
  try{chatBefore=run('shell','run-as','dev.pocketagent','cat','shared_prefs/dsh_chat.xml')}catch{chatBefore=null;}
  run('shell','run-as','dev.pocketagent','rm','-f','shared_prefs/dsh_chat.xml','shared_prefs/dsh_chat.xml.bak');
  run('shell','run-as','dev.pocketagent','rm','-f','shared_prefs/dsh_progress.xml','shared_prefs/dsh_progress.xml.bak');
  try { original=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml'); }
  catch { original='<?xml version="1.0" encoding="utf-8"?><map></map>'; run('shell','run-as','dev.pocketagent','mkdir','-p','shared_prefs'); }
  original=original.replace(/<map\s*\/>/,'<map></map>');
  proxy=run('shell','settings','get','global','http_proxy').trim();
  run('shell','settings','put','global','http_proxy',':0');run('shell','settings','delete','global','http_proxy');
  run('shell','am','force-stop','dev.pocketagent');
  const xml=original.replace(/<boolean name="settings\.computerLink"[^>]*\/>/g,'').replace(/<string name="settings\.linkToken">[^<]*<\/string>/g,'').replace('</map>',`<boolean name="settings.computerLink" value="true" /><string name="settings.linkToken">${token}</string></map>`);
  // A credential-free local model fixture; the original configuration stays only in memory.
  const configured=xml.replace(/<(string|boolean|int) name="config\.[^"]+"[^>]*>(?:[^<]*)<\/\1>/g,'').replace(/<(?:string|boolean|int) name="config\.[^"]+"[^>]*\/>/g,'').replace('</map>',
    '<boolean name="settings.notice" value="true"/><string name="config.kind">OPENAI_COMPAT</string><string name="config.baseUrl">http://127.0.0.1:18100/v1</string><string name="config.model">local-fixture</string><string name="config.images">true</string><string name="config.contextTokens">128000</string><string name="config.maxOutputTokens">4096</string></map>');
  prefsWrite(configured);
  run('shell','settings','put','secure','enabled_accessibility_services',without||"''");
  run('shell','settings','put','secure','enabled_accessibility_services',[without,'dev.pocketagent/dev.pocketagent.android.PocketAccessibilityService'].filter(Boolean).join(':'));
  run('shell','settings','put','secure','accessibility_enabled','1');
  run('shell','am','start','-n','dev.pocketagent/.ui.MainActivity');await delay(1300);
  run('forward','tcp:18765','tcp:8765');
  run('reverse','tcp:18100','tcp:18100');
  for(let retry=0;retry<3;retry++) {
    if(run('shell','settings','get','secure','enabled_accessibility_services').includes('dev.pocketagent/')) break;
    run('shell','settings','put','secure','enabled_accessibility_services',[without,'dev.pocketagent/dev.pocketagent.android.PocketAccessibilityService'].filter(Boolean).join(':'));
    run('shell','settings','put','secure','accessibility_enabled','1');
    await delay(1000);
  }
  let connected=false,startupError='';
  for(let attempt=0;attempt<100;attempt++){try{await rpc('control_status');connected=true;break;}catch(error){startupError=error.message;await delay(300);}}
  if(!connected) {
    const saved=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml');
    console.log('STARTUP_DIAGNOSTIC',JSON.stringify({linkEnabled:/name="settings\.computerLink" value="true"/.test(saved),tokenMatches:saved.includes(token),enabledServices:run('shell','settings','get','secure','enabled_accessibility_services').trim(),service:run('shell','dumpsys','accessibility').split('\n').filter(l=>/Bound services|Enabled services/.test(l))}));
    shot('00-startup-failed');
  }
  assert.ok(connected,'测试服务启动完成: '+startupError);
  const html=`<!doctype html><meta charset="utf-8"><meta name="google" content="notranslate"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:21px sans-serif;padding:20px}button,input{font:22px sans-serif;padding:12px;margin:10px;display:block}label{display:block;padding:18px}input[type=checkbox]{display:inline}</style><h1>dsh 回归测试</h1><button style="position:fixed;right:12px;top:30px;width:130px;z-index:10" onclick="this.textContent='已点中';fetch('/event?name=covered')">遮挡测试</button><label><input id="toggle" type="checkbox" onchange="fetch('/event?name='+encodeURIComponent('checked:'+this.checked))">测试提醒</label><input aria-label="测试输入框" id="entry" placeholder="测试输入框" oninput="fetch('/event?name='+encodeURIComponent('input:'+this.value))"><button onclick="this.textContent='选择张三成功';fetch('/event?name=exact')">张三</button><button>张三丰</button><button>同名联系人</button><button>同名联系人</button><a href="/canvas">打开画布</a><p id="result">测试准备完毕</p>`;
  const canvas=`<!doctype html><meta charset="utf-8"><meta name="google" content="notranslate"><meta name="viewport" content="width=device-width,initial-scale=1"><canvas id="c" width="660" height="850" style="width:100%;height:850px"></canvas><script>let c=document.querySelector('canvas'),x=c.getContext('2d');function draw(clicked=false){x.fillStyle=clicked?'#dcfce7':'#eef2ff';x.fillRect(0,0,660,850);x.font='32px sans-serif';['蓝色按钮','绿色按钮','橙色按钮'].forEach((s,i)=>{x.fillStyle=['#2563eb','#16a34a','#ea580c'][i];x.fillRect(70,120+i*200,480,100);x.fillStyle='white';x.fillText(s,180,183+i*200)});if(clicked){x.fillStyle='black';x.fillText('画布已响应',170,800)}}draw();c.onclick=()=>{draw(true);fetch('/event?name=canvas')};</script>`;
  const chat=`<!doctype html><meta charset="utf-8"><meta name="google" content="notranslate"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:22px sans-serif;padding:20px}button,input{font:22px sans-serif;padding:12px;margin:10px;display:block}</style><div id="list"><h1>微信流程本地测试</h1><button onclick="document.querySelector('#list').hidden=true;document.querySelector('#chat').hidden=false;fetch('/event?name=chat-open')">测试联系人</button><button>测试联系人二</button></div><div id="chat" hidden><h1>测试联系人</h1><div id="messages"></div><p id="status"></p><input id="message" aria-label="消息输入框" placeholder="消息输入框" oninput="fetch('/event?name='+encodeURIComponent('message:'+this.value))"><button onclick="let i=document.querySelector('#message');if(!i.value)return;fetch('/event?name='+encodeURIComponent('sent:'+i.value));document.querySelector('#messages').textContent=i.value+' 已发送';i.value='';">发送</button></div>`;
  const pendingChat=chat.replace("fetch('/event?name='+encodeURIComponent('sent:'+i.value));document.querySelector('#messages').textContent=i.value+' 已发送';i.value='';", "fetch('/event?name=send-pending');document.querySelector('#status').textContent='请求处理中';");
  server=createServer((req,res)=>{if(req.url.includes('/chat/completions')) {
    let body='';req.on('data',chunk=>{body+=chunk});req.on('end',()=>setTimeout(()=>{
      const scripted=modelResponder?.(JSON.parse(body));
      if(failModel||scripted?.failure){res.writeHead(400,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Local fixture error',type:'invalid_request_error'}}));return;}
      if(typeof scripted?.text==='string') {
        const timer=setTimeout(()=>{res.setHeader('content-type','application/json');res.end(JSON.stringify({choices:[{message:{role:'assistant',content:scripted.text},finish_reason:'stop'}],usage:{prompt_tokens:1,completion_tokens:1}}));},scripted.delayMs||0);
        res.once('close',()=>clearTimeout(timer));return;
      }
      const first=++modelRequests===1;
      const name=scripted?.name||(first?'checkpoint':'done');
      const args=scripted?.args||(first?{goal:'核对本地任务面板',completed:'观察页面',next:'保存测试结果',expected:'任务结束后面板消失'}:{summary:'本地生命周期检查完成',success:true});
      res.setHeader('content-type','application/json');res.end(JSON.stringify({choices:[{message:{role:'assistant',content:null,tool_calls:[{id:'fixture-'+modelRequests,type:'function',function:{name,arguments:JSON.stringify(args)}}]},finish_reason:'tool_calls'}],usage:{prompt_tokens:1,completion_tokens:1}}));
    },modelResponder?100:1400));return;
  }if(req.url.startsWith('/event')){events.push(new URL(req.url,'http://local').searchParams.get('name'));res.end('ok');return;}res.setHeader('content-type','text/html;charset=utf-8');res.end(req.url.startsWith('/canvas')?canvas:req.url.startsWith('/chat-pending')?pendingChat:req.url.startsWith('/chat')?chat:html);});
  await new Promise(r=>server.listen(18100,'0.0.0.0',r));
  if(process.env.DSH_NATIVE_ONLY!=='1') {
  const packages=run('shell','pm','list','packages'); const browser=packages.includes('org.chromium.webview_shell')?'org.chromium.webview_shell/.WebViewBrowserActivity':'com.android.chrome/com.google.android.apps.chrome.Main';
  function openPage(path){run('shell','am','force-stop',browser.split('/')[0]);run('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:18100'+path,'-n',browser);}
  openPage('/');await delay(2500);
  const pending=rpc('control_start',{task:'仅在本地回归测试页面检查读屏、勾选、中文输入和截图，允许在本地测试会话发送测试正文，禁止联系真实用户。'}).then(result=>({result}),error=>({error}));
  await delay(900);shot('01-approval');console.log(`WAIT_APPROVAL ${out}/01-approval.png`);
  if(process.env.DSH_SMOKE_APPROVE==='1') { await delay(700);run('shell','input','tap','780','2180'); }
  const settled=await pending;if(settled.error)throw settled.error;
  const initial=saveResult('02-control-start',settled.result);check('电脑控制需要手机批准后返回屏幕',!initial.isError&&text(initial).includes('dsh 回归测试'));
  const screen=await rpc('read_screen');saveResult('03-screen',screen);
  const toggle=nodeId(screen,'测试提醒');
  const firstTap=saveResult('03-tap',await rpc('tap',{node_id:toggle}));
  check('原生点击经过安全执行链',!firstTap.isError);
  let waited=saveResult('04-checked',await rpc('wait_for',{text:'测试提醒',checked:true,seconds:2}));
  if(waited.isError && !events.includes('checked:true')) {
    const row=text(waited).split('\n').find(l=>l.includes('"测试提醒"'));
    const b=row?.match(/bounds=\((\d+),(\d+),(\d+),(\d+)\)/)?.slice(1).map(Number);
    if(b){saveResult('04-coordinate-fallback',await rpc('tap',{x:Math.floor((b[0]+b[2])/2),y:Math.floor((b[1]+b[3])/2)}));waited=saveResult('04-checked-retry',await rpc('wait_for',{text:'测试提醒',checked:true,seconds:2}));}
  }
  if(text(waited).includes('checked=unknown')) {
    check('不可靠状态明确标为未知并附图',waited.isError&&waited.content.some(x=>x.type==='image'));
    check('单次原生触摸实际完成勾选',events.includes('checked:true'));
  } else check('勾选状态经真实读屏核验',!waited.isError&&text(waited).includes('checked=true'));
  const fresh=await rpc('read_screen');const entry=text(fresh).split('\n').find(l=>l.includes('view_id="entry"'))?.match(/\[([a-z]+\d+)\]/)?.[1] || nodeId(fresh,'测试输入框');
  const typed=await rpc('type',{node_id:entry,text:'中文输入测试',mode:'replace'});check('中文输入通过手机工具写入',!typed.isError&&events.includes('input:中文输入测试'));
  if(text(typed).includes('键盘已展开')) await rpc('back');
  const found=saveResult('04-duplicates',await rpc('find_elements',{text:'同名联系人'}));check('重名联系人返回多个候选',text(found).split('\n').filter(x=>x.includes('同名联系人')).length>=2);
  const ambiguous=await rpc('tap_text',{text:'同名联系人',scrolls:0});check('重名点击被拒绝',ambiguous.isError);
  const exact=saveResult('04-exact',await rpc('tap_text',{text:'张三',scrolls:0}));
  check('精确姓名优先于相似姓名',!exact.isError&&events.includes('exact'));
  shot('04-running-panel');
  check('运行时存在任务面板',run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
  const covered=await rpc('tap_text',{text:'遮挡测试',scrolls:0});
  check('面板覆盖的位置仍能准确触摸',!covered.isError&&events.includes('covered'));
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
  // Ordinary canvas navigation is allowed; payments and destructive targets still require approval.
  saveResult('08-canvas-action',point);check('画布操作有可见反馈或风险确认',typeof point.isError==='boolean');
  // Local chat fixture: actual browser touches and Chinese text, without contacting anybody.
  openPage('/chat');
  await delay(1000);
  const chatList=await rpc('read_screen');
  check('聊天列表自动带图',chatList.content.some(x=>x.type==='image'));
  const selected=saveResult('10-selected',await rpc('tap_text',{text:'测试联系人',scrolls:0}));
  check('一次触摸打开正确会话',!selected.isError&&events.includes('chat-open'));
  const chatScreen=saveResult('10-chat-screen',await rpc('read_screen'));
  const semantic=saveResult('10-semantic',await rpc('find_elements',{editable:true,view_id:'message'}));
  check('按可输入状态与资源id定位正文框',!semantic.isError&&text(semantic).includes('消息输入框')&&text(semantic).split('<screen_data>')[0].split(/<screen_data /)[0].split('\n').filter(l=>l.startsWith('[')).length===1);
  const field=text(chatScreen).split('\n').find(l=>l.includes('view_id="message"'))?.match(/\[([a-z]+\d+)\]/)?.[1];assert.ok(field,'message field');
  const message='明天十点见，这是本地回归测试。';
  const entered=await rpc('type',{node_id:field,text:message,mode:'replace'});
  check('聊天正文写入且核对中文',!entered.isError&&events.includes('message:'+message));
  if(text(entered).includes('键盘已展开')) await rpc('back');
  const sendPending=rpc('tap_text',{text:'发送',scrolls:0}).then(result=>({result}),error=>({error}));
  await delay(1000);shot('10-send-confirmation');
  if(process.env.DSH_SMOKE_APPROVE==='1')run('shell','input','tap','780','2180');
  const sendSettled=await sendPending;if(sendSettled.error)throw sendSettled.error;
  saveResult('10-send-result',sendSettled.result);
  check('单次确认后只发出一条本地消息',!sendSettled.result.isError&&events.filter(x=>x==='sent:'+message).length===1);
  check('发送结果有新增气泡与清空输入框的依据',text(sendSettled.result).includes('已核验：新增正文气泡'));
  const sent=saveResult('11-message-result',await rpc('read_screen'));
  check('发出后看见正文和已发送标记',text(sent).includes('已发送')&&text(sent).includes(message));
  check('输入提示词不被当作草稿内容',text(sent).split('\n').some(l=>l.includes('view_id="message"')&&l.includes('value_empty=true')));
  openPage('/chat');
  await delay(800);await rpc('read_screen');
  const draft='连续操作草稿';
  const sequence=saveResult('11-sequence',await rpc('perform_steps',{steps:[
    {action:'tap',target:{text:'测试联系人'},expect_text:'消息输入框'},
    {action:'type',target:{editable:true,view_id:'message'},text:draft,expect_text:draft},
  ]}));
  check('连续操作重新定位联系人和输入框并核验草稿',!sequence.isError&&text(sequence).includes('2/2')&&events.includes('message:'+draft));
  const blockedSequence=await rpc('perform_steps',{steps:[{action:'tap',target:{text:'发送'}}]});
  check('连续操作不能绕过发送确认',blockedSequence.isError&&!events.includes('sent:'+draft)&&!run('shell','dumpsys','window','windows').includes('dsh-confirm-card'));
  const missing=await rpc('perform_steps',{steps:[{action:'tap',target:{text:'不存在的按钮'}},{action:'back'}]});
  check('连续操作找不到目标时停止后续动作',missing.isError&&text(missing).includes('0/2'));
  openPage('/chat-pending');
  await delay(800);await rpc('read_screen');await rpc('tap_text',{text:'测试联系人',scrolls:0});
  const pendingField=await rpc('find_elements',{editable:true,view_id:'message'});
  const pendingId=text(pendingField).match(/\[([a-z]+\d+)\]/)?.[1];assert.ok(pendingId,'pending message field');
  const pendingTyped=await rpc('type',{node_id:pendingId,text:message,mode:'replace'});
  if(text(pendingTyped).includes('键盘已展开'))await rpc('back');
  const uncertainSend=rpc('tap_text',{text:'发送',scrolls:0});
  await delay(900);if(process.env.DSH_SMOKE_APPROVE==='1')run('shell','input','tap','780','2180');
  const uncertain=saveResult('12-uncertain-send',await uncertainSend);
  check('处理中的画面变化不冒充发送成功',text(uncertain).includes('尚未同时看到')&&events.filter(x=>x==='send-pending').length===1);
  const waitDraft=await rpc('wait_for',{text:message,seconds:1});check('草稿文字不能解除发送未确认状态',waitDraft.isError);
  const noRepeat=await rpc('tap_text',{text:'发送',scrolls:0});check('结果不明时阻止重复触摸发送',noRepeat.isError&&events.filter(x=>x==='send-pending').length===1);
  shot('12-before-stop');
  // This hits the actual panel's Stop button, rather than the MCP stop API.
  const dump=run('shell','dumpsys','window','windows');
  const start=dump.indexOf('dsh-task-panel');
  const panel=dump.slice(start,start+7000);
  const frame=panel.match(/mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/) || panel.match(/frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if(!frame) { writeFileSync(`${out}/window-diagnostic.txt`,panel); throw Error('task panel bounds unavailable'); }
  const [,left,top,right,bottom]=frame.map(Number);
  const density=Number(run('shell','wm','density').match(/(\d+)\s*$/)?.[1]||420)/160;
  run('shell','input','tap',String(Math.floor((left+right)/2)),String(Math.floor(bottom-34*density)));
  await delay(800);
  check('面板停止按钮取消正在运行的任务', (await rpc('read_screen')).isError);
  check('停止后任务面板完全移除',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
  const stopped=await rpc('control_stop');check('电脑控制可停止',!stopped.isError);
  const forbidden=await rpc('read_screen');check('停止后不能继续读屏',forbidden.isError);
  shot('09-finished');
  const completePending=rpc('run_task',{task:'运行本地任务面板生命周期检查'});
  await delay(700);if(process.env.DSH_SMOKE_APPROVE==='1')run('shell','input','tap','780','2180');
  await delay(2200);shot('13-progress-plan');
  check('本地模型任务运行中显示面板',run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
  const completed=saveResult('13-completed',await completePending);
  check('本地模型任务可正常完成',!completed.isError&&text(completed).includes('本地生命周期检查完成'));
  await delay(500);
  check('正常完成后面板自动移除',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
  failModel=true;
  const failedPending=rpc('run_task',{task:'运行本地模型错误清理检查'});
  await delay(700);if(process.env.DSH_SMOKE_APPROVE==='1')run('shell','input','tap','780','2180');
  const failed=await failedPending;check('模型错误有失败结果',failed.isError);
  await delay(500);check('模型错误后面板自动移除',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
  shot('14-idle');
  }
  if(process.env.DSH_NATIVE_FIXTURE==='1') {
    const {nativeChatCases}=await import('./native-chat-cases.mjs');
    if(process.env.DSH_RESUME_ONLY!=='1')await nativeChatCases({run,rpc,check,saveResult,shot});
    if(process.env.DSH_RESUME_SPEED==='1') {
      const {resumeSpeedCases}=await import('./resume-speed-cases.mjs');
      await resumeSpeedCases({run,rpc,check,saveResult,shot,out,setResponder:fn=>{modelResponder=fn;failModel=false;modelRequests=0;}});
    }
  }
  if(process.env.DSH_CHAT_QA==='1') {
    const {chatCases}=await import('./chat-cases.mjs');
    await chatCases({run,check,shot,out,setResponder:fn=>{modelResponder=fn;failModel=false;modelRequests=0;}});
  }
}catch(e){checks.push({name:'device run',passed:false,error:e.message});console.error(e.stack);process.exitCode=1;}
finally{
  // Restore the emulator's settings in memory; no account token is written to the report.
  try{await rpc('control_stop');}catch{}
  if(original){
    const without=enabled?.split(':').filter(x=>x&&x!=='null'&&!x.startsWith('dev.pocketagent/')).join(':');
    run('shell','settings','put','secure','enabled_accessibility_services',without||"''");await delay(250);
    run('shell','am','force-stop','dev.pocketagent');prefsWrite(original.replace(/<boolean name="settings\.computerLink"[^>]*\/>/g,'').replace(/<string name="settings\.linkToken">[^<]*<\/string>/g,''));
    if(progressBefore!=null)prefsWrite(progressBefore,'dsh_progress');
    else if(progressBefore===null)run('shell','run-as','dev.pocketagent','rm','-f','shared_prefs/dsh_progress.xml','shared_prefs/dsh_progress.xml.bak');
    if(chatBefore!=null)prefsWrite(chatBefore,'dsh_chat');
    else if(chatBefore===null)run('shell','run-as','dev.pocketagent','rm','-f','shared_prefs/dsh_chat.xml','shared_prefs/dsh_chat.xml.bak');
  }
  if(enabled!==undefined)run('shell','settings','put','secure','enabled_accessibility_services',enabled&&enabled!=='null'?enabled:"''");
  if(proxy!==undefined){if(proxy&&proxy!=='null')run('shell','settings','put','global','http_proxy',proxy);else {run('shell','settings','put','global','http_proxy',':0');run('shell','settings','delete','global','http_proxy');}}
  try{run('forward','--remove','tcp:18765');}catch{}
  try{run('reverse','--remove','tcp:18100');}catch{}
  server?.close();
  server?.closeAllConnections();
  writeFileSync(`${out}/results.json`,JSON.stringify({checks,events,timings},null,2),'utf8');
  console.log(`RESULTS ${checks.filter(x=>x.passed).length}/${checks.length}`);
  release();
}
