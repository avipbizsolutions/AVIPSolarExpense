/**
 * AVIP Auth client: paste this file (as a second file, "AuthClient.gs") into the
 * Apps Script of every AVIP Solar app backend. It talks to the AVIP Auth web app
 * that sits on the AVIP Team Directory.
 *
 * In that backend's Apps Script: Project Settings → Script properties → add
 *   AUTH_URL = the /exec URL of the AVIP Auth web app
 *   AUTH_KEY = the SERVER KEY printed by AVIP Auth's setup
 * (They are kept in script properties, not in code, because the code is on GitHub.)
 */

function authCall_(app, kind, body) {
  const p = PropertiesService.getScriptProperties();
  const url = p.getProperty('AUTH_URL');
  const key = p.getProperty('AUTH_KEY');
  if (!url || !key) throw new Error('Login service is not set up on the office server (AUTH_URL / AUTH_KEY).');
  const payload = Object.assign({}, body || {}, { serverKey: key, kind: kind, app: app });
  let text = '';
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = UrlFetchApp.fetch(url, {
        method: 'post', contentType: 'application/json', payload: JSON.stringify(payload),
        muteHttpExceptions: true, followRedirects: true,
      });
      text = res.getContentText();
      break;
    } catch (e) {
      if (attempt === 1) throw new Error('Login service did not answer. Try again in a minute.');
      Utilities.sleep(800);
    }
  }
  let o;
  try { o = JSON.parse(text); } catch (_) { throw new Error('Login service did not answer properly. Try again in a minute.'); }
  if (!o.ok) throw new Error(o.error || 'Login failed.');
  return o;
}

/** Forwards the phone's login to AVIP Auth. */
function authLogin_(app, d) {
  return authCall_(app, 'login', { mobile: d.mobile, pin: d.pin, deviceId: d.deviceId, appVersion: d.appVersion });
}

function authSetPin_(app, d) {
  return authCall_(app, 'setPin', { token: d.token, newPin: d.newPin });
}

/**
 * Checks the phone's session token. Returns the user:
 * { id, name, mobile, designation, team, role, roles, mustSetPin }.
 * Throws "SESSION: …" when the phone must log in again.
 */
function authUser_(app, d, allowTempPin) {
  const tok = String(d.token || '').trim();
  if (!tok) throw new Error('SESSION: Please login.');
  // A checked login is remembered here for a few minutes, so most requests need no
  // extra call to AVIP Auth. A disabled person or a new PIN still takes effect within that time.
  const cache = CacheService.getScriptCache();
  const key = 'au_' + app + '_' + Utilities.base64EncodeWebSafe(
    Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, tok)).slice(0, 40);
  let r = null;
  const hit = cache.get(key);
  if (hit) { try { r = JSON.parse(hit); } catch (_) { r = null; } }
  if (!r) {
    r = authCall_(app, 'verify', { token: tok });
    if (!r.mustSetPin) cache.put(key, JSON.stringify({ user: r.user, mustSetPin: false }), 300);
  }
  const u = r.user;
  u.mustSetPin = r.mustSetPin;
  if (!allowTempPin && r.mustSetPin) throw new Error('SESSION: Please set your own PIN first.');
  return u;
}

/** Active people who can use this app: [{ id, name, mobile, team, designation, role }]. Cached 10 minutes. */
function authUsers_(app) {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('authUsers_' + app);
  if (hit) return JSON.parse(hit);
  const list = authCall_(app, 'users', {}).users || [];
  cache.put('authUsers_' + app, JSON.stringify(list), 600);
  return list;
}
