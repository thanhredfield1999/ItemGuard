// Sets up the scene the jump test needs, using a real client: place a chest, get a tracked sword,
// put it into the chest and take it back. Server console handles blocks and items; the client does
// the inventory clicks so the real listener runs.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username: 'IGProbe',
  version: '1.21.11',
  auth: 'offline'
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

bot.once('spawn', async () => {
  const p = bot.entity.position;
  const base = { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
  // Two blocks to the east, on a clear floor, so the landing block in front stays walkable.
  const chest = { x: base.x + 3, y: base.y, z: base.z };
  out('spawned', { position: base, chest });

  // Flat, lit ground around the chest so "no safe spot" cannot be the outcome under test.
  bot.chat(`/fill ${chest.x - 3} ${chest.y - 1} ${chest.z - 3} ${chest.x + 3} ${chest.y - 1} ${chest.z + 3} stone`);
  await sleep(600);
  bot.chat(`/fill ${chest.x - 3} ${chest.y} ${chest.z - 3} ${chest.x + 3} ${chest.y + 2} ${chest.z + 3} air`);
  await sleep(600);
  bot.chat(`/setblock ${chest.x} ${chest.y} ${chest.z} chest[facing=west]`);
  await sleep(800);
  bot.chat('/give IGProbe netherite_sword 1');
  await sleep(1200);

  out('scene-ready', { chest });
  await sleep(500);

  // Walk next to the chest and open it, then shift-click the sword in and back out. Real clicks are
  // what make the listener classify the transfer and record the chest position.
  bot.chat(`/tp IGProbe ${chest.x + 1.5} ${chest.y} ${chest.z + 0.5}`);
  await sleep(1200);

  const block = bot.blockAt(bot.entity.position.offset(-1, 0, 0));
  out('block-in-front', { name: block ? block.name : 'none' });

  try {
    const window = await bot.openContainer(block);
    out('chest-open', { slots: window.slots.length });
    const sword = bot.inventory.items().find((item) => item.name.includes('sword'));
    if (!sword) {
      out('no-sword', {});
    } else {
      await window.deposit(sword.type, null, 1);
      await sleep(900);
      const stored = window.containerItems().find((item) => item.name.includes('sword'));
      out('deposited', { found: Boolean(stored) });
      if (stored) {
        await window.withdraw(stored.type, null, 1);
        await sleep(900);
        out('withdrawn', {});
      }
    }
    window.close();
  } catch (error) {
    out('chest-error', { message: String(error.message || error).slice(0, 160) });
  }

  await sleep(1000);
  out('done', { chest });
  bot.quit();
});

bot.on('message', (message) => {
  const text = message.toString();
  if (text.includes('ItemGuard')) out('chat', { text: text.slice(0, 140) });
});

bot.on('kicked', (reason) => out('kicked', { reason: String(reason).slice(0, 200) }));
bot.on('error', (error) => out('error', { message: String(error.message || error).slice(0, 200) }));

setTimeout(() => { out('timeout', {}); process.exit(1); }, 70000);
