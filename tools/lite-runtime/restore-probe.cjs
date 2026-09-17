// Exercises /ig restore against a real server and checks each refusal is the right one.
//
// The dangerous outcome is a restore that succeeds when it should not, so the sequence walks an item
// from held, through cleared, to already-judged, asserting the verdict changes correctly at each step.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const bot = mineflayer.createBot({
  host: '127.0.0.1', port, username: 'IGRestore', version: '1.21.11', auth: 'offline'
});

const plugin = [];
bot.on('message', (message) => {
  const text = message.toString();
  if (text.includes('ItemGuard') || /#[A-Z0-9]{6}/.test(text)) plugin.push(text);
});

// Waits for the next plugin line after a command, so replies are matched to their request.
async function say(command, label) {
  const mark = plugin.length;
  bot.chat(command);
  for (let i = 0; i < 40; i++) {
    await sleep(250);
    if (plugin.length > mark) break;
  }
  const reply = plugin.slice(mark).join(' | ');
  out('reply', { step: label, text: reply.slice(0, 200) });
  return reply;
}

bot.once('spawn', async () => {
  await sleep(2500);

  // A code that was never tracked: must refuse as unknown, not invent an item.
  const unknown = await say('/ig restore #ZZZZZZ IGRestore testing', 'unknown-code');

  // A tracked item currently in hand: must refuse because restoring it would duplicate it.
  bot.chat('/give IGRestore netherite_pickaxe 1');
  await sleep(2000);
  // An identity is assigned when the item is actually handled, not when it appears in the
  // inventory, so the pickaxe must be moved into the hand before it has a code at all.
  const pick = bot.inventory.items().find((i) => i.name.includes('pickaxe'));
  if (pick) { await bot.equip(pick, 'hand'); }
  await sleep(2500);
  const held = await say('/ig history', 'history');
  const match = held.match(/#([A-Z0-9]{6})/);
  const code = match ? match[1] : null;
  out('code', { code });
  if (!code) { out('no-code', {}); bot.quit(); return; }

  const stillExists = await say(`/ig restore #${code} IGRestore testing`, 'still-exists');

  // Now destroy it and ask again: the verdict must change.
  bot.chat('/clear');
  await sleep(4000);
  const afterClear = await say(`/ig restore #${code} IGRestore testing`, 'after-clear');

  // Missing arguments must produce usage, never a silent success.
  const usage = await say('/ig restore', 'usage');

  out('summary', {
    unknownRefused: unknown.includes('nothing to restore') || unknown.includes('No such'),
    heldRefused: stillExists.includes('still exists'),
    afterClearJudged: afterClear.includes('restorable') || afterClear.includes('đủ điều kiện'),
    afterClearText: afterClear.slice(0, 120),
    usageShown: usage.includes('Usage') || usage.includes('Cách dùng')
  });
  bot.quit();
});

bot.on('kicked', (r) => out('kicked', { reason: String(r).slice(0, 200) }));
bot.on('error', (e) => out('error', { message: String(e.message || e).slice(0, 200) }));
setTimeout(() => { out('timeout', {}); process.exit(1); }, 90000);
