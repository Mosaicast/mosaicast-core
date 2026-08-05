-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The seeded privacy page described the core's client storage by hand (V10). It had already drifted — it
-- never mentioned `mc.consent`, and it predates `mc.prefs.progress` — which is the predictable outcome of
-- keeping prose in step with code by discipline. Its framing has drifted too: it states flatly that no
-- consent banner is required, which stopped being unconditionally true the moment a plugin could declare a
-- third-party service.
--
-- The shell now appends the real, generated inventory below any page marked `privacy`
-- (CoreStorageInventory → GET /api/consent → CookieSettings), so this replaces the hand-written passage with
-- a pointer to it.
--
-- Deliberately a targeted `replace()` rather than a rewrite of the page: an operator is expected to have
-- edited this template — it ships with "complete this and have it reviewed" at the top — so their controller
-- details, their rights section and any wording they changed must survive. Where the passage has already
-- been edited, the replace matches nothing and the row is left exactly as it is.

UPDATE legal_page_translation
SET markdown = replace(markdown,
'This site uses only **strictly necessary and functional** first-party storage. No tracking, advertising or
third-party cookies are set by the platform, so **no consent banner is required** (ePrivacy Art. 5(3); in
Germany § 25 TDDDG). We still list everything for transparency:

- `MOSAICAST_SESSION` — cookie (httpOnly). Keeps you signed in. Strictly necessary; no consent.
- `XSRF-TOKEN` — cookie. Protects against cross-site request forgery. Strictly necessary; no consent.
- `mc.locale` — local storage. Remembers your chosen language. Functional; no consent.
- `mc.site` — local storage. Caches the site theme for a fast first paint. Functional; no consent.
- `mc.progress.*` — local storage. Remembers your playback position per episode. Functional; no consent.

If a plugin later introduces cookies or third-party content that are not strictly necessary, the site will
ask for your consent before loading them.',
'The platform itself sets no tracking or advertising cookies: it stores only what is **strictly necessary or
functional** for the service you asked for (ePrivacy Art. 5(3); in Germany § 25 TDDDG). Content provided by
other companies, where this site uses any, is loaded **only after you allow it**.

The complete, current list of what is stored — by us and by anyone else — is shown in the privacy settings
at the bottom of this page. It is generated from what the site actually does, so it cannot fall out of date,
and it is where you allow, refuse or withdraw. Remembering your playback position can be switched off
there too.')
WHERE locale = 'en'
  AND markdown LIKE '%`MOSAICAST_SESSION` — cookie (httpOnly). Keeps you signed in.%';

UPDATE legal_page_translation
SET markdown = replace(markdown,
'Diese Seite verwendet ausschließlich **unbedingt erforderliche und funktionale** Erstanbieter-Speicher. Die
Plattform setzt keine Tracking-, Werbe- oder Drittanbieter-Cookies, daher ist **kein Cookie-Banner
erforderlich** (ePrivacy Art. 5 Abs. 3; § 25 TDDDG). Zur Transparenz listen wir dennoch alles auf:

- `MOSAICAST_SESSION` — Cookie (httpOnly). Hält Sie angemeldet. Unbedingt erforderlich; keine Einwilligung.
- `XSRF-TOKEN` — Cookie. Schutz vor Cross-Site-Request-Forgery. Unbedingt erforderlich; keine Einwilligung.
- `mc.locale` — lokaler Speicher. Merkt sich Ihre Sprache. Funktional; keine Einwilligung.
- `mc.site` — lokaler Speicher. Zwischenspeichert das Theme für schnellen Seitenaufbau. Funktional; keine Einwilligung.
- `mc.progress.*` — lokaler Speicher. Merkt sich die Wiedergabeposition je Folge. Funktional; keine Einwilligung.

Sollte künftig ein Plugin nicht unbedingt erforderliche Cookies oder Drittinhalte einbinden, fragt die Seite
vor dem Laden nach Ihrer Einwilligung.',
'Die Plattform selbst setzt keine Tracking- oder Werbe-Cookies: Sie speichert nur, was für den von Ihnen
gewünschten Dienst **unbedingt erforderlich oder funktional** ist (ePrivacy Art. 5 Abs. 3; § 25 TDDDG).
Inhalte anderer Unternehmen — sofern diese Seite welche einbindet — werden **erst nach Ihrer Erlaubnis**
geladen.

Die vollständige, aktuelle Liste dessen, was gespeichert wird — von uns wie von anderen — steht in den
Datenschutz-Einstellungen am Ende dieser Seite. Sie wird aus dem erzeugt, was die Seite tatsächlich tut, und
kann deshalb nicht veralten; dort erlauben, verweigern oder widerrufen Sie auch. Das Merken der Hörposition
lässt sich ebenfalls dort abschalten.')
WHERE locale = 'de'
  AND markdown LIKE '%`MOSAICAST_SESSION` — Cookie (httpOnly). Hält Sie angemeldet.%';
