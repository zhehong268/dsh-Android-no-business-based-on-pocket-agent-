import {mkdirSync,openSync,writeFileSync,readFileSync,closeSync,unlinkSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

/** Both runners mutate test settings. Never run them concurrently on the same emulator. */
export function lockPhone(serial) {
  if(!/^emulator-\d+$/.test(serial))throw Error('仅允许使用独立模拟器');
  mkdirSync(fileURLToPath(new URL('../build/',import.meta.url)),{recursive:true});
  const path=fileURLToPath(new URL(`../build/phone-test-${serial}.lock`,import.meta.url));
  let fd;
  try{fd=openSync(path,'wx');}catch(error){
    if(error.code!=='EEXIST')throw error;
    const pid=Number(readFileSync(path,'utf8'));
    let alive=true;
    try{process.kill(pid,0);}catch(e){if(e.code==='ESRCH')alive=false;}
    if(alive)throw Error('该模拟器正在运行另一项测试，请等它结束');
    unlinkSync(path);fd=openSync(path,'wx');
  }
  writeFileSync(fd,String(process.pid));closeSync(fd);
  const release=()=>{try{if(readFileSync(path,'utf8')===String(process.pid))unlinkSync(path);}catch{}};
  process.once('exit',release);return release;
}
