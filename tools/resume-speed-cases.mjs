import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {hasTaskResult,tapTaskResult} from './result-popup.mjs';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');

export async function resumeSpeedCases({run,rpc,check,saveResult,shot,out,setResponder}) {
  const uiFile='/sdcard/dsh-resume-speed-ui.xml';
  function nodes(){run('shell','uiautomator','dump',uiFile);const xml=run('shell','cat',uiFile);return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],x[2]])));}
  async function open(scene){run('shell','am','start','-S','-W','-n','dev.dsh.phonefixture/.FixtureActivity','--es','scene',scene);await delay(450);}
  async function start(task){const pending=rpc('control_start',{task});await delay(950);run('shell','input','tap','780','2180');const result=await pending;assert.ok(!result.isError,'测试控制会话启动');}
  function windowBounds(title){const block=run('shell','dumpsys','window','windows').split(/Window #/).find(s=>s.includes(title));const m=block?.match(/\b(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);return m?.slice(1).map(Number);}
  function panelBounds(){const bounds=windowBounds('dsh-task-panel');assert.ok(bounds,'运行面板不存在');return bounds;}
  function tapConversation(fraction, bottomDp) {
    const bounds=windowBounds('dsh-task-conversation');
    assert.ok(bounds,'未找到任务对话窗口');
    const density=Number(run('shell','wm','density').match(/(\d+)\s*$/)?.[1])/160;
    run('shell','input','tap',String(Math.round(bounds[0]+(bounds[2]-bounds[0])*fraction)),String(Math.round(bounds[3]-bottomDp*density)));
  }
  try {
    await open('chat');await start('仅在本地原生应用核对悬浮窗拖动和停止，不联系真实用户。');
    const before=panelBounds();
    // Start on the plan/body, not the old title-only drag area; keep clear of Stop.
    run('shell','input','swipe',String(before[0]+90),String(before[1]+135),String(before[0]-260),String(before[1]+470),'450');await delay(150);
    const moved=panelBounds();
    check('悬浮窗正文区域可以拖动且没有误触停止',Math.abs(moved[0]-before[0])>100&&Math.abs(moved[1]-before[1])>100&&text(await rpc('control_status')).includes('电脑控制中'));
    run('shell','input','swipe',String(moved[0]+90),String(moved[1]+60),'0','0','350');await delay(120);
    const edge=panelBounds();check('悬浮窗拖到边缘仍留在屏幕内',edge[0]>=0&&edge[1]>=0&&edge[2]<=1080&&edge[3]<=2400);
    writeFileSync(`${out}/panel-bounds.json`,JSON.stringify({before,moved,edge}));shot('speed-panel-at-edge');
    run('shell','input','tap',String((edge[0]+edge[2])>>1),String(edge[3]-90));await delay(500);
    check('拖动后停止按钮仍能立即结束任务',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
    await rpc('control_stop');

    await open('delayed');await start('只在本地延迟发送场景填写并发送一条测试消息。');
    await rpc('fill_chat',{recipient:'张小明',text:'延迟气泡核验'});
    const sending=rpc('tap_text',{text:'发送',scrolls:0});await delay(700);run('shell','input','tap','780','2180');
    const sent=saveResult('speed-delayed-send',await sending);
    check('发送后输入框消失且气泡延迟出现仍能自动核验',!sent.isError&&text(sent).includes('已核验')&&text(sent).includes('sent=1'));
    await rpc('control_stop');

    await open('animated');await start('只读取持续刷新的本地测试页面，不点击、不输入。');
    const reads=[],ticks=[];
    for(let i=0;i<5;i++){const at=performance.now();const result=await rpc('read_ui_source');assert.ok(!result.isError);reads.push(Math.round(performance.now()-at));ticks.push(Number(text(result).match(/tick=(\d+)/)?.[1]));}
    const sorted=[...reads].sort((a,b)=>a-b);
    check('持续刷新页面及时返回最新内容而非旧缓存',Math.max(...reads)<1500&&ticks.every((value,i)=>Number.isFinite(value)&&(i===0||value>ticks[i-1])));
    const textReads=[],textTicks=[];
    for(let i=0;i<5;i++) {
      await delay(30); // The fixture advances every 20 ms. Only time the read below.
      const at=performance.now(), result=await rpc('read_screen',{include_image:false});
      assert.ok(!result.isError&&!result.content.some(c=>c.type==='image'));
      textReads.push(Math.round(performance.now()-at));
      textTicks.push(Number(text(result).match(/tick=(\d+)/)?.[1]));
    }
    writeFileSync(`${out}/refresh-timings.json`,JSON.stringify({source_with_image_ms:reads,ticks,median_ms:sorted[2],max_ms:sorted[4],text_only_ms:textReads,text_ticks:textTicks,text_median_ms:[...textReads].sort((a,b)=>a-b)[2]},null,2));
    check('文字读屏不等待截图且连续返回新的页面计数',textTicks.every((value,i)=>Number.isFinite(value)&&(i===0||value>textTicks[i-1])));
    await rpc('control_stop');

    await open('chat');
    const goal='本地失败后继续任务';let requests=0,resumed=null,allowResume=false;
    setResponder(body=>{
      requests++;
      if(requests===1)return {name:'checkpoint',args:{goal:'继续原任务剩余步骤',completed:'联系人已经核对',next:'检查草稿',expected:'在原会话完成'}};
      if(!allowResume)return {failure:true};
      const messages=JSON.stringify(body.messages);
      resumed={originalGoal:messages.includes('任务：'+goal),savedProgress:messages.includes('联系人已经核对'),originalApp:messages.includes('dev.dsh.phonefixture')};
      return {name:'done',args:{summary:'已继续原任务',success:true}};
    });
    const failing=rpc('run_task',{task:goal});await delay(900);run('shell','input','tap','780','2180');
    const failed=saveResult('speed-model-failure',await failing);check('模拟模型故障保留未完成任务',failed.isError);
    await delay(450);
    check('继续任务前失败弹窗可见',hasTaskResult(run));
    const enabled=run('shell','settings','get','secure','enabled_accessibility_services').trim();
    run('shell','input','keyevent','3');await delay(400);
    run('shell','am','start','-n','dev.pocketagent/.ui.MainActivity');await delay(600);
    check('切到后台再回来时无障碍授权和电脑连接都保留',enabled.includes('dev.pocketagent/') &&
      run('shell','settings','get','secure','enabled_accessibility_services').trim()===enabled && !((await rpc('control_status')).isError));
    tapTaskResult(run);
    await delay(850);
    for(let i=0;i<40&&(requests<3||!hasTaskResult(run));i++)await delay(200);
    shot('speed-repeat-failure');
    writeFileSync(`${out}/repeat-failure.json`,JSON.stringify({requests,resultShown:hasTaskResult(run)}));
    check('从弹窗继续后再次失败仍显示结果弹窗',hasTaskResult(run)&&requests>=3);
    allowResume=true;
    await delay(500);
    tapTaskResult(run);
    for(let i=0;i<30&&!resumed;i++)await delay(200);
    check('继续任务恢复原目标和进度并回到原应用',resumed?.originalGoal&&resumed?.savedProgress&&resumed?.originalApp);
    await delay(550);check('恢复任务完成后面板消失',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
    shot('speed-resumed');
    check('从失败弹窗继续任务后显示完成弹窗',hasTaskResult(run));
    tapTaskResult(run);
    writeFileSync(`${out}/resume-result.json`,JSON.stringify({requests,resumed},null,2));

    await open('chat');
    const longGoal='在本地测试中按顺序做题';
    let calls=0, supplementSeen=false;
    setResponder(({messages})=>{
      calls++;
      if(calls===1)return {name:'checkpoint',delayMs:8000,args:{goal:'原来的本地答题',completed:'',next:'核对题号',expected:'按顺序'}};
      if(calls===2) {
        supplementSeen=JSON.stringify(messages).includes('wait for question 8');
        return {name:'task_reply',args:{text:'先核对第 8 题。'}};
      }
      return {name:'done',delayMs:41_000,args:{summary:'长任务完成',success:true}};
    });
    const started=performance.now();
    const longTask=rpc('run_task',{task:longGoal}).then(result=>({result}),error=>({error}));
    await delay(900);run('shell','input','tap','780','2180');
    for(let i=0;i<20&&!windowBounds('dsh-task-panel');i++)await delay(150);
    await delay(650);
    const dp=Number(run('shell','wm','density').match(/(\d+)\s*$/)?.[1])/160;
    for(let i=0;i<4&&!windowBounds('dsh-task-conversation');i++){
      const panel=panelBounds();
      shot('task-conversation-panel');
      run('shell','input','tap',String((panel[0]+panel[2])>>1),String(Math.round(panel[3]-82*dp)));
      await delay(400);
    }
    shot('task-conversation-open');
    check('任务面板可展开对话窗口',Boolean(windowBounds('dsh-task-conversation')));
    tapConversation(0.5,95);
    await delay(600);
    run('shell','input','text','wait%sfor%squestion%s8');
    shot('task-conversation-input');
    tapConversation(0.75,40);
    for(let i=0;i<65&&(!supplementSeen||!windowBounds('dsh-task-conversation'));i++)await delay(180);
    shot('task-conversation-reply');
    // UiAutomator can omit accessibility overlay nodes. Save the reply window for visual review.
    const replyWindow=Boolean(windowBounds('dsh-task-conversation'));
    writeFileSync(`${out}/task-conversation.json`,JSON.stringify({calls,supplementSeen,replyWindow}));
    check('悬浮对话把补充发给同一任务并重新展开答复窗口',supplementSeen&&replyWindow&&calls===2);
    tapConversation(0.25,40);
    const {result:finished,error}=await longTask;
    if(error)throw error;
    check('答题任务超过 40 秒后仍可完成',!finished.isError&&performance.now()-started>40_000&&calls===3);
    check('任务结束后悬浮对话关闭',!windowBounds('dsh-task-conversation'));
    if(hasTaskResult(run))tapTaskResult(run);
  } finally {
    setResponder(null);
    try{await rpc('control_stop')}catch{}
    run('shell','rm','-f',uiFile);
    run('shell','am','force-stop','dev.dsh.phonefixture');
  }
}
