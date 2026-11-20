/**
 * What the page says when it cannot run: the store would not open, or a screen threw while
 * rendering. It imports no screen and never derives the ledger, so it still stands when those fail,
 * and it can still take a backup out of the store, which is the one way out that keeps the data.
 */
import type { ComponentChildren } from 'preact';
import { useErrorBoundary, useState } from 'preact/hooks';
import { PillButton, TextField } from './ui';
import { backupPassphraseProblem, exportBackup } from './backup';

export function Stuck({ canBackUp }: { canBackUp: boolean }) {
  const [pass, setPass] = useState('');
  const [again, setAgain] = useState('');
  const [said, setSaid] = useState<'done' | 'failed' | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const backUp = () => {
    // The export sheet's two checks, in its words: a reason on screen, not a button that stays grey.
    const why = backupPassphraseProblem(pass) ?? (again !== pass ? 'دوتا رمز یکی نیستن.' : null);
    setProblem(why);
    setSaid(null);
    if (!why) exportBackup(pass).then(() => setSaid('done'), () => setSaid('failed'));
  };
  return (
    <div class="screen no-tabs">
      <h1 class="screen-title">برنامه باز نشد</h1>
      <p class="muted">دوباره امتحان کن. اگه چقدر تومن توی پنجرهٔ دیگه‌ای هم بازه، اول اون رو ببند.</p>
      <div class="stack">
        <PillButton label="دوباره امتحان کن" voice="primary" block onClick={() => location.reload()} />
        {canBackUp && <>
          <p class="muted">اگه باز هم نشد، قبل از هر کار دیگه‌ای از دفترت پشتیبان بگیر.</p>
          <TextField label="رمز فایل پشتیبان" type="password" value={pass} onInput={(v) => { setPass(v); setProblem(null); }} />
          <TextField label="دوباره همون رمز" type="password" value={again} onInput={(v) => { setAgain(v); setProblem(null); }} />
          <PillButton label="ساختن فایل پشتیبان" block onClick={backUp} />
          {problem && <p class="set-note error">{problem}</p>}
          {said === 'done' && <p class="set-note">پشتیبان ساخته شد. فایل و رمزش رو جای امن نگه دار.</p>}
          {said === 'failed' && <p class="set-note error">پشتیبان ساخته نشد. دوباره امتحان کن.</p>}
        </>}
      </div>
    </div>
  );
}

export function Guard({ children }: { children: ComponentChildren }) {
  const [error] = useErrorBoundary((e) => console.error('render failed', e));
  // The store is loaded by the time anything renders, so the backup can still be taken out.
  return error ? <Stuck canBackUp /> : <>{children}</>;
}
