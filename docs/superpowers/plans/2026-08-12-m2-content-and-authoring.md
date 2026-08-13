# M2 — Content & Authoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Workspace-scoped question banks holding immutable, versioned questions of five types, with server-side grading and an import pipeline — so that M3's tournaments can pin exactly what a player saw and edits can never rewrite history.

**Architecture:** Populates the `content` module. A question row is immutable; editing writes a new row sharing a `lineage_id` and incrementing `version`. Type-specific data lives in a JSONB `payload` rather than five parallel tables, with a `QuestionGrader` per type resolving from the enum. Import adapters (OpenTDB, CSV) normalise into the same shape and de-duplicate by content hash.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Modulith, PostgreSQL 16 with `jsonb`, Flyway, Testcontainers, ArchUnit.

## Global Constraints

Everything in M1's Global Constraints still applies — Java 21, `com.quizforge` base package, Flyway-only schema with `ddl-auto=validate`, UUIDv7 in native `uuid` columns, explicit `@Transactional`, Conventional Commits ≤72 chars, no trailers, green `./mvnw verify` before every commit. In addition:

- **Cross-module types need `@NamedInterface`.** `content` will consume `platform::id`, `platform::error` and `identity` — declare them in `content/package-info.java` or `ModularityTest` fails with *"Allowed targets: …"*.
- **The legacy schema owns the name `question`.** V6 renames it to
  `legacy_question` and remaps the legacy entity, including an explicit
  `@CollectionTable`, because Hibernate derives the element-collection table
  name from the owning table. Discovered during Task 1, not at planning time.
- **Cross-module access goes through a published API.** `content` may depend on
  `identity`, but Modulith exposes only identity's *root* package — not
  `identity.app` or `identity.domain`. Depend on
  `com.quizforge.identity.WorkspaceAccess`, which answers permission questions
  as booleans, rather than widening the boundary. A caller handed a `Role`
  starts branching on it, reimplementing the permission matrix in the wrong
  module.
- **Watch for bean-name collisions with the legacy package.** Spring derives a
  default bean name from the simple class name, so `content.app.QuestionService`
  collides with `cs.quizzapp...QuestionService` and the context fails to start.
  Qualify the new bean (`@Service("contentQuestionService")`). M1 hit the same
  thing with `AuthController`; the qualifiers go away in M3.
- **Migrations are additive.** V1–V5 exist; start at **V6**. Never edit an applied migration — CI blocks it, because Flyway checksums every file and editing one breaks every existing database.
- **Correct answers never leave the server.** No DTO exposed to a player may carry the answer, and a test must assert it for every type.
- **Every change updates the tracking artefacts** — see `CONTRIBUTING.md`, "Definition of done for any change".

## Prerequisites

```bash
cd apps/api && ./mvnw -B clean verify   # expect BUILD SUCCESS, 56 tests
docker compose ps                        # postgres and mailpit healthy
```

M0 and M1 are merged. Issue #2 (credential revocation) remains open and does
not block this milestone.

---

## Domain decisions taken

Recorded here because they are not in the spec and each is expensive to
reverse. Any can be revisited before implementation starts.

**1. Immutability by new row, not by version column.** A `question` row is
never updated. Editing inserts a new row with the same `lineage_id`, `version + 1`,
and sets `superseded_by` on the old one. A tournament references a concrete
`question.id`, so what a player saw is pinned by construction rather than by
remembering to pin it.

*Alternative rejected:* a `question_version` side table. It doubles the joins
on the hot read path (M3 loads questions constantly) and buys nothing, because
questions are small.

**2. Type-specific data in a JSONB `payload`.** One table with a discriminator
and a validated payload, not five tables or twenty mostly-null columns. Each
type has a schema enforced in the application, not the database.

*Cost, stated plainly:* the database cannot validate payload shape. A malformed
payload is caught by the grader at read time rather than rejected at write
time. Mitigated by validating on write in `QuestionService` and by a test per
type.

**3. Banks are flat.** No folders, tags or nesting. A workspace has banks; a
bank has questions. Hierarchies are easy to add later and impossible to remove.

**4. Import de-duplicates by content hash**, not by exact text match — the same
question arrives from OpenTDB with different HTML entity encodings. Hash is
computed over the normalised prompt plus sorted normalised answers.

**5. `SHORT_TEXT` grading normalises but does not fuzzy-match.** Case,
surrounding whitespace, and accents are normalised; edit distance is not
applied. Fuzzy matching produces disputes with no principled threshold, and
authors can supply multiple accepted answers instead.

---

## File Structure

```
apps/api/src/main/java/com/quizforge/content/
├── package-info.java                     module declaration
├── domain/
│   ├── QuestionBank.java                 workspace-scoped container
│   ├── Question.java                     immutable, versioned
│   ├── QuestionType.java                 enum + payload contract
│   └── payload/                          typed payload records
│       ├── ChoicePayload.java            SINGLE_CHOICE, MULTI_CHOICE, TRUE_FALSE
│       ├── NumericPayload.java           value + tolerance
│       └── ShortTextPayload.java         accepted answers + normalisation
├── grading/
│   ├── QuestionGrader.java               interface
│   ├── GradingResult.java                correct + feedback, no answer leak
│   ├── ChoiceGrader.java
│   ├── NumericGrader.java
│   ├── ShortTextGrader.java
│   └── GraderRegistry.java               resolves grader by type
├── repo/{QuestionBankRepository,QuestionRepository}.java
├── app/
│   ├── QuestionBankService.java          create, rename, archive
│   ├── QuestionService.java              author, revise, retire
│   └── ContentHash.java                  de-duplication key
└── importer/
    ├── QuestionImporter.java             interface
    ├── OpenTdbImporter.java              replaces the legacy integration
    └── CsvImporter.java

apps/api/src/main/resources/db/migration/
├── V6__content.sql                       banks and questions
└── V7__content_rls.sql                   tenant isolation
```

---

## Task 1: Content schema and module declaration

**Files:**
- Create: `db/migration/V6__content.sql`, `content/package-info.java`
- Test: `content/ContentSchemaTest.java`

**Interfaces:**
- Consumes: M1's `workspace` table
- Produces: `question_bank` and `question` tables. Task 2 maps entities onto them.

- [x] **Step 1: Write the failing test**

```java
package com.quizforge.content;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSchemaTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsContentTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("question_bank", "question");
    }

    @Test
    void enforcesOneVersionPerLineage() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'question'::regclass",
                String.class);

        assertThat(constraints).contains("uk_question_lineage_version");
    }

    @Test
    void constrainsQuestionTypeToKnownValues() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'question'::regclass",
                String.class);

        assertThat(constraints).contains("ck_question_type");
    }

    @Test
    void indexesTheCurrentVersionOfEachLineage() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'question'",
                String.class);

        assertThat(indexes).contains("idx_question_current");
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=ContentSchemaTest`
Expected: FAIL — `relation "question" does not exist`.

- [x] **Step 3: Write the migration**

`apps/api/src/main/resources/db/migration/V6__content.sql`:

```sql
-- V6: Question banks and questions.
--
-- A question row is immutable. Editing inserts a new row sharing lineage_id
-- with version + 1, and stamps superseded_by on the previous row. Tournaments
-- reference a concrete question.id, so what a player saw is pinned by
-- construction rather than by remembering to pin it.

CREATE TABLE question_bank (
    id            UUID         PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(500),
    created_by    UUID         NOT NULL REFERENCES account (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    archived_at   TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_question_bank_name UNIQUE (workspace_id, name)
);

CREATE INDEX idx_question_bank_workspace
    ON question_bank (workspace_id) WHERE archived_at IS NULL;

CREATE TABLE question (
    id             UUID         PRIMARY KEY,
    bank_id        UUID         NOT NULL REFERENCES question_bank (id) ON DELETE CASCADE,
    workspace_id   UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,

    -- Stable identity across versions. The first version's id is reused as the
    -- lineage_id, so a lineage needs no separate table.
    lineage_id     UUID         NOT NULL,
    version        INTEGER      NOT NULL,
    superseded_by  UUID         REFERENCES question (id),

    type           VARCHAR(16)  NOT NULL,
    prompt         TEXT         NOT NULL,
    payload        JSONB        NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,

    difficulty     VARCHAR(8),
    created_by     UUID         NOT NULL REFERENCES account (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retired_at     TIMESTAMPTZ,

    CONSTRAINT uk_question_lineage_version UNIQUE (lineage_id, version),
    CONSTRAINT ck_question_type CHECK (type IN
        ('SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE', 'NUMERIC', 'SHORT_TEXT')),
    CONSTRAINT ck_question_difficulty CHECK (difficulty IS NULL OR difficulty IN
        ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT ck_question_version_positive CHECK (version >= 1)
);

-- The hot read path: current, non-retired questions in a bank.
CREATE INDEX idx_question_current ON question (bank_id)
    WHERE superseded_by IS NULL AND retired_at IS NULL;

CREATE INDEX idx_question_lineage   ON question (lineage_id);
CREATE INDEX idx_question_workspace ON question (workspace_id);

-- De-duplication on import. Scoped to the bank: the same question legitimately
-- appears in two banks, but never twice in one.
CREATE UNIQUE INDEX uk_question_bank_content
    ON question (bank_id, content_hash) WHERE superseded_by IS NULL;
```

> **Why `VARCHAR(64)` and not `CHAR(64)`.** M1 lost time to exactly this.
> Hibernate rejects `CHAR` as `bpchar` when validating against a `String`
> field, and Postgres blank-pads `CHAR`, which silently breaks hash
> comparison. Never use `CHAR` for a digest column.

- [x] **Step 4: Declare the module**

`apps/api/src/main/java/com/quizforge/content/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Content",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "identity"}
)
package com.quizforge.content;
```

- [x] **Step 5: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=ContentSchemaTest`
Expected: PASS, 4 tests.

- [x] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V6__content.sql \
        apps/api/src/main/java/com/quizforge/content \
        apps/api/src/test/java/com/quizforge/content
git commit -m "feat(content): add question bank and question schema"
```

Update `CHANGELOG.md` in the same commit.

---

## Task 2: Question types, payloads and validation

**Files:**
- Create: `content/domain/QuestionType.java`, `content/domain/payload/{ChoicePayload,NumericPayload,ShortTextPayload}.java`
- Test: `content/domain/QuestionPayloadTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: `QuestionType.parsePayload(String json) -> Payload` and `Payload.validate()` throwing `ApiException(INVALID_REQUEST)`. Task 3 persists them; Task 4 grades them.

- [x] **Step 1: Write the failing test**

```java
package com.quizforge.content.domain;

import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionPayloadTest {

    @Test
    void singleChoiceRequiresExactlyOneCorrectOption() {
        var valid = new ChoicePayload(
                List.of(new ChoicePayload.Option("a", true),
                        new ChoicePayload.Option("b", false)));
        valid.validate(QuestionType.SINGLE_CHOICE);

        var twoCorrect = new ChoicePayload(
                List.of(new ChoicePayload.Option("a", true),
                        new ChoicePayload.Option("b", true)));

        assertThatThrownBy(() -> twoCorrect.validate(QuestionType.SINGLE_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void multiChoiceRequiresAtLeastOneCorrectOption() {
        var noneCorrect = new ChoicePayload(
                List.of(new ChoicePayload.Option("a", false),
                        new ChoicePayload.Option("b", false)));

        assertThatThrownBy(() -> noneCorrect.validate(QuestionType.MULTI_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void choiceRequiresAtLeastTwoOptions() {
        var single = new ChoicePayload(List.of(new ChoicePayload.Option("a", true)));

        assertThatThrownBy(() -> single.validate(QuestionType.SINGLE_CHOICE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void numericRejectsNegativeTolerance() {
        assertThatThrownBy(() -> new NumericPayload(10.0, -1.0).validate(QuestionType.NUMERIC))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void shortTextRequiresAtLeastOneAcceptedAnswer() {
        assertThatThrownBy(() ->
                new ShortTextPayload(List.of(), true).validate(QuestionType.SHORT_TEXT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void payloadsRoundTripThroughJson() {
        var payload = new ChoicePayload(
                List.of(new ChoicePayload.Option("Paris", true),
                        new ChoicePayload.Option("Lyon", false)));

        String json = QuestionType.SINGLE_CHOICE.writePayload(payload);
        var parsed = (ChoicePayload) QuestionType.SINGLE_CHOICE.parsePayload(json);

        assertThat(parsed.options()).hasSize(2);
        assertThat(parsed.options().get(0).text()).isEqualTo("Paris");
        assertThat(parsed.options().get(0).correct()).isTrue();
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=QuestionPayloadTest`
Expected: FAIL — none of these types exist.

- [x] **Step 3: Implement the payload contract**

`content/domain/payload/Payload.java`:

```java
package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;

/** Type-specific question data. Implementations are immutable records. */
public interface Payload {

    /**
     * Rejects a payload that cannot be graded — no correct answer, too few
     * options, a negative tolerance. Called on write so a malformed question
     * can never reach the database, since the JSONB column cannot enforce
     * shape itself.
     */
    void validate(QuestionType type);
}
```

`content/domain/payload/ChoicePayload.java`:

```java
package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

import java.util.List;

public record ChoicePayload(List<Option> options) implements Payload {

    public record Option(String text, boolean correct) {
    }

    @Override
    public void validate(QuestionType type) {
        if (options == null || options.size() < 2) {
            throw ApiException.invalid("a choice question needs at least two options");
        }
        if (options.stream().anyMatch(o -> o.text() == null || o.text().isBlank())) {
            throw ApiException.invalid("every option needs text");
        }

        long correct = options.stream().filter(Option::correct).count();

        switch (type) {
            case SINGLE_CHOICE, TRUE_FALSE -> {
                if (correct != 1) {
                    throw ApiException.invalid(
                            "a " + type + " question needs exactly one correct option");
                }
            }
            case MULTI_CHOICE -> {
                if (correct < 1) {
                    throw ApiException.invalid(
                            "a multiple-answer question needs at least one correct option");
                }
            }
            default -> throw ApiException.invalid(type + " does not use a choice payload");
        }

        if (type == QuestionType.TRUE_FALSE && options.size() != 2) {
            throw ApiException.invalid("a true/false question needs exactly two options");
        }
    }
}
```

`content/domain/payload/NumericPayload.java`:

```java
package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

/** {@code tolerance} is absolute: an answer counts if |given - value| <= tolerance. */
public record NumericPayload(Double value, Double tolerance) implements Payload {

    @Override
    public void validate(QuestionType type) {
        if (type != QuestionType.NUMERIC) {
            throw ApiException.invalid(type + " does not use a numeric payload");
        }
        if (value == null) {
            throw ApiException.invalid("a numeric question needs a value");
        }
        if (tolerance == null || tolerance < 0) {
            throw ApiException.invalid("tolerance must be zero or greater");
        }
    }
}
```

`content/domain/payload/ShortTextPayload.java`:

```java
package com.quizforge.content.domain.payload;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.platform.error.ApiException;

import java.util.List;

/**
 * Accepted answers are compared after normalisation, never by edit distance.
 * Fuzzy matching produces disputes with no principled threshold; an author who
 * wants leniency supplies more accepted answers.
 */
public record ShortTextPayload(List<String> accepted, boolean ignoreCase) implements Payload {

    @Override
    public void validate(QuestionType type) {
        if (type != QuestionType.SHORT_TEXT) {
            throw ApiException.invalid(type + " does not use a short text payload");
        }
        if (accepted == null || accepted.isEmpty()) {
            throw ApiException.invalid("a short text question needs at least one accepted answer");
        }
        if (accepted.stream().anyMatch(a -> a == null || a.isBlank())) {
            throw ApiException.invalid("accepted answers cannot be blank");
        }
    }
}
```

- [x] **Step 4: Implement the type enum**

```java
package com.quizforge.content.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import com.quizforge.platform.error.ApiException;

public enum QuestionType {

    SINGLE_CHOICE(ChoicePayload.class),
    MULTI_CHOICE(ChoicePayload.class),
    TRUE_FALSE(ChoicePayload.class),
    NUMERIC(NumericPayload.class),
    SHORT_TEXT(ShortTextPayload.class);

    // Static because payload conversion happens inside entities and value
    // objects that Spring does not manage. Configured identically to the
    // application's mapper for the small surface used here: plain records.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Class<? extends Payload> payloadType;

    QuestionType(Class<? extends Payload> payloadType) {
        this.payloadType = payloadType;
    }

    public Payload parsePayload(String json) {
        try {
            return MAPPER.readValue(json, payloadType);
        } catch (Exception e) {
            throw ApiException.invalid("payload is not valid for a " + name() + " question");
        }
    }

    public String writePayload(Payload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw ApiException.invalid("payload could not be serialised");
        }
    }
}
```

- [x] **Step 5: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=QuestionPayloadTest`
Expected: PASS, 6 tests.

- [x] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/content apps/api/src/test/java/com/quizforge/content
git commit -m "feat(content): add question types with validated payloads"
```

---

## Task 3: Banks and immutable question authoring

**Files:**
- Create: `content/domain/{QuestionBank,Question}.java`, `content/repo/*.java`,
  `content/app/{QuestionBankService,QuestionService,ContentHash}.java`
- Test: `content/app/QuestionServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1–2, `identity`'s `WorkspaceService.roleOf`
- Produces:
  - `QuestionBankService.create(UUID workspaceId, UUID actorId, String name) -> QuestionBank`
  - `QuestionBankService.requireById(UUID bankId) -> QuestionBank` (throws `ApiException.notFound`)
  - `QuestionService.author(UUID bankId, UUID actorId, QuestionType type, String prompt, Payload payload, String difficulty) -> Question`
  - `QuestionService.revise(UUID questionId, UUID actorId, String prompt, Payload payload, String difficulty) -> Question`
  - `QuestionService.requireById(UUID questionId) -> Question`
  - `QuestionService.currentIn(UUID bankId) -> List<Question>`
  - `QuestionBankRepository.findByWorkspaceIdAndArchivedAtIsNull(UUID workspaceId) -> List<QuestionBank>`

- [x] **Step 1: Write the failing test**

```java
package com.quizforge.content.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionServiceTest extends AbstractIntegrationTest {

    @Autowired private QuestionService questions;
    @Autowired private QuestionBankService banks;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    private static ChoicePayload capitalOfFrance() {
        return new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));
    }

    @Test
    void authorsAQuestionAtVersionOne() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        var question = questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        assertThat(question.getVersion()).isEqualTo(1);
        assertThat(question.getLineageId()).isEqualTo(question.getId());
        assertThat(question.getSupersededBy()).isNull();
    }

    @Test
    void revisingCreatesANewVersionAndLeavesTheOriginalIntact() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        var first = questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        var second = questions.revise(first.getId(), owner,
                "Which city is the capital of France?", capitalOfFrance(), "EASY");

        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(second.getLineageId()).isEqualTo(first.getLineageId());
        assertThat(second.getId()).isNotEqualTo(first.getId());

        // The original row is untouched apart from the supersession pointer,
        // which is what lets a tournament pin exactly what a player saw.
        var original = questions.requireById(first.getId());
        assertThat(original.getPrompt()).isEqualTo("What is the capital of France?");
        assertThat(original.getSupersededBy()).isEqualTo(second.getId());
    }

    @Test
    void currentListingExcludesSupersededVersions() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        var first = questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");
        questions.revise(first.getId(), owner,
                "Which city is the capital of France?", capitalOfFrance(), "EASY");

        var current = questions.currentIn(bank.getId());

        assertThat(current).hasSize(1);
        assertThat(current.get(0).getVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAPayloadThatCannotBeGraded() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        var noCorrectAnswer = new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", false),
                new ChoicePayload.Option("Lyon", false)));

        assertThatThrownBy(() -> questions.author(bank.getId(), owner,
                QuestionType.SINGLE_CHOICE, "Capital?", noCorrectAnswer, "EASY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void viewersCannotAuthor() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        UUID viewer = newAccount();
        workspaces.addMember(workspace.getId(), owner, viewer, Role.VIEWER);

        assertThatThrownBy(() -> questions.author(bank.getId(), viewer,
                QuestionType.SINGLE_CHOICE, "Capital?", capitalOfFrance(), "EASY"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void rejectsADuplicateQuestionInTheSameBank() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                "What is the capital of France?", capitalOfFrance(), "EASY");

        // Same content, different whitespace and casing - the hash normalises.
        assertThatThrownBy(() -> questions.author(bank.getId(), owner,
                QuestionType.SINGLE_CHOICE, "  What is the CAPITAL of France?  ",
                capitalOfFrance(), "EASY"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.ALREADY_EXISTS));
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=QuestionServiceTest`
Expected: FAIL — `QuestionService` does not exist.

- [x] **Step 3: Implement the content hash**

```java
package com.quizforge.content.app;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * De-duplication key for imported and authored questions.
 *
 * <p>Hashes normalised content rather than raw text, because the same question
 * arrives from OpenTDB with different HTML entity encodings, casing and
 * spacing. Answers are sorted so option order does not change the hash.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(QuestionType type, String prompt, Payload payload) {
        String answers = switch (payload) {
            case ChoicePayload c -> c.options().stream()
                    .map(o -> normalise(o.text()) + ":" + o.correct())
                    .sorted()
                    .collect(Collectors.joining("|"));
            case ShortTextPayload s -> s.accepted().stream()
                    .map(ContentHash::normalise)
                    .sorted()
                    .collect(Collectors.joining("|"));
            default -> String.valueOf(payload);
        };

        return sha256(type.name() + " " + normalise(prompt) + " " + answers);
    }

    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
```

- [x] **Step 4: Implement entities, repositories and services**

The entities follow M1's pattern exactly: `@Id UUID`, explicit `@Column`
names, `protected` no-arg constructor for JPA, no setters beyond intentional
mutation. `Question` exposes `supersede(UUID)` and nothing else that mutates.

`QuestionService.author` must, in order: check `MANAGE_CONTENT` permission via
`WorkspaceService.roleOf`, validate the payload, compute the content hash,
reject a duplicate with `ALREADY_EXISTS`, then insert with `version = 1` and
`lineage_id = id`.

`QuestionService.revise` must: load the current version, check permission,
validate, insert a new row with `version + 1` and the same `lineage_id`, then
call `supersede` on the previous row. Both writes share one transaction.

- [x] **Step 5: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=QuestionServiceTest`
Expected: PASS, 6 tests.

- [x] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/content apps/api/src/test/java/com/quizforge/content
git commit -m "feat(content): add banks and immutable question authoring"
```

---

## Task 4: Server-side grading

**Files:**
- Create: `content/grading/{QuestionGrader,GradingResult,ChoiceGrader,NumericGrader,ShortTextGrader,GraderRegistry}.java`
- Test: `content/grading/GradingTest.java`

**Interfaces:**
- Consumes: Task 3
- Produces: `GraderRegistry.grade(QuestionType, Payload, String givenAnswer) -> GradingResult`, plus a convenience overload `grade(Question, String)` that unpacks the entity's type and payload. M3's attempt submission calls the latter.

- [x] **Step 1: Write the failing test**

```java
package com.quizforge.content.grading;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradingTest {

    private final GraderRegistry graders = new GraderRegistry(
            List.of(new ChoiceGrader(), new NumericGrader(), new ShortTextGrader()));

    @Test
    void singleChoiceAcceptsOnlyTheCorrectOption() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));

        assertThat(graders.grade(QuestionType.SINGLE_CHOICE, payload, "Paris").correct()).isTrue();
        assertThat(graders.grade(QuestionType.SINGLE_CHOICE, payload, "Lyon").correct()).isFalse();
    }

    @Test
    void multiChoiceRequiresEveryCorrectOptionAndNoIncorrectOnes() {
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("a", true),
                new ChoicePayload.Option("b", true),
                new ChoicePayload.Option("c", false)));

        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a,b").correct()).isTrue();
        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a").correct()).isFalse();
        assertThat(graders.grade(QuestionType.MULTI_CHOICE, payload, "a,b,c").correct()).isFalse();
    }

    @Test
    void numericAcceptsWithinTolerance() {
        var payload = new NumericPayload(10.0, 0.5);

        assertThat(graders.grade(QuestionType.NUMERIC, payload, "10.4").correct()).isTrue();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "10.6").correct()).isFalse();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "not a number").correct()).isFalse();
    }

    @Test
    void shortTextNormalisesCaseAccentsAndWhitespace() {
        var payload = new ShortTextPayload(List.of("Café"), true);

        assertThat(graders.grade(QuestionType.SHORT_TEXT, payload, "  cafe  ").correct()).isTrue();
        assertThat(graders.grade(QuestionType.SHORT_TEXT, payload, "coffee").correct()).isFalse();
    }

    @Test
    void gradingResultNeverCarriesTheCorrectAnswerWhenWrong() {
        // The player-facing result must not leak the answer. Revealing it is a
        // separate, deliberate step after an attempt is graded and closed.
        var payload = new ChoicePayload(List.of(
                new ChoicePayload.Option("Paris", true),
                new ChoicePayload.Option("Lyon", false)));

        var result = graders.grade(QuestionType.SINGLE_CHOICE, payload, "Lyon");

        assertThat(result.correct()).isFalse();
        assertThat(result.toString()).doesNotContain("Paris");
    }

    @Test
    void anEmptyAnswerIsIncorrectRatherThanAnError() {
        var payload = new NumericPayload(10.0, 0.0);

        assertThat(graders.grade(QuestionType.NUMERIC, payload, null).correct()).isFalse();
        assertThat(graders.grade(QuestionType.NUMERIC, payload, "").correct()).isFalse();
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=GradingTest`
Expected: FAIL — the grading package does not exist.

- [x] **Step 3: Implement the contract**

```java
package com.quizforge.content.grading;

/**
 * The outcome of grading one answer. Deliberately carries no reference to the
 * correct answer: this crosses the network to the player, and revealing the
 * answer is a separate step taken only after an attempt is closed.
 */
public record GradingResult(boolean correct) {

    public static GradingResult correct() {
        return new GradingResult(true);
    }

    public static GradingResult incorrect() {
        return new GradingResult(false);
    }
}
```

```java
package com.quizforge.content.grading;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.Payload;

import java.util.Set;

public interface QuestionGrader {

    /** The types this grader handles. */
    Set<QuestionType> handles();

    /**
     * Grades one answer. A null, blank, or unparseable answer is incorrect,
     * never an error — a player fumbling input must not produce a 500.
     */
    GradingResult grade(Payload payload, String given);
}
```

- [x] **Step 4: Implement the three graders and the registry**

`ChoiceGrader` handles `SINGLE_CHOICE`, `MULTI_CHOICE` and `TRUE_FALSE`.
Multi-choice splits the given answer on commas, normalises each with
`ContentHash.normalise`, and requires set equality with the correct options —
selecting every correct option plus an incorrect one is wrong.

`NumericGrader` parses with `Double.parseDouble` inside a try/catch returning
`incorrect()`, then compares `Math.abs(given - value) <= tolerance`.

`ShortTextGrader` normalises both sides with `ContentHash.normalise` (which
already applies NFKC, whitespace collapsing and lowercasing) and checks
membership in the accepted list. When `ignoreCase` is false, compare the
NFKC-normalised originals instead.

`GraderRegistry` builds a `Map<QuestionType, QuestionGrader>` from the injected
list at construction, and throws `ApiException(INTERNAL, …)` for a type with no
grader — an unreachable state that must fail loudly rather than silently
marking answers wrong.

- [x] **Step 5: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=GradingTest`
Expected: PASS, 6 tests.

- [x] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/content/grading \
        apps/api/src/test/java/com/quizforge/content/grading
git commit -m "feat(content): add server-side grading for every question type"
```

---

## Task 5: Tenant isolation for content

**Files:**
- Create: `db/migration/V7__content_rls.sql`
- Test: `content/ContentIsolationTest.java`

**Interfaces:**
- Consumes: Tasks 1–4, M1's `TenantAwareDataSource`
- Produces: `question_bank` and `question` enforced by PostgreSQL.

- [x] **Step 1: Write the failing test**

Model it on `RlsRuntimeEnforcementTest`: create banks in two workspaces, set
`TenantContext` to the second, and assert a repository query explicitly asking
for the first workspace's bank returns nothing.

```java
package com.quizforge.content;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.repo.QuestionBankRepository;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIsolationTest extends AbstractIntegrationTest {

    @Autowired private QuestionBankRepository bankRepository;
    @Autowired private com.quizforge.content.app.QuestionBankService banks;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;
    @Autowired private TransactionTemplate transactions;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void aWorkspaceCannotSeeAnotherWorkspacesBanks() {
        UUID ownerA = newAccount();
        var workspaceA = workspaces.create(ownerA, "Acme");
        TenantContext.set(workspaceA.getId());
        banks.create(workspaceA.getId(), ownerA, "Geography");
        TenantContext.clear();

        UUID ownerB = newAccount();
        var workspaceB = workspaces.create(ownerB, "Globex");

        TenantContext.set(workspaceB.getId());
        long visible = transactions.execute(status ->
                (long) bankRepository.findByWorkspaceIdAndArchivedAtIsNull(
                        workspaceA.getId()).size());
        TenantContext.clear();

        assertThat(visible)
                .as("workspace B must not see workspace A's banks, even when asking")
                .isZero();
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Expected: FAIL — `visible` is 1, because no policy exists yet.

- [x] **Step 3: Write the migration**

```sql
-- V7: Tenant isolation for content, matching the pattern established in V5.

ALTER TABLE question_bank ENABLE ROW LEVEL SECURITY;
ALTER TABLE question_bank FORCE ROW LEVEL SECURITY;

CREATE POLICY question_bank_tenant_isolation ON question_bank
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

ALTER TABLE question ENABLE ROW LEVEL SECURITY;
ALTER TABLE question FORCE ROW LEVEL SECURITY;

CREATE POLICY question_tenant_isolation ON question
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- question carries workspace_id denormalised from its bank precisely so this
-- policy needs no join. A policy that joins runs on every row of every query.
```

- [x] **Step 4: Run to verify it passes**

Expected: PASS. If it still returns 1, the transaction is not assuming the
restricted role — check that `TenancyConfig`'s wrapper is `@Primary` and that
the query runs inside a transaction.

- [x] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V7__content_rls.sql \
        apps/api/src/test/java/com/quizforge/content/ContentIsolationTest.java
git commit -m "feat(content): enforce tenant isolation on banks and questions"
```

---

## Task 6: Import pipeline

**Files:**
- Create: `content/importer/{QuestionImporter,ImportReport,OpenTdbImporter,CsvImporter}.java`
- Test: `content/importer/{CsvImporterTest,OpenTdbImporterTest}.java`

**Interfaces:**
- Consumes: Task 3
- Produces: `QuestionImporter.importInto(bankId, actorId, source) -> ImportReport(imported, skipped, failed, messages)`.

- [x] **Step 1: Write the failing CSV test**

```java
package com.quizforge.content.importer;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImporterTest extends AbstractIntegrationTest {

    @Autowired private CsvImporter importer;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static final String CSV = """
            type,prompt,options,correct,difficulty
            SINGLE_CHOICE,What is the capital of France?,Paris|Lyon|Nice,Paris,EASY
            TRUE_FALSE,The Earth is flat.,True|False,False,EASY
            NUMERIC,How many continents are there?,,7,MEDIUM
            """;

    private UUID setUpBank() {
        UUID owner = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(owner, "Acme");
        TenantContext.set(workspace.getId());
        return banks.create(workspace.getId(), owner, "Imported").getId();
    }

    @Test
    void importsEveryValidRow() {
        UUID bankId = setUpBank();
        UUID actor = ownerOf(bankId);

        var report = importer.importInto(bankId, actor, CSV);

        assertThat(report.imported()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(questions.currentIn(bankId)).hasSize(3);
    }

    @Test
    void skipsDuplicatesRatherThanFailing() {
        UUID bankId = setUpBank();
        UUID actor = ownerOf(bankId);

        importer.importInto(bankId, actor, CSV);
        var second = importer.importInto(bankId, actor, CSV);

        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isEqualTo(3);
        assertThat(questions.currentIn(bankId)).hasSize(3);
    }

    @Test
    void reportsBadRowsWithoutAbandoningGoodOnes() {
        UUID bankId = setUpBank();
        UUID actor = ownerOf(bankId);

        String mixed = """
                type,prompt,options,correct,difficulty
                SINGLE_CHOICE,Good question?,A|B,A,EASY
                NONSENSE_TYPE,Bad question?,A|B,A,EASY
                SINGLE_CHOICE,Missing answer?,A|B,,EASY
                """;

        var report = importer.importInto(bankId, actor, mixed);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(2);
        assertThat(report.messages()).hasSize(2);
        assertThat(report.messages().get(0)).contains("row 3");
    }

    private UUID ownerOf(UUID bankId) {
        return banks.requireById(bankId).getCreatedBy();
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Expected: FAIL — `CsvImporter` does not exist.

- [x] **Step 3: Implement the report and interface**

```java
package com.quizforge.content.importer;

import java.util.List;

/**
 * The outcome of an import. Partial success is the normal case: one malformed
 * row must not discard the other four hundred, and the caller needs to know
 * precisely which rows failed and why.
 */
public record ImportReport(int imported, int skipped, int failed, List<String> messages) {
}
```

```java
package com.quizforge.content.importer;

import java.util.UUID;

public interface QuestionImporter {

    /** Imports into an existing bank. Never creates one. */
    ImportReport importInto(UUID bankId, UUID actorId, String source);
}
```

- [x] **Step 4: Implement the CSV importer**

Columns: `type,prompt,options,correct,difficulty`. `options` is pipe-separated
and empty for `NUMERIC` and `SHORT_TEXT`. `correct` is the option text, the
numeric value, or a pipe-separated list of accepted answers.

Each row is imported in its own transaction so a failure rolls back only that
row. Duplicates — detected by `ALREADY_EXISTS` from `QuestionService.author` —
increment `skipped`, not `failed`; re-importing the same file must be a no-op
rather than an error. Messages are 1-indexed by file line including the header,
so `row 3` is the third line a human sees in a spreadsheet.

- [x] **Step 5: Implement the OpenTDB importer**

Replaces the legacy `OpenTDBService`. Differences that matter:

- Fetches into the new `Question` model rather than the legacy entity
- Decodes `base64` encoding rather than `url3986` — OpenTDB's URL encoding
  produces the double-encoding bugs visible in the legacy code
- Maps OpenTDB `multiple` to `SINGLE_CHOICE` and `boolean` to `TRUE_FALSE`
- De-duplicates through the same content hash, so re-importing a category
  after OpenTDB adds questions imports only the new ones
- Respects OpenTDB's rate limit with a single retry and a bounded wait, and
  **fails the import with a clear message rather than sleeping for 30 seconds**
  the way the legacy code did

The importer is disabled in tests via the existing
`quizforge.opentdb.bootstrap-enabled` property so the suite stays off the
network. `OpenTdbImporterTest` exercises the mapping with a recorded response
fixture, not a live call.

- [x] **Step 6: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest='CsvImporterTest,OpenTdbImporterTest'`
Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/content/importer \
        apps/api/src/test/java/com/quizforge/content/importer
git commit -m "feat(content): add csv and opentdb import pipeline"
```

---

## Definition of done for M2

- [x] `cd apps/api && ./mvnw verify` passes from a clean clone
- [x] A question can be authored, revised, and listed; revision leaves the original row intact
- [x] A tournament could pin a specific `question.id` and be certain of what it contains
- [x] All five question types validate on write and grade on read
- [x] No player-facing type carries a correct answer — asserted by test
- [x] `question_bank` and `question` are RLS-enforced, proven by an isolation test
- [x] CSV import handles partial failure, reports precise row numbers, and is idempotent
- [x] OpenTDB import replaces the legacy service and makes no network call in tests
- [x] `ModularityTest` and `ArchitectureTest` pass
- [x] `CHANGELOG.md`, `STATUS.md` and this plan's checkboxes are current

## Explicitly out of scope for M2

HTTP endpoints for content management — those arrive in M4, generated from the
OpenAPI contract, for the same reason workspace management was deferred in M1.
Also deferred: media attachments on questions, question tags and search,
bulk edit, LaTeX or Markdown rendering in prompts, and per-question analytics
(which needs M3's attempt data to exist first).

The legacy `cs.quizzapp` package is **not** removed here. `OpenTdbImporter`
replaces `OpenTDBService` functionally, but the legacy quiz endpoints keep
using the old one until M3 retires the package wholesale.
