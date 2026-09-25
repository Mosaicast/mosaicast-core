-- SPDX-License-Identifier: AGPL-3.0-or-later
-- SPDX-FileCopyrightText: 2026 The Mosaicast Authors
--
-- The seeded privacy page (V16) called the generated inventory "the complete, current list of what is
-- stored — by us and by anyone else". It listed browser storage only; a signed-in listener's email address,
-- name, avatar reference and playback position, all kept in the database, were nowhere on it (core#176).
-- The settings now list what core keeps in an account as well, and this rewords the sentence to say what the
-- lists cover rather than claim a completeness they do not have: data a plugin's own backend keeps is the
-- operator's to describe in their own words.
--
-- The same targeted `replace()` as V16, for the same reason: an operator is expected to have edited this
-- template, and a passage they changed matches nothing and is left exactly as they wrote it.

UPDATE legal_page_translation
SET markdown = replace(markdown,
'The complete, current list of what is stored — by us and by anyone else — is shown in the privacy settings
at the bottom of this page. It is generated from what the site actually does, so it cannot fall out of date,
and it is where you allow, refuse or withdraw.',
'What this site stores on your device — itself and on behalf of other companies — and what it keeps in your
account when you are signed in is listed in the privacy settings at the bottom of this page. Those lists are
generated from what the site actually does, so they cannot fall out of date, and they are where you allow,
refuse or withdraw.')
WHERE locale = 'en'
  AND markdown LIKE '%The complete, current list of what is stored — by us and by anyone else —%';

UPDATE legal_page_translation
SET markdown = replace(markdown,
'Die vollständige, aktuelle Liste dessen, was gespeichert wird — von uns wie von anderen — steht in den
Datenschutz-Einstellungen am Ende dieser Seite. Sie wird aus dem erzeugt, was die Seite tatsächlich tut, und
kann deshalb nicht veralten; dort erlauben, verweigern oder widerrufen Sie auch.',
'Was diese Seite auf Ihrem Gerät speichert — selbst und im Auftrag anderer Unternehmen — und was sie in Ihrem
Konto aufbewahrt, wenn Sie angemeldet sind, steht in den Datenschutz-Einstellungen am Ende dieser Seite.
Diese Listen werden aus dem erzeugt, was die Seite tatsächlich tut, und können deshalb nicht veralten; dort
erlauben, verweigern oder widerrufen Sie auch.')
WHERE locale = 'de'
  AND markdown LIKE '%Die vollständige, aktuelle Liste dessen, was gespeichert wird%';
