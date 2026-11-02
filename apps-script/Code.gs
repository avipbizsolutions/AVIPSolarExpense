/**
 * AVIP Solar Expense: backend for the Android app.
 *
 * Bound to the Google Sheet "AVIP Solar – Team Expense Tracker".
 * Login, PINs and roles come from the AVIP Team Directory through AVIP Auth
 * (see AuthClient.gs, which must be pasted next to this file).
 *
 * Setup (once, from the AVIP Google account that owns the tracker):
 *   1. Open the tracker → Extensions → Apps Script.
 *      Paste this file as Code.gs and ../auth-service/AuthClient.gs as a second file AuthClient.gs → Save.
 *   2. Project Settings → Script properties → add AUTH_URL and AUTH_KEY (from AVIP Auth).
 *   3. Run "setup" once and allow access.
 *   4. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone → Deploy.
 *   5. Put the /exec URL in config.json in the app's GitHub repo ("endpoint").
 * After any change: Deploy → Manage deployments → Edit → Version: New version.
 *
 * Roles (Expense App Role in the Team Directory):
 *   Member   – submit and see own claims, see own advances and balance
 *   Approver – also approve / reject claims of others
 *   Accounts – also record advances and mark approved claims as paid
 *   Admin    – everything
 */

const APP = 'expense';
const SHARED_KEY = 'avip-expense-4Rt8';
const TZ = 'Asia/Kolkata';
const BILLS_FOLDER_ID = '1o7ZOSqrC49JbHbHzylFfq98-gdfp21cd'; // "AVIP Solar – Expense Bills"
const LOG = 'Expense Log';
const ADV = 'Advances';
const PAY_VIA = ['Cash', 'UPI', 'Bank Transfer'];
const PAID_BY = ['Self', 'Advance', 'Company'];
const ST = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', REIMBURSED: 'Reimbursed' };
const MAX_PHOTO_BYTES = 6 * 1024 * 1024;

// ------------------------------------------------------------------ web app

function doGet() {
  return out({ ok: true, app: 'AVIP Solar Expense' });
}

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return out({ ok: false, error: 'Not allowed' });
    const h = HANDLERS[d.kind];
    if (!h) return out({ ok: false, error: 'unknown kind' });
    const result = h(d) || {};
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
  login: function (d) { return authLogin_(APP, d); },
  setPin: function (d) { return authSetPin_(APP, d); },
  bootstrap: bootstrap,
  home: home,
  myClaims: myClaims,
  submitClaim: submitClaim,
  deleteClaim: deleteClaim,
  billPhoto: billPhoto,
  teamClaims: teamClaims,
  decide: decide,
  markPaid: markPaid,
  advances: advances,
  addAdvance: addAdvance,
};

/** Run once from the editor. */
function setup() {
  DriveApp.getFolderById(BILLS_FOLDER_ID);
  table(LOG); table(ADV);
  const p = PropertiesService.getScriptProperties();
  if (!p.getProperty('AUTH_URL') || !p.getProperty('AUTH_KEY')) {
    Logger.log('Add AUTH_URL and AUTH_KEY in Project Settings → Script properties before deploying.');
  } else {
    Logger.log('Login service reachable: ' + (authUsers_(APP).length) + ' people can use AVIP Solar Expense.');
  }
  Logger.log('Ready. Now deploy as a web app (Execute as: Me, Who has access: Anyone).');
}

// ------------------------------------------------------------------ sheet helpers

function book() { return SpreadsheetApp.getActiveSpreadsheet(); }

function table(name) {
  const sh = book().getSheetByName(name);
  if (!sh) throw new Error('Tab missing: ' + name);
  const headers = sh.getRange(1, 1, 1, sh.getLastColumn()).getValues()[0].map(function (h) { return String(h).trim(); });
  return {
    sh: sh, headers: headers,
    col: function (h) {
      const i = headers.indexOf(h);
      if (i < 0) throw new Error('Column missing: ' + name + ' → ' + h);
      return i + 1;
    },
  };
}

function lastRow(sh) {
  const n = sh.getLastRow();
  if (n < 2) return 1;
  const v = sh.getRange(2, 1, n - 1, 1).getValues();
  for (let i = v.length - 1; i >= 0; i--) if (String(v[i][0]).trim() !== '') return i + 2;
  return 1;
}

function rows(name) {
  const t = table(name);
  const n = lastRow(t.sh);
  if (n < 2) return [];
  const vals = t.sh.getRange(2, 1, n - 1, t.headers.length).getValues();
  const list = [];
  vals.forEach(function (r, i) {
    if (String(r[0]).trim() === '') return;
    const o = { _row: i + 2 };
    // first column with a header wins (Advances has a second "Team Member" in its summary block)
    t.headers.forEach(function (h, j) { if (h && !(h in o)) o[h] = r[j]; });
    list.push(o);
  });
  return list;
}

/** Writes only the given columns (never the formula columns Month and Team). */
function setFields(name, row, obj) {
  const t = table(name);
  Object.keys(obj).forEach(function (k) {
    t.sh.getRange(row, t.col(k)).setValue(obj[k] === undefined || obj[k] === null ? '' : obj[k]);
  });
}

function append(name, obj) {
  const t = table(name);
  const row = lastRow(t.sh) + 1;
  if (row > t.sh.getMaxRows()) t.sh.insertRowsAfter(t.sh.getMaxRows(), 500);
  setFields(name, row, obj);
  return row;
}

function clean(v, max) {
  const s = String(v === undefined || v === null ? '' : v).trim();
  return max ? s.slice(0, max) : s;
}
function num(v) { const n = Number(v); return isFinite(n) ? n : 0; }
function money(v) { return Math.round(num(v) * 100) / 100; }
function now() { return new Date(); }
function iso(v) { return v instanceof Date ? Utilities.formatDate(v, TZ, 'yyyy-MM-dd HH:mm') : (v === undefined || v === null ? '' : v); }
function isoDay(v) { return v instanceof Date ? Utilities.formatDate(v, TZ, 'yyyy-MM-dd') : clean(v); }

/** "2026-11-02" from the phone → a date in the sheet. */
function toDate(v) {
  const m = clean(v).match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) throw new Error('Date is not valid.');
  const dt = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  if (dt.getTime() > Date.now() + 86400000) throw new Error('Date cannot be in the future.');
  return dt;
}

// ------------------------------------------------------------------ people & roles

function me(d) { return authUser_(APP, d, false); }
function role(u) { return clean(u.role); }
function canApprove(u) { return ['Approver', 'Admin'].indexOf(role(u)) >= 0; }
function isAccounts(u) { return ['Accounts', 'Admin'].indexOf(role(u)) >= 0; }
function seesAll(u) { return canApprove(u) || isAccounts(u); }

function sameName(a, b) { return clean(a).toLowerCase() === clean(b).toLowerCase(); }

/** A claim belongs to u when its Employee ID matches (or, for rows typed in the sheet, the name). */
function owns(u, c) {
  const id = clean(c['Employee ID']);
  return id ? id === u.id : sameName(c['Team Member'], u.name);
}

/** Keeps the Lists tab (dropdowns and summary) in step with the Team Directory. */
function syncMembers() {
  const cache = CacheService.getScriptCache();
  if (cache.get('membersSynced')) return;
  const people = authUsers_(APP);
  const sh = book().getSheetByName('Lists');
  if (sh && people.length) {
    const vals = people.map(function (p) { return [p.name, p.team]; });
    const max = Math.max(vals.length, 59);
    const block = [];
    for (let i = 0; i < max; i++) block.push(vals[i] || ['', '']);
    sh.getRange(2, 1, block.length, 2).setValues(block);
  }
  cache.put('membersSynced', '1', 1800);
}

function listColumn(col) {
  const sh = book().getSheetByName('Lists');
  if (!sh) return [];
  const n = Math.max(sh.getLastRow() - 1, 1);
  return sh.getRange(2, col, n, 1).getValues().map(function (r) { return clean(r[0]); }).filter(String);
}

// ------------------------------------------------------------------ claims

function claimOut(c, withMember) {
  const o = {
    id: clean(c['Claim ID']), date: isoDay(c['Date']), billNo: clean(c['Bill / Voucher No.']),
    category: clean(c['Category']), description: clean(c['Description / Purpose']),
    site: clean(c['Customer / Site']), district: clean(c['District / Location']),
    km: num(c['KM Travelled']), amount: money(c['Amount (₹)']), paymentMode: clean(c['Payment Mode']),
    paidBy: clean(c['Paid By']), hasPhoto: clean(c['Bill Photo Link']) !== '',
    status: clean(c['Status']) || ST.PENDING, approvedBy: clean(c['Approved By']), approvedOn: iso(c['Approved On']),
    remarks: clean(c['Remarks']), reimbursedOn: isoDay(c['Reimbursed On']), reimbursedVia: clean(c['Reimbursed Via']),
    paymentRef: clean(c['Payment Reference']), submittedOn: iso(c['Submitted On']),
  };
  if (withMember) { o.member = clean(c['Team Member']); o.memberId = clean(c['Employee ID']); o.team = clean(c['Team']); }
  return o;
}

function sortNewest(list) {
  return list.sort(function (a, b) {
    return String(b.date + b.submittedOn).localeCompare(String(a.date + a.submittedOn));
  });
}

function findClaim(id) {
  const c = rows(LOG).filter(function (r) { return clean(r['Claim ID']) === clean(id); })[0];
  if (!c) throw new Error('Claim not found: ' + id);
  return c;
}

/** Advance totals for one person, worked out from the sheet rows. */
function balanceFor(name, id, claims, advs) {
  const given = advs.filter(function (a) { return sameName(a['Team Member'], name); })
    .reduce(function (s, a) { return s + num(a['Amount (₹)']); }, 0);
  const mine = claims.filter(function (c) { return clean(c['Employee ID']) ? clean(c['Employee ID']) === id : sameName(c['Team Member'], name); });
  const sum = function (filter) { return mine.filter(filter).reduce(function (s, c) { return s + num(c['Amount (₹)']); }, 0); };
  const st = function (c) { return clean(c['Status']) || ST.PENDING; };
  const pb = function (c) { return clean(c['Paid By']); };
  const adjusted = sum(function (c) { return pb(c) === 'Advance' && (st(c) === ST.APPROVED || st(c) === ST.REIMBURSED); });
  return {
    advanceGiven: money(given),
    advanceAdjusted: money(adjusted),
    advanceBalance: money(given - adjusted),
    advancePending: money(sum(function (c) { return pb(c) === 'Advance' && st(c) === ST.PENDING; })),
    pendingApproval: money(sum(function (c) { return st(c) === ST.PENDING; })),
    toReimburse: money(sum(function (c) { return pb(c) === 'Self' && st(c) === ST.APPROVED; })),
    reimbursed: money(sum(function (c) { return st(c) === ST.REIMBURSED; })),
  };
}

function advOut(a) {
  return {
    id: clean(a['Advance ID']), date: isoDay(a['Date']), member: clean(a['Team Member']), amount: money(a['Amount (₹)']),
    via: clean(a['Paid Via']), ref: clean(a['Payment Reference']), purpose: clean(a['Purpose']), givenBy: clean(a['Given By']),
  };
}

// ------------------------------------------------------------------ handlers

function bootstrap(d) {
  const u = me(d);
  try { syncMembers(); } catch (_) {}
  const claims = rows(LOG);
  const advs = rows(ADV);
  const res = {
    user: u,
    categories: listColumn(4),
    paymentModes: listColumn(6),
    paidBy: PAID_BY,
    payVia: PAY_VIA,
    balance: balanceFor(u.name, u.id, claims, advs),
    counts: {},
  };
  if (canApprove(u)) {
    res.counts.toApprove = claims.filter(function (c) { return (clean(c['Status']) || ST.PENDING) === ST.PENDING && !owns(u, c); }).length;
  }
  if (isAccounts(u)) {
    res.counts.toPay = claims.filter(function (c) { return clean(c['Status']) === ST.APPROVED && clean(c['Paid By']) === 'Self'; }).length;
    res.members = authUsers_(APP).map(function (p) { return { id: p.id, name: p.name, team: p.team }; });
  }
  return res;
}

/** Everything the home screen needs in one request: lists, totals, counts and own claims. */
function home(d) {
  const b = bootstrap(d);
  const claims = rows(LOG);
  const advs = rows(ADV);
  const u = b.user;
  b.claims = sortNewest(claims.filter(function (c) { return owns(u, c); }).map(function (c) { return claimOut(c, false); })).slice(0, 300);
  b.advances = advs.filter(function (a) { return sameName(a['Team Member'], u.name); }).map(advOut).reverse();
  return b;
}

function myClaims(d) {
  const u = me(d);
  const claims = rows(LOG);
  const advs = rows(ADV);
  return {
    claims: sortNewest(claims.filter(function (c) { return owns(u, c); }).map(function (c) { return claimOut(c, false); })).slice(0, 300),
    advances: advs.filter(function (a) { return sameName(a['Team Member'], u.name); }).map(advOut).reverse(),
    balance: balanceFor(u.name, u.id, claims, advs),
  };
}

function submitClaim(d) {
  const u = me(d);
  const c = d.claim || {};
  const id = clean(c.id);
  if (!/^CLM-[A-Z0-9-]{6,40}$/.test(id)) throw new Error('Claim number is not valid. Update the app.');
  const amount = money(c.amount);
  if (!(amount > 0) || amount > 500000) throw new Error('Enter the amount in rupees.');
  const category = clean(c.category, 60);
  if (listColumn(4).indexOf(category) < 0) throw new Error('Choose a category from the list.');
  const paidBy = clean(c.paidBy);
  if (PAID_BY.indexOf(paidBy) < 0) throw new Error('Choose who paid: Self, Advance or Company.');
  const description = clean(c.description, 300);
  if (description.length < 3) throw new Error('Write what the expense was for.');

  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const existing = rows(LOG).filter(function (r) { return clean(r['Claim ID']) === id; })[0];
    if (existing) {
      if (!owns(u, existing)) throw new Error('Claim number already used.');
      if (d.photo && !clean(existing['Bill Photo Link'])) setFields(LOG, existing._row, { 'Bill Photo Link': saveBill(id, u, d.photo) });
      return { claim: claimOut(rows(LOG).filter(function (r) { return clean(r['Claim ID']) === id; })[0], false), duplicate: true };
    }
    const link = d.photo ? saveBill(id, u, d.photo) : '';
    const row = append(LOG, {
      'Date': toDate(c.date),
      'Bill / Voucher No.': "'" + clean(c.billNo, 40),
      'Team Member': u.name,
      'Category': category,
      'Description / Purpose': description,
      'Customer / Site': clean(c.site, 100),
      'District / Location': clean(c.district, 80),
      'KM Travelled': num(c.km) > 0 ? Math.round(num(c.km)) : '',
      'Amount (₹)': amount,
      'Payment Mode': clean(c.paymentMode, 30),
      'Paid By': paidBy,
      'Bill Photo Link': link,
      'Status': ST.PENDING,
      'Claim ID': id,
      'Submitted On': now(),
      'Employee ID': u.id,
    });
    SpreadsheetApp.flush();
    const saved = rows(LOG).filter(function (r) { return r._row === row; })[0];
    return { claim: claimOut(saved, false) };
  } finally {
    lock.releaseLock();
  }
}

function saveBill(claimId, u, photo) {
  const data = clean(photo.data);
  if (!data) return '';
  const bytes = Utilities.base64Decode(data);
  if (bytes.length > MAX_PHOTO_BYTES) throw new Error('Bill photo is too large.');
  const mime = /^image\/(jpeg|png|webp)$/.test(clean(photo.mime)) ? clean(photo.mime) : 'image/jpeg';
  const ext = mime === 'image/png' ? '.png' : mime === 'image/webp' ? '.webp' : '.jpg';
  const folder = monthFolder();
  const name = claimId + ' - ' + u.name + ext;
  const f = folder.createFile(Utilities.newBlob(bytes, mime, name));
  return f.getUrl();
}

function monthFolder() {
  const root = DriveApp.getFolderById(BILLS_FOLDER_ID);
  const name = Utilities.formatDate(now(), TZ, 'yyyy-MM');
  const it = root.getFoldersByName(name);
  return it.hasNext() ? it.next() : root.createFolder(name);
}

function fileIdFromUrl(url) {
  const m = clean(url).match(/[-\w]{25,}/);
  return m ? m[0] : '';
}

/** Bill photo bytes for the app, so the Drive folder can stay private. */
function billPhoto(d) {
  const u = me(d);
  const c = findClaim(d.claimId);
  if (!owns(u, c) && !seesAll(u)) throw new Error('This is not your claim.');
  const id = fileIdFromUrl(c['Bill Photo Link']);
  if (!id) throw new Error('No bill photo for this claim.');
  const blob = DriveApp.getFileById(id).getBlob();
  return { mime: blob.getContentType(), data: Utilities.base64Encode(blob.getBytes()) };
}

function deleteClaim(d) {
  const u = me(d);
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const c = findClaim(d.claimId);
    if (!owns(u, c)) throw new Error('This is not your claim.');
    if ((clean(c['Status']) || ST.PENDING) !== ST.PENDING) throw new Error('Only a pending claim can be deleted.');
    const fid = fileIdFromUrl(c['Bill Photo Link']);
    if (fid) { try { DriveApp.getFileById(fid).setTrashed(true); } catch (_) {} }
    table(LOG).sh.deleteRow(c._row);
    return {};
  } finally {
    lock.releaseLock();
  }
}

function teamClaims(d) {
  const u = me(d);
  if (!seesAll(u)) throw new Error('Only approvers and accounts can see team claims.');
  const status = clean(d.status);
  const list = rows(LOG)
    .filter(function (c) { return !status || (clean(c['Status']) || ST.PENDING) === status; })
    .map(function (c) { return claimOut(c, true); });
  return { claims: sortNewest(list).slice(0, 500) };
}

function decide(d) {
  const u = me(d);
  if (!canApprove(u)) throw new Error('Only approvers can approve claims.');
  const approve = d.approve === true;
  const note = clean(d.note, 300);
  if (!approve && note.length < 3) throw new Error('Write why the claim is rejected.');
  const ids = Array.isArray(d.claimIds) ? d.claimIds : [d.claimId];
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const all = rows(LOG);
    let done = 0;
    const skipped = [];
    ids.forEach(function (id) {
      const c = all.filter(function (r) { return clean(r['Claim ID']) === clean(id); })[0];
      if (!c) { skipped.push(id + ': not found'); return; }
      if (owns(u, c)) { skipped.push(id + ': you cannot approve your own claim'); return; }
      if ((clean(c['Status']) || ST.PENDING) !== ST.PENDING) { skipped.push(id + ': already ' + clean(c['Status'])); return; }
      setFields(LOG, c._row, {
        'Status': approve ? ST.APPROVED : ST.REJECTED,
        'Approved By': u.name,
        'Approved On': now(),
        'Remarks': note,
      });
      done++;
    });
    return { done: done, skipped: skipped };
  } finally {
    lock.releaseLock();
  }
}

function markPaid(d) {
  const u = me(d);
  if (!isAccounts(u)) throw new Error('Only accounts can mark claims as paid.');
  const via = clean(d.via);
  if (PAY_VIA.indexOf(via) < 0) throw new Error('Choose Cash, UPI or Bank Transfer.');
  const ref = clean(d.ref, 60);
  const day = d.date ? toDate(d.date) : new Date(new Date().setHours(0, 0, 0, 0));
  const ids = Array.isArray(d.claimIds) ? d.claimIds : [d.claimId];
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const all = rows(LOG);
    let done = 0;
    let total = 0;
    const skipped = [];
    ids.forEach(function (id) {
      const c = all.filter(function (r) { return clean(r['Claim ID']) === clean(id); })[0];
      if (!c) { skipped.push(id + ': not found'); return; }
      if (clean(c['Status']) !== ST.APPROVED) { skipped.push(id + ': not approved yet'); return; }
      if (clean(c['Paid By']) !== 'Self') { skipped.push(id + ': paid by ' + clean(c['Paid By']) + ', nothing to reimburse'); return; }
      setFields(LOG, c._row, {
        'Status': ST.REIMBURSED, 'Reimbursed On': day, 'Reimbursed Via': via,
        'Payment Reference': ref ? "'" + ref : '',
      });
      done++;
      total += num(c['Amount (₹)']);
    });
    return { done: done, total: money(total), skipped: skipped };
  } finally {
    lock.releaseLock();
  }
}

/** Own advances and balance; with team=true (accounts / approvers only) everyone's. */
function advances(d) {
  const u = me(d);
  const claims = rows(LOG);
  const advs = rows(ADV);
  if (d.team !== true || !seesAll(u)) {
    return {
      advances: advs.filter(function (a) { return sameName(a['Team Member'], u.name); }).map(advOut).reverse(),
      balance: balanceFor(u.name, u.id, claims, advs),
    };
  }
  const people = authUsers_(APP);
  return {
    advances: advs.map(advOut).reverse().slice(0, 500),
    people: people.map(function (p) {
      const b = balanceFor(p.name, p.id, claims, advs);
      b.id = p.id; b.name = p.name; b.team = p.team;
      return b;
    }),
  };
}

function addAdvance(d) {
  const u = me(d);
  if (!isAccounts(u)) throw new Error('Only accounts can record advances.');
  const person = authUsers_(APP).filter(function (p) { return p.id === clean(d.memberId); })[0];
  if (!person) throw new Error('Choose the team member.');
  const amount = money(d.amount);
  if (!(amount > 0) || amount > 1000000) throw new Error('Enter the advance amount.');
  const via = clean(d.via);
  if (PAY_VIA.indexOf(via) < 0) throw new Error('Choose Cash, UPI or Bank Transfer.');
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const advs = rows(ADV);
    let n = 0;
    advs.forEach(function (a) { const m = clean(a['Advance ID']).match(/^ADV-(\d+)$/); if (m) n = Math.max(n, Number(m[1])); });
    const id = 'ADV-' + ('000' + (n + 1)).slice(-4);
    append(ADV, {
      'Advance ID': id, 'Date': d.date ? toDate(d.date) : new Date(new Date().setHours(0, 0, 0, 0)),
      'Team Member': person.name, 'Amount (₹)': amount, 'Paid Via': via,
      'Payment Reference': clean(d.ref) ? "'" + clean(d.ref, 60) : '', 'Purpose': clean(d.purpose, 200),
      'Given By': u.name, 'Remarks': '',
    });
    return { id: id };
  } finally {
    lock.releaseLock();
  }
}
