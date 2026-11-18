import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
const delay=ms=>new Promise(r=>setTimeout(r,ms));

export async function chatCases({run,check,shot,out,setResponder}) {
  const file='/sdcard/dsh-chat-ui.xml';let calls=0,secondRoles=[],toolFree=true;
  const services=run('shell','settings','get','secure','enabled_accessibility_services').trim();
  function savedProgress(){try{return run('shell','run-as','dev.pocketagent','cat','shared_prefs/dsh_progress.xml')}catch{return null}}
  const progress=savedProgress();
  function nodes(){run('shell','uiautomator','dump',file);const xml=run('shell','cat',file);return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],x[2]])));}
  function click(n){assert.ok(n,'聊天界面目标不存在');const b=n.bounds.match(/\d+/g).map(Number);run('shell','input','tap',String((b[0]+b[2])>>1),String((b[1]+b[3])>>1));}
  function tap(label,list=nodes()){click(list.find(n=>n.text===label||n['content-desc']===label));}
  async function wait(label){for(let i=0;i<15;i++){const list=nodes();if(list.some(n=>n.text===label))return list;await delay(150);}throw Error('聊天未出现：'+label);}
  function question(text){const list=nodes();click(list.find(n=>n.class==='android.widget.EditText'));run('shell','input','text',text.replaceAll(' ','%s'));run('shell','input','keyevent','4');tap('发送问题');}
  async function open(){run('shell','am','start','-S','-W','-n','dev.pocketagent/.ui.MainActivity');await delay(500);tap('聊天答疑');await delay(250);}
  setResponder(body=>{
    calls++;toolFree&&= !('tools' in body)&&!('tool_choice' in body)&&!JSON.stringify(body).includes('<screen_data');
    if(calls===1)return {text:'42'};
    if(calls===2){secondRoles=body.messages.map(m=>m.role);return {text:'40'};}
    if(calls===3)return {failure:true};
    if(calls===4)return {text:'重试成功'};
    return {text:'迟到的回答不应显示',delayMs:6000};
  });
  try {
    const other=services.split(':').filter(x=>x&&x!=='null'&&!x.startsWith('dev.pocketagent/')).join(':');
    run('shell','settings','put','secure','enabled_accessibility_services',other||"''");await delay(400);
    await open();question('17+25');await wait('42');
    check('未开启无障碍仍能从首页进入聊天并得到回答',!run('shell','settings','get','secure','enabled_accessibility_services').includes('dev.pocketagent/')&&toolFree);
    question('minus2');await wait('40');
    check('聊天追问保留原生多轮上下文且不发送手机工具',secondRoles.join(',')==='system,user,assistant,user'&&toolFree);
    const copies=nodes().filter(n=>n.text==='复制');click(copies.at(-1));
    click(nodes().find(n=>n.class==='android.widget.EditText'));run('shell','input','keyevent','279');
    check('聊天回答可以复制并粘贴到问题框',nodes().some(n=>n.class==='android.widget.EditText'&&n.text==='40'));
    run('shell','input','keyevent','123');run('shell','input','keyevent','67','67');run('shell','input','keyevent','4');
    question('network');await wait('服务商返回了意外的响应（400）');tap('重试');const retried=await wait('重试成功');
    check('聊天失败可重试且问题不会重复插入',retried.filter(n=>n.text==='network').length===1);
    question('stop');tap('停止');await wait('已停止生成，可以重试。');
    check('聊天停止会取消请求且不显示迟到回答',!nodes().some(n=>n.text==='迟到的回答不应显示'));
    await open();await wait('上次回复未完成，可以重试。');
    check('进程重启恢复聊天记录和未完成问题',nodes().some(n=>n.text==='stop'));
    shot('chat-restored');
    tap('清空');const dialog=nodes();click(dialog.find(n=>n.text==='清空'&&n.class==='android.widget.Button'));await wait('有什么想问的？');
    check('清空会话删除本机聊天记录',!run('shell','run-as','dev.pocketagent','cat','shared_prefs/dsh_chat.xml').includes('chat.messages.v1'));
    check('聊天没有修改手机任务恢复进度',savedProgress()===progress);
    check('聊天全程不显示手机任务悬浮窗',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
    shot('chat-empty');writeFileSync(`${out}/chat-results.json`,JSON.stringify({calls,secondRoles,toolFree},null,2));
  } finally {
    setResponder(null);run('shell','am','force-stop','dev.pocketagent');run('shell','rm','-f',file);
    run('shell','settings','put','secure','enabled_accessibility_services',services&&services!=='null'?services:"''");await delay(800);
  }
}
