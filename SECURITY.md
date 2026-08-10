# Security Policy

Please report vulnerabilities **privately** via GitHub Security Advisories on this repository ("Security" tab -> "Report a vulnerability") — not as public issues. We aim to respond within 14 days. Please include reproduction steps and affected versions.

## Known residuals

Things a review will find, that we know about and have decided to keep. Each is a trade we would make again;
if you can show the trade is wrong, that is worth a report.

### `style-src 'unsafe-inline'`

Plugin UIs are Web Components that style themselves with a `<style>` block inside their shadow root, and
shadow-DOM styles are governed by the *document's* CSP. Dropping this breaks every plugin that styles itself
— the plugin UI contract, not an edge of it — and nonces do not rescue it, because a plugin's bundle
constructs its shadow root at runtime with no per-response nonce to carry.

What it costs: inline CSS wherever attacker-influenced HTML is rendered, which is show notes and feed
descriptions. That is closed on the **sanitizer** side instead — the shell strips `<style>` and `style` from
feed HTML before insertion (`frontend/src/util/sanitize.ts`), and legal pages are server-sanitized with jsoup
`Safelist.relaxed()` from admin-authored input. `script-src` stays `'self'` with no `unsafe-inline` or
`unsafe-eval`.

### Image and media sources are open by default

`img-src 'self' data: https:` allows an image from any host, which makes an `<img>` a working one-way
exfiltration and tracking channel that side-steps `connect-src 'self'` and the consent framework. It is open
because episode artwork and audio come from whatever host the podcaster's feed points at.

`mosaicast.security.strict-media-sources=true` narrows both directives to the origins the site's own content
references, plus the plugin hosts the visitor consented to, with
`mosaicast.security.extra-media-sources` for anything the derivation cannot see. Recommended for any install
whose feed set is stable.

### DNS rebinding against outbound feed fetches (TOCTOU)

`OutboundTargetPolicy` resolves a feed's host and requires every returned address to be publicly routable,
then the JDK `HttpClient` resolves again at connection time. A name answering public at check-time and
private at fetch-time lands internally. Closing it needs the connection pinned to the checked address, which
the JDK does not expose; doing it by hand means connecting to an IP literal with a forced `Host` header and
an SNI override, which breaks TLS to legitimate feeds in ways that are hard to predict.

Redirects are re-checked per hop, so the easy version of the bypass is closed. **An operator who needs the
racy version closed wants an egress proxy** — this is a deployment control, not an application one.

`mosaicast.feed.allow-private-targets` disables the address check entirely. It requires
`mosaicast.feed.allow-private-targets-confirmed` as well, and the app refuses to start otherwise.

### PODCASTER is a trusted outbound-fetch role

`/api/admin/feeds/**` is ADMIN **or PODCASTER**, on reads and writes alike (ARCHITECTURE §8.5 — feeds are a
podcaster capability). That includes `POST /api/admin/feeds/preview`, which dereferences a URL server-side
and returns the fetched channel and item titles, and `POST /api/admin/feeds`, which fetches immediately and
publishes the episodes. Both are bounded by `OutboundTargetPolicy` and by nothing else.

This is deliberate: PODCASTER is a role an admin grants explicitly. Gating preview alone would close nothing,
since create is the stronger read primitive. `FeedAdminAuthzMatrixIntegrationTest` pins the matrix.

Note for anyone measuring it: an unsafe method without a valid CSRF token returns **403 before authorization
is consulted**, which is indistinguishable from a role refusal by status code alone. Take a fresh token after
logging in.

### Shared plugin documents have no owner

Authorization on the plugin doc store is per *plugin*, not per *document*. `data.backendOwned` reserves the
keys a plugin's backend authors, and the `USER` scope gives per-user data a partition no request can name —
but anything else in a shared scope (`site`, `feed`, `season`, `episode`) can be overwritten or deleted by
any caller above the plugin's `writableBy` floor, including one another session wrote. On a multi-podcaster
install that is one tenant able to tamper with the others' plugin data.

Binding a shared document to its author needs an ownership concept the domain model does not have (feeds
have no owner column). Until then: reserve the key with `backendOwned`, or keep the data in `USER` scope.
