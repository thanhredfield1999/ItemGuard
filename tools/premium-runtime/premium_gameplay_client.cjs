// Premium real-client gameplay journey. Two real protocol clients (PremiumStaff, PremiumMember)
// perform /give, real equip, real drop, real walk-pickup, real chest put/take, /ig commands and the
// full GUI journeys (legacy player browser + Premium catalog) with real window clicks. Every step
// waits for a packet or a rendered window; nothing is simulated server-side.
//
// Order matters and follows the product's own custody rule: the item belongs to staff until the
// member picks it up (every recorded action updates tracked_items.owner_uuid), so the staff-owned
// command and legacy-browser checks run BEFORE the hand-over, and the member-owned checks after it.
const mineflayer = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/mineflayer');
const Vec3 = require('E:/AI.WORK/botcheckerminecraft-botchecker/node_modules/vec3');

const port = Number(process.argv[2]);
const mode = process.argv[3] || 'full';
const expectedCode = process.argv[4] || null;
const MC_VERSION = process.env.ITEMGUARD_BOT_MC_VERSION || '1.21.11';
const emit = (event, data = {}) => process.stdout.write(JSON.stringify({ ...data, event }) + '\n');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const bots = new Map();
const states = new Map();

function normalize(value) {
  return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
}

// Paper sends the window title as a chat component and mineflayer keeps it as an object whose
// toString() is "[object Object]". JSON.stringify preserves the literal text, which is what the
// layout predicates compare against.
function titleText(window) {
  const title = window && window.title;
  if (title == null) return '';
  if (typeof title === 'string') return title;
  try {
    return JSON.stringify(title);
  } catch (_) {
    return String(title);
  }
}

function notPane(item) {
  return Boolean(item) && String(item.name || '').toLowerCase() !== 'gray_stained_glass_pane';
}

function itemName(item) {
  return item && String(item.name || '').toLowerCase();
}

function makeBot(name) {
  const state = { messages: [], windows: [], ended: false };
  const bot = mineflayer.createBot({
    host: '127.0.0.1',
    port,
    username: name,
    auth: 'offline',
    version: MC_VERSION
  });
  bots.set(name, bot);
  states.set(name, state);
  bot.once('spawn', () => emit('spawn', { player: name }));
  bot.on('messagestr', (message) => {
    const text = String(message);
    state.messages.push(text);
    emit('message', { player: name, text });
  });
  bot.on('windowOpen', (window) => {
    state.windows.push(window);
    emit('window-open', {
      player: name,
      id: window.id,
      title: titleText(window),
      slots: window.slots.length
    });
  });
  bot.on('error', (error) => emit('BOT_ERROR', { player: name, error: String(error) }));
  bot.on('kicked', (reason) => emit('BOT_ERROR', { player: name, reason: String(reason) }));
  bot.on('end', (reason) => {
    state.ended = true;
    emit('end', { player: name, reason: String(reason || '') });
  });
  return bot;
}

async function waitUntil(predicate, timeoutMs, description) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await sleep(100);
  }
  throw new Error(description + (lastError ? ` (${lastError})` : ''));
}

function windowCursor(bot) {
  return states.get(bot.username).windows.length;
}

function messageCursor(bot) {
  return states.get(bot.username).messages.length;
}

async function waitWindow(bot, baseline, predicate, timeoutMs, description) {
  const state = states.get(bot.username);
  return waitUntil(() => {
    for (let i = baseline; i < state.windows.length; i++) {
      const window = state.windows[i];
      try {
        if (predicate(window, i)) return window;
      } catch (_) {}
    }
    return null;
  }, timeoutMs, description);
}

async function waitRenderedWindow(bot, baseline, titleFragment, timeoutMs, description) {
  return waitWindow(bot, baseline,
    (window) => normalize(titleText(window)).includes(titleFragment) && notPane(window.slots[9]),
    timeoutMs, description);
}

// The catalog admits one read per second across all viewers (CatalogReadGate). A read issued inside
// that window is refused and the UI renders its designed retry screen ("ItemGuard · Lỗi truy vấn",
// retry at slot 49). A real player clicks retry; so does this client, and the retry count is part of
// the receipt rather than a silent pass.
async function waitCatalogRead(bot, titleFragment, timeoutMs, description, attempts = 3) {
  let retries = 0;
  for (let attempt = 0; attempt < attempts; attempt++) {
    const baseline = windowCursor(bot);
    try {
      const window = await waitRenderedWindow(bot, baseline, titleFragment, timeoutMs, description);
      return { window, retries };
    } catch (error) {
      const refused = states.get(bot.username).windows.slice(baseline)
        .find((window) => normalize(titleText(window)).includes('loi truy van'));
      if (!refused) throw error;
      retries += 1;
      await sleep(1300);
      await bot.clickWindow(49, 0, 0);
    }
  }
  throw new Error(description + ' (retry budget exhausted)');
}

async function waitMessage(bot, baseline, predicate, timeoutMs, description) {
  const state = states.get(bot.username);
  return waitUntil(() => {
    for (let i = baseline; i < state.messages.length; i++) {
      if (predicate(state.messages[i])) return state.messages[i];
    }
    return null;
  }, timeoutMs, description);
}

async function waitItem(bot, predicate, timeoutMs, description) {
  return waitUntil(() => bot.inventory.items().find(predicate), timeoutMs, description);
}

function sword(bot) {
  return bot.inventory.items().find((item) => itemName(item) === 'diamond_sword');
}

// Select the hotbar slot through a real packet instead of trusting the client's own view.
// mineflayer's setQuickBarSlot is a no-op when its local index already matches, which after a
// restart can differ from the server's selected slot: the item is in the inventory client-side
// while /ig check answers "Please hold an item in your hand!". So the packet is always sent, and
// the caller proves the result against the server's own answer rather than bot.heldItem.
function forceHeldSlot(bot, slotIndex) {
  bot.quickBarSlot = slotIndex;
  bot._client.write('held_item_slot', { slotId: slotIndex });
  if (typeof bot.updateHeldItem === 'function') bot.updateHeldItem();
}

async function ensureSwordInHand(bot) {
  const item = await waitItem(bot, (candidate) => itemName(candidate) === 'diamond_sword', 20000,
    `${bot.username} has no tracked sword in the inventory`);
  if (item.slot >= 36) {
    forceHeldSlot(bot, item.slot - 36);
  } else {
    await bot.equip(item, 'hand');
    const moved = sword(bot);
    if (moved && moved.slot >= 36) forceHeldSlot(bot, moved.slot - 36);
  }
  await sleep(400);
  return item;
}

function position(bot) {
  return {
    x: Math.floor(bot.entity.position.x),
    y: Math.floor(bot.entity.position.y),
    z: Math.floor(bot.entity.position.z)
  };
}

async function closeWindow(bot) {
  if (!bot.currentWindow) return;
  const current = bot.currentWindow;
  bot.closeWindow(current);
  await waitUntil(() => bot.currentWindow == null, 6000, `${bot.username} window did not close`);
}

async function chat(bot, command) {
  emit('chat-command', { player: bot.username, command });
  bot.chat(command);
  await sleep(500);
}

async function teleport(operator, target, coords) {
  await chat(operator, `/tp ${target} ${coords.x} ${coords.y} ${coords.z}`);
  await sleep(700);
}

async function commandCheck(staff) {
  const state = states.get(staff.username);
  let code = null;
  for (let attempt = 0; attempt < 5 && !code; attempt++) {
    if (attempt > 0) await ensureSwordInHand(staff);
    const baseline = state.messages.length;
    await chat(staff, '/ig check');
    await waitMessage(staff, baseline,
      (text) => /Code:\s*#?[A-Z0-9]{6}/.test(text) || /Identity/i.test(text)
        || /persist identity/i.test(text) || /hold an item/i.test(text)
        || /not being tracked/i.test(text),
      9000, 'item check did not return any result');
    const recent = state.messages.slice(baseline).join(' ');
    const match = recent.match(/Code:\s*#?([A-Z0-9]{6})/);
    if (match) code = match[1];
    else await sleep(1800);
  }
  if (!code) throw new Error('item check never returned a tracked code');
  emit('identity', { player: staff.username, code });
  return code;
}

async function runFull() {
  const staff = makeBot('PremiumStaff');
  const member = makeBot('PremiumMember');
  await waitUntil(() => [...bots.values()].every((bot) => Boolean(bot.entity)), 60000,
    'real client players did not spawn');
  await sleep(1200);

  // -- real /give + real client equip + /ig check ------------------------------------------------
  await chat(staff, '/clear PremiumStaff');
  await chat(staff, '/give PremiumStaff minecraft:diamond_sword 1');
  const issued = await ensureSwordInHand(staff);
  emit('item-equipped', { player: staff.username, name: issued.name });
  const code = await commandCheck(staff);

  // -- staff-owned commands: /ig info, /ig stats, /ig search (custody is still staff's) -----------
  let baseline = messageCursor(staff);
  await chat(staff, '/ig info');
  await waitMessage(staff, baseline, (text) => /MYSQL/.test(text), 9000,
    '/ig info did not render the MySQL backend');
  baseline = messageCursor(staff);
  await chat(staff, '/ig stats');
  await waitMessage(staff, baseline, (text) => /Database:\s*MYSQL/i.test(text), 9000,
    '/ig stats did not render the MySQL backend');
  baseline = messageCursor(staff);
  await chat(staff, '/ig search PremiumStaff');
  const searchLine = await waitMessage(staff, baseline,
    (text) => /Search:\s*PremiumStaff/i.test(text) || /Tìm kiếm:\s*PremiumStaff/i.test(text),
    9000, '/ig search did not render the player result');
  emit('commands', { info: true, stats: true, search: true, search_line: searchLine });

  // -- staff-owned legacy browser: items -> history -> detail -> back -> exit -> close ------------
  await closeWindow(staff);
  baseline = windowCursor(staff);
  await chat(staff, '/ig browser PremiumStaff');
  const legacyBrowser = await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('do cua') && notPane(window.slots[10]),
    12000, 'legacy player browser did not open with a tracked item');
  await staff.clickWindow(10, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('itemguard - lich su item') && notPane(window.slots[10]),
    10000, 'legacy browser item click did not open history with a row');
  await staff.clickWindow(10, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('chi tiet'),
    8000, 'legacy history item click did not open detail');
  await staff.clickWindow(0, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('itemguard - lich su item'),
    8000, 'legacy detail back did not return to history');
  await staff.clickWindow(49, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('do cua'),
    10000, 'legacy history exit did not return to the player browser');
  await staff.clickWindow(49, 0, 0);
  await waitUntil(() => staff.currentWindow == null, 6000, 'legacy player browser close did not close');
  emit('legacy-gui', {
    browser_title: titleText(legacyBrowser),
    browser: true, history: true, detail: true, back: true, exit_to_browser: true, close: true
  });

  // -- real drop by staff, real walk-pickup by member (custody changes here) ---------------------
  const staffOrigin = position(staff);
  await teleport(staff, 'PremiumMember', { x: staffOrigin.x, y: staffOrigin.y, z: staffOrigin.z + 8 });
  let droppedEntity = null;
  const onStaffEntity = (entity) => {
    if (!droppedEntity && (entity.name === 'item' || entity.objectType === 'Item')) {
      droppedEntity = entity;
    }
  };
  staff.on('entitySpawn', onStaffEntity);
  try {
    await staff.tossStack(staff.heldItem || sword(staff));
    await waitUntil(() => droppedEntity, 6000, 'real client toss did not spawn an item entity');
  } finally {
    staff.removeListener('entitySpawn', onStaffEntity);
  }
  emit('real-drop', { player: staff.username, entity: droppedEntity.id });
  await teleport(staff, 'PremiumStaff', { x: staffOrigin.x, y: staffOrigin.y, z: staffOrigin.z + 20 });
  await teleport(staff, 'PremiumMember', { x: staffOrigin.x, y: staffOrigin.y, z: staffOrigin.z + 4 });
  const pickupDeadline = Date.now() + 15000;
  while (Date.now() < pickupDeadline && !sword(member)) {
    const drop = member.nearestEntity((entity) =>
      (entity.name === 'item' || entity.objectType === 'Item')
        && entity.position.distanceTo(member.entity.position) < 12
    );
    if (drop) {
      await member.lookAt(drop.position.offset(0, 0.25, 0), true);
      member.setControlState('forward', true);
    } else {
      member.setControlState('forward', false);
    }
    await sleep(200);
  }
  member.setControlState('forward', false);
  if (!sword(member)) throw new Error('member never picked the dropped item up by walking to it');
  emit('real-pickup', { player: member.username, name: sword(member).name });

  // -- real chest put/take through window clicks -------------------------------------------------
  const memberPos = position(member);
  const chest = { x: memberPos.x + 3, y: memberPos.y, z: memberPos.z };
  await chat(staff, `/setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest[facing=west]`);
  await sleep(600);
  await teleport(staff, 'PremiumMember', { x: chest.x - 2, y: chest.y, z: chest.z });
  await sleep(600);
  const chestBlock = member.blockAt(new Vec3(chest.x, chest.y, chest.z));
  if (!chestBlock || chestBlock.name !== 'chest') {
    throw new Error('fixture chest was not placed where the client could see it');
  }
  const chestWindow = await member.openContainer(chestBlock);
  const chestStart = chestWindow.inventoryStart;
  const playerSwordSlot = chestWindow.slots.findIndex((item, index) =>
    index >= chestStart && itemName(item) === 'diamond_sword');
  if (playerSwordSlot < 0) throw new Error('real chest window did not contain the picked-up sword');
  await member.clickWindow(playerSwordSlot, 0, 1);
  const chestSword = await waitUntil(() => {
    const index = chestWindow.slots.findIndex((item, i) => i < chestStart && itemName(item) === 'diamond_sword');
    return index >= 0 ? { index } : null;
  }, 8000, 'shift-click did not put the sword into the real chest');
  emit('real-chest-put', { player: member.username, slot: playerSwordSlot });
  await member.clickWindow(chestSword.index, 0, 1);
  await waitItem(member, (item) => itemName(item) === 'diamond_sword', 8000,
    'shift-click did not take the sword back from the real chest');
  emit('real-chest-take', { player: member.username, slot: chestSword.index });
  chestWindow.close();
  await sleep(900);

  // -- member-owned history GUI, now that custody moved -------------------------------------------
  baseline = windowCursor(member);
  await chat(member, '/ig history');
  const memberHistory = await waitWindow(member, baseline,
    (window) => normalize(titleText(window)).includes('itemguard - lich su item') && notPane(window.slots[10]),
    12000, 'player history GUI did not open with an event row');
  await member.clickWindow(10, 0, 0);
  baseline = windowCursor(member);
  await waitWindow(member, baseline,
    (window) => normalize(titleText(window)).includes('chi tiet'),
    8000, 'history detail GUI did not open');
  await member.clickWindow(0, 0, 0);
  baseline = windowCursor(member);
  await waitWindow(member, baseline,
    (window) => normalize(titleText(window)).includes('itemguard - lich su item'),
    8000, 'history back button did not return to the history list');
  const exitWindowBaseline = windowCursor(member);
  const exitMessageBaseline = messageCursor(member);
  await member.clickWindow(49, 0, 0);
  const exitOutcome = await waitUntil(() => {
    const opened = states.get(member.username).windows.slice(exitWindowBaseline)
      .find((window) => normalize(titleText(window)).includes('do cua'));
    if (opened) return { kind: 'browser' };
    const refused = states.get(member.username).messages.slice(exitMessageBaseline)
      .find((text) => normalize(text).includes('chua co vat pham nao')
        || normalize(text).includes('khong the tai'));
    return refused ? { kind: 'message' } : null;
  }, 10000, 'history exit produced neither the player browser nor a refusal message');
  if (exitOutcome.kind === 'browser') {
    await member.clickWindow(49, 0, 0);
    await waitUntil(() => member.currentWindow == null, 6000, 'member player browser close did not close');
  } else {
    await closeWindow(member);
  }
  emit('member-history-gui', {
    player: member.username,
    title: titleText(memberHistory),
    detail: true,
    back: true,
    exit_outcome: exitOutcome.kind
  });

  // -- Premium catalog (owner-independent): page -> profile -> history -> event -> back -> close --
  baseline = windowCursor(staff);
  await chat(staff, '/ig browser');
  const catalogPage = await waitRenderedWindow(staff, baseline, 'kho tra cuu', 15000,
    'Premium catalog did not render a page with a tracked row');
  emit('catalog-inspected', { title: titleText(catalogPage), slot: 9, item: catalogPage.slots[9].name });
  await staff.clickWindow(9, 0, 0);
  baseline = windowCursor(staff);
  const profile = await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('ho so'),
    10000, 'catalog item click did not open the profile screen');
  await staff.clickWindow(20, 0, 0);
  const catalogHistoryRead = await waitCatalogRead(staff, 'lich su', 12000,
    'catalog history action did not render history rows');
  const catalogHistory = catalogHistoryRead.window;
  await staff.clickWindow(9, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('su kien'),
    10000, 'catalog history row did not open the event screen');
  await staff.clickWindow(45, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('lich su'),
    8000, 'catalog event back did not return to history');
  await staff.clickWindow(45, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('ho so'),
    8000, 'catalog history back did not return to the profile');
  await staff.clickWindow(45, 0, 0);
  baseline = windowCursor(staff);
  await waitWindow(staff, baseline,
    (window) => normalize(titleText(window)).includes('kho tra cuu'),
    8000, 'catalog profile back did not return to the catalog');
  await staff.clickWindow(53, 0, 0);
  await waitUntil(() => staff.currentWindow == null, 6000, 'catalog close did not close');
  emit('catalog-gui', {
    profile_title: titleText(profile),
    history_title: titleText(catalogHistory),
    catalog_history_retries: catalogHistoryRead.retries,
    open: true, profile: true, history: true, event_screen: true, back_chain: true, close: true
  });

  emit('CLIENT_RESULT', {
    status: 'PASS',
    code,
    actions: ['give', 'equip', 'check', 'commands', 'legacy_gui', 'drop', 'walk_pickup',
      'chest_put', 'chest_take', 'member_history_gui', 'catalog_gui']
  });
  return code;
}

async function runRestart() {
  const member = makeBot('PremiumMember');
  await waitUntil(() => Boolean(member.entity), 60000, 'restart client did not spawn');
  await ensureSwordInHand(member);
  const state = states.get(member.username);
  let readback = null;
  for (let attempt = 0; attempt < 5 && !readback; attempt++) {
    if (attempt > 0) await ensureSwordInHand(member);
    const baseline = state.messages.length;
    await chat(member, '/ig check');
    await waitMessage(member, baseline,
      (text) => /Code:\s*#?[A-Z0-9]{6}/.test(text) || /Identity/i.test(text)
        || /persist identity/i.test(text) || /hold an item/i.test(text)
        || /not being tracked/i.test(text),
      10000, 'restart /ig check returned no result');
    const recent = state.messages.slice(baseline).join(' ');
    const match = recent.match(/Code:\s*#?([A-Z0-9]{6})/);
    if (match && (expectedCode == null || match[1] === expectedCode)) readback = match[1];
    else await sleep(1800);
  }
  if (!readback) {
    throw new Error(`restart /ig check never read back the persisted identity ${expectedCode}`);
  }
  const baseline = state.messages.length;
  await chat(member, '/ig stats');
  await waitMessage(member, baseline, (text) => /Database:\s*MYSQL/i.test(text), 9000,
    'restart /ig stats did not read the MySQL backend');
  emit('RESTART_RESULT', {
    status: 'PASS',
    code: readback,
    actions: ['inventory_readback', 'check_readback', 'stats_readback']
  });
  return readback;
}

async function stopBots() {
  for (const bot of bots.values()) {
    try { bot.quit(); } catch (_) {}
  }
  await waitUntil(() => [...states.values()].every((state) => state.ended), 12000,
    'client bots did not exit cleanly');
}

(async () => {
  try {
    if (mode === 'restart') await runRestart();
    else await runFull();
    await stopBots();
    process.exit(0);
  } catch (error) {
    emit('CLIENT_FAIL', { error: String((error && error.stack) || error) });
    try { await stopBots(); } catch (cleanupError) {
      emit('CLIENT_CLEANUP_FAIL', { error: String(cleanupError) });
    }
    process.exit(1);
  }
})();
