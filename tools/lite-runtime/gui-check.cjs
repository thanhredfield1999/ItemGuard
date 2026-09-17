// Minimal check: does /ig gui actually open a window for a real client? Reports every window and
// every plugin message, so a silent failure is distinguishable from a client-side miss.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const bot = mineflayer.createBot({
  host: '127.0.0.1', port, username: 'IGProbe', version: '1.21.11', auth: 'offline'
});

bot._client.on('open_screen', (packet) => out('open_screen_packet', {
  windowId: packet.windowId, type: packet.inventoryType ?? packet.windowType ?? null
}));

bot.on('windowOpen', (window) => out('windowOpen', {
  title: String(window.title || '').slice(0, 80), slots: window.slots.length
}));

bot.on('message', (message) => out('msg', { text: message.toString().slice(0, 160) }));

bot.once('spawn', async () => {
  await sleep(2000);
  out('step', { what: 'ig check' });
  bot.chat('/ig check');
  await sleep(3000);
  out('step', { what: 'ig history' });
  bot.chat('/ig history');
  await sleep(4000);
  out('step', { what: 'ig gui' });
  bot.chat('/ig gui');
  await sleep(8000);
  out('done', {});
  bot.quit();
});

bot.on('kicked', (r) => out('kicked', { reason: String(r).slice(0, 200) }));
bot.on('error', (e) => out('error', { message: String(e.message || e).slice(0, 200) }));
setTimeout(() => { out('timeout', {}); process.exit(1); }, 45000);
