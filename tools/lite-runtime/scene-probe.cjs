// Builds a scene with one tracked sword carrying every kind of timeline row, so the Vietnamese GUI
// can be judged in one screen instead of being assembled by hand.
//
// Leaves the chest, placed shulker and ender chest standing next to spawn and the sword in Thanh's reach.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const fixture = process.argv[3] || '';
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForInventoryItem(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const slot = bot.inventory.slots.findIndex((item) => item && predicate(item));
    if (slot >= 0) return { slot, item: bot.inventory.slots[slot] };
    await sleep(250);
  }
  return null;
}

const bot = mineflayer.createBot({
  host: '127.0.0.1', port, username: 'IGScene', version: '1.21.11', auth: 'offline'
});

const plugin = [];
bot.on('message', (m) => {
  const text = m.toString();
  if (text.includes('ItemGuard') || /#[A-Z0-9]{6}/.test(text)) plugin.push(text);
});

bot.once('spawn', async () => {
  await sleep(2000);
  try {
    require('fs').writeFileSync(
      require('path').join(fixture, 'console.in'),
      ['op IGScene', ''].join('\n'));
    await sleep(3000);
  } catch (error) { out('op-failed', { message: String(error).slice(0, 120) }); }

  const p = bot.entity.position;
  const x = Math.floor(p.x);
  const y = Math.floor(p.y);
  const z = Math.floor(p.z);
  const chest = { x: x + 3, y, z };
  const shulker = { x: x + 3, y, z: z + 1 };
  const ender = { x: x + 3, y, z: z + 2 };

  // Flat lit ground so the jump has somewhere to land and the scene is readable.
  bot.chat(`/fill ${x - 4} ${y - 1} ${z - 4} ${x + 5} ${y - 1} ${z + 5} smooth_stone`);
  await sleep(700);
  bot.chat(`/fill ${x - 4} ${y} ${z - 4} ${x + 5} ${y + 3} ${z + 5} air`);
  await sleep(700);
  bot.chat(`/setblock ${chest.x} ${chest.y} ${chest.z} chest[facing=west]`);
  await sleep(500);
  bot.chat(`/setblock ${shulker.x} ${shulker.y} ${shulker.z} purple_shulker_box[facing=up]`);
  await sleep(500);
  bot.chat(`/setblock ${ender.x} ${ender.y} ${ender.z} ender_chest`);
  await sleep(800);

  // Keep the fixture command to the target server's baseline syntax. A later presentation pass can
  // add display components after the identity/tagging journey is proven; this first needs a real
  // non-stackable sword in the player's inventory.
  bot.chat('/give IGScene minecraft:netherite_sword 1');
  const swordInInventory = await waitForInventoryItem((item) => item.name.includes('sword'), 8000);
  const swordSlot = swordInInventory?.slot ?? -1;
  const sword = swordInInventory?.item ?? null;
  // /give itself is not an inventory interaction. Trigger the real player-slot click path so the
  // LITE plugin can tag and publish this item before the GUI/history scene asks for its code.
  if (!sword || swordSlot < 0) {
    out('sword-missing', { slots: bot.inventory.slots.filter(Boolean).map((item) => item.name) });
    throw new Error('scene sword was not present in a player inventory slot');
  }
  await bot.clickWindow(swordSlot, 0, 0);
  await sleep(400);
  await bot.clickWindow(swordSlot, 0, 0);
  await sleep(3000);

  const mark = plugin.length;
  bot.chat('/ig history');
  await sleep(2500);
  const code = (plugin.slice(mark).join(' ').match(/#([A-Z0-9]{6})/) || [])[1] || null;
  out('code', { code });

  // Drop and retake: proves the self-cycle is recorded but not counted as a hand-over.
  bot.chat(`/tp IGScene ${x + 0.5} ${y} ${z + 0.5}`);
  await sleep(800);
  const held = bot.inventory.items().find((i) => i.name.includes('sword'));
  if (held) {
    await bot.toss(held.type, null, 1);
    await sleep(1500);
    bot.setControlState('forward', true);
    await sleep(1200);
    bot.setControlState('forward', false);
    await sleep(1500);
  }

  // Chest round trip, then ender chest round trip, using real clicks.
  const chestBlock = bot.blockAt(bot.entity.position.offset(0, 0, 0).set(chest.x, chest.y, chest.z));
  try {
    bot.chat(`/tp IGScene ${chest.x + 1.5} ${chest.y} ${chest.z + 0.5}`);
    await sleep(1500);
    const front = bot.blockAt(bot.entity.position.offset(-1, 0, 0));
    const window = await bot.openContainer(front);
    const size = window.inventoryStart;
    const slot = window.slots.findIndex((s, i) => i >= size && s && s.name.includes('sword'));
    if (slot >= 0) {
      await bot.clickWindow(slot, 0, 1);
      await sleep(1200);
      const inside = window.slots.findIndex((s, i) => i < size && s && s.name.includes('sword'));
      if (inside >= 0) { await bot.clickWindow(inside, 0, 1); await sleep(1200); }
    }
    window.close();
    out('chest-done', {});
  } catch (error) { out('chest-error', { message: String(error.message || error).slice(0, 140) }); }

  // A placed shulker is shared world storage, unlike a shulker opened from a player's inventory.
  // This must produce SHULKER_PUT/SHULKER_TAKE, a shulker icon and the block's coordinates.
  try {
    bot.chat(`/tp IGScene ${shulker.x + 1.5} ${shulker.y} ${shulker.z + 0.5}`);
    await sleep(1500);
    const front = bot.blockAt(bot.entity.position.offset(-1, 0, 0));
    const window = await bot.openContainer(front);
    const size = window.inventoryStart;
    const slot = window.slots.findIndex((s, i) => i >= size && s && s.name.includes('sword'));
    if (slot >= 0) {
      await bot.clickWindow(slot, 0, 1);
      await sleep(1200);
      const inside = window.slots.findIndex((s, i) => i < size && s && s.name.includes('sword'));
      if (inside >= 0) { await bot.clickWindow(inside, 0, 1); await sleep(1200); }
    }
    window.close();
    out('shulker-done', { shulker });
  } catch (error) { out('shulker-error', { message: String(error.message || error).slice(0, 140) }); }

  await sleep(1500);
  out('ready', {
    code,
    chest,
    shulker,
    ender,
    note: 'sword left in IGScene inventory; drop it for Thanh or give him the code'
  });

  // Leave the sword on the ground where Thanh spawns, so he can pick it up and own the timeline.
  const last = bot.inventory.items().find((i) => i.name.includes('sword'));
  if (last) { bot.chat(`/tp IGScene ${x + 0.5} ${y} ${z + 0.5}`); await sleep(900); await bot.toss(last.type, null, 1); }
  await sleep(1500);
  out('sword-dropped', { at: { x: x + 0.5, y, z: z + 0.5 } });
  clearTimeout(timeout);
  bot.quit();
  process.exit(0);
});

bot.on('kicked', (r) => out('kicked', { reason: String(r).slice(0, 160) }));
bot.on('error', (e) => out('error', { message: String(e.message || e).slice(0, 160) }));
const timeout = setTimeout(() => { out('timeout', {}); process.exit(1); }, 120000);
