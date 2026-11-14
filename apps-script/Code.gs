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
 *   Approver – also approve / reject claims of Members and Accounts, up to the "Manager approval limit"
 *              in the Settings tab (above it only Admin – Atul / Rahul – can decide)
 * Claims of Approvers (managers) and Admins are decided only by an Admin (Atul / Rahul),
 * whatever the amount; nobody decides their own claim.
 * Fuel claims whose start and end odometer photos are on different dates (or have no date)
 * are also decided only by an Admin.
 *   Accounts – also record advances and mark approved claims as paid
 *   Admin    – everything
 */

const APP = 'expense';
const SHARED_KEY = 'avip-expense-4Rt8';
const TZ = 'Asia/Kolkata';
const BILLS_FOLDER_ID = '1o7ZOSqrC49JbHbHzylFfq98-gdfp21cd'; // "AVIP Solar – Expense Bills"
const LOG = 'Expense Log';
const ADV = 'Advances';
const REQ = 'Advance Requests';
const RQ = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', PAID: 'Paid' };
const REQ_HEADERS = ['Request ID', 'Requested On', 'Team Member', 'Employee ID', 'Team', 'Amount (₹)', 'Purpose', 'Needed By',
  'Pay Via', 'Status', 'Approved By', 'Approved On', 'Remarks', 'Paid On', 'Paid By', 'Payment Reference', 'Advance ID'];
const PAY_VIA = ['Cash', 'UPI', 'Bank Transfer'];
const PAID_BY = ['Self', 'Advance', 'Company'];
const ST = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', REIMBURSED: 'Reimbursed' };
const MAX_PHOTO_BYTES = 6 * 1024 * 1024;
const SETTINGS = 'Settings';
const DEFAULT_FUEL_RATE = 3.5;     // ₹ per km, bike
const DEFAULT_APPROVAL_LIMIT = 1000;
const MAX_TRIP_KM = 1500;

/** Fuel / Petrol claims: amount comes from odometer photos × rate. */
function isFuel(category) { return /fuel|petrol/i.test(clean(category)); }

/** Numbers from the Settings tab (column A = name, B = value), with safe defaults. */
function settings() {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('settings');
  if (hit) return JSON.parse(hit);
  const res = { fuelRate: DEFAULT_FUEL_RATE, approvalLimit: DEFAULT_APPROVAL_LIMIT };
  const sh = book().getSheetByName(SETTINGS);
  if (sh && sh.getLastRow() > 1) {
    sh.getRange(2, 1, sh.getLastRow() - 1, 2).getValues().forEach(function (r) {
      const k = clean(r[0]).toLowerCase();
      const v = Number(r[1]);
      if (!isFinite(v) || v <= 0) return;
      if (k.indexOf('fuel') >= 0 || k.indexOf('petrol') >= 0) res.fuelRate = v;
      else if (k.indexOf('approval') >= 0 || k.indexOf('limit') >= 0) res.approvalLimit = v;
    });
  }
  cache.put('settings', JSON.stringify(res), 120);
  return res;
}

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
  requestAdvance: requestAdvance,
  advanceRequests: advanceRequests,
  decideAdvance: decideAdvance,
  payAdvance: payAdvance,
  pending: pending,
};

/** Run once from the editor. */
function setup() {
  DriveApp.getFolderById(BILLS_FOLDER_ID);
  table(LOG); table(ADV); reqSheet();
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
  const sh = book().getSheetByName(name) || (name === REQ ? reqSheet() : null);
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
    if (name === LOG) {
      // a row with only a date (no person, no amount) is not a claim
      const pi = t.headers.indexOf('Team Member'), ai = t.headers.indexOf('Amount (₹)');
      if (pi >= 0 && ai >= 0 && String(r[pi]).trim() === '' && String(r[ai]).trim() === '') return;
    }
    // first column with a header wins (Advances has a second "Team Member" in its summary block)
    t.headers.forEach(function (h, j) { if (h && !(h in o)) o[h] = r[j]; });
    list.push(o);
  });
  return list;
}

/** Writes only the given columns (never the formula columns Month and Team). */
function setFields(name, row, obj) {
  const t = table(name);
  // [column, value] pairs, sorted; each contiguous run is written in ONE setValues,
  // so a run is all-or-nothing — a rejected cell can never leave a half-written row.
  const cells = Object.keys(obj).map(function (k) {
    const v = obj[k];
    return [t.col(k), v === undefined || v === null ? '' : v];
  }).sort(function (a, b) { return a[0] - b[0]; });
  let i = 0;
  while (i < cells.length) {
    let j = i;
    while (j + 1 < cells.length && cells[j + 1][0] === cells[j][0] + 1) j++;
    const vals = [];
    for (let k = i; k <= j; k++) vals.push(cells[k][1]);
    t.sh.getRange(row, cells[i][0], 1, vals.length).setValues([vals]);
    i = j + 1;
  }
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
function isAdmin(u) { return role(u) === 'Admin'; }
/** Expense App Role of the person who made the claim (by Employee ID, else by name). */
let ROLE_BY_ID_ = null;
function ownerRole(c) {
  if (!ROLE_BY_ID_) {
    ROLE_BY_ID_ = {};
    try { authUsers_(APP).forEach(function (p) { ROLE_BY_ID_[p.id] = p.role; ROLE_BY_ID_['name:' + clean(p.name).toLowerCase()] = p.role; }); } catch (_) {}
  }
  const id = clean(c['Employee ID']);
  return clean(id ? ROLE_BY_ID_[id] : ROLE_BY_ID_['name:' + clean(c['Team Member']).toLowerCase()]);
}
/** Fuel claim whose odometer photos are not both from the same day (or have no date). */
function odoDatesDiffer(c) {
  if (!isFuel(c['Category']) || !clean(c['Start Odometer Photo'])) return false;
  const a = c['Start Photo Time'], b = c['End Photo Time'];
  if (!(a instanceof Date) || !(b instanceof Date)) return true;
  return Utilities.formatDate(a, TZ, 'yyyy-MM-dd') !== Utilities.formatDate(b, TZ, 'yyyy-MM-dd');
}
/** Why only Atul / Rahul can decide this claim, or '' when a manager can. */
function adminReason(c, limit) {
  if (money(c['Amount (₹)']) > limit) return 'above ₹' + limit;
  if (['Approver', 'Admin'].indexOf(ownerRole(c)) >= 0) return 'claim of a manager';
  if (odoDatesDiffer(c)) return 'odometer photos on different dates';
  return '';
}
/** Only Atul / Rahul (Admin): above the limit, claims of managers, fuel claims with photos on different dates. */
function needsAdmin(c, limit) { return adminReason(c, limit) !== ''; }
function canDecide(u, c, limit) {
  if (!canApprove(u)) return false;
  return isAdmin(u) || !needsAdmin(c, limit);
}

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
  const lim = settings().approvalLimit;
  const o = {
    id: clean(c['Claim ID']), date: isoDay(c['Date']), billNo: clean(c['Bill / Voucher No.']),
    category: clean(c['Category']), description: clean(c['Description / Purpose']),
    site: clean(c['Customer / Site']), district: clean(c['District / Location']),
    km: num(c['KM Travelled']), amount: money(c['Amount (₹)']), paymentMode: clean(c['Payment Mode']),
    paidBy: clean(c['Paid By']), hasPhoto: clean(c['Bill Photo Link']) !== '',
    billCount: String(c['Bill Photo Link'] || '').split('\n').filter(String).length,
    status: clean(c['Status']) || ST.PENDING, approvedBy: clean(c['Approved By']), approvedOn: iso(c['Approved On']),
    remarks: clean(c['Remarks']), reimbursedOn: isoDay(c['Reimbursed On']), reimbursedVia: clean(c['Reimbursed Via']),
    paymentRef: clean(c['Payment Reference']), submittedOn: iso(c['Submitted On']),
    odoStart: num(c['Odometer Start']), odoEnd: num(c['Odometer End']), rate: num(c['Rate per KM (₹)']),
    hasOdoStart: clean(c['Start Odometer Photo']) !== '', hasOdoEnd: clean(c['End Odometer Photo']) !== '',
    adminOnly: needsAdmin(c, lim), adminReason: adminReason(c, lim), odoDatesDiffer: odoDatesDiffer(c),
    odoStartTime: iso(c['Start Photo Time']), odoEndTime: iso(c['End Photo Time']),
    bike: /^company/i.test(clean(c['Bike'])) ? 'Company' : (isFuel(c['Category']) ? 'Own' : ''),
    bikeNo: clean(c['Bike']).replace(/^company bike\s*[–-]\s*/i, '').replace(/^own bike$/i, ''),
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
  // advance claims draw the advance down only as far as it goes — the balance never reads negative
  const advClaims = sum(function (c) { return pb(c) === 'Advance' && (st(c) === ST.APPROVED || st(c) === ST.REIMBURSED); });
  const adjusted = Math.min(advClaims, given);
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
    settings: settings(),
    counts: {},
  };
  if (canApprove(u)) {
    const lim = res.settings.approvalLimit;
    const pending = claims.filter(function (c) { return (clean(c['Status']) || ST.PENDING) === ST.PENDING && !owns(u, c); });
    res.counts.toApprove = pending.filter(function (c) { return canDecide(u, c, lim); }).length;
    res.counts.forAdmin = pending.length - res.counts.toApprove;
    const reqs = rows(REQ).filter(function (r) { return clean(r['Status']) === RQ.PENDING && !owns(u, r); });
    res.counts.advToApprove = reqs.filter(function (r) { return canDecide(u, r, lim); }).length;
  }
  if (isAccounts(u)) {
    res.counts.toPay = claims.filter(function (c) { return clean(c['Status']) === ST.APPROVED && clean(c['Paid By']) === 'Self'; }).length;
    res.counts.advToPay = rows(REQ).filter(function (r) { return clean(r['Status']) === RQ.APPROVED; }).length;
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
  b.advRequests = rows(REQ).filter(function (r) { return owns(u, r); }).map(function (r) { return reqOut(r); }).reverse().slice(0, 100);
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
  const category = clean(c.category, 60);
  if (listColumn(4).indexOf(category) < 0) throw new Error('Choose a category from the list.');
  const fuel = isFuel(category);
  // a rejected claim corrected by its owner and sent again: the photos already with the office may be kept
  const fixing = c.fix === true;
  const hasBill = !!d.photo || (Array.isArray(d.photos) && d.photos.length > 0);
  const companyBike = fuel && clean(c.bike).toLowerCase() === 'company';
  const bikeNo = clean(c.bikeNo, 20).toUpperCase();
  if (companyBike && bikeNo.length < 4) throw new Error('Company bike: enter the bike number.');
  let amount = money(c.amount);
  let odoStart = 0, odoEnd = 0, km = num(c.km), rate = 0, startStamp = null, endStamp = null;
  if (fuel) {
    odoStart = Math.round(num(c.odoStart) * 10) / 10;
    odoEnd = Math.round(num(c.odoEnd) * 10) / 10;
    if (!(odoStart > 0) || !(odoEnd > 0)) throw new Error('Fuel claim: enter the start and end odometer readings.');
    // both odometer photos from the app's GPS camera; the end photo newer than the start photo
    startStamp = stampInfo(c.odoStartStamp);
    endStamp = stampInfo(c.odoEndStamp);
    if (!fixing && (!startStamp || !endStamp)) throw new Error('Fuel claim: odometer photos must be taken with the GPS camera of the latest app. Update the app and take the photos again.');
    if (startStamp && endStamp && endStamp.time.getTime() <= startStamp.time.getTime()) throw new Error('Fuel claim: the end odometer photo must be newer than the start photo.');
    if (odoEnd <= odoStart) throw new Error('Fuel claim: end odometer reading must be more than the start reading.');
    km = Math.round((odoEnd - odoStart) * 10) / 10;
    if (km > MAX_TRIP_KM) throw new Error('Fuel claim: ' + km + ' km looks wrong. Check the odometer readings.');
    if (companyBike) {
      // company bike: the petrol bill, as a lump sum (cost per km is only shown)
      if (!hasBill && !fixing) throw new Error('Company bike: add the photo of the petrol bill.');
    } else {
      rate = settings().fuelRate;
      amount = money(km * rate);
    }
  }
  if (!(amount > 0) || amount > 500000) throw new Error('Enter the amount in rupees.');
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
      if (fixing && clean(existing['Status']) === ST.REJECTED) {
        return { claim: resendClaim_(u, d, existing, {
          fuel: fuel, companyBike: companyBike, bikeNo: bikeNo, amount: amount, km: km, rate: rate, odoStart: odoStart, odoEnd: odoEnd,
          startStamp: startStamp, endStamp: endStamp, paidBy: paidBy, description: description, hasBill: hasBill }), resent: true };
      }
      if ((d.photo || (Array.isArray(d.photos) && d.photos.length)) && !clean(existing['Bill Photo Link'])) setFields(LOG, existing._row, { 'Bill Photo Link': billLinks_(id, u, d) });
      if (d.odoStartPhoto && !clean(existing['Start Odometer Photo'])) setFields(LOG, existing._row, { 'Start Odometer Photo': saveBill(id, u, d.odoStartPhoto, 'start odometer') });
      if (d.odoEndPhoto && !clean(existing['End Odometer Photo'])) setFields(LOG, existing._row, { 'End Odometer Photo': saveBill(id, u, d.odoEndPhoto, 'end odometer') });
      return { claim: claimOut(rows(LOG).filter(function (r) { return clean(r['Claim ID']) === id; })[0], false), duplicate: true };
    }
    if (fuel && (!d.odoStartPhoto || !d.odoEndPhoto)) throw new Error('Fuel claim: add photos of the start and end odometer.');
    if (fuel && (!startStamp || !endStamp)) throw new Error('Fuel claim: odometer photos must be taken with the GPS camera of the latest app. Update the app and take the photos again.');
    const link = billLinks_(id, u, d);
    const startLink = fuel ? saveBill(id, u, d.odoStartPhoto, 'start odometer') : '';
    const endLink = fuel ? saveBill(id, u, d.odoEndPhoto, 'end odometer') : '';

    // From-advance claim bigger than the advance left: the advance covers what it can
    // (never below zero) and the rest becomes a reimbursement the accounts team pays.
    let payBy = paidBy;
    let mainAmt = amount;
    let extra = 0;
    if (!fuel && paidBy === 'Advance') {
      const bal = balanceFor(u.name, u.id, rows(LOG), rows(ADV));
      const avail = Math.max(0, money(bal.advanceBalance - bal.advancePending));
      if (amount > avail) {
        if (avail > 0) { mainAmt = avail; extra = money(amount - avail); }
        else { payBy = 'Self'; }
      }
    }

    const row = append(LOG, {
      'Date': toDate(c.date),
      'Bill / Voucher No.': "'" + clean(c.billNo, 40),
      'Team Member': u.name,
      'Category': category,
      'Description / Purpose': description,
      'Customer / Site': clean(c.site, 100),
      'District / Location': clean(c.district, 80),
      'KM Travelled': km > 0 ? (fuel ? km : Math.round(km)) : '',
      'Amount (₹)': mainAmt,
      'Payment Mode': clean(c.paymentMode, 30),
      'Paid By': payBy,
      'Bill Photo Link': link,
      'Status': ST.PENDING,
      'Claim ID': id,
      'Submitted On': now(),
      'Employee ID': u.id,
      'Odometer Start': fuel ? odoStart : '',
      'Odometer End': fuel ? odoEnd : '',
      'Start Odometer Photo': startLink,
      'End Odometer Photo': endLink,
      'Rate per KM (₹)': fuel ? rate : '',
      'Start Photo Time': fuel ? startStamp.time : '',
      'End Photo Time': fuel ? endStamp.time : '',
      'Bike': fuel ? (companyBike ? 'Company bike – ' + bikeNo : 'Own bike') : '',
    });
    if (extra > 0) {
      append(LOG, {
        'Date': toDate(c.date),
        'Bill / Voucher No.': "'" + clean(c.billNo, 40),
        'Team Member': u.name,
        'Category': category,
        'Description / Purpose': description + ' (Advance से ज़्यादा — बाकी reimburse)',
        'Customer / Site': clean(c.site, 100),
        'District / Location': clean(c.district, 80),
        'Amount (₹)': extra,
        'Payment Mode': clean(c.paymentMode, 30),
        'Paid By': 'Self',
        'Bill Photo Link': link,
        'Status': ST.PENDING,
        'Claim ID': id + '-R',
        'Submitted On': now(),
        'Employee ID': u.id,
      });
    }
    SpreadsheetApp.flush();
    const saved = rows(LOG).filter(function (r) { return r._row === row; })[0];
    return { claim: claimOut(saved, false) };
  } finally {
    lock.releaseLock();
  }
}

/**
 * A rejected claim, corrected by its owner, goes back for approval in the same row.
 * Photos are replaced only when new ones come; otherwise the ones already saved stay.
 */
function resendClaim_(u, d, ex, v) {
  const c = d.claim || {};
  const id = clean(ex['Claim ID']);
  const trash = function (cell) {
    String(cell || '').split('\n').filter(String).forEach(function (url) {
      const fid = fileIdFromUrl(url);
      if (fid) { try { DriveApp.getFileById(fid).setTrashed(true); } catch (_) {} }
    });
  };
  const set = {};
  if (v.fuel) {
    const t = function (x) { return x instanceof Date ? x.getTime() : 0; };
    if (d.odoStartPhoto && !v.startStamp) throw new Error('Fuel claim: take the start odometer photo again with the GPS camera.');
    if (d.odoEndPhoto && !v.endStamp) throw new Error('Fuel claim: take the end odometer photo again with the GPS camera.');
    if (!d.odoStartPhoto && !clean(ex['Start Odometer Photo'])) throw new Error('Fuel claim: add the start odometer photo.');
    if (!d.odoEndPhoto && !clean(ex['End Odometer Photo'])) throw new Error('Fuel claim: add the end odometer photo.');
    const st = d.odoStartPhoto ? v.startStamp.time.getTime() : t(ex['Start Photo Time']);
    const en = d.odoEndPhoto ? v.endStamp.time.getTime() : t(ex['End Photo Time']);
    if (st && en && en <= st) throw new Error('Fuel claim: the end odometer photo must be newer than the start photo.');
    if (v.companyBike && !v.hasBill && !clean(ex['Bill Photo Link'])) throw new Error('Company bike: add the photo of the petrol bill.');
    if (d.odoStartPhoto) { trash(ex['Start Odometer Photo']); set['Start Odometer Photo'] = saveBill(id, u, d.odoStartPhoto, 'start odometer'); set['Start Photo Time'] = v.startStamp.time; }
    if (d.odoEndPhoto) { trash(ex['End Odometer Photo']); set['End Odometer Photo'] = saveBill(id, u, d.odoEndPhoto, 'end odometer'); set['End Photo Time'] = v.endStamp.time; }
    set['Odometer Start'] = v.odoStart;
    set['Odometer End'] = v.odoEnd;
    set['Rate per KM (₹)'] = v.rate;
    set['Bike'] = v.companyBike ? 'Company bike – ' + v.bikeNo : 'Own bike';
  }
  if (v.hasBill) { trash(ex['Bill Photo Link']); set['Bill Photo Link'] = billLinks_(id, u, d); }
  // from advance, but nothing left in the advance: the office pays it back instead
  let payBy = v.paidBy;
  if (!v.fuel && payBy === 'Advance') {
    const others = rows(LOG).filter(function (r) { return r._row !== ex._row; });
    const bal = balanceFor(u.name, u.id, others, rows(ADV));
    if (money(bal.advanceBalance - bal.advancePending) <= 0) payBy = 'Self';
  }
  const why = clean(ex['Remarks']);
  set['Bill / Voucher No.'] = "'" + clean(c.billNo, 40);
  set['Description / Purpose'] = v.description;
  set['Customer / Site'] = clean(c.site, 100);
  set['District / Location'] = clean(c.district, 80);
  set['KM Travelled'] = v.km > 0 ? (v.fuel ? v.km : Math.round(v.km)) : '';
  set['Amount (₹)'] = v.amount;
  set['Payment Mode'] = clean(c.paymentMode, 30);
  set['Paid By'] = payBy;
  set['Status'] = ST.PENDING;
  set['Approved By'] = '';
  set['Approved On'] = '';
  set['Remarks'] = clean('Corrected and sent again' + (why ? ' · earlier rejected: ' + why.replace(/^Corrected and sent again · earlier rejected: /, '') : ''), 300);
  set['Submitted On'] = now();
  setFields(LOG, ex._row, set);
  SpreadsheetApp.flush();
  return claimOut(rows(LOG).filter(function (r) { return r._row === ex._row; })[0], false);
}

/**
 * GPS-camera stamp from the app: "AVIPSE|<ms>|<lat>|<lng>|<sig>". Returns { time: Date, lat, lng }
 * when the signature is right (taken with the app's GPS camera, times not edited), else null.
 */
function stampInfo(s) {
  const p = clean(s).split('|');
  if (p.length !== 5 || p[0] !== 'AVIPSE') return null;
  const dig = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, 'AVIPSE|' + p[1] + '|' + p[2] + '|' + p[3] + '|' + SHARED_KEY, Utilities.Charset.UTF_8);
  const hex = dig.slice(0, 8).map(function (b) { return ('0' + (b & 0xff).toString(16)).slice(-2); }).join('');
  if (hex !== p[4]) return null;
  const ms = Number(p[1]);
  if (!(ms > 0) || ms > Date.now() + 600000) return null;
  return { time: new Date(ms), lat: Number(p[2]), lng: Number(p[3]) };
}

/** Time a photo was taken (milliseconds from the phone), or '' when unknown / not believable. */
function photoTime(ms) {
  const n = num(ms);
  if (!(n > 0)) return '';
  const t = new Date(n);
  if (n > Date.now() + 3600000 || n < Date.now() - 400 * 86400000) return '';
  return t;
}

/** Saves one or many bill photos and returns their Drive links joined by newlines. */
function billLinks_(id, u, d) {
  if (Array.isArray(d.photos) && d.photos.length) {
    return d.photos.map(function (p, i) { return saveBill(id, u, p, d.photos.length > 1 ? 'bill ' + (i + 1) : 'bill'); }).filter(String).join('\n');
  }
  return d.photo ? saveBill(id, u, d.photo) : '';
}

function saveBill(claimId, u, photo, what) {
  const data = clean(photo.data);
  if (!data) return '';
  const bytes = Utilities.base64Decode(data);
  if (bytes.length > MAX_PHOTO_BYTES) throw new Error('Bill photo is too large.');
  const mime = /^image\/(jpeg|png|webp)$/.test(clean(photo.mime)) ? clean(photo.mime) : 'image/jpeg';
  const ext = mime === 'image/png' ? '.png' : mime === 'image/webp' ? '.webp' : '.jpg';
  const folder = monthFolder();
  const name = claimId + ' - ' + u.name + (what ? ' - ' + what : '') + ext;
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
  const col = d.which === 'odoStart' ? 'Start Odometer Photo' : d.which === 'odoEnd' ? 'End Odometer Photo' : 'Bill Photo Link';
  let cell = c[col];
  if (col === 'Bill Photo Link') {
    const links = String(cell || '').split('\n').filter(String);
    const idx = Math.max(0, Math.floor(num(d.index)) || 0);
    cell = links[idx] || links[0] || '';
  }
  const id = fileIdFromUrl(cell);
  if (!id) throw new Error('No such photo for this claim.');
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
    ['Bill Photo Link', 'Start Odometer Photo', 'End Odometer Photo'].forEach(function (k) {
      String(c[k] || '').split('\n').filter(String).forEach(function (url) {
        const fid = fileIdFromUrl(url);
        if (fid) { try { DriveApp.getFileById(fid).setTrashed(true); } catch (_) {} }
      });
    });
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
    const lim = settings().approvalLimit;
    let done = 0;
    const skipped = [];
    ids.forEach(function (id) {
      const c = all.filter(function (r) { return clean(r['Claim ID']) === clean(id); })[0];
      if (!c) { skipped.push(id + ': not found'); return; }
      if (owns(u, c)) { skipped.push(id + ': you cannot approve your own claim'); return; }
      if (!canDecide(u, c, lim)) {
        skipped.push(id + ': ' + adminReason(c, lim) + ', only Atul or Rahul can approve');
        return;
      }
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
  // a plain approver (manager) sees the team's advances, but not the founders' (Admins')
  const hideFounders = canApprove(u) && !isAccounts(u) && !isAdmin(u);
  const isFounderName = {};
  if (hideFounders) people.forEach(function (p) { if (clean(p.role) === 'Admin') isFounderName[clean(p.name).toLowerCase()] = true; });
  const visiblePeople = hideFounders ? people.filter(function (p) { return clean(p.role) !== 'Admin'; }) : people;
  // skip any stray row with no team member (e.g. an old half-written row)
  const named = advs.filter(function (a) { return clean(a['Team Member']) !== ''; });
  const visibleAdvs = hideFounders ? named.filter(function (a) { return !isFounderName[clean(a['Team Member']).toLowerCase()]; }) : named;
  return {
    advances: visibleAdvs.map(advOut).reverse().slice(0, 500),
    people: visiblePeople.map(function (p) {
      const b = balanceFor(p.name, p.id, claims, advs);
      b.id = p.id; b.name = p.name; b.team = p.team;
      return b;
    }),
  };
}

function addAdvance(d) {
  // Direct advances are off. Every advance must go through the
  // request -> approve -> pay flow so it carries proper approval.
  // (payAdvance uses insertAdvance_ directly, so paying still works.)
  throw new Error('Direct advances are off. Create an advance request, get it approved, then pay it.');
}

/** Adds one row to the Advances tab (call inside the script lock); returns its ADV- id. */
function insertAdvance_(name, amount, via, ref, purpose, givenBy, date, remarks) {
  const advs = rows(ADV);
  let n = 0;
  advs.forEach(function (a) { const m = clean(a['Advance ID']).match(/^ADV-(\d+)$/); if (m) n = Math.max(n, Number(m[1])); });
  const id = 'ADV-' + ('000' + (n + 1)).slice(-4);
  append(ADV, {
    'Advance ID': id, 'Date': date ? toDate(date) : new Date(new Date().setHours(0, 0, 0, 0)),
    'Team Member': name, 'Amount (₹)': amount, 'Paid Via': via,
    'Payment Reference': clean(ref) ? "'" + clean(ref, 60) : '', 'Purpose': clean(purpose, 200),
    'Given By': givenBy, 'Remarks': remarks || '',
  });
  return id;
}

// ------------------------------------------------------------------ notifications

/**
 * What is waiting for this person right now: claims and advance requests they can approve,
 * and (accounts) approved advances to pay. The phone checks this in the background and
 * notifies about items it has not shown before.
 */
function pending(d) {
  const u = me(d);
  const out = { claims: [], advances: [], toPay: [] };
  const lim = settings().approvalLimit;
  if (canApprove(u)) {
    rows(LOG).forEach(function (c) {
      if ((clean(c['Status']) || ST.PENDING) !== ST.PENDING || owns(u, c) || !canDecide(u, c, lim)) return;
      out.claims.push({ id: clean(c['Claim ID']), member: clean(c['Team Member']), amount: money(c['Amount (₹)']), category: clean(c['Category']) });
    });
    rows(REQ).forEach(function (r) {
      if (clean(r['Status']) !== RQ.PENDING || owns(u, r) || !canDecide(u, r, lim)) return;
      out.advances.push({ id: clean(r['Request ID']), member: clean(r['Team Member']), amount: money(r['Amount (₹)']), purpose: clean(r['Purpose']) });
    });
  }
  if (isAccounts(u)) {
    rows(REQ).forEach(function (r) {
      if (clean(r['Status']) !== RQ.APPROVED) return;
      out.toPay.push({ id: clean(r['Request ID']), member: clean(r['Team Member']), amount: money(r['Amount (₹)']) });
    });
  }
  // the person's own claims and advance requests of the last 90 days, with their status,
  // so the phone can say when one is approved, rejected or paid
  const since = Date.now() - 90 * 86400000;
  const when = function (v) { return v instanceof Date ? v.getTime() : 0; };
  out.myClaims = rows(LOG).filter(function (c) { return owns(u, c) && (when(c['Submitted On']) || when(c['Date'])) >= since; }).map(function (c) {
    return { id: clean(c['Claim ID']), status: clean(c['Status']) || ST.PENDING, amount: money(c['Amount (₹)']), category: clean(c['Category']),
      by: clean(c['Approved By']), remarks: clean(c['Remarks']), via: clean(c['Reimbursed Via']) };
  }).filter(function (c) { return c.id; });
  out.myRequests = rows(REQ).filter(function (r) { return owns(u, r) && when(r['Requested On']) >= since; }).map(function (r) {
    return { id: clean(r['Request ID']), status: clean(r['Status']) || RQ.PENDING, amount: money(r['Amount (₹)']), purpose: clean(r['Purpose']),
      by: clean(r['Approved By']), remarks: clean(r['Remarks']), via: clean(r['Pay Via']) };
  });
  return out;
}

// ------------------------------------------------------------------ advance requests

/** The "Advance Requests" tab, made on first use. */
function reqSheet() {
  const b = book();
  let sh = b.getSheetByName(REQ);
  if (!sh) {
    sh = b.insertSheet(REQ);
    sh.getRange(1, 1, 1, REQ_HEADERS.length).setValues([REQ_HEADERS]).setFontWeight('bold').setBackground('#6B3FA0').setFontColor('#FFFFFF');
    sh.setFrozenRows(1);
  }
  return sh;
}

function reqOut(r, u, lim) {
  const o = {
    id: clean(r['Request ID']), requestedOn: iso(r['Requested On']), member: clean(r['Team Member']), memberId: clean(r['Employee ID']),
    team: clean(r['Team']), amount: money(r['Amount (₹)']), purpose: clean(r['Purpose']), neededBy: isoDay(r['Needed By']),
    payVia: clean(r['Pay Via']), status: clean(r['Status']) || RQ.PENDING, approvedBy: clean(r['Approved By']),
    approvedOn: iso(r['Approved On']), remarks: clean(r['Remarks']), paidOn: isoDay(r['Paid On']), paidBy: clean(r['Paid By']),
    paymentRef: clean(r['Payment Reference']), advanceId: clean(r['Advance ID']),
  };
  if (u) {
    o.adminOnly = needsAdmin(r, lim);
    o.adminReason = adminReason(r, lim);
    o.canDecide = o.status === RQ.PENDING && !owns(u, r) && canDecide(u, r, lim);
  }
  return o;
}

/**
 * A person's earlier advances, oldest first, each marked Open or Settled: the amount adjusted
 * in approved "Advance" claims is set against the oldest advances first.
 */
function advHistory(name, id, claims, advs) {
  const b = balanceFor(name, id, claims, advs);
  let used = b.advanceAdjusted;
  const list = advs.filter(function (a) { return sameName(a['Team Member'], name); }).map(function (a) {
    const amt = money(a['Amount (₹)']);
    const take = Math.min(amt, Math.max(0, used));
    used -= take;
    const o = advOut(a);
    o.used = money(take); o.left = money(amt - take); o.settled = o.left <= 0;
    return o;
  });
  return { balance: b, advances: list.reverse().slice(0, 20), openCount: list.filter(function (a) { return !a.settled; }).length };
}

function requestAdvance(d) {
  const u = me(d);
  const r = d.request || {};
  const id = clean(r.id, 40);
  if (!/^ARQ-[0-9A-Za-z-]+$/.test(id)) throw new Error('Request id is missing. Update the app.');
  const amount = money(r.amount);
  if (!(amount > 0) || amount > 500000) throw new Error('Enter the advance amount.');
  const purpose = clean(r.purpose, 300);
  if (purpose.length < 3) throw new Error('Write what the advance is for.');
  const via = clean(r.payVia);
  if (PAY_VIA.indexOf(via) < 0) throw new Error('Choose Cash, UPI or Bank Transfer.');
  let needed = '';
  const m = clean(r.neededBy).match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (m) needed = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    reqSheet();
    const existing = rows(REQ).filter(function (x) { return clean(x['Request ID']) === id; })[0];
    if (existing) return { request: reqOut(existing), duplicate: true };
    const row = append(REQ, {
      'Request ID': id, 'Requested On': now(), 'Team Member': u.name, 'Employee ID': u.id, 'Team': clean(u.team),
      'Amount (₹)': amount, 'Purpose': purpose, 'Needed By': needed, 'Pay Via': via, 'Status': RQ.PENDING,
    });
    return { request: reqOut(rows(REQ).filter(function (x) { return x._row === row; })[0] || { 'Request ID': id }) };
  } finally {
    lock.releaseLock();
  }
}

/** Own requests; with team=true (approvers / accounts) everyone's, with each person's earlier advances. */
function advanceRequests(d) {
  const u = me(d);
  const lim = settings().approvalLimit;
  const reqs = rows(REQ);
  if (d.team !== true || !seesAll(u)) {
    return { requests: reqs.filter(function (r) { return owns(u, r); }).map(function (r) { return reqOut(r, u, lim); }).reverse() };
  }
  const claims = rows(LOG);
  const advs = rows(ADV);
  const list = reqs.map(function (r) { return reqOut(r, u, lim); }).reverse().slice(0, 300);
  const history = {};
  list.forEach(function (o) {
    const key = o.memberId || o.member;
    if (!history[key]) {
      const h = advHistory(o.member, o.memberId, claims, advs);
      h.requests = reqs.filter(function (r) { return (clean(r['Employee ID']) || clean(r['Team Member'])) === key; })
        .map(function (r) { return reqOut(r); }).reverse().slice(0, 10);
      history[key] = h;
    }
  });
  return { requests: list, history: history, approvalLimit: lim };
}

function decideAdvance(d) {
  const u = me(d);
  if (!canApprove(u)) throw new Error('Only approvers can approve advances.');
  const approve = d.approve === true;
  const note = clean(d.note, 200);
  if (!approve && note.length < 3) throw new Error('Write why the advance is rejected.');
  const ids = Array.isArray(d.requestIds) ? d.requestIds : [d.requestId];
  const lim = settings().approvalLimit;
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const all = rows(REQ);
    let done = 0;
    const skipped = [];
    ids.forEach(function (id) {
      const r = all.filter(function (x) { return clean(x['Request ID']) === clean(id); })[0];
      if (!r) { skipped.push(id + ': not found'); return; }
      if (owns(u, r)) { skipped.push(id + ': you cannot approve your own advance'); return; }
      if ((clean(r['Status']) || RQ.PENDING) !== RQ.PENDING) { skipped.push(id + ': already ' + clean(r['Status']).toLowerCase()); return; }
      if (!canDecide(u, r, lim)) { skipped.push(id + ': ' + adminReason(r, lim) + ', only Atul or Rahul can approve'); return; }
      setFields(REQ, r._row, { 'Status': approve ? RQ.APPROVED : RQ.REJECTED, 'Approved By': u.name, 'Approved On': now(), 'Remarks': note });
      done++;
    });
    return { done: done, skipped: skipped };
  } finally {
    lock.releaseLock();
  }
}

/** Accounts pays an approved request: it becomes an advance (Advances tab) and the request is Paid. */
function payAdvance(d) {
  const u = me(d);
  if (!isAccounts(u)) throw new Error('Only accounts can pay advances.');
  const via = clean(d.via);
  if (PAY_VIA.indexOf(via) < 0) throw new Error('Choose Cash, UPI or Bank Transfer.');
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const r = rows(REQ).filter(function (x) { return clean(x['Request ID']) === clean(d.requestId); })[0];
    if (!r) throw new Error('Advance request not found.');
    if (clean(r['Status']) === RQ.PAID) return { advanceId: clean(r['Advance ID']), duplicate: true };
    if (clean(r['Status']) !== RQ.APPROVED) throw new Error('This advance is not approved yet.');
    const advId = insertAdvance_(clean(r['Team Member']), money(r['Amount (₹)']), via, d.ref,
      clean(r['Purpose']) + ' (' + clean(r['Request ID']) + ')', u.name, d.date);
    setFields(REQ, r._row, {
      'Status': RQ.PAID, 'Paid On': d.date ? toDate(d.date) : new Date(new Date().setHours(0, 0, 0, 0)), 'Paid By': u.name,
      'Payment Reference': clean(d.ref) ? "'" + clean(d.ref, 60) : '', 'Pay Via': via, 'Advance ID': advId,
    });
    return { advanceId: advId };
  } finally {
    lock.releaseLock();
  }
}
