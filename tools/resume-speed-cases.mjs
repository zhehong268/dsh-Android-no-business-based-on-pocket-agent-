import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {hasTaskResult,tapTaskResult} from './result-popup.mjs';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');

export async function resumeSpeedCases({run,rpc,check,saveResult,shot,out,setResponder}) {
  const uiFile='/sdcard/dsh-resume-speed-ui.xml';
  function nodes(){run('shell','uiautomator','dump',uiFile);const xml=run('shell','cat',uiFile);return [...xml.matchAll(/<node\s+([^>]+)>/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(x=>[x[1],x[2]])));}
  function tap(label){const n=nodes().find(n=>n.text===label);assert.ok(n,'界面没有找到：'+label);const b=n.bounds.match(/\d+/g).map(Number);run('shell','input','tap',String((b[0]+b[2])>>1),String((b[1]+b[3])>>1));}
  async function open(scene){run('shell','am','start','-S','-W','-n','dev.dsh.phonefixture/.FixtureActivity','--es','scene',scene);await delay(450);}
  async function start(task){const pending=rpc('control_start',{task});await delay(950);run('shell','input','tap','780','2180');const result=await pending;assert.ok(!result.isError,'测试控制会话启动');}
  function panelBounds(){const block=run('shell','dumpsys','window','windows').split(/Window #/).find(s=>s.includes('dsh-task-panel'));assert.ok(block,'运行面板不存在');const m=block.match(/\b(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);assert.ok(m,'无法读取面板位置');return m.slice(1).map(Number);}
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
    tapTaskResult(run,'close');
    run('shell','am','start','-n','dev.pocketagent/.ui.MainActivity');await delay(600);
    tap('记录');await delay(300);tap(goal);await delay(300);
    const labels=nodes().map(n=>n.text);
    check('失败记录提供继续任务入口',labels.includes('继续任务')&&!labels.includes('重新运行'));
    tap('继续任务');
    for(let i=0;i<20&&!hasTaskResult(run);i++)await delay(200);
    check('从记录继续后再次失败仍显示结果弹窗',hasTaskResult(run)&&requests>=3);
    allowResume=true;
    tapTaskResult(run,'continue');
    for(let i=0;i<30&&!resumed;i++)await delay(200);
    check('继续任务恢复原目标和进度并回到原应用',resumed?.originalGoal&&resumed?.savedProgress&&resumed?.originalApp);
    await delay(550);check('恢复任务完成后面板消失',!run('shell','dumpsys','window','windows').includes('dsh-task-panel'));
    shot('speed-resumed');
    check('从失败弹窗继续任务后显示完成弹窗',hasTaskResult(run));
    tapTaskResult(run);
    writeFileSync(`${out}/resume-result.json`,JSON.stringify({requests,resumed},null,2));
  } finally {
    setResponder(null);
    try{await rpc('control_stop')}catch{}
    run('shell','rm','-f',uiFile);
    run('shell','am','force-stop','dev.dsh.phonefixture');
  }
}
