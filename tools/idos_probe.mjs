// Zjišťuje, jak IDOS pojmenovává zastávky: zkouší odkazy na vyhledání spojení a našeptávač.
import { chromium } from 'playwright';
import fs from 'fs';
const out = [];
const log = (...a) => { const s = a.join(' '); console.log(s); out.push(s); };
const b = await chromium.launch();
const p = await b.newPage({ locale: 'cs-CZ' });
const net = [];
p.on('response', async r => { const u = r.url(); if (/idos\.cz/.test(u) && !/\.(png|svg|css|js|woff2?)(\?|$)/.test(u)) net.push(r.status() + ' ' + u.slice(0, 250)); });
const pairs = [
  ['Praha,Hlavní nádraží', 'Brno,Hlavní nádraží'],
  ['Hlavní nádraží', 'Brno,Hlavní nádraží'],
  ['Praha hl.n.', 'Olomouc hl.n.'],
  ['Žerotín,u kaple', 'Olomouc hl.n.'],
  ['Žerotín, u kaple', 'Olomouc hl. n.'],
  ['Boskovice,Obora', 'Praha hl.n.'],
  ['Obora', 'Praha hl.n.'],
  ['Dolní Břežany,Lhota', 'Písnice'],
];
for (const fc of ['1', '']) for (const [f, t] of pairs) {
  const q = new URLSearchParams({ f, t, date: '19.10.2026', time: '12:00' });
  if (fc) { q.set('fc', fc); q.set('tc', fc); }
  const url = 'https://idos.cz/vlakyautobusymhdvse/spojeni/vysledky/?' + q.toString();
  try {
    await p.goto(url, { waitUntil: 'networkidle', timeout: 45000 });
    await p.waitForTimeout(1500);
    const txt = (await p.locator('body').innerText()).replace(/\s+/g, ' ');
    const err = (txt.match(/(Objekt nebyl nalezen|Upřesněte[^.]*\.|nebyl nalezen[^.]*\.)/g) || []).join(' | ');
    const first = (txt.match(/\d{1,2}:\d{2}[^]{0,160}/) || [''])[0];
    log(`fc=${fc || '-'} | ${f} → ${t} | ${err || 'OK'} | ${p.url().slice(0, 140)} | ${first.slice(0, 160)}`);
  } catch (e) { log(`fc=${fc} | ${f} → ${t} | CHYBA ${e.message.slice(0, 100)}`); }
}
// našeptávač
await p.goto('https://idos.cz/vlakyautobusymhdvse/spojeni/', { waitUntil: 'networkidle' });
try { await p.getByRole('button', { name: /Souhlas|Přijmout|OK/i }).first().click({ timeout: 3000 }); } catch {}
const inputs = await p.locator('input[type=text], input:not([type])').all();
log('vstupních polí', inputs.length);
for (const word of ['Žerotín', 'Hlavní nádraží', 'Obora', 'Olomouc hl']) {
  net.length = 0;
  try {
    const inp = p.locator('input').first();
    await inp.fill(''); await inp.type(word, { delay: 80 }); await p.waitForTimeout(2500);
    const items = await p.locator('[role=option], .ui-menu-item, li[class*=item], .autocomplete li, ul[role=listbox] li').allInnerTexts();
    log('NAŠEPTÁVAČ', word, '→', items.slice(0, 12).map(x => x.replace(/\s+/g, ' ')).join(' ; '));
  } catch (e) { log('NAŠEPTÁVAČ chyba', e.message.slice(0, 100)); }
  log('  síť:', net.slice(-6).join(' || '));
}
fs.mkdirSync('o', { recursive: true });
fs.writeFileSync('o/idos.txt', out.join('\n'));
await p.screenshot({ path: 'o/idos.png' });
await b.close();
