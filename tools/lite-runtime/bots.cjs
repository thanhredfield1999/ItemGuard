const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');
const readline = require('node:readline');
const bots = new Map();
const emit = (event, data = {}) => console.log(JSON.stringify({event, ...data}));
let quitting = false, ended = 0;
// Player inventory window (id 0) slot numbers: 0 is the craft result, 1-4 the 2x2 craft inputs,
// 9-35 the main inventory and 36-44 the hotbar.
const CRAFT_INPUT_SLOT = 1, FIRST_HOTBAR_SLOT = 36;
// How long a client action may wait for the server's own answer. Well inside every driver
// budget, so a state that never arrives surfaces as a named BOT_ERROR rather than as a probe
// deadline three commands later.
const CLIENT_WAIT_MS = 5000;
// Item entities spawn at the dropper's feet. Nothing else drops while a toss is in flight: the
// driver waits for that toss's receipt before it sends any other action.
const DROP_RADIUS = 6;
const isDrop = (bot, entity) => (entity.name === 'item' || entity.objectType === 'Item') &&
  entity.position && bot.entity && entity.position.distanceTo(bot.entity.position) <= DROP_RADIUS;
// Wait for a condition the SERVER has to produce, re-checking on each event rather than sleeping
// for a guessed interval. `describe` is read only on failure, so the error names what was actually
// observed instead of a generic timeout.
const until = (source, event, ready, ms, describe) => new Promise((resolve, reject) => {
  let timer;
  const settle = () => { clearTimeout(timer); source.removeListener(event, check); };
  const check = () => { const value = ready(); if (value) { settle(); resolve(value); } };
  timer = setTimeout(() => { settle(); reject(new Error(describe())); }, ms);
  source.on(event, check);
  check();
});
for (const username of ['LiteStaff', 'LiteMember']) {
  // The protocol version must match the server being tested. It was hardcoded to 1.21.11,
  // which silently worked across the Paper matrix but made every non-1.21.11 server reject
  // the bot outright ("This server is version X, you are using version 1.21.11"). The
  // fixture then failed with a missing outcome.json, which reads like a plugin fault and is
  // not one. Callers testing another version set ITEMGUARD_BOT_MC_VERSION.
  const mcVersion = process.env.ITEMGUARD_BOT_MC_VERSION || '1.21.11';
  const bot = mineflayer.createBot({host:'127.0.0.1', port:Number(process.argv[2]), username, auth:'offline', version:mcVersion});
  bots.set(username, bot);
  bot.once('spawn', () => emit('spawn', {player:username}));
  bot.on('messagestr', message => emit('message', {player:username, message}));
  bot.on('windowOpen', window => emit('window', {player:username, id:window.id, title:window.title}));
  bot.on('error', error => emit('BOT_ERROR', {player:username, error:String(error)}));
  bot.on('kicked', reason => { if (!quitting) emit('BOT_ERROR', {player:username, reason}); });
  bot.on('end', reason => { emit('end', {player:username, reason}); if (++ended === 2) process.exit(quitting ? 0 : 1); });
}
readline.createInterface({input:process.stdin}).on('line', async line => {
  try {
    const request = JSON.parse(line);
    if (request.quit) { quitting = true; for (const bot of bots.values()) bot.quit(); return; }
    const bot = bots.get(request.player);
    if (!bot) throw new Error('Unknown actor');
    if (request.chat) { bot.chat(request.chat); emit('sent', request); }
    else if (request.guiOpen) {
      const opened = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('ItemGuard GUI did not open')), 20000);
        bot.once('windowOpen', window => { clearTimeout(timer); resolve(window); });
      });
      bot.chat('/ig gui');
      const window = await opened;
      const title = JSON.stringify(window.title || '');
      if (!title.includes('ItemGuard -')) throw new Error('Expected ItemGuard preview, saw ' + title);
      emit('gui-open', {player:request.player, id:window.id});
    } else if (request.toss) {
      // Throw a tracked item on the ground. Real PlayerDropItemEvent, not a simulated one.
      //
      // Two client/server races made the old receipt lie, and the third self-drop cycle of
      // fixture 7133b80dd6a5 is what they cost. (1) The `liteprobe pull` that precedes this toss
      // resolves server-side; its pickup packets may not have reached this client yet, so
      // `bot.heldItem` and the slot array are a tick or two old. (2) `clickWindow` applies a click
      // to the local window optimistically, so the slot looks empty straight after `tossStack`
      // whether or not the server moved anything. Dropping a slot the server already emptied
      // moves nothing, spawns no Item entity, and still returned — after which `liteprobe pull`
      // polls a world with nothing in it until its own pull-deadline.
      //
      // So the stack is resolved only once this client's view actually holds it (after a pull that
      // can only come from the server's own slot packet), and the receipt waits for the Item
      // entity the drop produced, which exists only if a drop really happened.
      const wanted = request.name;
      const holding = () => bot.inventory.items().find(i => !wanted || i.name === wanted);
      const candidate = await until(bot.inventory, 'updateSlot', holding, CLIENT_WAIT_MS,
        () => 'No item to toss for ' + request.player
          + '; inventory=' + JSON.stringify(bot.inventory.items().map(i => i.name)));
      // After a pickup the item can land in any slot, so select it rather than assuming the hand.
      await bot.equip(candidate, 'hand');
      const held = bot.heldItem || candidate;
      // Latched BEFORE the click: a stack thrown at the actor's feet can legitimately be collected
      // again within a tick or two, and a rapid pickup like that would otherwise read as a toss
      // that never left. The spawn is the departure; where the stack sits afterwards is not.
      let drop = null;
      const watch = entity => { if (!drop && isDrop(bot, entity)) drop = entity; };
      bot.on('entitySpawn', watch);
      try {
        await bot.tossStack(held);
        await until(bot, 'entitySpawn', () => drop, CLIENT_WAIT_MS,
          () => 'Toss moved nothing for ' + request.player + ': no drop entity spawned, inventory='
            + JSON.stringify(bot.inventory.items().map(i => i.name)));
      } finally { bot.removeListener('entitySpawn', watch); }
      emit('tossed', {player:request.player, name:held.name, count:held.count, entity:drop.id,
        remaining:bot.inventory.items().filter(i => i.name === held.name).length});
    }
    else if (request.cursorPick) {
      // Park a tracked stack on the cursor with a real window click, exactly as a human dragging
      // an item does. The server moves it to the carried slot itself; nothing is simulated, and
      // the plugin side only ever observes the result.
      //
      // The stack is resolved only once this client's view actually holds it: a close that just
      // returned the grid contents, or a pickup, lands here as a slot packet a tick or two later,
      // and clicking a slot this client has not seen filled yet would move nothing.
      const wanted = request.name || 'diamond_sword';
      const holding = () => bot.inventory.items().find(i => i.name === wanted);
      const item = await until(bot.inventory, 'updateSlot', holding, CLIENT_WAIT_MS,
        () => 'No ' + wanted + ' to pick up for ' + request.player
          + '; inventory=' + JSON.stringify(bot.inventory.items().map(i => i.name)));
      await bot.clickWindow(item.slot, 0, 0);
      emit('cursor-picked', {player:request.player, slot:item.slot, name:item.name});
    }
    else if (request.craftWindowClose) {
      // The CLIENT closes its own screen. This is the only path Paper reports as
      // InventoryCloseEvent.Reason.PLAYER, which is what the crafting-close cases read as their
      // acknowledgement: a probe-side closeInventory() arrives as PLUGIN and cannot forge it.
      //
      // Window 0 is the player's own inventory screen, which is where the 2x2 crafting grid lives,
      // so the close is sent for the current container when one is open and for that screen
      // otherwise. Vanilla empties the grid as it handles this packet.
      const window = bot.currentWindow || bot.inventory;
      bot.closeWindow(window);
      emit('craft-window-closed', {player:request.player, id:window.id});
    }
    else if (request.craftGridPlace) {
      // Window 0 slot 1 is the first of the player's own 2x2 crafting inputs: still the player's
      // screen, but outside Inventory.getContents().
      await bot.clickWindow(CRAFT_INPUT_SLOT, 0, 0);
      emit('craft-grid-placed', {player:request.player, slot:CRAFT_INPUT_SLOT});
    }
    else if (request.craftGridReturn) {
      // Take it back out and put it in the first hotbar slot, so the clear control deletes it out
      // of a real inventory slot rather than out of the grid the previous case just proved is
      // suppressed. Two clicks: pick up from the grid, place into the slot.
      await bot.clickWindow(CRAFT_INPUT_SLOT, 0, 0);
      await bot.clickWindow(FIRST_HOTBAR_SLOT, 0, 0);
      emit('craft-grid-returned', {player:request.player, slot:FIRST_HOTBAR_SLOT});
    }
    else if (request.gather) {
      // Walk onto nearby ground items so the server raises a real pickup for this player.
      const before = bot.inventory.items().length;
      const deadline = Date.now() + (request.timeoutMs || 8000);
      let picked = false;
      while (Date.now() < deadline && !picked) {
        const drop = bot.nearestEntity(e => e.name === 'item' || e.objectType === 'Item');
        if (drop) {
          const target = drop.position;
          bot.entity.position.set(target.x, target.y, target.z);
          bot.entity.onGround = true;
        }
        await new Promise(resolve => setTimeout(resolve, 250));
        picked = bot.inventory.items().length > before;
      }
      if (!picked) throw new Error('No pickup observed for ' + request.player);
      emit('gathered', {player:request.player, items:bot.inventory.items().map(i => i.name)});
    }
    else if (request.click) {
      // Inspect before click. The LITE overview intentionally renders the real tracked material
      // (one recognizable identity per slot), so assert the preview window and a non-empty slot,
      // never a hardcoded material.
      const window = bot.currentWindow;
      const title = window && JSON.stringify(window.title || '');
      if (!window || !title || !title.includes('ItemGuard -')) throw new Error('Expected an ItemGuard preview window, saw ' + title);
      // Slot 0 is the decorative border; the first tracked identity lives in the framed content area.
      const contentSlot = 10;
      const slot = window.slots[contentSlot];
      if (!slot) throw new Error('Expected a tracked identity in content slot ' + contentSlot);
      emit('inspected', {player:request.player, id:window.id, slot:contentSlot, name:slot.name, count:slot.count});
      await bot.clickWindow(contentSlot, 0, 0);
      emit('clicked', {player:request.player, id:window.id, slot:contentSlot, name:slot.name});
    } else if (request.craftOpen) {
      const table = bot.findBlock({matching: block => block.name === 'crafting_table', maxDistance: 8});
      if (!table) throw new Error('No crafting table near ' + request.player);
      emit('craft-found', {player:request.player, table:table.position.toString(), bot:bot.entity.position.toString()});
      await bot.lookAt(table.position.offset(0.5, 0.5, 0.5), true);
      const window = await bot.openBlock(table);
      emit('craft-open', {player:request.player, id:window.id});
    } else if (request.craftClick) {
      const window = bot.currentWindow;
      if (!window || !String(window.type).includes('crafting')) throw new Error('Expected crafting window');
      await bot.clickWindow(0, 0, request.craftClick === 'shift' ? 1 : 0);
      emit('craft-clicked', {player:request.player, mode:request.craftClick, id:window.id});
    } else if (request.craftReady) {
      const window = bot.currentWindow;
      if (!window || !String(window.type).includes('crafting')) throw new Error('Expected crafting window');
      emit('craft-ready', {player:request.player, id:window.id});
    } else throw new Error('Unknown action');
  } catch (error) { emit('BOT_ERROR', {error:String(error)}); }
});
