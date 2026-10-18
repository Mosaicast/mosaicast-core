<!--
SPDX-License-Identifier: AGPL-3.0-or-later
SPDX-FileCopyrightText: 2026 The Mosaicast Authors
-->

# Writing for Mosaicast

How every text a person reads in the product sounds: the shell, the admin, the edge pages, and every
plugin. The same guide ships with the plugin skill (`mosaicast-skills`, `writing-style.md`), so
third-party plugins sound like the rest of the site.

## The voice

**Friendly plain.** Write like someone who runs the site and explains it to a friend: short, warm, direct.
Plausible, Overcast and Basecamp are good models.

1. **Say what it does or what happens. Then stop.** Give a reason only when it changes what the reader
   does next. Background belongs in the docs, a code comment or the PR.
2. **Short sentences, one idea each.** Put the key word first. Active voice.
3. **Talk to the reader.** "you" in English, **"du" in German, everywhere** (visitors, accounts, admin).
   English uses contractions: can't, you'll, it's, don't.
4. **Say the positive thing.** No "not X but Y", "X, not Y", "rather than", "instead of" set-ups.
   "Only the web player counts." beats "This isn't app plays, rather web plays."
5. **No dashes as punctuation.** No em dash (—), no en dash (–) between clauses, no " - ". Use a period
   or a comma. En dashes in ranges ("1–5") are fine.
6. **No examples in hints**, unless the format is not obvious (a URL, a pattern). Then one, after a colon.
7. **No history.** No "new", "now", "used to", "since 0.8". The changelog says what changed.
8. **No filler.** Never: simply, just, actually, exactly, really, deliberately, by design, note that,
   please note, the point is, ensure, seamless, robust, powerful, leverage, effortless.
   German: einfach, eigentlich, genau, bewusst, wirklich, selbstverständlich.
9. **Numbers over adjectives.** "Up to 64 characters" beats "a short address".
10. **Sentence case** for buttons, labels, headings and menu entries.

## Length budgets

| Kind | Budget |
|---|---|
| Button, menu entry | 1–3 words, a verb first: "Save", "Add feed" |
| Label, column, heading | up to 4 words |
| Hint under a field | one sentence, about 100 characters |
| Intro under a page title | one or two sentences |
| Confirm dialog | the action as a question, what goes with it, "You can't undo this." |
| Error | what happened, then what to do. Two short sentences at most. |
| Empty state | what will show here, and how to get it there |

## Who reads it

- **Visitors** (pages, player, consent, sign-in): never say *plugin*, *slot*, *manifest* or *host*.
  Say *service*, *provider*, *stored on your device*. Name the company, not the hostname. Purposes are
  plain outcomes ("Keeps you signed in").
- **Podcasters and admins**: *plugin*, *feed*, *episode* are fine. Name an environment variable only when
  the admin has to set it, and only once.
- **Legal and consent text** follows the same voice, but it must stay complete: every purpose, every
  company, how long, how to withdraw. Short beats long only when nothing is lost.

## German

- **du**, lower case ("du", "dein"). Never "Sie" in the product.
- Verbs over nouns: "Wird gespeichert, sobald du Play drückst" beats "Die Speicherung erfolgt bei
  Wiedergabe".
- Common German words over English ones: Folge, Staffel, Einstellungen, Hochladen. Keep the words people
  use in German anyway: Podcast, Feed, Plugin, Tag, Login, Shownotes.
- Neutral forms over gender marks: "alle", "wer", "Hörende", "Personen". Use ":innen" only when no
  neutral word exists.
- Translate the meaning, not the sentence. The German may be shorter or longer, never wordier.

## Before and after

| Before | After |
|---|---|
| Reloads count. Plays in podcast apps go straight from the feed to your audio host and never reach this site, so only the web player is counted. There are no unique visitors, sessions or returning visitors here, by design: those would need to recognise people. | Only the web player counts. Plays in podcast apps go straight to your audio host and never show up here. Reloads count as views. |
| Delete "{{title}}" and its {{count}} episodes? Their show notes, tags, everyone's listening position in them and anything plugins stored about them go too. The tag vocabulary stays, since other feeds may use it. This cannot be undone. | Delete "{{title}}" with all {{count}} episodes? Show notes, tags, listening progress and plugin data go with it. You can't undo this. |
| This site stores what it needs to work: your session, your language, and this choice. Some content is loaded from other companies, which store things on your device too — that part is up to you. | This site stores what it needs to work: your session, your language and this choice. Some content comes from other companies that store things on your device too. You decide about those. |
| That episode already has plugin data of its own (for example a bingo or a poll), so it cannot be merged into the planned one without losing something. Remove that data first, or keep both episodes. | That episode already has its own plugin data, so merging would lose it. Remove the data first, or keep both episodes. |
| A robots.txt rule is a request, not a barrier. Well-behaved crawlers honour it; nothing here can stop one that ignores it. | Crawlers can ignore robots.txt. Most well-known ones follow it. |
| Diese Seite speichert, was sie zum Funktionieren braucht: Ihre Sitzung, Ihre Sprache und diese Entscheidung. Manche Inhalte werden von anderen Unternehmen geladen, die dabei ebenfalls etwas auf Ihrem Gerät speichern — darüber entscheiden Sie. | Diese Seite speichert, was sie zum Laufen braucht: deine Sitzung, deine Sprache und diese Auswahl. Manche Inhalte kommen von anderen Firmen, die auch etwas auf deinem Gerät speichern. Darüber entscheidest du. |

## Checklist before you commit a string

- Would a friend say it like this out loud?
- Can a word go without losing meaning? Then it goes.
- Any dash, "not … but", "rather than", or a word from the filler list? Rewrite.
- Both locales changed, same keys, same `{{placeholders}}`, every plural form.
- Visitor text free of *plugin*, *slot*, *manifest*, *host*?
