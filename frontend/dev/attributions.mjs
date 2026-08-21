// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * What Mosaicast is built on — the source of truth for the "Built with" section of `/about` and for the
 * index in `THIRD-PARTY-NOTICES.md`. Both are generated from this file (`npm run attributions`), so the
 * page and the notice cannot drift apart; attribution that quietly stops matching what ships is the
 * failure mode nobody notices for a year.
 *
 * **Curated, and deliberately generous.** This is not a machine-generated transitive dependency dump — it
 * names the projects a reader would recognise and that we would want to thank, with a line on what each
 * actually does *here*. Add anything that carries real weight: over-attributing costs a few lines in this
 * file, under-attributing costs someone their credit.
 *
 * A plain module rather than YAML or JSON so it needs no parser and no dependency, and so entries can
 * carry comments like this one.
 *
 * - `note`    what it does *for us*, in plain language — not a description lifted from its homepage.
 * - `scope`   `runtime` ships inside the artefacts; `build` is used to make them and never shipped.
 * - `license` SPDX. Frontend values were read out of each package's own `package.json` rather than
 *             recalled from memory; keep it that way when you add one.
 *
 * Obligations that require a licence text reproduced *in full* live in the hand-written part of
 * `THIRD-PARTY-NOTICES.md`. This is the credit list, not the legal record.
 *
 * @type {{ name: string, version?: string, license: string, url: string,
 *          scope: 'runtime' | 'build', note: string }[]}
 */
export default [
  // ---- backend ----
  {
    name: 'Spring Boot',
    version: '4.1.0',
    license: 'Apache-2.0',
    url: 'https://spring.io/projects/spring-boot',
    scope: 'runtime',
    note: 'The application framework the whole backend is built on.',
  },
  {
    name: 'Spring Security',
    license: 'Apache-2.0',
    url: 'https://spring.io/projects/spring-security',
    scope: 'runtime',
    note: 'Authentication, OAuth2 login, CSRF, and the deny-by-default API rules.',
  },
  {
    name: 'Hibernate ORM',
    license: 'Apache-2.0',
    url: 'https://hibernate.org/orm/',
    scope: 'runtime',
    note: 'Maps episodes, feeds and plugin data onto PostgreSQL.',
  },
  {
    name: 'PostgreSQL',
    license: 'PostgreSQL',
    url: 'https://www.postgresql.org/',
    scope: 'runtime',
    note: 'Stores everything — including plugin documents, as JSONB.',
  },
  {
    name: 'Flyway',
    license: 'Apache-2.0',
    url: 'https://flywaydb.org/',
    scope: 'runtime',
    note: 'Every schema change the platform makes, versioned and repeatable.',
  },
  {
    name: 'PF4J',
    version: '3.15.0',
    license: 'Apache-2.0',
    url: 'https://pf4j.org/',
    scope: 'runtime',
    note: 'Loads plugin backends as isolated extensions.',
  },
  {
    name: 'ROME',
    version: '2.1.0',
    license: 'Apache-2.0',
    url: 'https://rometools.github.io/rome/',
    scope: 'runtime',
    note: 'Parses podcast RSS, including the iTunes season and episode tags.',
  },
  {
    name: 'Apache Commons Text',
    version: '1.15.0',
    license: 'Apache-2.0',
    url: 'https://commons.apache.org/proper/commons-text/',
    scope: 'runtime',
    note: 'Jaro-Winkler similarity, used to match announced episodes to published ones.',
  },
  {
    name: 'ShedLock',
    version: '7.7.0',
    license: 'Apache-2.0',
    url: 'https://github.com/lukas-krecan/ShedLock',
    scope: 'runtime',
    note: 'Stops two instances running the same scheduled job at once.',
  },
  {
    name: 'jsoup',
    version: '1.23.1',
    license: 'MIT',
    url: 'https://jsoup.org/',
    scope: 'runtime',
    note: 'Sanitises show notes, legal pages and uploaded artwork — why feed HTML is safe to render.',
  },
  {
    name: 'commonmark-java',
    version: '0.30.0',
    license: 'BSD-2-Clause',
    url: 'https://github.com/commonmark/commonmark-java',
    scope: 'runtime',
    note: "Renders the markdown behind legal pages and this instance's About text.",
  },
  {
    name: 'Jackson',
    license: 'Apache-2.0',
    url: 'https://github.com/FasterXML/jackson',
    scope: 'runtime',
    note: 'Reads every plugin manifest and writes every API response.',
  },
  {
    name: 'Eclipse Temurin',
    license: 'GPL-2.0-with-classpath-exception',
    url: 'https://adoptium.net/',
    scope: 'runtime',
    note: 'The Java runtime the server runs on.',
  },

  // ---- frontend ----
  {
    name: 'React',
    version: '18.3.1',
    license: 'MIT',
    url: 'https://react.dev/',
    scope: 'runtime',
    note: 'The shell you are reading this in.',
  },
  {
    name: 'React Router',
    version: '7.18.1',
    license: 'MIT',
    url: 'https://reactrouter.com/',
    scope: 'runtime',
    note: 'Routing, including the deep links plugins get under /p/.',
  },
  {
    name: 'i18next',
    version: '24.2.3',
    license: 'MIT',
    url: 'https://www.i18next.com/',
    scope: 'runtime',
    note: 'Serves the interface in English and German.',
  },
  {
    name: 'DOMPurify',
    version: '3.4.11',
    license: 'MPL-2.0 OR Apache-2.0',
    url: 'https://github.com/cure53/DOMPurify',
    scope: 'runtime',
    note: 'The client-side half of keeping third-party HTML from executing.',
  },
  {
    name: 'Bootstrap Icons',
    version: '1.13.1',
    license: 'MIT',
    url: 'https://icons.getbootstrap.com/',
    scope: 'runtime',
    note: 'Every icon in the interface, and the palette plugins draw from.',
  },

  // ---- build & test ----
  {
    name: 'Vite',
    version: '6.4.3',
    license: 'MIT',
    url: 'https://vite.dev/',
    scope: 'build',
    note: 'Builds the shell bundle.',
  },
  {
    name: 'TypeScript',
    version: '5.9.3',
    license: 'Apache-2.0',
    url: 'https://www.typescriptlang.org/',
    scope: 'build',
    note: 'Types the shell and the plugin contract.',
  },
  {
    name: 'Gradle',
    license: 'Apache-2.0',
    url: 'https://gradle.org/',
    scope: 'build',
    note: 'Builds and tests the backend.',
  },
  {
    name: 'JUnit',
    license: 'EPL-2.0',
    url: 'https://junit.org/',
    scope: 'build',
    note: 'Runs the backend test suite.',
  },
  {
    name: 'AssertJ',
    license: 'Apache-2.0',
    url: 'https://assertj.github.io/doc/',
    scope: 'build',
    note: 'The assertions those tests are written in.',
  },
  {
    name: 'Testcontainers',
    license: 'MIT',
    url: 'https://testcontainers.com/',
    scope: 'build',
    note: 'Gives the integration tests a real PostgreSQL instead of a mock.',
  },
  {
    name: 'Vitest',
    version: '2.1.9',
    license: 'MIT',
    url: 'https://vitest.dev/',
    scope: 'build',
    note: 'Runs the frontend test suite.',
  },
  {
    name: 'Testing Library',
    version: '16.3.2',
    license: 'MIT',
    url: 'https://testing-library.com/',
    scope: 'build',
    note: 'Lets those tests assert on what a user would actually see.',
  },
];
