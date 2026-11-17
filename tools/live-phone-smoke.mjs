import {spawnSync} from 'node:child_process';
import {createServer} from 'node:http';
import {randomBytes} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const key=process.env.POCKET_AGENT_LIVE_KEY||'';
if(!key || !/^[A-Za-z0-9_-]+$/.test(key))throw Error('需要通过环境提供可用的 DeepSeek API 密钥');
const adb=process.env.ADB||'adb',serial=process.env.DSH_TEST_SERIAL||'emulator-5556';
if(!serial.startsWith('emulator-'))throw Error('仅允许在独立模拟器运行');
const out=process.env.DSH_LIVE_OUT||fileURLToPath(new URL('../build/live-phone',import.meta.url));
mkdirSync(out,{recursive:true});
function run(...args){const r=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:30000});if(r.status!==0)throw Error('adb 操作失败：'+args.slice(0,2).join(' '));return r.stdout;}
function prefs(xml){const r=spawnSync(adb,['-s',serial,'shell','run-as','dev.pocketagent','sh','-c',"'cat > shared_prefs/pocketagent_setup.xml'"],{input:xml,encoding:'utf8',timeout:10000});if(r.status!==0)throw Error('无法恢复测试配置');}
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const uiFile=`/sdcard/dsh-live-ui-${Date.now()}.xml`;
function nodes(){run('shell','uiautomator','dump',uiFile);const xml=run('shell','cat',uiFile);return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],x[2]])));}
function tapNode(n){assert.ok(n,'测试界面目标不存在');const b=n.bounds.match(/\d+/g).map(Number);run('shell','input','tap',String(Math.floor((b[0]+b[2])/2)),String(Math.floor((b[1]+b[3])/2)));}
function tapText(label,list=nodes()){const n=list.find(n=>n.text===label);if(!n)return false;tapNode(n);return true;}
function input(n,text){tapNode(n);run('shell','input','keyevent','123');if(n.text?.length)run('shell','input','keyevent',...Array(n.text.length).fill('67'));run('shell','input','text',text);run('shell','input','keyevent','4');}
function screenshot(name){const r=spawnSync(adb,['-s',serial,'exec-out','screencap','-p'],{timeout:10000});if(r.status===0)writeFileSync(`${out}/${name}.png`,r.stdout);}
let original,enabled,proxy,server,token=randomBytes(24).toString('hex'),id=0,approvals=0;
const checks=[],events=[],tasks=[];
function check(name,ok){assert.ok(ok,name);checks.push({name,passed:true});console.log('PASS '+name);}
async function rpc(name,args={},timeout=250000){const r=await fetch('http://127.0.0.1:18765/mcp',{method:'POST',headers:{'content-type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({jsonrpc:'2.0',id:++id,method:'tools/call',params:{name,arguments:args}}),signal:AbortSignal.timeout(timeout)});const data=await r.json();if(data.error)throw Error('MCP 请求失败');return data.result;}
async function task(text){const start=performance.now();let end;const pending=rpc('run_task',{task:text}).then(result=>{end={result};},()=>{end={error:true};});let cardWasShown=false;
  while(!end && performance.now()-start<245000){
    const shown=run('shell','dumpsys','window','windows').includes('dsh-confirm-card');
    if(shown&&!cardWasShown){await delay(500);run('shell','input','tap','780','2180');approvals++;}
    cardWasShown=shown;await delay(450);
  }
  if(!end)throw Error('真实模型任务超时');await pending;assert.ok(end.result&&!end.result.isError,'真实模型任务没有成功完成');tasks.push({ms:Math.round(performance.now()-start),approvals,metrics:end.result._meta?.dsh});return end.result;
}
try{
  enabled=run('shell','settings','get','secure','enabled_accessibility_services').trim();proxy=run('shell','settings','get','global','http_proxy').trim();
  original=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml').replace(/<map\s*\/>/,'<map></map>');
  run('shell','am','force-stop','dev.pocketagent');
  prefs(original.replace(/<boolean name="settings\.computerLink"[^>]*\/>/g,'').replace(/<string name="settings\.linkToken">[^<]*<\/string>/g,'').replace('</map>',`<boolean name="settings.computerLink" value="true"/><string name="settings.linkToken">${token}</string></map>`));
  run('reverse','tcp:17897','tcp:7897');
  run('shell','settings','put','global','http_proxy',process.env.DSH_PHONE_PROXY||'127.0.0.1:17897');
  const other=enabled.split(':').filter(x=>x&&x!=='null'&&!x.startsWith('dev.pocketagent/')).join(':');
  run('shell','settings','put','secure','enabled_accessibility_services',other||"''");await delay(500);
  run('shell','settings','put','secure','enabled_accessibility_services',[other,'dev.pocketagent/dev.pocketagent.android.PocketAccessibilityService'].filter(Boolean).join(':'));
  run('shell','settings','put','secure','accessibility_enabled','1');
  run('shell','am','start','-n','dev.pocketagent/.ui.MainActivity');await delay(1500);
  for(let i=0;i<5;i++) {const list=nodes();if(tapText('继续设置',list)){await delay(400);break;}if(tapText('继续',list)||tapText('我明白了',list)){await delay(400);continue;}if(list.some(n=>n.text==='AI 服务商与密钥'))break;if(tapText('设置',list)){await delay(400);continue;}break;}
  let list=nodes();if(!list.some(n=>n.text==='DeepSeek')){tapText('AI 服务商与密钥',list);await delay(300);}
  assert.ok(tapText('DeepSeek'),'没有找到 DeepSeek 预设');await delay(300);
  list=nodes();if(tapText('更换',list)){await delay(200);list=nodes();}
  const secretField=list.find(n=>n.class==='android.widget.EditText'&&n.password==='true');
  assert.ok(secretField,'没有找到密钥输入框');input(secretField,key);assert.ok(tapText('保存'));await delay(400);
  list=nodes();if(!list.some(n=>n.class==='android.widget.EditText'&&n.password==='false')){tapText('模型',list);await delay(300);list=nodes();}
  const model=list.find(n=>n.class==='android.widget.EditText'&&n.password==='false');input(model,'deepseek-flash');assert.ok(tapText('测试模型'));
  let passed=false;for(let i=0;i<110;i++){const xml=run('shell','run-as','dev.pocketagent','cat','shared_prefs/pocketagent_setup.xml');if(/name="check\.passed" value="true"/.test(xml)){passed=true;break;}await delay(900);}
  if(!passed){const labels=nodes().filter(n=>n.class==='android.widget.TextView'&&/连接|测试.*失败|图片.*失败|测试中/.test(n.text)).map(n=>n.text.slice(0,100));console.log('MODEL_CHECK_STATUS '+JSON.stringify(labels));}
  check('真实 DeepSeek 看图与工具调用检查通过',passed);
  // Never save setup screenshots or dumps containing credential fields.
  const html=`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:21px sans-serif;padding:20px}button,input{font:22px sans-serif;padding:12px;margin:10px;display:block}</style><div id="list"><h1>本地聊天联调</h1><button onclick="document.querySelector('#list').hidden=true;document.querySelector('#chat').hidden=false;fetch('/event?name=open')">测试联系人</button><button>测试联系人二</button></div><div id="chat" hidden><h1>测试联系人</h1><div id="messages"></div><input id="message" aria-label="消息输入框" placeholder="消息输入框" oninput="fetch('/event?name='+encodeURIComponent('typed:'+this.value))"><button onclick="let i=document.querySelector('#message');if(!i.value)return;fetch('/event?name='+encodeURIComponent('sent:'+i.value));document.querySelector('#messages').textContent=i.value+' 已发送';i.value='';">发送</button></div>`;
  server=createServer((req,res)=>{if(req.url.startsWith('/event')){events.push(new URL(req.url,'http://local').searchParams.get('name'));res.end('ok');}else{res.setHeader('content-type','text/html;charset=utf-8');res.end(html);}});
  await new Promise(resolve=>server.listen(18100,'0.0.0.0',resolve));run('reverse','tcp:18100','tcp:18100');run('forward','tcp:18765','tcp:8765');
  run('shell','am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:18100/','-n','com.android.chrome/com.google.android.apps.chrome.Main');await delay(1200);
  const body='真实模型联调：明天十点见';
  await task(`只在当前“本地聊天联调”网页操作：打开完整名称为“测试联系人”的会话，然后在标为“消息输入框”的正文框填写“${body}”作为草稿。不要点击发送，不要打开其他应用。可以用连续操作，但每一步都要核对目标。`);
  check('真实模型打开正确联系人并填入中文草稿',events.includes('open')&&events.includes('typed:'+body)&&!events.some(e=>e.startsWith('sent:')));
  check('普通导航和输入没有额外确认',approvals===1);screenshot('01-real-draft');
  await task('只在当前本地测试会话发送已有草稿一次，核对新增正文气泡和输入框清空。不要重发，不要打开其他应用。');
  check('真实模型经一次发送确认只发送一条本地消息',events.filter(e=>e==='sent:'+body).length===1&&approvals===3);
  check('真实任务结束后运行面板移除',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));screenshot('02-real-finished');
}catch(e){checks.push({name:'live phone',passed:false,error:String(e.message).split(key).join('[redacted]')});process.exitCode=1;console.error('真实手机执行链联调未通过：'+String(e.message).split(key).join('[redacted]'));}
finally{
  try{await rpc('control_stop',{},3000);}catch{}
  if(original){try{run('shell','am','force-stop','dev.pocketagent');prefs(original);}catch{}}
  if(enabled!==undefined)try{run('shell','settings','put','secure','enabled_accessibility_services',enabled&&enabled!=='null'?enabled:"''");}catch{}
  if(proxy!==undefined)try{if(proxy&&proxy!=='null')run('shell','settings','put','global','http_proxy',proxy);else {run('shell','settings','put','global','http_proxy',':0');run('shell','settings','delete','global','http_proxy');}}catch{}
  for(const args of [['forward','--remove','tcp:18765'],['reverse','--remove','tcp:18100'],['reverse','--remove','tcp:17897'],['shell','rm','-f',uiFile]])try{run(...args);}catch{}
  server?.closeAllConnections();server?.close();
  writeFileSync(`${out}/results.json`,JSON.stringify({checks,events,tasks},null,2),'utf8');console.log(`RESULTS ${checks.filter(x=>x.passed).length}/${checks.length}`);
}
