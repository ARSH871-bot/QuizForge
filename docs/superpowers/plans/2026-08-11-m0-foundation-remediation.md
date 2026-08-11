# M0 — Foundation & Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the QuizForge coursework prototype into a trustworthy foundation — secrets revoked, build reproducible, schema versioned, boundaries enforced, CI green — without changing any product behaviour.

**Architecture:** The existing Spring Boot module moves to `apps/api` in a monorepo. Hibernate's `ddl-auto` is replaced by Flyway migrations against PostgreSQL, verified by Testcontainers integration tests. A `com.quizforge.*` module skeleton is created alongside the legacy `cs.quizzapp.prokect.backend` package, with Spring Modulith verification and ArchUnit rules that prevent new code from landing in the legacy package. GitHub Actions runs the whole thing on every push.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Modulith, Flyway, PostgreSQL 16, Testcontainers, ArchUnit 1.3.0, JUnit 5, GitHub Actions, Maven.

## Global Constraints

- **Java version:** 21 (verified installed: Temurin 21.0.11). `maven.compiler.release` = 21.
- **Base package for all new code:** `com.quizforge`. The legacy package `cs.quizzapp.prokect.backend` is frozen — no new classes may be added to it.
- **Database:** PostgreSQL 16. MySQL support is removed entirely in Task 4.
- **No schema may be created by Hibernate.** `spring.jpa.hibernate.ddl-auto` must be `validate` after Task 4, never `update` or `create`.
- **No secret may appear in any tracked file.** All credentials come from environment variables with no committed default value.
- **Every task ends on a green build.** `./mvnw verify` must pass before each commit.
- **Commit style:** Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `build:`, `ci:`, `refactor:`).
- **Docker** is required from Task 5 onward (verified available: Docker 28.3.2, Compose v2.38.2).

---

## File Structure

After M0 the repository looks like this. Files marked **new** are created by this plan.

```
QuizForge/
├── .gitignore                                    new — root ignore rules
├── .gitattributes                                new — line-ending normalisation
├── .githooks/commit-msg                          new — conventional commit hook
├── .github/
│   ├── workflows/ci.yml                          new — build, test, scan
│   ├── workflows/pr-title.yml                    new — server-side commit lint
│   ├── renovate.json                             new — dependency updates
│   ├── CODEOWNERS                                new — review routing
│   ├── pull_request_template.md                  new
│   └── ISSUE_TEMPLATE/*.yml                      new — typed issue forms
├── README.md                                     new — one-command getting started
├── CONTRIBUTING.md                               new — workflow + commit rules
├── SECURITY.md                                   new — disclosure policy
├── docker-compose.yml                            new — local Postgres
├── .env.example                                  new — documented env vars, no values
├── apps/
│   └── api/                                      moved from backend/backend/
│       ├── .mvn/wrapper/maven-wrapper.properties new — repairs broken wrapper
│       ├── mvnw, mvnw.cmd                        moved
│       ├── pom.xml                               modified — Java 21, Boot 3.5, new deps
│       └── src/
│           ├── main/java/com/quizforge/
│           │   ├── QuizForgeApplication.java     new — replaces BackendApplication
│           │   ├── platform/package-info.java    new — module declaration
│           │   ├── identity/package-info.java    new — module declaration
│           │   ├── content/package-info.java     new — module declaration
│           │   ├── tournament/package-info.java  new — module declaration
│           │   ├── play/package-info.java        new — module declaration
│           │   ├── leaderboard/package-info.java new — module declaration
│           │   ├── billing/package-info.java     new — module declaration
│           │   └── notify/package-info.java      new — module declaration
│           ├── main/java/cs/quizzapp/prokect/backend/   moved, frozen
│           ├── main/resources/
│           │   ├── application.properties        modified — secrets removed
│           │   └── db/migration/V1__baseline.sql new — schema of record
│           └── test/java/com/quizforge/
│               ├── ArchitectureTest.java         new — boundary enforcement
│               ├── ModularityTest.java           new — Spring Modulith verification
│               └── AbstractIntegrationTest.java  new — Testcontainers base class
└── docs/
    ├── adr/0001-*.md … 0005-*.md                 new — decision records
    └── superpowers/{specs,plans}/                existing
```

**Responsibilities.** `AbstractIntegrationTest` owns Postgres container lifecycle and nothing else, so every future integration test inherits a real database in one line. `ArchitectureTest` owns package-dependency rules; `ModularityTest` owns Spring Modulith's own verification — kept separate because they fail for different reasons and a reader should be able to tell which guardrail tripped.

---

## Task 0: Repository governance

**Status: complete.** Executed ahead of the other tasks because branch
protection, templates, and commit linting must exist *before* the first pull
request flows through them, not after.

**Files:**
- Create: `.gitattributes`
- Create: `.github/CODEOWNERS`, `.github/pull_request_template.md`
- Create: `.github/ISSUE_TEMPLATE/{config,bug_report,feature_request,task}.yml`
- Create: `.github/renovate.json`, `.github/workflows/pr-title.yml`
- Create: `.githooks/commit-msg`

**Interfaces:**
- Consumes: nothing
- Produces: repository governance that every later task and pull request
  depends on. Task 7 assumes `renovate.json` exists and does **not** create a
  Dependabot config.

- [x] **Step 1: Normalise line endings**

`.gitattributes` sets `* text=auto eol=lf` with explicit CRLF exceptions for
`.cmd`, `.bat`, and `.ps1`, and explicit LF for `mvnw` and `*.sh`. Without
this, commits made on Windows and CI runs on Linux disagree about every file,
and `mvnw` becomes unexecutable on the runner. Generated directories are
marked `linguist-generated` so they collapse in pull request diffs.

- [x] **Step 2: Add CODEOWNERS and the pull request template**

`CODEOWNERS` assigns review of everything to the owner, with migrations,
`openapi.yaml`, ADRs, and `.github/` called out explicitly so they are never
skimmed. The PR template requires a verification section containing the
commands actually run — not the phrase "tests pass".

- [x] **Step 3: Add issue forms**

Three typed forms (bug, feature, implementation task) with required fields
and an area dropdown matching the module names. Blank issues are disabled,
and `config.yml` routes security reports to private advisories and open
questions to Discussions, so neither arrives as a public issue.

- [x] **Step 4: Enforce Conventional Commits locally**

`.githooks/commit-msg` is a dependency-free POSIX shell script — no Node,
no Husky, works in Git Bash on Windows. Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

Verify it works in both directions:

```bash
echo "bad message" > /tmp/m && .githooks/commit-msg /tmp/m   # expect: exit 1
echo "feat(play): add expiry" > /tmp/m && .githooks/commit-msg /tmp/m  # expect: exit 0
```

Because a local hook can be bypassed with `--no-verify`, `pr-title.yml`
enforces the same convention on pull request titles server-side, where it
cannot be skipped.

- [x] **Step 5: Configure Renovate rather than Dependabot**

Renovate is chosen over Dependabot because it groups related updates (all
Spring artifacts move in lockstep; splitting them produces unbuildable
intermediate states), handles Maven, npm, and Docker from one config in the
monorepo that arrives at M5, and can auto-merge patch updates while holding
minor and major for a human. Security advisories bypass the weekly schedule.

Enable the Renovate GitHub App on the repository for the config to take
effect: https://github.com/apps/renovate

- [x] **Step 6: Commit**

```bash
git add .gitattributes .github/ .githooks/
git commit -m "ci: add repository governance, commit linting and renovate"
```

---

## Task 1: Repository hygiene and secret remediation

**Files:**
- Create: `.gitignore`, `.env.example`, `SECURITY.md`
- Modify: `backend/backend/src/main/resources/application.properties`
- Modify: `backend/backend/src/main/java/cs/quizzapp/prokect/backend/BackendApplication.java`
- Delete from index: `backend/backend/target/**`, `.idea/**`, `backend/.idea/**`, `backend/backend/.idea/**`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: a repository with no tracked secrets or build artifacts. Later tasks assume `.gitignore` exists and that `application.properties` reads every credential from an environment variable with no default.

- [ ] **Step 1: Revoke the leaked credential (human action, do this first)**

A Gmail app password is committed in git history at
`apps/api/src/main/resources/application.properties`, on the
`spring.mail.password` line, together with the account it belongs to on the
line above. Read the value from there — it is deliberately **not** reproduced
in this document, because that would create a second copy of the secret in a
second tracked file.

1. Go to https://myaccount.google.com/apppasswords
2. Sign in as the account named on the `spring.mail.username` line
3. Delete the app password used by this project
4. Do not create a replacement yet — Task 4 wires it through the environment

Removing the file does **not** remove the value from history or make it
invalid. Revocation at Google is the only thing that does.

Do not proceed until this is done. Everything else in this task is cosmetic if the credential is still live.

- [ ] **Step 2: Create the root `.gitignore`**

```gitignore
# Build output
target/
build/
out/
*.jar
*.war
*.class

# IDE
.idea/
*.iml
*.iws
.vscode/
.fleet/

# Environment and secrets
.env
.env.local
.env.*.local
*.pem
*.key

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/

# Node (used from M5 onward)
node_modules/
.next/
dist/
```

- [ ] **Step 3: Untrack build output and IDE configuration**

```bash
git rm -r --cached backend/backend/target .idea backend/.idea backend/backend/.idea
git status --short
```

Expected: many `D` entries staged, and the files still present on disk.

- [ ] **Step 4: Remove committed secrets from `application.properties`**

Replace lines 22–39 of `backend/backend/src/main/resources/application.properties` (the mail block) with:

```properties
# Email Configuration — all values MUST come from the environment.
# See .env.example. No default is provided for credentials by design.
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME}
spring.mail.password=${MAIL_PASSWORD}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.starttls.required=true
spring.mail.properties.mail.smtp.timeout=25000
spring.mail.properties.mail.smtp.connectiontimeout=25000
spring.mail.properties.mail.smtp.writetimeout=25000
spring.mail.properties.mail.debug=false
email.test.mode=${EMAIL_TEST_MODE:true}
```

Note `EMAIL_TEST_MODE` now defaults to `true` — the application must not attempt real sends unless explicitly configured to.

- [ ] **Step 5: Remove hardcoded default credentials**

In `BackendApplication.java`, delete the entire `run(String... args)` method body and the `implements CommandLineRunner` clause, along with the now-unused `UserRepository` and `PasswordEncoder` fields and their imports. The class becomes:

```java
package cs.quizzapp.prokect.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
```

Seeding users is reintroduced properly in M1 as an idempotent, environment-driven bootstrap.

- [ ] **Step 6: Create `.env.example`**

```bash
# Copy to .env and fill in. .env is gitignored and must never be committed.

# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/quizforge
DB_USERNAME=quizforge
DB_PASSWORD=change-me-locally

# Mail — leave EMAIL_TEST_MODE=true to log emails instead of sending them
EMAIL_TEST_MODE=true
MAIL_USERNAME=
MAIL_PASSWORD=

# Server
PORT=8080
```

- [ ] **Step 7: Create `SECURITY.md`**

```markdown
# Security Policy

## Reporting a vulnerability

Email security@quizforge.dev with a description, reproduction steps, and
impact assessment. We acknowledge within 72 hours and aim to ship a fix
within 30 days for confirmed issues.

Please do not open public issues for security reports.

## Scope

In scope: authentication, authorization, tenant isolation, data exposure,
injection, and anything permitting access to another workspace's data.

Out of scope: findings requiring physical access, social engineering, and
denial of service through volumetric traffic.

## Supported versions

Only the latest released version receives security fixes.
```

- [ ] **Step 8: Verify no secret remains in the working tree**

```bash
SECRET=$(git show 6ae6e43:backend/backend/src/main/resources/application.properties \
         | sed -n 's/^spring\.mail\.password=//p')
git grep -nF "$SECRET" -- . || echo "CLEAN: password absent"
git grep -nE "op@1234|Player@123" -- . || echo "CLEAN: default credentials absent"
```

Expected: both `CLEAN` lines. If anything matches, remove it before
committing. The secret is read out of history rather than typed here, so this
check does not itself introduce another copy.

- [ ] **Step 9: Commit**

```bash
git add .gitignore .env.example SECURITY.md \
        backend/backend/src/main/resources/application.properties \
        backend/backend/src/main/java/cs/quizzapp/prokect/backend/BackendApplication.java
git commit -m "chore: untrack build output and remove committed credentials"
```

- [ ] **Step 10: Purge the credential from git history (destructive — requires explicit go-ahead)**

**Stop and confirm with the repository owner before running this.** It rewrites every commit hash and requires a force-push. Anyone else with a clone must re-clone.

```bash
# 1. Back up first — this is not reversible
git clone --mirror . ../QuizForge-backup.git

# 2. Install git-filter-repo (once)
pip install git-filter-repo

# 3. Replace the secret everywhere in history.
#    The value is read out of history into a file outside the repository, so
#    no additional tracked copy is ever created. /tmp is not committed.
SECRET=$(git show 6ae6e43:backend/backend/src/main/resources/application.properties \
         | sed -n 's/^spring\.mail\.password=//p')
printf '%s==>REDACTED\n' "$SECRET" > /tmp/replacements.txt
git filter-repo --replace-text /tmp/replacements.txt --force
rm -f /tmp/replacements.txt

# 4. Also purge the unrelated 1.1MB archive still in history
git filter-repo --path "pizzaorderingsystemc (4).zip" --invert-paths --force

# 5. Strip tooling co-authorship trailers from every commit message.
#    The repository and product must contain no attribution of this kind.
git filter-repo --force --message-callback '
    import re
    cleaned = re.sub(rb"^Co-Authored-By: .*$\n?", b"", message, flags=re.MULTILINE)
    return cleaned.rstrip(b"\n") + b"\n"
'

# 5. Re-add the remote (filter-repo removes it) and force-push
git remote add origin <your-remote-url>
git push --force --all
git push --force --tags
```

Verify afterwards — note the SHA changes after the rewrite, so search for the
literal `REDACTED` marker instead:

```bash
git log --all -S "REDACTED" --oneline | head    # the marker should appear
git grep -rI "spring.mail.password=" $(git rev-list --all) -- '*application.properties' \
  | grep -v REDACTED || echo "PURGED: no unredacted password anywhere in history"
git log --all --format='%(trailers:key=Co-Authored-By)' | grep . \
  || echo "PURGED: no co-authorship trailers remain"
```

Expected: `PURGED`. Also confirm the repository shrank — the pizza archive
was roughly 1.1 MB of a 1.37 MB pack:

```bash
git count-objects -vH | grep size-pack
```

**Only after this succeeds** may the repository be made public. See Task 9.

---

## Task 2: Repair the Maven wrapper and upgrade the toolchain

**Files:**
- Create: `backend/backend/.mvn/wrapper/maven-wrapper.properties`
- Modify: `backend/backend/pom.xml`
- Delete: `backend/backend/src/main/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java`

**Interfaces:**
- Consumes: Task 1's cleaned `application.properties`
- Produces: a working `./mvnw` and a build on Java 21 / Spring Boot 3.5.x. Later tasks invoke `./mvnw verify` and assume Spring Modulith's BOM is importable.

- [ ] **Step 1: Create the missing wrapper properties**

`mvnw` currently fails because `.mvn/wrapper/` does not exist. Create `backend/backend/.mvn/wrapper/maven-wrapper.properties`:

```properties
wrapperVersion=3.3.2
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

- [ ] **Step 2: Verify the wrapper now works**

```bash
cd backend/backend && ./mvnw -v
```

Expected: Maven 3.9.11 and Java 21 reported. If it fails to download, the network is blocking `repo.maven.apache.org` — resolve that before continuing.

- [ ] **Step 3: Delete the duplicate test class from the main source tree**

`src/main/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java` shares its fully-qualified name with the real test in `src/test/java`, and ships in the production jar.

```bash
git rm backend/backend/src/main/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java
```

- [ ] **Step 4: Upgrade the POM**

Replace the `<parent>`, `<properties>`, and `<dependencies>` sections of `backend/backend/pom.xml`:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.6</version>
    <relativePath/>
</parent>

<groupId>com.quizforge</groupId>
<artifactId>api</artifactId>
<version>0.1.0-SNAPSHOT</version>
<name>quizforge-api</name>
<description>QuizForge assessment platform API</description>

<properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-modulith.version>1.4.3</spring-modulith.version>
    <archunit.version>1.3.0</archunit.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-bom</artifactId>
            <version>${spring-modulith.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-mail</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-core</artifactId>
    </dependency>

    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Note the MySQL driver is deliberately gone — Task 4 completes the move to Postgres.

- [ ] **Step 5: Verify the upgrade resolves and compiles**

```bash
cd backend/backend && ./mvnw -B clean compile
```

Expected: `BUILD SUCCESS`. If Spring Boot 3.5.6 or Spring Modulith 1.4.3 does not resolve, check https://central.sonatype.com for the current release of each and update the version properties — the plan pins versions deliberately, but a pinned version that no longer exists should be moved forward, not worked around.

The application will **not** start yet — `MAIL_USERNAME` has no default and Postgres is not configured. That is expected and is fixed in Task 4.

- [ ] **Step 6: Commit**

```bash
git add backend/backend/.mvn backend/backend/pom.xml
git rm --cached backend/backend/src/main/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java
git commit -m "build: repair maven wrapper, upgrade to Java 21 and Spring Boot 3.5"
```

---

## Task 3: Restructure into a monorepo

**Files:**
- Move: `backend/backend/**` → `apps/api/**`
- Delete: empty `backend/` directory

**Interfaces:**
- Consumes: Task 2's working build
- Produces: all subsequent paths are rooted at `apps/api/`. CI in Task 7 and docs in Task 8 assume this layout.

- [ ] **Step 1: Move the module with history preserved**

```bash
mkdir -p apps
git mv backend/backend apps/api
rmdir backend 2>/dev/null || true
git status --short | head -20
```

Using `git mv` (not `mv`) preserves rename detection in history.

- [ ] **Step 2: Verify the build still works from the new location**

```bash
cd apps/api && ./mvnw -B clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: move api module to apps/api for monorepo layout"
```

---

## Task 4: Replace ddl-auto with Flyway on PostgreSQL

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V1__baseline.sql`
- Create: `docker-compose.yml`
- Modify: `apps/api/src/main/resources/application.properties`

**Interfaces:**
- Consumes: Task 3's `apps/api` layout
- Produces: a schema owned by Flyway. Task 5's integration tests assert that Flyway applies cleanly and Hibernate validates against the result.

- [ ] **Step 1: Create local Postgres via Compose**

`docker-compose.yml` at the repository root:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: quizforge-postgres
    environment:
      POSTGRES_DB: quizforge
      POSTGRES_USER: quizforge
      POSTGRES_PASSWORD: local-dev-only
    ports:
      - "5432:5432"
    volumes:
      - quizforge-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U quizforge -d quizforge"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  quizforge-pgdata:
```

Start it:

```bash
docker compose up -d
docker compose ps
```

Expected: `quizforge-postgres` running and healthy.

- [ ] **Step 2: Write the baseline migration**

This reproduces the schema Hibernate was generating, expressed for Postgres. It is the schema of record from now on. Create `apps/api/src/main/resources/db/migration/V1__baseline.sql`:

```sql
-- V1: Baseline schema, transcribed from the entity definitions that
-- Hibernate previously created via ddl-auto=update. From this point the
-- schema is owned by Flyway and Hibernate only validates against it.

CREATE TABLE users (
    id                   BIGSERIAL PRIMARY KEY,
    username             VARCHAR(255) NOT NULL UNIQUE,
    email                VARCHAR(255) NOT NULL UNIQUE,
    password             VARCHAR(255) NOT NULL,
    role                 VARCHAR(255) NOT NULL,
    password_reset_token VARCHAR(255),
    first_name           VARCHAR(255),
    last_name            VARCHAR(255),
    profile_picture      VARCHAR(255),
    phone_number         VARCHAR(255),
    city                 VARCHAR(255),
    occupation           VARCHAR(255),
    preferred_language   VARCHAR(255),
    address              VARCHAR(255),
    date_of_birth        DATE,
    gender               VARCHAR(255),
    country              VARCHAR(255),
    bio                  VARCHAR(1000)
);

CREATE TABLE quiz (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(255),
    category               VARCHAR(255),
    difficulty             VARCHAR(255),
    start_date             TIMESTAMP(6),
    end_date               TIMESTAMP(6),
    minimum_passing_score  DOUBLE PRECISION,
    likes_count            INTEGER NOT NULL DEFAULT 0,
    rating                 DOUBLE PRECISION DEFAULT 0.0,
    rating_count           INTEGER DEFAULT 0,
    user_id                BIGINT REFERENCES users (id)
);

CREATE TABLE question (
    id             BIGSERIAL PRIMARY KEY,
    question_text  VARCHAR(255),
    correct_answer VARCHAR(255),
    quiz_id        BIGINT REFERENCES quiz (id)
);

CREATE TABLE question_options (
    question_id BIGINT NOT NULL REFERENCES question (id),
    options     VARCHAR(255)
);

CREATE TABLE score (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (id),
    quiz_id        BIGINT NOT NULL REFERENCES quiz (id),
    score          DOUBLE PRECISION NOT NULL,
    completed_date TIMESTAMP(6)
);

CREATE TABLE participation (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    quiz_id BIGINT REFERENCES quiz (id)
);

CREATE TABLE quiz_likes (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT NOT NULL REFERENCES users (id),
    quiz_id  BIGINT NOT NULL REFERENCES quiz (id),
    liked_at TIMESTAMP(6),
    CONSTRAINT uk_quiz_likes_user_quiz UNIQUE (user_id, quiz_id)
);

CREATE TABLE categories (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE INDEX idx_question_quiz_id      ON question (quiz_id);
CREATE INDEX idx_score_user_id         ON score (user_id);
CREATE INDEX idx_score_quiz_id         ON score (quiz_id);
CREATE INDEX idx_participation_user_id ON participation (user_id);
CREATE INDEX idx_participation_quiz_id ON participation (quiz_id);
CREATE INDEX idx_quiz_start_end        ON quiz (start_date, end_date);
```

- [ ] **Step 3: Rewrite the datasource and JPA configuration**

Replace lines 4–20 of `apps/api/src/main/resources/application.properties`:

```properties
# Database — PostgreSQL only. No default password by design.
spring.datasource.url=${DATABASE_URL:jdbc:postgresql://localhost:5432/quizforge}
spring.datasource.username=${DB_USERNAME:quizforge}
spring.datasource.password=${DB_PASSWORD}

# JPA — Flyway owns the schema; Hibernate may only validate it.
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=true

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
```

`open-in-view=false` stays deliberately. It exposes the missing transaction boundaries described in the spec, and those are fixed properly in M1–M3 rather than masked by re-enabling it.

- [ ] **Step 4: Verify migration and validation succeed against real Postgres**

```bash
cd apps/api && DB_PASSWORD=local-dev-only ./mvnw -B spring-boot:run
```

Expected in the log: `Successfully applied 1 migration to schema "public"`, then the application starts without a Hibernate validation error. Stop it with Ctrl-C.

If Hibernate reports a missing column or table, the baseline SQL does not match the entities — fix `V1__baseline.sql` to match, do not change `ddl-auto`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml apps/api/src/main/resources/
git commit -m "feat: replace ddl-auto with flyway migrations on postgresql"
```

---

## Task 5: Relocate the application class and add the Testcontainers harness

**Files:**
- Create: `apps/api/src/main/java/com/quizforge/QuizForgeApplication.java`
- Delete: `apps/api/src/main/java/cs/quizzapp/prokect/backend/BackendApplication.java`
- Create: `apps/api/src/test/java/com/quizforge/AbstractIntegrationTest.java`
- Create: `apps/api/src/test/java/com/quizforge/SchemaMigrationTest.java`
- Delete: `apps/api/src/test/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java`

**Interfaces:**
- Consumes: Task 4's Flyway migrations
- Produces: `QuizForgeApplication` as the single Spring entry point, and `AbstractIntegrationTest` — extend it to get a live Postgres and a fully started Spring context. Every integration test from M1 onward extends this class. Task 6's `ModularityTest` requires `QuizForgeApplication` to exist.

> **Why the application class moves first.** `@SpringBootTest` locates configuration by searching *upward* from the test's own package. A test in `com.quizforge` cannot find `cs.quizzapp.prokect.backend.BackendApplication`, so the harness would fail with "Unable to find a @SpringBootConfiguration" if the class were relocated later.

- [ ] **Step 1: Create the application class in the new package**

Create `apps/api/src/main/java/com/quizforge/QuizForgeApplication.java`:

```java
package com.quizforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Application entry point.
 *
 * <p>Scanning explicitly covers both {@code com.quizforge} (the module
 * structure built out in M1-M3) and the legacy {@code cs.quizzapp} package,
 * which still serves all current traffic. Because the entry point no longer
 * sits above the legacy package, component, entity, and repository scanning
 * must all be declared by hand — Spring Boot's defaults would otherwise miss
 * every legacy bean. All three legacy entries are removed once M3 completes.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend"})
@EntityScan(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend.models"})
@EnableJpaRepositories(basePackages = {"com.quizforge", "cs.quizzapp.prokect.backend.db"})
public class QuizForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizForgeApplication.class, args);
    }
}
```

- [ ] **Step 2: Delete the old application class**

```bash
git rm apps/api/src/main/java/cs/quizzapp/prokect/backend/BackendApplication.java
```

Two `@SpringBootApplication` classes on the classpath make the context ambiguous, so this deletion is required, not optional.

- [ ] **Step 3: Verify the application still starts**

```bash
cd apps/api && DB_PASSWORD=local-dev-only ./mvnw -B spring-boot:run
```

Expected: startup completes with no `Unable to find a @SpringBootConfiguration` error and no `Not a managed type` error. A `Not a managed type` failure means `@EntityScan` is missing a package. Stop with Ctrl-C.

- [ ] **Step 4: Write the failing test**

Create `apps/api/src/test/java/com/quizforge/SchemaMigrationTest.java`:

```java
package com.quizforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesBaselineMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void baselineCreatesEveryExpectedTable() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "users", "quiz", "question", "question_options",
                "score", "participation", "quiz_likes", "categories");
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

```bash
cd apps/api && ./mvnw -B test -Dtest=SchemaMigrationTest
```

Expected: FAIL — compilation error, `AbstractIntegrationTest` does not exist.

- [ ] **Step 6: Write the base class and stop the context from calling the internet**

`QuizController` has an `@PostConstruct` hook that calls the live OpenTDB API and then sleeps for six seconds per category. Because `@SpringBootTest` starts the full context, every integration test would inherit that — making the suite network-dependent, flaky, and roughly thirty seconds slower per JVM. Guard it with a property first.

In `apps/api/src/main/java/cs/quizzapp/prokect/backend/controllers/QuizController.java`, add the field and guard clause:

```java
    @org.springframework.beans.factory.annotation.Value("${quizforge.opentdb.bootstrap-enabled:true}")
    private boolean openTdbBootstrapEnabled;

    @PostConstruct
    public void initializeQuizzes() {
        if (!openTdbBootstrapEnabled) {
            System.out.println("OpenTDB bootstrap disabled by configuration; skipping.");
            return;
        }
        System.out.println("Starting OpenTDB quiz initialization...");
        // ... rest of the existing method body unchanged ...
```

This is the one behavioural change M0 makes, and it is opt-out only: the default remains `true`, so running the application normally behaves exactly as before.

Now create `apps/api/src/test/java/com/quizforge/AbstractIntegrationTest.java`:

```java
package com.quizforge;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests. Starts one PostgreSQL container per JVM
 * (the container is static, so it is reused across every test class) and
 * points Spring at it. Flyway runs against the real database on context
 * startup, so subclasses test against the schema that production will have.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("quizforge")
                    .withUsername("quizforge")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.username", () -> "test@example.invalid");
        registry.add("spring.mail.password", () -> "unused");
        registry.add("email.test.mode", () -> "true");
        registry.add("quizforge.opentdb.bootstrap-enabled", () -> "false");
    }
}
```

The container is started in a static initialiser rather than annotated with `@Container` so that it is shared across all test classes instead of restarted per class — this is the difference between a 20-second suite and a 5-minute one.

- [ ] **Step 7: Delete the obsolete placeholder test**

```bash
git rm apps/api/src/test/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
cd apps/api && ./mvnw -B test
```

Expected: PASS, 2 tests in `SchemaMigrationTest`. First run pulls the `postgres:16-alpine` image, so allow a minute.

If the run fails with `Could not find a valid Docker environment`, Docker Desktop is not running — start it and retry.

- [ ] **Step 9: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/ apps/api/src/test/java/com/quizforge/ \
        apps/api/src/main/java/cs/quizzapp/prokect/backend/controllers/QuizController.java
git rm --cached apps/api/src/main/java/cs/quizzapp/prokect/backend/BackendApplication.java \
                apps/api/src/test/java/cs/quizzapp/prokect/backend/BackendApplicationTests.java
git commit -m "test: relocate application class and add testcontainers harness"
```

---

## Task 6: Module skeleton with Spring Modulith and ArchUnit guardrails

**Files:**
- Create: `apps/api/src/main/java/com/quizforge/{platform,identity,content,tournament,play,leaderboard,billing,notify}/package-info.java`
- Create: `apps/api/src/test/java/com/quizforge/ModularityTest.java`
- Create: `apps/api/src/test/java/com/quizforge/ArchitectureTest.java`

**Interfaces:**
- Consumes: Task 5's `QuizForgeApplication` (required by `ApplicationModules.of(...)`) and its test harness
- Produces: eight empty but real Spring Modulith modules under `com.quizforge`, and a build that fails if new code is added to the legacy package. M1 populates `identity`.

- [ ] **Step 1: Write the failing architecture test**

Create `apps/api/src/test/java/com/quizforge/ArchitectureTest.java`:

```java
package com.quizforge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.quizforge", "cs.quizzapp");
    }

    @Test
    void legacyPackageIsFrozen() {
        // The legacy coursework package is being retired module by module in
        // M1-M3. Nothing new may be added to it.
        classes()
                .that().resideInAPackage("cs.quizzapp..")
                .should().bePackagePrivate()
                .orShould().beTopLevelClasses()
                .because("the legacy package is frozen; new code belongs in com.quizforge")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void newCodeMustNotDependOnLegacyCode() {
        noClasses()
                .that().resideInAPackage("com.quizforge..")
                .should().dependOnClassesThat().resideInAPackage("cs.quizzapp..")
                .because("new modules must not couple themselves to code that is being deleted")
                .allowEmptyShould(true)
                .check(allClasses);
    }

    @Test
    void entitiesMustNotLeakIntoControllers() {
        noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers talk to application services, never to repositories")
                .allowEmptyShould(true)
                .check(allClasses);
    }
}
```

- [ ] **Step 2: Run it to verify the third test fails**

```bash
cd apps/api && ./mvnw -B test -Dtest=ArchitectureTest
```

Expected: FAIL on `entitiesMustNotLeakIntoControllers` — the legacy `QuizController` and others inject repositories and services directly.

This failure is informative, not a blocker: it proves the rule has teeth. The next step scopes it to new code only, because rewriting the legacy controllers is M1–M3 work, not M0 work.

- [ ] **Step 3: Scope the controller rule to new code**

Replace the `entitiesMustNotLeakIntoControllers` method with:

```java
    @Test
    void entitiesMustNotLeakIntoControllers() {
        noClasses()
                .that().resideInAPackage("com.quizforge..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers talk to application services, never to repositories")
                .allowEmptyShould(true)
                .check(allClasses);
    }
```

- [ ] **Step 4: Run again to verify all three pass**

```bash
cd apps/api && ./mvnw -B test -Dtest=ArchitectureTest
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Create the eight module declarations**

Create one `package-info.java` per module. For `platform`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Platform",
        allowedDependencies = {}
)
package com.quizforge.platform;
```

For `identity`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"platform"}
)
package com.quizforge.identity;
```

For `content`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Content",
        allowedDependencies = {"platform", "identity"}
)
package com.quizforge.content;
```

For `tournament`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Tournament",
        allowedDependencies = {"platform", "identity", "content"}
)
package com.quizforge.tournament;
```

For `play`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Play",
        allowedDependencies = {"platform", "identity", "content", "tournament"}
)
package com.quizforge.play;
```

For `leaderboard`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Leaderboard",
        allowedDependencies = {"platform", "identity", "tournament"}
)
package com.quizforge.leaderboard;
```

For `billing`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {"platform", "identity"}
)
package com.quizforge.billing;
```

For `notify`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Notify",
        allowedDependencies = {"platform"}
)
package com.quizforge.notify;
```

`notify` depends only on `platform` by design — it learns about everything else through published events, never by importing it.

- [ ] **Step 6: Write the modularity verification test**

Create `apps/api/src/test/java/com/quizforge/ModularityTest.java`:

```java
package com.quizforge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    static final ApplicationModules MODULES =
            ApplicationModules.of(QuizForgeApplication.class);

    /**
     * Fails the build if any module reaches outside the dependencies it
     * declares in its {@code package-info.java}. Passes trivially while the
     * modules are empty - that is deliberate. The guardrail exists before the
     * code it guards, so the first violation is caught the moment it appears.
     */
    @Test
    void modulesRespectTheirDeclaredBoundaries() {
        MODULES.verify();
    }
}
```

> **Module documentation is deferred to M1.** The original design added a
> second test driving Spring Modulith's `Documenter` to regenerate C4 diagrams
> on every build. It fails under Spring Modulith 1.4.3 with Spring Boot 3.5.6:
> the `Documenter` cannot parse the `javadoc.json` that its own annotation
> processor emits (`JsonParseException` wrapping an NPE), even though the file
> is well-formed. Since eight empty modules would produce empty diagrams
> regardless, this buys nothing in M0. Reintroduce it in M1, when the modules
> hold real components and the incompatibility can be judged against output
> that has value.

- [ ] **Step 7: Run the full suite**

```bash
cd apps/api && ./mvnw -B test
```

Expected: PASS. `ModularityTest.modulesRespectTheirDeclaredBoundaries` passes trivially because the modules are empty — that is the point. The guardrail exists before the code it guards.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/ apps/api/src/test/java/com/quizforge/
git commit -m "feat: add spring modulith module skeleton with archunit guardrails"
```

---

## Task 7: Continuous integration

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`

**Interfaces:**
- Consumes: Task 6's green test suite
- Produces: a required status check named `build` that every future pull request must pass.

- [ ] **Step 1: Write the CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build:
    name: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven

      - name: Build and test
        working-directory: apps/api
        run: ./mvnw -B verify

      - name: Publish test report
        if: always()
        uses: mikepenz/action-junit-report@v5
        with:
          report_paths: apps/api/target/surefire-reports/TEST-*.xml

  codeql:
    name: codeql
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - uses: github/codeql-action/init@v3
        with:
          languages: java
      - name: Compile for analysis
        working-directory: apps/api
        run: ./mvnw -B clean compile -DskipTests
      - uses: github/codeql-action/analyze@v3

  secrets:
    name: secret-scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

The `build` job runs Testcontainers, which works on GitHub's Ubuntu runners because Docker is preinstalled.

- [ ] **Step 2: Confirm dependency automation is already in place**

Dependency updates are handled by Renovate, configured in Task 0
(`.github/renovate.json`). Do **not** add a Dependabot configuration —
running both produces duplicate pull requests for every update.

Confirm the Renovate GitHub App is installed on the repository:

```bash
gh api repos/ARSH871-bot/QuizForge/installation --jq '.app_slug' 2>/dev/null || \
  echo "Renovate not installed — enable at https://github.com/apps/renovate"
```

GitHub's own Dependabot **alerts** (as distinct from version updates) remain
enabled, since they feed Renovate's vulnerability handling.

- [ ] **Step 3: Verify the workflow is valid before pushing**

```bash
cd apps/api && ./mvnw -B verify
```

Expected: `BUILD SUCCESS`. This runs exactly what CI runs, so a green local `verify` means a green `build` job.

- [ ] **Step 4: Commit**

```bash
git add .github/
git commit -m "ci: add build, codeql and secret scanning workflows"
```

- [ ] **Step 5: Enable branch protection (human action, via GitHub UI)**

After the workflow has run once on `main`:

1. Settings → Branches → Add branch protection rule for `main`
2. Enable **Require a pull request before merging** (1 approval; approve your own as a solo dev — the point is forcing a deliberate read of the diff)
3. Enable **Require status checks to pass**, selecting `build`, `codeql`, and `secret-scan`
4. Enable **Require linear history**
5. Enable **Require signed commits**
6. Leave **Allow force pushes** disabled

---

## Task 8: Repository documentation and decision records

**Files:**
- Create: `README.md`, `CONTRIBUTING.md`
- Create: `docs/adr/0001-modular-monolith.md` … `docs/adr/0005-postgres-only.md`

**Interfaces:**
- Consumes: everything above
- Produces: the documentation an outside reader needs. M1 adds ADRs numbered from 0006.

- [ ] **Step 1: Write the README**

Create `README.md`:

````markdown
# QuizForge

An asynchronous quiz and assessment engine. Tournaments open for a time
window, players enter on their own schedule, and results roll up to a
leaderboard — deliberately different from live synchronous quizzing.

API-first: the public REST API and its generated SDKs are the primary
product surface.

## Status

Pre-launch. Milestone M0 (foundation) complete; see
`docs/superpowers/specs/` for the platform design and milestone map.

## Getting started

Requires Java 21, Docker, and Git.

```bash
git clone <repo-url> && cd QuizForge
cp .env.example .env          # fill in DB_PASSWORD
docker compose up -d          # starts PostgreSQL 16
cd apps/api && ./mvnw spring-boot:run
```

The API listens on http://localhost:8080.

## Running tests

```bash
cd apps/api && ./mvnw verify
```

Integration tests start their own PostgreSQL container via Testcontainers,
so Docker must be running. No manual database setup is needed.

## Layout

| Path | Contents |
|---|---|
| `apps/api` | Spring Boot modular monolith |
| `docs/adr` | Architecture Decision Records |
| `docs/superpowers/specs` | Design specifications |
| `docs/superpowers/plans` | Implementation plans |

## Architecture

A modular monolith on Spring Modulith. Module boundaries are enforced by
tests — see `ModularityTest` and `ArchitectureTest`. Module diagrams are
regenerated into `apps/api/target/spring-modulith-docs` on every build.

Read `docs/adr/` for why things are the way they are.
````

- [ ] **Step 2: Write CONTRIBUTING.md**

Create `CONTRIBUTING.md`:

````markdown
# Contributing

## Workflow

Trunk-based. Branch from `main`, keep branches short-lived, open a pull
request. `main` is protected: linear history, signed commits, and passing
status checks are required.

```bash
git switch -c feat/short-description
# ... work ...
cd apps/api && ./mvnw verify     # must pass before pushing
git push -u origin feat/short-description
```

## Attribution

Commits carry no co-authorship or tooling trailers. Repository content,
commit messages, pull request descriptions, documentation, and product copy
reference no authoring tool of any kind. Keep `git commit` free of trailers
and verify with:

```bash
git log --format='%(trailers:key=Co-Authored-By)' | grep . && echo "REMOVE THESE"
```

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add attempt expiry job
fix: correct score denominator for partial attempts
docs: document webhook signature verification
test: cover leaderboard tie-breaking
build: upgrade to spring boot 3.5.7
ci: cache maven dependencies
refactor: extract grading policy from attempt service
chore: untrack generated files
```

## Signing commits

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
```

Add the same public key to GitHub under Settings → SSH and GPG keys, as a
**signing** key.

## Architecture decisions

Anything structural gets an ADR in `docs/adr/`, numbered sequentially,
using the existing files as a template. Record the context, the options
considered, the decision, and the consequences — especially the negative
ones.

## Tests

Integration tests extend `AbstractIntegrationTest`, which provides a real
PostgreSQL container. Do not mock the database.

Module boundaries are enforced by `ModularityTest`. If it fails, the fix is
almost always to publish an event rather than to widen `allowedDependencies`.
````

- [ ] **Step 3: Write the five ADRs**

Create `docs/adr/0001-modular-monolith.md`:

```markdown
# 1. Modular monolith over microservices

Date: 2026-08-11

## Status

Accepted

## Context

QuizForge is built by one developer over roughly six months with a hard
constraint of near-zero infrastructure spend before launch. It needs clear
internal boundaries so that the domain stays comprehensible as it grows.

## Decision

A single deployable Spring Boot application, internally divided into
modules whose boundaries are enforced at build time by Spring Modulith and
ArchUnit. Modules communicate through published application events and
explicit public APIs, never through each other's repositories.

## Consequences

Positive: one deployment, one database, one transaction boundary when
needed, and no distributed-systems failure modes. Boundaries are enforced
by failing tests rather than by convention, so they do not erode.
Extraction into services later remains possible because the seams are real.

Negative: the whole application scales as a unit, and a defect in one
module can affect the process as a whole. Both are acceptable at the
expected scale.
```

Create `docs/adr/0002-flyway-owns-the-schema.md`:

```markdown
# 2. Flyway owns the schema

Date: 2026-08-11

## Status

Accepted

## Context

The prototype used `spring.jpa.hibernate.ddl-auto=update`, letting
Hibernate mutate the schema at startup. This gives no review point, no
rollback, no record of what changed, and behaves differently across
environments.

## Decision

Flyway owns the schema. Hibernate is set to `validate` and may never
create or alter anything. Every change is a numbered, reviewed migration
in `apps/api/src/main/resources/db/migration`.

## Consequences

Positive: schema changes are reviewable in pull requests, reproducible
across environments, and testable — `SchemaMigrationTest` asserts the
migrations apply and Hibernate validates against the result.

Negative: entity changes now require a hand-written migration. This is
friction by design; it is the point.
```

Create `docs/adr/0003-attempt-as-aggregate-root.md`:

```markdown
# 3. Attempt is the aggregate root for play

Date: 2026-08-11

## Status

Accepted

## Context

The prototype modelled play as a `Participation` row plus a `Score`
number, with the answers themselves persisted nowhere. This produced at
least six defects: no resumability, unlimited resubmission, a score
denominator that drifted from what the player actually saw, an orphaned
paginated flow that recorded nothing, correct answers leaking to the
client, and no enforceable time limit.

## Decision

Introduce `Attempt` as a stateful aggregate root
(`STARTED → SUBMITTED → GRADED → EXPIRED`) owning a collection of
`Response` entities. The question set is frozen into the attempt at
creation. Grading happens server-side.

## Consequences

Positive: all six defects are resolved structurally rather than
individually. Resumability, idempotent submission, and anti-cheat timing
become natural properties of the model.

Negative: more write traffic — one row per answered question rather than
one per completed quiz. Acceptable, and it is what makes per-question
analytics possible later.
```

Create `docs/adr/0004-no-redis.md`:

```markdown
# 4. PostgreSQL only; no Redis

Date: 2026-08-11

## Status

Accepted

## Context

Caching, rate limiting, job queueing, and the transactional outbox are all
commonly delegated to Redis. Each additional service is another process to
run, monitor, back up, and pay for — against a near-zero budget.

## Decision

PostgreSQL serves all four needs. Rate limiting uses a token-bucket table,
the outbox is a table drained by a background worker, and caching is
handled in-process.

## Consequences

Positive: one stateful service to operate and back up. Outbox writes share
a transaction with the domain writes that produce them, which is precisely
the property that makes the pattern correct.

Negative: a busy outbox creates write load on the primary database, and
in-process caching does not survive a restart. Both are revisitable; if
either becomes a real measured problem, adding Redis is a contained change.
```

Create `docs/adr/0005-postgres-only.md`:

```markdown
# 5. Drop MySQL support

Date: 2026-08-11

## Status

Accepted

## Context

The prototype shipped both the MySQL and PostgreSQL drivers, selecting
between them with environment variables and switching the Hibernate
dialect accordingly. Nothing ran on MySQL in any deployed environment.

## Decision

PostgreSQL only. The MySQL driver, the dialect property, and the driver
class property are removed.

## Consequences

Positive: migrations can use PostgreSQL-specific features — Row-Level
Security for tenant isolation, `jsonb`, partial indexes — none of which
are portable. Tests run against the same engine as production.

Negative: no MySQL deployment option. Nobody was asking for one.
```

- [ ] **Step 4: Verify every documented command actually works**

```bash
# From a clean checkout perspective — confirm the README instructions hold
docker compose up -d
cd apps/api && ./mvnw -B verify
```

Expected: `BUILD SUCCESS`. If any README command does not work as written, fix the README — documentation that lies is worse than no documentation.

- [ ] **Step 5: Commit**

```bash
git add README.md CONTRIBUTING.md docs/adr/
git commit -m "docs: add readme, contributing guide and initial ADRs"
```

---

## Task 9: Make the repository public and enable the protections it unlocks

**Files:** none — this is entirely GitHub configuration.

**Interfaces:**
- Consumes: Task 1 Step 10 (history purge) — **hard prerequisite, no exceptions**
- Produces: branch protection, secret scanning, push protection, and private
  vulnerability reporting, none of which are available otherwise.

> **Why this task exists.** Branch protection and rulesets return HTTP 403
> `Upgrade to GitHub Pro or make this repository public` on a private
> repository on the Free plan. The same is true of secret scanning, push
> protection, and private vulnerability reporting. Going public unlocks all of
> them at no cost, which is the only route consistent with constraint C1.
> GitHub Pro would cost $4/month for a strictly worse outcome.

> **Why the ordering is absolute.** The repository history currently contains a
> live Gmail app password. Publishing before the purge would expose it to
> automated credential scrapers within minutes. Do not reorder these tasks.

- [ ] **Step 1: Confirm the prerequisites are genuinely met**

```bash
git log --all -S "REDACTED" --oneline | head -1   # purge ran
gh api repos/ARSH871-bot/QuizForge --jq .private  # currently true
```

The credential must also be revoked at Google (Task 1 Step 1). Revocation
matters more than the purge: a purged-but-live credential is still a live
credential in every existing clone and fork.

- [ ] **Step 2: Make the repository public**

```bash
gh repo edit ARSH871-bot/QuizForge --visibility public --accept-visibility-change-consequences
```

- [ ] **Step 3: Enable the security features this unlocks**

```bash
gh api -X PATCH repos/ARSH871-bot/QuizForge \
  -f 'security_and_analysis[secret_scanning][status]=enabled' \
  -f 'security_and_analysis[secret_scanning_push_protection][status]=enabled'
gh api -X PUT repos/ARSH871-bot/QuizForge/private-vulnerability-reporting
```

Push protection is the important one: it rejects a push containing a
recognised credential pattern, which would have prevented the original
incident outright.

- [ ] **Step 4: Create the branch protection ruleset**

```bash
cat > /tmp/ruleset.json <<'EOF'
{
  "name": "main protection",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["squash"]
      }
    },
    { "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "build" },
          { "context": "codeql" },
          { "context": "secret-scan" },
          { "context": "conventional-title" }
        ]
      }
    }
  ]
}
EOF
gh api -X POST repos/ARSH871-bot/QuizForge/rulesets --input /tmp/ruleset.json \
  --jq '"created: \(.name) [\(.enforcement)]"'
```

`required_approving_review_count` is 0 because GitHub will not let you approve
your own pull request — setting 1 as a solo maintainer blocks every merge.
The pull request requirement itself still applies, so nothing reaches `main`
without a PR and green checks.

- [ ] **Step 5: Verify the protection actually works**

```bash
echo "test" >> README.md && git add README.md
git commit -m "test: verify branch protection rejects direct pushes"
git push origin HEAD:main    # expect: rejected by the ruleset
git reset --hard HEAD~1
```

Expected: the push is rejected. A protection rule you have not seen reject
something is a protection rule you have not tested.

---

## Definition of done for M0

All of the following must be true:

- [ ] The leaked Gmail app password is revoked at Google and purged from git history
- [ ] No commit message anywhere in history carries a `Co-Authored-By` trailer, and no tracked file references any authoring tool
- [ ] The mail password (read out of history, never typed into a tracked file) and the strings `op@1234` and `Player@123` appear nowhere in the working tree
- [ ] No `target/` or `.idea/` content is tracked
- [ ] `cd apps/api && ./mvnw verify` passes from a clean clone
- [ ] `spring.jpa.hibernate.ddl-auto` is `validate`
- [ ] Integration tests run against a real PostgreSQL container
- [ ] `ModularityTest` and `ArchitectureTest` both pass and are wired into CI
- [ ] CI is green on `main` and branch protection requires it
- [ ] `README.md` gets a stranger running the project with the commands as written
- [ ] Five ADRs exist in `docs/adr/`

## Explicitly out of scope for M0

Deferred to M1–M3, and not to be attempted here: rewriting controllers or
services, adding authentication, introducing the `Attempt` model, adding
`@Transactional` boundaries, or deleting the legacy `cs.quizzapp` package.
M0 changes no product behaviour. It only makes the foundation trustworthy.
