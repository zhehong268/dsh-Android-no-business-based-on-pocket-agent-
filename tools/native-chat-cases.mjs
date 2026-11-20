import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');

export async function nativeChatCases({run,rpc,check,saveResult,shot}) {
  const apk=fileURLToPath(new URL('phone-fixture/build/outputs/apk/debug/phone-fixture-debug.apk',import.meta.url));
  if(!existsSync(apk))throw Error('先构建 :phone-fixture:assembleDebug');
  run('install','-r',apk);
  const scale=run('shell','settings','get','system','font_scale').trim();
  const showIme=run('shell','settings','get','secure','show_ime_with_hard_keyboard').trim();
  const originalIme=run('shell','settings','get','secure','default_input_method').trim();
  const testIme='dev.dsh.phonefixture/.FixtureKeyboard';
  const size=run('shell','wm','size').match(/Override size: (\d+x\d+)/)?.[1];
  async function open(scene='contacts',theme='light') {
    run('shell','am','start','-S','-W','-n','dev.dsh.phonefixture/.FixtureActivity','--es','scene',scene,'--es','theme',theme);
    await delay(650);return rpc('read_screen');
  }
  try {
    await open();
    const pending=rpc('control_start',{task:'仅在本地原生聊天测试应用中核对联系人、字体、壁纸、粘贴与输入框，允许发送一条本地测试消息，不联系真实用户。'});
    await delay(1200);run('shell','input','tap','780','2180');
    const started=saveResult('native-00-start',await pending);
    check('原生测试控制会话经手机批准启动',!started.isError);
    run('shell','settings','put','system','font_scale','1.0');
    run('shell','settings','put','secure','show_ime_with_hard_keyboard','1');
    run('shell','ime','enable',testIme);
    run('shell','ime','set',testIme);
    await delay(700);
    await open('appearance','dark');
    const appearance=saveResult('native-appearance-hidden',await rpc('check_appearance',{include_visible_colors:false}));
    check('纯文字核对包含桌面和锁屏壁纸主色且不唤起键盘',
      !appearance.isError&&!appearance.content.some(c=>c.type==='image')&&text(appearance).includes('桌面壁纸主色=')&&
      text(appearance).includes('锁屏壁纸主色=')&&text(appearance).includes('键盘：未显示'));
    const styledSource=saveResult('native-styled-text',await rpc('read_ui_source'));
    const fontRow=text(styledSource).split('\n').find(l=>l.includes('字形样本 Aa 012')&&l.includes('class='));
    check('运行控件分别上报原文和公开字族',Boolean(fontRow?.includes('font_family="monospace"')));
    const styledId=fontRow.match(/\[([a-z]+\d+)\]/)?.[1];
    const styled=saveResult('native-font-by-node',await rpc('font_reference',{text:'Aa 012',node_id:styledId}));
    check('按当前节点生成字形参照',!styled.isError&&text(styled).includes('所选控件上报字族：monospace')&&styled.content.some(c=>c.type==='image'));
    const beforeKeyboard=await rpc('read_screen',{include_image:false});
    const editor=text(beforeKeyboard).split('\n').find(l=>l.includes('本地外观输入框'))
      ?.match(/bounds=\((\d+),(\d+),(\d+),(\d+)\)/)?.slice(1).map(Number);
    if(!editor)throw Error('未找到本地外观输入框');
    const [el,et,er,eb]=editor;
    // Simulate the user opening their keyboard, independently of an agent action.
    let imeShown=false;
    for(let i=0;i<4&&!imeShown;i++){
      run('shell','input','tap',String((el+er)>>1),String((et+eb)>>1));
      await delay(700);
      imeShown=run('shell','dumpsys','accessibility').includes('type=TYPE_INPUT_METHOD');
    }
    check('测试设备确认软键盘已经可见',imeShown);
    let report;
    for(let i=0;i<6;i++){
      await delay(200);
      report=await rpc('check_appearance');
      if(text(report).includes('可见键盘外观：偏'))break;
      if(!run('shell','dumpsys','input_method').includes('mInputShown=true')) {
        run('shell','input','tap',String((el+er)>>1),String((et+eb)>>1));
        await delay(450);
      }
    }
    saveResult('native-appearance-ime',report);
    if (!text(report).includes('可见键盘外观：偏')) {
      console.log('IME_DIAGNOSTIC',JSON.stringify({
        shown:run('shell','dumpsys','input_method').includes('mInputShown=true'),
        windowExposed:run('shell','dumpsys','accessibility').split('\n').find(l=>l.includes('type=TYPE_INPUT_METHOD'))?.trim(),
      }));
      shot('native-ime-diagnostic');
    }
    check('真实键盘窗口配色和应用背景分别采样且核对不上传截图',
      !report.isError&&!report.content.some(c=>c.type==='image')&&text(report).includes('可见键盘外观：偏')&&
      text(report).includes('当前应用可见背景采样')&&text(report).includes('键盘位置（屏幕像素）')&&
      !text(report).includes('测试候选词'));
    const keyboard=text(report).match(/键盘位置（屏幕像素）：\((\d+),(\d+),(\d+),(\d+)\)/);
    if(!keyboard)throw Error('键盘遮挡范围未返回');
    const [,kl,kt,kr,kb]=keyboard.map(Number);
    const blockedKey=await rpc('tap',{x:(kl+kr)>>1,y:(kt+kb)>>1});
    check('键盘坐标被拒绝且不触发按键',blockedKey.isError&&text(blockedKey).includes('坐标落在键盘区域'));
    const masked=saveResult('native-masked-ime',await rpc('screenshot'));
    check('键盘展开时仍返回遮盖后的截图',!masked.isError&&masked.content.some(c=>c.type==='image'));
    await rpc('back');
    if(originalIme&&originalIme!=='null')run('shell','ime','set',originalIme);
    run('shell','ime','disable',testIme);
    run('shell','settings','put','system','font_scale',scale&&scale!=='null'?scale:'1.0');
    if(originalIme&&originalIme!=='null'&&originalIme!==testIme)run('shell','ime','set',originalIme);
    if(originalIme!==testIme)run('shell','ime','disable',testIme);
    await open();
    const opened=saveResult('native-01-contact',await rpc('open_contact',{name:'张小明'}));
    check('原生通讯录搜索并核对联系人资料后进入会话',!opened.isError&&text(opened).includes('已核验会话标题')&&text(opened).includes('opened=1'));
    const raw=saveResult('native-02-source',await rpc('read_ui_source'));
    check('花字体下读取原始姓名和自定义输入控件',!raw.isError&&text(raw).includes('张小明')&&text(raw).includes('android.view.View')&&text(raw).includes('message_body'));
    const font=saveResult('native-03-font',await rpc('font_reference',{text:'张小明 发送'}));
    check('实际加载字体并返回字形参照图片',!font.isError&&font.content.some(c=>c.type==='image')&&text(font).includes('系统当前默认字体'));
    const found=await rpc('find_chat_input');
    check('无标签且未声明editable的正文框仍被识别',!found.isError&&text(found).includes('message_body'));
    const refused=await rpc('fill_chat',{recipient:'另一个人',text:'不应写入'});
    check('收件人标题不符时不填写草稿',refused.isError&&text(refused).includes('paste=0'));
    const body='  Aa 中文🙂\n第二行  ';
    const typed=saveResult('native-04-paste',await rpc('fill_chat',{recipient:'张小明',text:body}));
    check('原生聊天框只用一次粘贴接收完整中文和emoji',!typed.isError&&text(typed).includes('paste=1')&&text(typed).includes('set_text=0')&&text(typed).includes('keys=0'));
    const source=await rpc('read_ui_source');
    check('粘贴保留大小写空格和换行',text(source).includes(body));
    check('输入没有调出用户键盘',!text(typed).includes('键盘已展开'));
    const appended=await rpc('fill_chat',{recipient:'张小明',text:'追加',mode:'append'});
    check('已有草稿可整段选择后追加且无键盘输入',!appended.isError&&text(appended).includes('paste=2')&&text(appended).includes('keys=0'));
    const sending=rpc('tap_text',{text:'发送',scrolls:0});await delay(850);shot('native-05-send-confirmation');run('shell','input','tap','780','2180');
    const sent=saveResult('native-06-sent',await sending);
    check('原生会话一次确认后发送且核验气泡与空框',!sent.isError&&text(sent).includes('新增正文气泡')&&text(sent).includes('sent=1'));
    await open('voice');await rpc('read_screen');
    const voice=await rpc('fill_chat',{recipient:'张小明',text:'语音模式切回文字'});
    check('先识别语音模式再切文字并粘贴',!voice.isError&&text(voice).includes('toggle=1')&&text(voice).includes('paste=1'));
    const cleared=await rpc('fill_chat',{recipient:'张小明',text:'',mode:'clear'});
    check('通过整段选择与粘贴清空原生草稿',!cleared.isError&&text(cleared).includes('value_empty=true'));
    await open('slow_search');
    const combined=saveResult('native-delayed-search-draft',await rpc('fill_chat',{recipient:'张小明',text:'合并查人与草稿',open_if_needed:true}));
    check('一次调用等待延迟搜索结果并核对联系人后填草稿',!combined.isError&&text(combined).includes('合并查人与草稿')&&text(combined).includes('opened=1')&&text(combined).includes('paste=1')&&text(combined).includes('sent=0'));
    await open('ambiguous');await rpc('read_screen');
    const ambiguous=await rpc('fill_chat',{recipient:'张小明',text:'不应写入'});
    check('两个同样可信的正文框不会擅选',ambiguous.isError&&text(ambiguous).includes('paste=0'));
    await open('duplicates');await rpc('read_screen');
    const duplicate=await rpc('open_contact',{name:'同名联系人'});
    check('通讯录同名返回备注且不随意选人',duplicate.isError&&text(duplicate).includes('研发')&&text(duplicate).includes('销售')&&text(duplicate).includes('opened=0'));
    const ambiguousDraft=await rpc('fill_chat',{recipient:'同名联系人',text:'不应写入',open_if_needed:true});
    check('合并导航遇到同名停在候选页',ambiguousDraft.isError&&text(ambiguousDraft).includes('opened=0')&&text(ambiguousDraft).includes('paste=0'));
    const exact=saveResult('native-duplicate-resolved',await rpc('open_contact',{name:'同名联系人',hint:'研发'}));
    check('用户给出备注后可定位唯一联系人',!exact.isError&&text(exact).includes('opened=1'));
    await rpc('control_stop');await delay(250);await open('hidden');
    const hiddenStart=rpc('control_start',{task:'核对仅通过扩展控件结构可见的本地正文框，只填写草稿，不发送。'});
    await delay(1200);run('shell','input','tap','780','2180');
    if((await hiddenStart).isError)throw Error('原生隐藏控件测试会话未启动');
    const hiddenBefore=await rpc('find_chat_input');
    check('新任务恢复普通读屏并识别缺失的正文控件',hiddenBefore.isError);
    const hidden=saveResult('native-07-expanded-source',await rpc('fill_chat',{recipient:'张小明',text:'从运行结构定位正文'}));
    check('正文缺失时自动读取扩展控件结构后完成粘贴',!hidden.isError&&text(hidden).includes('paste=1'));
    run('shell','settings','put','system','font_scale','1.5');await delay(700);
    await open('chat','dark');await rpc('read_screen');
    const large=saveResult('native-07-large-dark',await rpc('fill_chat',{recipient:'张小明',text:'大字体深色背景'}));
    check('大字体与深色花纹聊天背景下正常定位粘贴',!large.isError&&text(large).includes('fontScale=1.5')&&text(large).includes('paste=1'));
    run('shell','wm','size','720x1600');await delay(800);await open('chat','dark');await rpc('read_screen');
    const small=saveResult('native-08-small-screen',await rpc('fill_chat',{recipient:'张小明',text:'较小屏幕适配'}));
    check('不同分辨率与大字体下正文框定位不靠固定坐标',!small.isError&&text(small).includes('paste=1'));
  } finally {
    try{await rpc('control_stop');}catch{}
    run('shell','settings','put','system','font_scale',scale&&scale!=='null'?scale:'1.0');
    if(showIme==='null')run('shell','settings','delete','secure','show_ime_with_hard_keyboard');
    else run('shell','settings','put','secure','show_ime_with_hard_keyboard',showIme);
    if(size)run('shell','wm','size',size);else run('shell','wm','size','reset');
    run('shell','am','force-stop','dev.dsh.phonefixture');
  }
}
