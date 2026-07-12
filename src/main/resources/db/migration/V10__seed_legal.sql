-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Seed starter legal pages so a fresh install ships with a reachable imprint + privacy/cookie notice
-- (ARCHITECTURE §12.5/§12.6). The cookies-&-storage section is FACTUAL (the only client storage the core
-- sets); the surrounding prose is a clearly-marked template the operator must complete and have reviewed.
-- Idempotent: ON CONFLICT DO NOTHING never clobbers an admin's own edits or re-seeds on existing databases.

-- ---- Imprint (DE DDG §5 template) ----
WITH p AS (
    INSERT INTO legal_page (id, slug, role_marker, sort_order)
    VALUES (gen_random_uuid(), 'imprint', 'imprint', 10)
    ON CONFLICT (slug) DO NOTHING
    RETURNING id
)
INSERT INTO legal_page_translation (id, page_id, locale, title, markdown)
SELECT gen_random_uuid(), p.id, v.locale, v.title, v.markdown
FROM p, (VALUES
    ('en', 'Imprint', $md$> ⚠ **Template — complete this and have it reviewed.** This starter text is not legal advice.

## Information pursuant to § 5 DDG

[Name / company]
[Street and number]
[Postal code and city]

## Contact

Email: [your email]

## Responsible for content pursuant to § 18 (2) MStV

[Name]
[Address]
$md$),
    ('de', 'Impressum', $md$> ⚠ **Vorlage — bitte ausfüllen und rechtlich prüfen lassen.** Dieser Starttext ist keine Rechtsberatung.

## Angaben gemäß § 5 DDG

[Name / Firma]
[Straße und Hausnummer]
[PLZ und Ort]

## Kontakt

E-Mail: [Ihre E-Mail]

## Verantwortlich für den Inhalt nach § 18 Abs. 2 MStV

[Name]
[Anschrift]
$md$)
) AS v(locale, title, markdown);

-- ---- Privacy & cookies ----
WITH p AS (
    INSERT INTO legal_page (id, slug, role_marker, sort_order)
    VALUES (gen_random_uuid(), 'privacy', 'privacy', 20)
    ON CONFLICT (slug) DO NOTHING
    RETURNING id
)
INSERT INTO legal_page_translation (id, page_id, locale, title, markdown)
SELECT gen_random_uuid(), p.id, v.locale, v.title, v.markdown
FROM p, (VALUES
    ('en', 'Privacy Policy', $md$> ⚠ **Template — complete this and have it reviewed.** This starter text is not legal advice.

## Controller

[Name and contact details of the controller — see the Imprint.]

## Cookies and local storage

This site uses only **strictly necessary and functional** first-party storage. No tracking, advertising or
third-party cookies are set by the platform, so **no consent banner is required** (ePrivacy Art. 5(3); in
Germany § 25 TDDDG). We still list everything for transparency:

- `MOSAICAST_SESSION` — cookie (httpOnly). Keeps you signed in. Strictly necessary; no consent.
- `XSRF-TOKEN` — cookie. Protects against cross-site request forgery. Strictly necessary; no consent.
- `mc.locale` — local storage. Remembers your chosen language. Functional; no consent.
- `mc.site` — local storage. Caches the site theme for a fast first paint. Functional; no consent.
- `mc.progress.*` — local storage. Remembers your playback position per episode. Functional; no consent.

If a plugin later introduces cookies or third-party content that are not strictly necessary, the site will
ask for your consent before loading them.

## Your rights

[Access, rectification, erasure, etc. — complete for your jurisdiction.]
$md$),
    ('de', 'Datenschutzerklärung', $md$> ⚠ **Vorlage — bitte ausfüllen und rechtlich prüfen lassen.** Dieser Starttext ist keine Rechtsberatung.

## Verantwortlicher

[Name und Kontaktdaten des Verantwortlichen — siehe Impressum.]

## Cookies und lokaler Speicher

Diese Seite verwendet ausschließlich **unbedingt erforderliche und funktionale** Erstanbieter-Speicher. Die
Plattform setzt keine Tracking-, Werbe- oder Drittanbieter-Cookies, daher ist **kein Cookie-Banner
erforderlich** (ePrivacy Art. 5 Abs. 3; § 25 TDDDG). Zur Transparenz listen wir dennoch alles auf:

- `MOSAICAST_SESSION` — Cookie (httpOnly). Hält Sie angemeldet. Unbedingt erforderlich; keine Einwilligung.
- `XSRF-TOKEN` — Cookie. Schutz vor Cross-Site-Request-Forgery. Unbedingt erforderlich; keine Einwilligung.
- `mc.locale` — lokaler Speicher. Merkt sich Ihre Sprache. Funktional; keine Einwilligung.
- `mc.site` — lokaler Speicher. Zwischenspeichert das Theme für schnellen Seitenaufbau. Funktional; keine Einwilligung.
- `mc.progress.*` — lokaler Speicher. Merkt sich die Wiedergabeposition je Folge. Funktional; keine Einwilligung.

Sollte künftig ein Plugin nicht unbedingt erforderliche Cookies oder Drittinhalte einbinden, fragt die Seite
vor dem Laden nach Ihrer Einwilligung.

## Ihre Rechte

[Auskunft, Berichtigung, Löschung usw. — für Ihre Rechtsordnung ergänzen.]
$md$)
) AS v(locale, title, markdown);
