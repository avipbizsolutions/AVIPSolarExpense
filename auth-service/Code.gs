/**
 * AVIP Auth: one login for all AVIP Solar internal apps
 * (AVIP Solar Sales, AVIP Solar Installation, AVIP Solar Expense).
 *
 * Bound to the Google Sheet "AVIP Team Directory (Confidential)".
 * That sheet is never shared. Phones never call this script directly:
 * each app's own backend script forwards login / PIN / session checks here,
 * signed with SERVER_KEY.
 *
 * Setup (once, from the AVIP Google account that owns the Team Directory):
 *   1. Open the Team Directory → Extensions → Apps Script → paste this file → Save.
 *   2. Run "setup" once and allow access. Copy the SERVER KEY it prints in the log.
 *   3. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *   4. Put the /exec URL and the SERVER KEY into each app backend (AUTH_URL, AUTH_KEY).
 * After any change to this file: Deploy → Manage deployments → Edit → Version: New version.
 *
 * Users tab (one row per person):
 *   Employee ID | Name | Mobile | Designation | Team | Status | Sales App Role |
 *   Installation App Role | Expense App Role | PIN | PIN Salt | Must Set PIN |
 *   Failed Attempts | Locked Until | Device ID | Last Login | Added On
 *
 *   PIN: the office types a temporary 4-digit PIN. At first login the person
 *   chooses their own PIN; only a salted, scrambled code (starting with #) is kept.
 *   To reset a forgotten PIN: type a new temporary 4-digit PIN in the PIN cell.
 *   Status "Disabled" blocks the person from every app within 5 minutes.
 *   A blank app role means the person cannot open that app.
 */

const TZ = 'Asia/Kolkata';
const SESSION_DAYS = 30;          // a login stays valid this long on the phone
const MAX_FAILS = 5;              // wrong PINs before the number is locked
const LOCK_MINUTES = 15;
const VERIFY_CACHE_SECONDS = 300; // a disabled person is blocked within this time

const APP_ROLE_COLUMN = {
  sales: 'Sales App Role',
  installation: 'Installation App Role',
  expense: 'Expense App Role',
};
const APP_NAME = { sales: 'AVIP Solar Sales', installation: 'AVIP Solar Installation', expense: 'AVIP Solar Expense' };

const WEAK_PINS = ['0000', '1111', '2222', '3333', '4444', '5555', '6666', '7777', '8888', '9999',
  '1234', '2345', '3456', '4567', '5678', '6789', '4321', '9876', '1212', '1122', '0123', '1004', '2580'];

// ------------------------------------------------------------------ web app

function doGet() {
  return out({ ok: true, app: 'AVIP Auth' });
}

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (!d.serverKey || d.serverKey !== serverKey()) return out({ ok: false, error: 'Not allowed' });
    const handler = HANDLERS[d.kind];
    if (!handler) return out({ ok: false, error: 'unknown kind' });
    if (!APP_ROLE_COLUMN[d.app]) return out({ ok: false, error: 'Unknown app' });
    const result = handler(d) || {};
    result.ok = true;
    return out(result);
  } catch (err) {
    return out({ ok: false, error: String((err && err.message) || err) });
  }
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

const HANDLERS = {
  login: login,
  setPin: setPin,
  verify: verify,
  users: users,
};

/** Run once from the editor. */
function setup() {
  secret();
  Logger.log('SERVER KEY (put it in each app backend as AUTH_KEY): ' + serverKey());
  Logger.log('Now deploy as a web app (Execute as: Me, Who has access: Anyone).');
}

/** Run from the editor if the server key ever leaks. Then update AUTH_KEY in every app backend. */
function newServerKey() {
  PropertiesService.getScriptProperties().deleteProperty('SERVER_KEY');
  Logger.log('New SERVER KEY: ' + serverKey());
}

/** Run from the editor to log every phone out at once (e.g. after a lost phone). */
function logoutEveryone() {
  PropertiesService.getScriptProperties().deleteProperty('SECRET');
  secret();
  Logger.log('All sessions ended. Everyone must log in again.');
}

// ------------------------------------------------------------------ secrets

function prop(name, make) {
  const p = PropertiesService.getScriptProperties();
  let v = p.getProperty(name);
  if (!v) {
    v = make();
    p.setProperty(name, v);
  }
  return v;
}

function secret() { return prop('SECRET', function () { return Utilities.getUuid() + Utilities.getUuid(); }); }
function serverKey() { return prop('SERVER_KEY', function () { return 'avk_' + Utilities.getUuid().replace(/-/g, ''); }); }

function b64(bytes) { return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/, ''); }

function hashPin(pin, salt) {
  let h = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, secret() + '|' + salt + '|' + pin);
  // stretch it, so a copied sheet cannot be guessed quickly
  for (let i = 0; i < 500; i++) h = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, h.concat(Utilities.newBlob(salt).getBytes()));
  return '#' + b64(h).slice(0, 40);
}

function tokenFor(u, app) {
  const exp = Math.floor(Date.now() / 1000) + SESSION_DAYS * 86400;
  return u.id + '.' + app + '.' + exp.toString(36) + '.' + sign(u, app, exp);
}

/** Signature changes when the PIN changes, so a new PIN ends old sessions. */
function sign(u, app, exp) {
  return b64(Utilities.computeHmacSha256Signature(u.id + '|' + app + '|' + exp + '|' + u.pin, secret())).slice(0, 32);
}

function sameText(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// ------------------------------------------------------------------ sheet

function sheet() {
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Users');
  if (!sh) throw new Error('Users tab missing in the Team Directory.');
  return sh;
}

function clean(v) { return String(v === undefined || v === null ? '' : v).trim(); }
function last10(v) { return String(v || '').replace(/\D/g, '').slice(-10); }

/** All people as objects; col(name) gives the 1-based column of a header. */
function readUsers() {
  const sh = sheet();
  const values = sh.getDataRange().getValues();
  const headers = values[0].map(clean);
  const col = function (name, alt) {
    let i = headers.indexOf(name);
    if (i < 0 && alt) i = headers.indexOf(alt);
    if (i < 0) throw new Error('Column missing in Users tab: ' + name);
    return i;
  };
  const c = {
    id: col('Employee ID'), name: col('Name'), mobile: col('Mobile'), designation: col('Designation'),
    team: col('Team'), status: col('Status'), pin: col('PIN', 'PIN Hash'), salt: col('PIN Salt'),
    must: col('Must Set PIN'), fails: col('Failed Attempts'), locked: col('Locked Until'),
    device: col('Device ID'), last: col('Last Login'),
    sales: col('Sales App Role'), installation: col('Installation App Role'), expense: col('Expense App Role'),
  };
  const list = [];
  for (let r = 1; r < values.length; r++) {
    const v = values[r];
    if (clean(v[c.id]) === '' && clean(v[c.mobile]) === '') continue;
    list.push({
      row: r + 1,
      id: clean(v[c.id]), name: clean(v[c.name]), mobile: last10(v[c.mobile]),
      designation: clean(v[c.designation]), team: clean(v[c.team]),
      active: clean(v[c.status]).toLowerCase() !== 'disabled',
      pin: clean(v[c.pin]), salt: clean(v[c.salt]),
      must: v[c.must] === true || clean(v[c.must]).toUpperCase() === 'TRUE',
      fails: Number(v[c.fails]) || 0,
      locked: v[c.locked] instanceof Date ? v[c.locked] : null,
      roles: { sales: clean(v[c.sales]), installation: clean(v[c.installation]), expense: clean(v[c.expense]) },
    });
  }
  return { list: list, col: c, sh: sh };
}

function setCell(sh, row, colIndex, value) {
  sh.getRange(row, colIndex + 1).setValue(value);
}

function info(u, app) {
  return {
    id: u.id, name: u.name, mobile: u.mobile, designation: u.designation, team: u.team,
    role: u.roles[app] || '', roles: u.roles,
  };
}

function logLogin(mobile, app, result, d) {
  try {
    const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Login Log');
    if (!sh) return;
    sh.appendRow([new Date(), "'" + mobile, APP_NAME[app] || app, result, clean(d.deviceId).slice(0, 60), clean(d.appVersion).slice(0, 20)]);
  } catch (_) {}
}

// ------------------------------------------------------------------ handlers

function login(d) {
  const app = d.app;
  const mobile = last10(d.mobile);
  const pin = clean(d.pin);
  if (mobile.length !== 10) throw new Error('Enter your 10-digit mobile number.');
  if (!/^\d{4}$/.test(pin)) throw new Error('PIN must be 4 digits.');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const t = readUsers();
    const u = t.list.filter(function (x) { return x.mobile === mobile; })[0];
    if (!u) { logLogin(mobile, app, 'Not registered', d); throw new Error('This mobile number is not registered. Please ask the office.'); }
    if (!u.active) { logLogin(mobile, app, 'Disabled', d); throw new Error('Your login is disabled. Please contact the office.'); }
    if (!u.roles[app]) { logLogin(mobile, app, 'No access to app', d); throw new Error('You do not have access to ' + APP_NAME[app] + '. Please ask the office.'); }
    if (u.locked && u.locked.getTime() > Date.now()) {
      const mins = Math.ceil((u.locked.getTime() - Date.now()) / 60000);
      logLogin(mobile, app, 'Locked', d);
      throw new Error('Too many wrong PINs. Try again after ' + mins + ' minute' + (mins > 1 ? 's' : '') + '.');
    }
    if (!u.pin) { logLogin(mobile, app, 'No PIN set', d); throw new Error('Your PIN is not set yet. Please ask the office for a PIN.'); }

    const isHash = u.pin.charAt(0) === '#';
    const ok = isHash ? sameText(hashPin(pin, u.salt), u.pin) : sameText(pin, u.pin);
    if (!ok) {
      const fails = u.fails + 1;
      if (fails >= MAX_FAILS) {
        setCell(t.sh, u.row, t.col.fails, 0);
        setCell(t.sh, u.row, t.col.locked, new Date(Date.now() + LOCK_MINUTES * 60000));
        logLogin(mobile, app, 'Wrong PIN, locked', d);
        throw new Error('Too many wrong PINs. Locked for ' + LOCK_MINUTES + ' minutes.');
      }
      setCell(t.sh, u.row, t.col.fails, fails);
      logLogin(mobile, app, 'Wrong PIN', d);
      throw new Error('Wrong PIN. ' + (MAX_FAILS - fails) + ' tries left.');
    }
    setCell(t.sh, u.row, t.col.fails, 0);
    setCell(t.sh, u.row, t.col.locked, '');
    setCell(t.sh, u.row, t.col.last, new Date());
    if (clean(d.deviceId)) setCell(t.sh, u.row, t.col.device, clean(d.deviceId).slice(0, 60));
    logLogin(mobile, app, 'OK', d);
    return { user: info(u, app), token: tokenFor(u, app), mustSetPin: !isHash || u.must };
  } finally {
    lock.releaseLock();
  }
}

function setPin(d) {
  const app = d.app;
  const pin = clean(d.newPin);
  if (!/^\d{4}$/.test(pin)) throw new Error('PIN must be 4 digits.');
  if (WEAK_PINS.indexOf(pin) >= 0) throw new Error('This PIN is too easy. Choose another.');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const t = readUsers();
    const u = checkToken(t.list, d.token, app);
    if (u.pin.charAt(0) !== '#' && u.pin === pin) throw new Error('Choose a PIN different from the office PIN.');
    const salt = Utilities.getUuid().replace(/-/g, '').slice(0, 16);
    const hash = hashPin(pin, salt);
    setCell(t.sh, u.row, t.col.pin, hash);
    setCell(t.sh, u.row, t.col.salt, salt);
    setCell(t.sh, u.row, t.col.must, false);
    u.pin = hash;
    u.salt = salt;
    return { user: info(u, app), token: tokenFor(u, app) };
  } finally {
    lock.releaseLock();
  }
}

/** Called by app backends on every request. Cached for a few minutes. */
function verify(d) {
  const app = d.app;
  const tok = clean(d.token);
  const cache = CacheService.getScriptCache();
  const key = 'v_' + b64(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, app + '|' + tok)).slice(0, 40);
  const hit = cache.get(key);
  if (hit) return JSON.parse(hit);
  const u = checkToken(readUsers().list, tok, app);
  const res = { user: info(u, app), mustSetPin: u.pin.charAt(0) !== '#' || u.must };
  cache.put(key, JSON.stringify(res), VERIFY_CACHE_SECONDS);
  return res;
}

function checkToken(list, token, app) {
  const parts = clean(token).split('.');
  if (parts.length !== 4 || parts[1] !== app) throw new Error('SESSION: Please login again.');
  const exp = parseInt(parts[2], 36);
  if (!exp || exp * 1000 < Date.now()) throw new Error('SESSION: Your login has expired. Please login again.');
  const u = list.filter(function (x) { return x.id === parts[0]; })[0];
  if (!u || !u.active || !u.roles[app]) throw new Error('SESSION: Your access has changed. Please login again.');
  if (!sameText(sign(u, app, exp), parts[3])) throw new Error('SESSION: Please login again.');
  return u;
}

/** Active people who can use the app, for lists inside it (no PINs, no mobiles of others unless needed). */
function users(d) {
  return {
    users: readUsers().list
      .filter(function (u) { return u.active && u.roles[d.app]; })
      .map(function (u) { return { id: u.id, name: u.name, mobile: u.mobile, team: u.team, designation: u.designation, role: u.roles[d.app] }; }),
  };
}
