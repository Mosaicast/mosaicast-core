-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- Repairs privacy pages that V16 was supposed to fix and silently did not.
--
-- V16 replaced a ~900-character passage with `replace()`, which needs a byte-exact match, but guarded on a
-- single bullet line ('`MOSAICAST_SESSION` — cookie (httpOnly). Keeps you signed in.'). Any operator edit
-- anywhere else in that passage — a reworded sentence, a removed line, different line wrapping — still
-- satisfied the guard while the replace matched nothing. Flyway reported V16 applied, no error, no signal.
--
-- The consequence is the part that matters: those sites still tell visitors, in their published privacy
-- policy, that **no consent banner is required** — while the shell renders a consent banner on the same
-- site, because a plugin declared a third-party service. A privacy policy contradicting the page it is on is
-- worse than one that is merely out of date.
--
-- The lesson V16 teaches is applied here: this guards on exactly the text it replaces, and nothing wider. It
-- targets the single false sentence rather than a passage, so it works whatever else the operator changed
-- around it — and if the sentence is already gone (V16 succeeded, or it was rewritten by hand), it matches
-- nothing and changes nothing, which is the same outcome either way.
--
-- The inventory bullets are deliberately left alone. They are stale rather than false, the generated list
-- below the page supersedes them, and rewriting prose an operator may have edited is what caused this.

-- English.
UPDATE legal_page_translation
SET markdown = replace(markdown,
'so **no consent banner is required** (ePrivacy Art. 5(3); in
Germany § 25 TDDDG)',
'so the platform''s own storage needs no consent (ePrivacy Art. 5(3); in
Germany § 25 TDDDG). Content from other companies, where a plugin adds any, is loaded **only after you allow
it** — the privacy settings at the bottom of this page list what is stored and let you allow, refuse or
withdraw')
WHERE locale = 'en'
  AND markdown LIKE '%so **no consent banner is required** (ePrivacy Art. 5(3); in%Germany § 25 TDDDG)%';

-- The same sentence with the whole claim on one line, which is how it reads after a re-wrap.
UPDATE legal_page_translation
SET markdown = replace(markdown,
'so **no consent banner is required**',
'so the platform''s own storage needs no consent. Content from other companies, where a plugin adds any, is
loaded **only after you allow it** — see the privacy settings at the bottom of this page')
WHERE locale = 'en'
  AND markdown LIKE '%so **no consent banner is required**%';

-- German.
UPDATE legal_page_translation
SET markdown = replace(markdown,
'daher ist **kein Cookie-Banner
erforderlich** (ePrivacy Art. 5 Abs. 3; § 25 TDDDG)',
'daher ist für die Speicherung der Plattform selbst keine Einwilligung
erforderlich (ePrivacy Art. 5 Abs. 3; § 25 TDDDG). Inhalte anderer Unternehmen — sofern ein Plugin welche
einbindet — werden **erst nach Ihrer Erlaubnis** geladen; die Datenschutz-Einstellungen am Ende dieser Seite
zeigen, was gespeichert wird, und dort erlauben, verweigern oder widerrufen Sie')
WHERE locale = 'de'
  AND markdown LIKE '%daher ist **kein Cookie-Banner%erforderlich** (ePrivacy Art. 5 Abs. 3; § 25 TDDDG)%';

UPDATE legal_page_translation
SET markdown = replace(markdown,
'daher ist **kein Cookie-Banner erforderlich**',
'daher ist für die Speicherung der Plattform selbst keine Einwilligung erforderlich. Inhalte anderer
Unternehmen — sofern ein Plugin welche einbindet — werden **erst nach Ihrer Erlaubnis** geladen; siehe die
Datenschutz-Einstellungen am Ende dieser Seite')
WHERE locale = 'de'
  AND markdown LIKE '%daher ist **kein Cookie-Banner erforderlich**%';
