import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const text=r=>r.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');

export async function nativeChatCases({run,rpc,check,saveResult,shot}) {
  const apk=fileURLToPath(new URL('phone-fixture/build/outputs/apk/debug/phone-fixture-debug.apk',import.meta.url));
  if(!existsSync(apk))throw Error('先构建 :phone-fixture:assembleDebug');
  run('install','-r',apk);
  const scale=run('shell','settings','get','system','font_scale').trim();
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
    await open('ambiguous');await rpc('read_screen');
    const ambiguous=await rpc('fill_chat',{recipient:'张小明',text:'不应写入'});
    check('两个同样可信的正文框不会擅选',ambiguous.isError&&text(ambiguous).includes('paste=0'));
    await open('duplicates');await rpc('read_screen');
    const duplicate=await rpc('open_contact',{name:'同名联系人'});
    check('通讯录同名返回备注且不随意选人',duplicate.isError&&text(duplicate).includes('研发')&&text(duplicate).includes('销售')&&text(duplicate).includes('opened=0'));
    const exact=await rpc('open_contact',{name:'同名联系人',hint:'研发'});
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
    if(size)run('shell','wm','size',size);else run('shell','wm','size','reset');
    run('shell','am','force-stop','dev.dsh.phonefixture');
  }
}
