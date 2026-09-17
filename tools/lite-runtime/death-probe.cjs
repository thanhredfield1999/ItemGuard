// Death hand-over: A dies carrying a tracked item, B picks it up off the ground.
//
// Two real clients, a real death, a real pickup. The custody count must rise by exactly one and the
// holder list must name both players -- this is how loot is actually transferred on a server.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');

const port = Number(process.argv[2]);
const out = (event, data) => console.log(JSON.stringify({ event, ...data }));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function connect(username) {
  return mineflayer.createBot({
    host: '127.0.0.1', port, username, version: '1.21.11', auth: 'offline'
  });
}

const victim = connect('IGVictim');
const looter = connect('IGLooter');

const plugin = [];
for (const [name, bot] of [['victim', victim], ['looter', looter]]) {
  bot.on('message', (m) => {
    const text = m.toString();
    if (text.includes('ItemGuard') || /#[A-Z0-9]{6}/.test(text)) plugin.push(`${name}: ${text}`);
  });
  bot.on('kicked', (r) => out('kicked', { who: name, reason: String(r).slice(0, 160) }));
  bot.on('error', (e) => out('error', { who: name, message: String(e.message || e).slice(0, 160) }));
}

const spawned = new Set();
for (const [name, bot] of [['victim', victim], ['looter', looter]]) {
  bot.once('spawn', () => { spawned.add(name); out('spawned', { who: name }); });
}

async function waitForBoth() {
  for (let i = 0; i < 60; i++) {
    if (spawned.size === 2) return true;
    await sleep(500);
  }
  return false;
}

async function main() {
  if (!await waitForBoth()) { out('never-spawned', {}); process.exit(1); }
  await sleep(2000);

  // ops.json is only read at startup, so bots joining later are not operators yet. The console
  // channel grants it now that they are online.
  const inbox = require('path').join(process.argv[6] || '', 'console.in');
  try {
    require('fs').writeFileSync(inbox, ['op IGVictim', 'op IGLooter', ''].join('\n'));
    out('op-requested', { inbox });
    await sleep(3000);
  } catch (error) {
    out('op-failed', { message: String(error).slice(0, 120) });
  }

  // Put the two bots in the same place so the dropped loot is within reach.
  const spot = victim.entity.position;
  const x = Math.floor(spot.x);
  const y = Math.floor(spot.y);
  const z = Math.floor(spot.z);
  victim.chat(`/tp IGVictim ${x + 0.5} ${y} ${z + 0.5}`);
  await sleep(800);
  victim.chat(`/tp IGLooter ${x + 0.5} ${y} ${z + 1.5}`);
  await sleep(1200);

  // Give the victim a tracked sword and make sure it is handled, so it gets an identity.
  victim.chat('/give IGVictim diamond_sword 1');
  await sleep(1500);
  const sword = victim.inventory.items().find((i) => i.name.includes('sword'));
  if (sword) await victim.equip(sword, 'hand');
  await sleep(2000);

  const before = plugin.length;
  victim.chat('/ig history');
  await sleep(2500);
  const code = (plugin.slice(before).join(' ').match(/#([A-Z0-9]{6})/) || [])[1] || null;
  out('code', { code });
  if (!code) { out('no-code', {}); process.exit(1); }

  // Kill the victim. Keep-inventory must be off so the sword actually drops.
  victim.chat('/gamerule keepInventory false');
  await sleep(600);
  victim.chat('/kill IGVictim');
  await sleep(3000);
  out('victim-died', {});

  // The looter must genuinely walk onto the drop. Teleporting on top of it does not trigger
  // collection: the server only picks items up while the player is moving through them.
  const looterPickup = new Promise((resolve) => {
    let done = false;
    looter.on('playerCollect', (collector) => {
      if (!done && collector.username === 'IGLooter') { done = true; resolve(true); }
    });
    setTimeout(() => { if (!done) resolve(false); }, 25000);
  });
  const drop = Object.values(looter.entities)
    .find((e) => e.name === 'item' || String(e.displayName || '').includes('Item'));
  out('drop-entity', { found: Boolean(drop), at: drop ? drop.position : null });
  // Stand a couple of blocks away, then walk through the item.
  looter.chat(`/tp IGLooter ${x + 0.5} ${y} ${z + 3.5}`);
  await sleep(1500);
  looter.setControlState('forward', true);
  for (let step = 0; step < 20; step++) {
    await sleep(400);
    const still = Object.values(looter.entities)
      .filter((e) => e.name === 'item').length;
    if (still === 0) break;
  }
  looter.setControlState('forward', false);
  const collected = await looterPickup;
  out('looter-collected', { collected });
  await sleep(3000);

  // Read the custody figures the looter sees on the item's timeline.
  const mark = plugin.length;
  looter.chat(`/ig history #${code}`);
  await sleep(3000);
  const timeline = plugin.slice(mark).join(' | ');
  out('timeline', { text: timeline.slice(0, 400) });

  out('done', { code });
  victim.quit();
  looter.quit();
  process.exit(0);
}

main().catch((error) => { out('crash', { message: String(error).slice(0, 200) }); process.exit(1); });
setTimeout(() => { out('timeout', {}); process.exit(1); }, 110000);
