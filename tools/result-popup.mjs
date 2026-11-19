import assert from 'node:assert/strict';

const title = 'dsh-task-result';

export function hasTaskResult(run) {
  return run('shell', 'dumpsys', 'window', 'windows').includes(title);
}

// UiAutomator dumps the active app, not the non-focusable result window.
export function tapTaskResult(run, action = 'only') {
  const dump = run('shell', 'dumpsys', 'window', 'windows');
  const window = dump.split(/Window #/).find(block => block.includes(title));
  const frame = window?.match(/\b(?:mFrame|frame)=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  assert.ok(frame, '无法读取任务结果窗口位置');
  const [left, top, right, bottom] = frame.slice(1).map(Number);
  const fraction = {only: 0.5, close: 0.30, continue: 0.75}[action];
  assert.ok(fraction);
  const density = Number(run('shell', 'wm', 'density').match(/(\d+)\s*$/)?.[1]) / 160;
  const x = Math.round(left + (right - left) * fraction);
  const y = Math.max(top, Math.round(bottom - 35 * density));
  run('shell', 'input', 'tap', String(x), String(y));
}
