// Rewrites the web client's index.html for the bundled Android shell. Accepts LF or CRLF checkouts.
export function nativeIndexHtml(html) {
  for (const [before, after] of [
    [/<link rel="manifest" href="\/manifest\.webmanifest">\r?\n/, ''],
    ['src="/app.js"', 'src="./native-shell.js"'],
    ['href="/style.css"', 'href="./style.css"'],
    ['href="/icon-192.png"', 'href="./icon-192.png"'],
    ['href="/" aria-label="Little Garden home"', 'href="./" aria-label="Little Garden home"']
  ]) {
    const found = typeof before === 'string' ? html.includes(before) : before.test(html);
    if (!found) throw new Error(`Source UI changed: missing ${before}`);
    html = html.replace(before, after);
  }
  return html;
}
