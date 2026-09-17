// Puts the tracked sword into the chest and takes it back, then jumps from the timeline and measures
// where the client actually lands and whether particles mark that chest.
//
// One script so the whole sequence runs in a single client session: the chest transfer must exist
// before the timeline can offer a teleport row.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const chest = { x: Number(process.argv[3]), y: Number(process.argv[4]), z: Number(process.argv[5]) };
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const bot = mineflayer.createBot({
  host: '127.0.0.1', port, username: 'IGProbe', version: '1.21.11', auth: 'offline'
});

const particles = [];
for (const name of ['world_particles', 'particle']) {
  bot._client.on(name, (packet) => particles.push({
    x: packet.x, y: packet.y, z: packet.z,
    id: packet.particleId ?? packet.particle?.type ?? null
  }));
}

bot.once('spawn', async () => {
  await sleep(1500);
  bot.chat(`/tp IGProbe ${chest.x + 1.5} ${chest.y} ${chest.z + 0.5}`);
  await sleep(1500);

  // Shift-click the sword into the chest, then back out. Raw clicks, because the high-level
  // deposit helper needs exact metadata the tracked sword does not match.
  const block = bot.blockAt(bot.entity.position.offset(-1, 0, 0));
  const window = await bot.openContainer(block);
  const chestSize = window.inventoryStart;
  const sword = window.slots.findIndex((s, i) => i >= chestSize && s && s.name.includes('sword'));
  out('opened', { chestSize, swordSlot: sword });
  if (sword < 0) { out('no-sword-in-inventory', {}); bot.quit(); return; }

  await bot.clickWindow(sword, 0, 1);            // shift-click into the chest
  await sleep(1200);
  const inChest = window.slots.findIndex((s, i) => i < chestSize && s && s.name.includes('sword'));
  out('after-deposit', { inChestSlot: inChest });
  if (inChest >= 0) {
    await bot.clickWindow(inChest, 0, 1);        // shift-click back out
    await sleep(1200);
    out('after-withdraw', {});
  }
  window.close();
  await sleep(2500);

  // Now the timeline has a chest row. The GUI opens after an async database query, so the
  // client must stay connected long enough for the window packet to arrive.
  out('requesting-gui', {});
  bot.chat('/ig gui');
  await sleep(8000);
  if (!jumped) out('gui-never-opened', {});
});

let jumped = false;

bot.on('windowOpen', async (window) => {
  // window.title is a chat component object, so String() yields "[object Object]". Serialising it
  // is what makes the title readable -- matching on String() silently matched nothing.
  const title = JSON.stringify(window.title || '');
  if (!title.includes('ItemGuard') || jumped) return;

  // "/ig gui" with no code opens the OVERVIEW, which lists items. The teleport rows live one level
  // deeper, in a single item's timeline, so the overview entry must be clicked first.
  if (!title.includes('Timeline') && !title.includes('Dòng')) {
    const item = window.slots.findIndex((s, i) => i >= 10 && i <= 43 && s && s.name.includes('sword'));
    out('overview', { itemSlot: item });
    if (item < 0) { out('no-item-in-overview', {}); bot.quit(); return; }
    await bot.clickWindow(item, 0, 0);
    return;
  }

  let target = null;
  const rows = [];
  window.slots.forEach((slot, index) => {
    if (!slot) return;
    const nbt = JSON.stringify(slot.nbt || {});
    rows.push({ slot: index, name: slot.name });
    // Lore text is not always exposed in the client's NBT view, so the chest icon itself is the
    // signal: only container rows render a chest, and only container rows are jump targets.
    if (target === null && slot.name === 'chest' && index >= 10 && index <= 43) target = index;
  });
  out('timeline', { rows: rows.slice(0, 30) });

  if (target === null) { out('no-clickable-row', {}); bot.quit(); return; }
  jumped = true;

  particles.length = 0;
  const before = bot.entity.position.clone();
  out('clicking', { slot: target });
  await bot.clickWindow(target, 0, 0);           // plain left click

  await sleep(6000);
  const after = bot.entity.position;
  // Vector from the landing toward the chest. Getting this backwards reports a perfect landing as
  // 180 degrees wrong, which is exactly what happened on the first run of this probe.
  const dx = (chest.x + 0.5) - after.x;
  const dz = (chest.z + 0.5) - after.z;
  const horizontal = Math.sqrt(dx * dx + dz * dz);
  const bearing = Math.atan2(-dx, dz) * 180 / Math.PI;
  const yaw = bot.entity.yaw * 180 / Math.PI;
  const facingError = Math.abs(((bearing - yaw + 540) % 360) - 180);
  const near = particles.filter((p) =>
    Math.abs(p.x - (chest.x + 0.5)) < 1.2 &&
    Math.abs(p.y - (chest.y + 0.5)) < 1.2 &&
    Math.abs(p.z - (chest.z + 0.5)) < 1.2);

  out('result', {
    movedBlocks: Number(before.distanceTo(after).toFixed(2)),
    landedAt: { x: +after.x.toFixed(2), y: +after.y.toFixed(2), z: +after.z.toFixed(2) },
    chest,
    horizontalDistanceToChest: +horizontal.toFixed(2),
    facingErrorDegrees: +facingError.toFixed(1),
    particlesTotal: particles.length,
    particlesAtChest: near.length
  });
  bot.quit();
});

bot.on('message', (m) => {
  const text = m.toString();
  if (text.includes('ItemGuard')) out('chat', { text: text.slice(0, 140) });
});
bot.on('kicked', (r) => out('kicked', { reason: String(r).slice(0, 200) }));
bot.on('error', (e) => out('error', { message: String(e.message || e).slice(0, 200) }));
setTimeout(() => { out('timeout', {}); process.exit(1); }, 120000);
