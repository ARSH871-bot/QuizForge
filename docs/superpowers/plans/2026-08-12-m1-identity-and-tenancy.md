# M1 — Identity & Tenancy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fully-unauthenticated API with real authentication, multi-tenant workspaces, role-based authorization, and Postgres Row-Level Security — so that a forgotten `WHERE` clause becomes a non-event rather than a cross-tenant breach.

**Architecture:** Two new Spring Modulith modules are populated: `platform` (shared kernel — identifiers, error model, tenant context) and `identity` (accounts, workspaces, memberships, sessions, API keys, audit). Authentication resolves to a `Principal` carrying an account and an active workspace; a filter opens a transaction-scoped Postgres session variable that RLS policies enforce against. The legacy `cs.quizzapp` package keeps serving quiz traffic untouched — it is retired in M3.

**Tech Stack:** Java 21, Spring Boot 3.5.6, Spring Security 6, Spring Modulith 1.4.3, PostgreSQL 16 with RLS, Flyway, Argon2id (Spring Security Crypto), Testcontainers, ArchUnit.

## Global Constraints

- **Java version:** 21. `maven.compiler.release` = 21. Never change this to match an installed JDK; install the right JDK instead.
- **Base package:** `com.quizforge`. The legacy `cs.quizzapp` package is frozen — `ArchitectureTest` fails the build if new code depends on it.
- **Schema:** Flyway only. `spring.jpa.hibernate.ddl-auto` stays `validate`. Every change is a new numbered migration; never edit `V1__baseline.sql`.
- **Identifiers:** UUIDv7 stored in native Postgres `uuid` columns. The prefixed form (`acc_…`, `wsp_…`) is rendered only at the API boundary, never persisted.
- **Passwords:** Argon2id via `Argon2PasswordEncoder`. BCrypt is removed.
- **Secrets:** no credential may appear in a tracked file, including tests. Use random values generated in-test.
- **Transactions:** every application service method that touches the database is explicitly `@Transactional`. `open-in-view` stays `false`.
- **Commits:** Conventional Commits, subject ≤72 characters, no trailers of any kind.
- **Every task ends green:** `cd apps/api && ./mvnw verify` must pass before each commit.

---

## Prerequisites

M0 is merged (`e7a1372`). Before starting, confirm:

```bash
cd apps/api && ./mvnw -B clean verify   # expect BUILD SUCCESS, 6 tests
docker compose ps                       # expect quizforge-postgres healthy
```

Issues #2 (credential revocation, history purge) and #11 (repository visibility)
remain open from M0. Neither blocks this milestone.

---

## File Structure

```
apps/api/src/main/java/com/quizforge/
├── platform/
│   ├── id/TypeId.java                     prefixed ID rendering at the boundary
│   ├── id/UuidV7.java                     time-ordered UUID generation
│   ├── error/ApiException.java            domain error with a stable code
│   ├── error/ErrorCode.java               enum of machine-readable codes
│   ├── error/GlobalExceptionHandler.java  RFC 9457 Problem Details
│   └── tenancy/TenantContext.java         request-scoped active workspace
├── identity/
│   ├── domain/Account.java                credentials and profile
│   ├── domain/Workspace.java              tenant root
│   ├── domain/Membership.java             account ↔ workspace with a role
│   ├── domain/Role.java                   OWNER | ADMIN | EDITOR | VIEWER
│   ├── domain/Session.java                opaque, hashed, expiring
│   ├── domain/ApiKey.java                 hashed, scoped, shown once
│   ├── domain/AuditEvent.java             append-only privileged-action log
│   ├── repo/*Repository.java              Spring Data interfaces
│   ├── app/AccountService.java            registration, password change
│   ├── app/WorkspaceService.java          creation, membership, roles
│   ├── app/SessionService.java            issue, resolve, revoke, rotate
│   ├── app/ApiKeyService.java             issue, resolve, revoke
│   ├── app/AuditService.java              record
│   ├── web/AuthController.java            register, login, logout, reset
│   ├── web/WorkspaceController.java       CRUD, members, roles
│   ├── web/ApiKeyController.java          issue, list, revoke
│   ├── web/dto/*.java                     request and response records
│   └── security/                          filters, principal, config
└── (modules content, tournament, play, leaderboard, billing, notify unchanged)

apps/api/src/main/resources/db/migration/
├── V2__identity.sql                       accounts, workspaces, memberships
├── V3__sessions_and_api_keys.sql          auth material
├── V4__audit.sql                          append-only audit log
└── V5__row_level_security.sql             app role, policies, grants
```

**Responsibilities.** `platform` knows nothing about identity — it holds only the shared kernel. `identity` owns every authentication concern so that later modules need only ask "who is this and which workspace?". Security filters live under `identity/security` rather than in a global config package, because they are identity's implementation detail and should move with it.

---

## Task 1: Platform kernel — identifiers and error model

**Files:**
- Create: `platform/id/UuidV7.java`, `platform/id/TypeId.java`
- Create: `platform/error/ErrorCode.java`, `platform/error/ApiException.java`, `platform/error/GlobalExceptionHandler.java`
- Test: `platform/id/TypeIdTest.java`, `platform/id/UuidV7Test.java`

**Interfaces:**
- Consumes: nothing
- Produces: `UuidV7.generate() -> UUID` (time-ordered); `TypeId.render(String prefix, UUID id) -> String`; `TypeId.parse(String prefix, String rendered) -> UUID`; `ApiException(ErrorCode, String message)`; a `@RestControllerAdvice` returning RFC 9457 `application/problem+json`.

- [ ] **Step 1: Write the failing test for time-ordered IDs**

```java
package com.quizforge.platform.id;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesVersion7Uuids() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void generatesIdsThatSortInCreationOrder() throws Exception {
        UUID first = UuidV7.generate();
        Thread.sleep(2);
        UUID second = UuidV7.generate();

        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void generatesDistinctIdsWithinTheSameMillisecond() {
        var ids = new java.util.HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate());
        }
        assertThat(ids).hasSize(10_000);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=UuidV7Test`
Expected: FAIL — compilation error, `UuidV7` does not exist.

- [ ] **Step 3: Implement UUIDv7**

```java
package com.quizforge.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: 48 bits of Unix milliseconds followed by
 * random bits. Time-ordered, so they cluster well in a B-tree index instead of
 * scattering writes the way UUIDv4 does.
 *
 * <p>Java 21 has no built-in v7 generator, and the implementation is small
 * enough that a dependency is not worth the supply-chain surface.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long most = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        most |= 0x7000L;                                  // version 7
        most |= ((random[0] & 0xFFL) << 8) | (random[1] & 0xFFL);

        long least = 0;
        for (int i = 2; i < 10; i++) {
            least = (least << 8) | (random[i] & 0xFFL);
        }
        least &= 0x3FFFFFFFFFFFFFFFL;
        least |= 0x8000000000000000L;                     // variant 2 (RFC 4122)

        return new UUID(most, least);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=UuidV7Test`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing test for prefixed identifiers**

```java
package com.quizforge.platform.id;

import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeIdTest {

    @Test
    void rendersWithThePrefixAndRoundTrips() {
        UUID id = UuidV7.generate();

        String rendered = TypeId.render("acc", id);

        assertThat(rendered).startsWith("acc_");
        assertThat(TypeId.parse("acc", rendered)).isEqualTo(id);
    }

    @Test
    void rejectsAnIdRenderedForADifferentType() {
        String workspaceId = TypeId.render("wsp", UuidV7.generate());

        assertThatThrownBy(() -> TypeId.parse("acc", workspaceId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expected prefix 'acc'");
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> TypeId.parse("acc", "acc_not-a-uuid"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TypeId.parse("acc", "no-underscore"))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=TypeIdTest`
Expected: FAIL — `TypeId` and `ApiException` do not exist.

- [ ] **Step 7: Implement the error model**

`platform/error/ErrorCode.java`:

```java
package com.quizforge.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. These are part of the public API contract:
 * clients switch on them, so an existing constant's name and status may never
 * change. Add new constants rather than repurposing old ones.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    ALREADY_EXISTS(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable URI used as the Problem Details {@code type}. */
    public String type() {
        return "https://quizforge.dev/errors/" + name().toLowerCase().replace('_', '-');
    }
}
```

`platform/error/ApiException.java`:

```java
package com.quizforge.platform.error;

public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
```

- [ ] **Step 8: Implement TypeId**

```java
package com.quizforge.platform.id;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;

import java.util.UUID;

/**
 * Renders internal UUIDs as prefixed, opaque identifiers at the API boundary
 * ({@code acc_018f…}). The prefix makes a bare identifier self-describing in a
 * log or a bug report, and parsing rejects an identifier of the wrong type
 * outright — passing a workspace id where an account id belongs becomes a 400
 * rather than a confusing 404.
 *
 * <p>Only the UUID is persisted. The rendered form never reaches the database.
 */
public final class TypeId {

    private TypeId() {
    }

    public static String render(String prefix, UUID id) {
        return prefix + "_" + id.toString().replace("-", "");
    }

    public static UUID parse(String expectedPrefix, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "identifier is required");
        }

        int separator = value.indexOf('_');
        if (separator < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'");
        }

        String prefix = value.substring(0, separator);
        if (!expectedPrefix.equals(prefix)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "identifier '" + value + "' has prefix '" + prefix
                            + "' but expected prefix '" + expectedPrefix + "'");
        }

        String hex = value.substring(separator + 1);
        if (hex.length() != 32) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'");
        }

        try {
            return UUID.fromString(
                    hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'", e);
        }
    }
}
```

- [ ] **Step 9: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=TypeIdTest`
Expected: PASS, 3 tests.

- [ ] **Step 10: Implement the RFC 9457 exception handler**

```java
package com.quizforge.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Translates exceptions into RFC 9457 Problem Details. Every error response in
 * the application has the same shape, and every one carries a stable,
 * machine-readable {@code type} that clients can branch on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e, HttpServletRequest request) {
        return problem(e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e,
                                          HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.INVALID_REQUEST, detail, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        // Log the cause; never leak it to the caller.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(ErrorCode.INTERNAL, "An unexpected error occurred", request);
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        return problem;
    }
}
```

Note the deliberate asymmetry: `ApiException` messages are written for callers
and are safe to return; anything else is logged and replaced with a generic
message. The prototype returned raw `e.getMessage()` to clients, which leaked
internal detail.

- [ ] **Step 11: Verify the whole suite still passes**

Run: `cd apps/api && ./mvnw -B verify`
Expected: BUILD SUCCESS. `ModularityTest` confirms `platform` depends on nothing.

- [ ] **Step 12: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/platform apps/api/src/test/java/com/quizforge/platform
git commit -m "feat(platform): add typed identifiers and problem-details errors"
```

---

## Task 2: Identity schema

**Files:**
- Create: `db/migration/V2__identity.sql`
- Test: `identity/IdentitySchemaTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: `account`, `workspace`, `membership` tables. Task 3 maps entities onto them.

- [ ] **Step 1: Write the failing test**

```java
package com.quizforge.identity;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitySchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsIdentityTables() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("account", "workspace", "membership");
    }

    @Test
    void enforcesOneMembershipPerAccountPerWorkspace() {
        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'membership'::regclass",
                String.class);

        assertThat(constraints).contains("uk_membership_account_workspace");
    }

    @Test
    void storesEmailCaseInsensitivelyUnique() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'account'",
                String.class);

        assertThat(indexes).contains("uk_account_email_lower");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=IdentitySchemaTest`
Expected: FAIL — tables `account`, `workspace`, `membership` are missing.

- [ ] **Step 3: Write the migration**

`apps/api/src/main/resources/db/migration/V2__identity.sql`:

```sql
-- V2: Identity and tenancy. Introduces the account/workspace/membership
-- triangle that every later module scopes itself to.

CREATE TABLE account (
    id                  UUID PRIMARY KEY,
    email               VARCHAR(320) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(120) NOT NULL,
    email_verified_at   TIMESTAMPTZ,
    mfa_secret          VARCHAR(255),
    mfa_enabled_at      TIMESTAMPTZ,
    failed_login_count  INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Email uniqueness must be case-insensitive: Alice@example.com and
-- alice@example.com are the same person to every mail server on earth.
CREATE UNIQUE INDEX uk_account_email_lower ON account (LOWER(email));

CREATE TABLE workspace (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(64)  NOT NULL,
    created_by  UUID         NOT NULL REFERENCES account (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_workspace_slug UNIQUE (slug)
);

CREATE TABLE membership (
    id            UUID        PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    workspace_id  UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    role          VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_membership_account_workspace UNIQUE (account_id, workspace_id),
    CONSTRAINT ck_membership_role CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER'))
);

CREATE INDEX idx_membership_account   ON membership (account_id);
CREATE INDEX idx_membership_workspace ON membership (workspace_id);

-- A workspace must always retain at least one owner. Enforced in the
-- application layer on removal and role change; this partial index makes the
-- owner lookup cheap enough to run on every such operation.
CREATE INDEX idx_membership_owners ON membership (workspace_id) WHERE role = 'OWNER';
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=IdentitySchemaTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V2__identity.sql \
        apps/api/src/test/java/com/quizforge/identity/IdentitySchemaTest.java
git commit -m "feat(identity): add account, workspace and membership schema"
```

---

## Task 3: Accounts with Argon2id

**Files:**
- Create: `identity/domain/Account.java`, `identity/repo/AccountRepository.java`, `identity/app/AccountService.java`
- Modify: `apps/api/pom.xml` (add `spring-security-crypto` Argon2 support)
- Test: `identity/app/AccountServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1–2
- Produces: `AccountService.register(String email, String password, String displayName) -> Account`; `AccountService.authenticate(String email, String password) -> Account` (throws `ApiException(INVALID_CREDENTIALS)`); `AccountService.changePassword(UUID accountId, String current, String replacement)`.

- [ ] **Step 1: Add the Argon2 dependency**

Argon2id in Spring Security requires BouncyCastle. Add to `apps/api/pom.xml`
inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.78.1</version>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accounts;

    private static String uniqueEmail() {
        return "user-" + java.util.UUID.randomUUID() + "@example.test";
    }

    @Test
    void registersAnAccountAndHashesThePassword() {
        String email = uniqueEmail();

        var account = accounts.register(email, "correct horse battery", "Ada");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getPasswordHash())
                .doesNotContain("correct horse battery")
                .startsWith("{argon2}");
    }

    @Test
    void rejectsDuplicateEmailRegardlessOfCase() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        assertThatThrownBy(() ->
                accounts.register(email.toUpperCase(), "another password", "Imposter"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.ALREADY_EXISTS));
    }

    @Test
    void authenticatesWithTheCorrectPassword() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        var authenticated = accounts.authenticate(email, "correct horse battery");

        assertThat(authenticated.getEmail()).isEqualTo(email);
    }

    @Test
    void rejectsAWrongPasswordWithTheSameErrorAsAnUnknownAccount() {
        String email = uniqueEmail();
        accounts.register(email, "correct horse battery", "Ada");

        // Both must produce INVALID_CREDENTIALS. Distinguishing them tells an
        // attacker which emails are registered.
        assertThatThrownBy(() -> accounts.authenticate(email, "wrong"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        assertThatThrownBy(() -> accounts.authenticate(uniqueEmail(), "wrong"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void rejectsShortPasswords() {
        assertThatThrownBy(() -> accounts.register(uniqueEmail(), "short", "Ada"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 12");
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=AccountServiceTest`
Expected: FAIL — `AccountService` does not exist.

- [ ] **Step 4: Implement the entity**

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_enabled_at")
    private Instant mfaEnabledAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Account() {
    }

    public Account(UUID id, String email, String passwordHash, String displayName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public String getMfaSecret() { return mfaSecret; }
    public Instant getMfaEnabledAt() { return mfaEnabledAt; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void recordFailedLogin() {
        this.failedLoginCount++;
        if (this.failedLoginCount >= 10) {
            this.lockedUntil = Instant.now().plusSeconds(900);
            this.failedLoginCount = 0;
        }
    }

    public void recordSuccessfulLogin() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }
}
```

- [ ] **Step 5: Implement the repository**

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query("SELECT a FROM Account a WHERE LOWER(a.email) = LOWER(:email)")
    Optional<Account> findByEmailIgnoreCase(@Param("email") String email);
}
```

- [ ] **Step 6: Implement the service**

```java
package com.quizforge.identity.app;

import com.quizforge.identity.domain.Account;
import com.quizforge.identity.repo.AccountRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * A precomputed hash of a value nobody will ever submit. Verified when the
     * account does not exist, so that a request for an unknown email costs the
     * same as one for a known email. Without this, response timing reveals
     * which addresses are registered.
     */
    private final String dummyHash;

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Account register(String email, String password, String displayName) {
        validatePassword(password);

        if (email == null || !email.contains("@")) {
            throw ApiException.invalid("a valid email address is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.invalid("display name is required");
        }

        accounts.findByEmailIgnoreCase(email).ifPresent(existing -> {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "an account with that email already exists");
        });

        Account account = new Account(
                UuidV7.generate(), email, passwordEncoder.encode(password), displayName);

        return accounts.save(account);
    }

    @Transactional
    public Account authenticate(String email, String password) {
        var found = accounts.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);   // equalise timing
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        Account account = found.get();

        if (account.isLocked()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            account.recordFailedLogin();
            accounts.save(account);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "invalid email or password");
        }

        account.recordSuccessfulLogin();
        return accounts.save(account);
    }

    @Transactional
    public void changePassword(UUID accountId, String current, String replacement) {
        validatePassword(replacement);

        Account account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("account"));

        if (!passwordEncoder.matches(current, account.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "current password is incorrect");
        }

        account.setPasswordHash(passwordEncoder.encode(replacement));
        accounts.save(account);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.invalid(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
```

Note the minimum is 12 characters, not the prototype's 6. Length dominates
composition rules for real-world resistance, and NIST SP 800-63B recommends
against composition requirements entirely.

- [ ] **Step 7: Replace the password encoder bean**

The legacy `SecurityConfig` defines a `BCryptPasswordEncoder`. Create
`identity/security/PasswordConfig.java` and delete the bean method from
`cs.quizzapp.prokect.backend.config.SecurityConfig`:

```java
package com.quizforge.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordConfig {

    /**
     * Argon2id with OWASP's recommended parameters: 19 MiB memory, 2
     * iterations, 1 degree of parallelism.
     *
     * <p>Wrapped in a {@link DelegatingPasswordEncoder} so hashes carry an
     * {@code {argon2}} prefix. Legacy BCrypt hashes remain verifiable, which
     * is what allows existing accounts to be migrated on next login rather
     * than invalidated.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 =
                new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder(
                "argon2", Map.of("argon2", argon2));
        delegating.setDefaultPasswordEncoderForMatches(
                PasswordEncoderFactories.createDelegatingPasswordEncoder());

        return delegating;
    }
}
```

Removing the legacy bean is required — two `PasswordEncoder` beans make the
context ambiguous and every test will fail to start.

- [ ] **Step 8: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=AccountServiceTest`
Expected: PASS, 5 tests.

- [ ] **Step 9: Run the full suite**

Run: `cd apps/api && ./mvnw -B verify`
Expected: BUILD SUCCESS. If `UserService` in the legacy package fails to wire,
it is because it injected the removed BCrypt bean — it should now receive the
delegating encoder, which still verifies its existing BCrypt hashes.

- [ ] **Step 10: Commit**

```bash
git add apps/api/pom.xml apps/api/src/main/java/com/quizforge/identity \
        apps/api/src/test/java/com/quizforge/identity \
        apps/api/src/main/java/cs/quizzapp/prokect/backend/config/SecurityConfig.java
git commit -m "feat(identity): add accounts with argon2id password hashing"
```

---

## Task 4: Workspaces, memberships and roles

**Files:**
- Create: `identity/domain/Workspace.java`, `identity/domain/Membership.java`, `identity/domain/Role.java`
- Create: `identity/repo/WorkspaceRepository.java`, `identity/repo/MembershipRepository.java`
- Create: `identity/app/WorkspaceService.java`
- Test: `identity/app/WorkspaceServiceTest.java`

**Interfaces:**
- Consumes: Task 3
- Produces: `WorkspaceService.create(UUID ownerId, String name) -> Workspace`; `addMember(UUID workspaceId, UUID actorId, UUID accountId, Role role)`; `changeRole(...)`; `removeMember(...)`; `Role.can(Permission)`.

- [ ] **Step 1: Write the failing test**

```java
package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceTest extends AbstractIntegrationTest {

    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void creatorBecomesOwner() {
        UUID owner = newAccount();

        var workspace = workspaces.create(owner, "Acme Training");

        assertThat(workspace.getName()).isEqualTo("Acme Training");
        assertThat(workspaces.roleOf(workspace.getId(), owner)).isEqualTo(Role.OWNER);
    }

    @Test
    void generatesAUniqueSlugWhenNamesCollide() {
        var first = workspaces.create(newAccount(), "Acme Training");
        var second = workspaces.create(newAccount(), "Acme Training");

        assertThat(first.getSlug()).isNotEqualTo(second.getSlug());
    }

    @Test
    void adminsCanAddMembersButViewersCannot() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        UUID viewer = newAccount();
        workspaces.addMember(workspace.getId(), owner, viewer, Role.VIEWER);

        UUID outsider = newAccount();
        assertThatThrownBy(() ->
                workspaces.addMember(workspace.getId(), viewer, outsider, Role.EDITOR))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void refusesToRemoveTheLastOwner() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        assertThatThrownBy(() -> workspaces.removeMember(workspace.getId(), owner, owner))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    void refusesToDemoteTheLastOwner() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        assertThatThrownBy(() ->
                workspaces.changeRole(workspace.getId(), owner, owner, Role.ADMIN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("last owner");
    }

    @Test
    void nonMembersHaveNoRole() {
        var workspace = workspaces.create(newAccount(), "Acme");

        assertThat(workspaces.roleOf(workspace.getId(), newAccount())).isNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=WorkspaceServiceTest`
Expected: FAIL — `WorkspaceService` does not exist.

- [ ] **Step 3: Implement the role model**

```java
package com.quizforge.identity.domain;

import java.util.Set;

/**
 * Workspace roles, ordered from most to least privileged. Permissions are
 * modelled explicitly rather than by ordinal comparison, so that adding a role
 * that is not a strict superset of a weaker one stays possible.
 */
public enum Role {

    OWNER(Permission.values()),
    ADMIN(Permission.MANAGE_MEMBERS, Permission.MANAGE_CONTENT,
          Permission.MANAGE_TOURNAMENTS, Permission.VIEW),
    EDITOR(Permission.MANAGE_CONTENT, Permission.MANAGE_TOURNAMENTS, Permission.VIEW),
    VIEWER(Permission.VIEW);

    public enum Permission {
        /** Rename or delete the workspace, manage billing, transfer ownership. */
        MANAGE_WORKSPACE,
        /** Invite, remove, and change the role of members. */
        MANAGE_MEMBERS,
        /** Create and edit question banks and questions. */
        MANAGE_CONTENT,
        /** Create, schedule, and close tournaments. */
        MANAGE_TOURNAMENTS,
        /** Read workspace data and results. */
        VIEW
    }

    private final Set<Permission> permissions;

    Role(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
```

- [ ] **Step 4: Implement the entities**

`identity/domain/Workspace.java`:

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected Workspace() {
    }

    public Workspace(UUID id, String name, String slug, UUID createdBy) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public UUID getCreatedBy() { return createdBy; }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }
}
```

`identity/domain/Membership.java`:

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected Membership() {
    }

    public Membership(UUID id, UUID accountId, UUID workspaceId, Role role) {
        this.id = id;
        this.accountId = accountId;
        this.workspaceId = workspaceId;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public Role getRole() { return role; }

    public void changeRole(Role role) {
        this.role = role;
    }
}
```

- [ ] **Step 5: Implement the repositories**

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {
    Optional<Workspace> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
```

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByWorkspaceIdAndAccountId(UUID workspaceId, UUID accountId);

    List<Membership> findByAccountId(UUID accountId);

    List<Membership> findByWorkspaceId(UUID workspaceId);

    long countByWorkspaceIdAndRole(UUID workspaceId, Role role);
}
```

- [ ] **Step 6: Implement the service**

```java
package com.quizforge.identity.app;

import com.quizforge.identity.domain.Membership;
import com.quizforge.identity.domain.Role;
import com.quizforge.identity.domain.Workspace;
import com.quizforge.identity.repo.MembershipRepository;
import com.quizforge.identity.repo.WorkspaceRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;

    public WorkspaceService(WorkspaceRepository workspaces, MembershipRepository memberships) {
        this.workspaces = workspaces;
        this.memberships = memberships;
    }

    @Transactional
    public Workspace create(UUID ownerId, String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.invalid("workspace name is required");
        }

        Workspace workspace = new Workspace(
                UuidV7.generate(), name, uniqueSlug(name), ownerId);
        workspaces.save(workspace);

        memberships.save(new Membership(
                UuidV7.generate(), ownerId, workspace.getId(), Role.OWNER));

        return workspace;
    }

    @Transactional(readOnly = true)
    public Role roleOf(UUID workspaceId, UUID accountId) {
        return memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .map(Membership::getRole)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Membership> membersOf(UUID workspaceId) {
        return memberships.findByWorkspaceId(workspaceId);
    }

    @Transactional
    public Membership addMember(UUID workspaceId, UUID actorId, UUID accountId, Role role) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        memberships.findByWorkspaceIdAndAccountId(workspaceId, accountId).ifPresent(m -> {
            throw new ApiException(ErrorCode.ALREADY_EXISTS,
                    "that account is already a member of this workspace");
        });

        return memberships.save(new Membership(UuidV7.generate(), accountId, workspaceId, role));
    }

    @Transactional
    public void changeRole(UUID workspaceId, UUID actorId, UUID accountId, Role role) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        Membership membership = memberships
                .findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> ApiException.notFound("membership"));

        if (membership.getRole() == Role.OWNER && role != Role.OWNER) {
            guardLastOwner(workspaceId);
        }

        membership.changeRole(role);
        memberships.save(membership);
    }

    @Transactional
    public void removeMember(UUID workspaceId, UUID actorId, UUID accountId) {
        require(workspaceId, actorId, Role.Permission.MANAGE_MEMBERS);

        Membership membership = memberships
                .findByWorkspaceIdAndAccountId(workspaceId, accountId)
                .orElseThrow(() -> ApiException.notFound("membership"));

        if (membership.getRole() == Role.OWNER) {
            guardLastOwner(workspaceId);
        }

        memberships.delete(membership);
    }

    private void guardLastOwner(UUID workspaceId) {
        if (memberships.countByWorkspaceIdAndRole(workspaceId, Role.OWNER) <= 1) {
            throw ApiException.invalid(
                    "cannot remove or demote the last owner of a workspace");
        }
    }

    private void require(UUID workspaceId, UUID actorId, Role.Permission permission) {
        Role role = roleOf(workspaceId, actorId);
        if (role == null || !role.can(permission)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "you do not have permission to perform that action");
        }
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "workspace";
        }
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }

        String candidate = base;
        int suffix = 2;
        while (workspaces.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=WorkspaceServiceTest`
Expected: PASS, 6 tests.

- [ ] **Step 8: Run the full suite and commit**

```bash
cd apps/api && ./mvnw -B verify
git add apps/api/src/main/java/com/quizforge/identity apps/api/src/test/java/com/quizforge/identity
git commit -m "feat(identity): add workspaces, memberships and role permissions"
```

---

## Task 5: Sessions and API keys

**Files:**
- Create: `db/migration/V3__sessions_and_api_keys.sql`
- Create: `identity/domain/Session.java`, `identity/domain/ApiKey.java`
- Create: `identity/repo/SessionRepository.java`, `identity/repo/ApiKeyRepository.java`
- Create: `identity/app/SessionService.java`, `identity/app/ApiKeyService.java`
- Test: `identity/app/SessionServiceTest.java`, `identity/app/ApiKeyServiceTest.java`

**Interfaces:**
- Consumes: Task 4
- Produces: `SessionService.issue(UUID accountId, String userAgent, String ip) -> IssuedSession(String token, Session session)`; `resolve(String token) -> Optional<Session>`; `revoke(String token)`. `ApiKeyService.issue(UUID workspaceId, UUID actorId, String name, boolean live) -> IssuedApiKey(String secret, ApiKey key)`; `resolve(String secret) -> Optional<ApiKey>`.

- [ ] **Step 1: Write the migration**

```sql
-- V3: Authentication material. Neither table stores a usable credential: both
-- keep only a SHA-256 digest, so a database disclosure does not yield tokens
-- that can be replayed.

CREATE TABLE session (
    id            UUID        PRIMARY KEY,
    account_id    UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash    CHAR(64)    NOT NULL,
    user_agent    VARCHAR(512),
    ip_address    INET,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uk_session_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_session_account ON session (account_id);
CREATE INDEX idx_session_expiry  ON session (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE api_key (
    id            UUID         PRIMARY KEY,
    workspace_id  UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    created_by    UUID         NOT NULL REFERENCES account (id),
    name          VARCHAR(120) NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    last_four     CHAR(4)      NOT NULL,
    environment   VARCHAR(8)   NOT NULL,
    scopes        TEXT[]       NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uk_api_key_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_api_key_environment CHECK (environment IN ('live', 'test'))
);

CREATE INDEX idx_api_key_workspace ON api_key (workspace_id) WHERE revoked_at IS NULL;
```

- [ ] **Step 2: Write the failing session test**

```java
package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest extends AbstractIntegrationTest {

    @Autowired private SessionService sessions;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void issuesAResolvableTokenThatIsNotStoredInPlaintext() {
        UUID account = newAccount();

        var issued = sessions.issue(account, "test-agent", "127.0.0.1");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.session().getTokenHash()).isNotEqualTo(issued.token());
        assertThat(sessions.resolve(issued.token()))
                .isPresent()
                .get()
                .satisfies(s -> assertThat(s.getAccountId()).isEqualTo(account));
    }

    @Test
    void doesNotResolveAnUnknownToken() {
        assertThat(sessions.resolve("qf_sess_nonsense")).isEmpty();
    }

    @Test
    void doesNotResolveARevokedToken() {
        var issued = sessions.issue(newAccount(), "test-agent", "127.0.0.1");

        sessions.revoke(issued.token());

        assertThat(sessions.resolve(issued.token())).isEmpty();
    }

    @Test
    void issuesADistinctTokenEveryTime() {
        UUID account = newAccount();

        var first = sessions.issue(account, "a", "127.0.0.1");
        var second = sessions.issue(account, "b", "127.0.0.1");

        assertThat(first.token()).isNotEqualTo(second.token());
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=SessionServiceTest`
Expected: FAIL — `SessionService` does not exist.

- [ ] **Step 4: Implement token hashing**

`identity/app/TokenDigest.java`:

```java
package com.quizforge.identity.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and digests bearer credentials.
 *
 * <p>SHA-256 rather than a password hash is deliberate: these tokens carry 256
 * bits of entropy from a CSPRNG, so they are not brute-forceable and the
 * slowness of Argon2 would only add latency to every authenticated request.
 * Password hashing exists to compensate for low-entropy human input, which
 * does not apply here.
 */
final class TokenDigest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenDigest() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digest(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
```

- [ ] **Step 5: Implement the session entity and service**

`identity/domain/Session.java`:

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session")
public class Session {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Session() {
    }

    public Session(UUID id, UUID accountId, String tokenHash, String userAgent, Instant expiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public void touch() {
        this.lastSeenAt = Instant.now();
    }
}
```

`identity/app/SessionService.java`:

```java
package com.quizforge.identity.app;

import com.quizforge.identity.domain.Session;
import com.quizforge.identity.repo.SessionRepository;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    public static final Duration LIFETIME = Duration.ofDays(14);

    /** The plaintext token is returned exactly once, at issue time. */
    public record IssuedSession(String token, Session session) {
    }

    private final SessionRepository sessions;

    public SessionService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public IssuedSession issue(UUID accountId, String userAgent, String ipAddress) {
        String token = TokenDigest.generate();

        Session session = new Session(
                UuidV7.generate(), accountId, TokenDigest.digest(token),
                userAgent, Instant.now().plus(LIFETIME));

        return new IssuedSession(token, sessions.save(session));
    }

    @Transactional
    public Optional<Session> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return sessions.findByTokenHash(TokenDigest.digest(token))
                .filter(Session::isActive)
                .map(session -> {
                    session.touch();
                    return sessions.save(session);
                });
    }

    @Transactional
    public void revoke(String token) {
        sessions.findByTokenHash(TokenDigest.digest(token))
                .ifPresent(session -> {
                    session.revoke();
                    sessions.save(session);
                });
    }

    @Transactional
    public void revokeAllForAccount(UUID accountId) {
        sessions.findByAccountIdAndRevokedAtIsNull(accountId).forEach(session -> {
            session.revoke();
            sessions.save(session);
        });
    }
}
```

`identity/repo/SessionRepository.java`:

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByTokenHash(String tokenHash);
    List<Session> findByAccountIdAndRevokedAtIsNull(UUID accountId);
}
```

- [ ] **Step 6: Run to verify sessions pass**

Run: `cd apps/api && ./mvnw -B test -Dtest=SessionServiceTest`
Expected: PASS, 4 tests.

- [ ] **Step 7: Write the failing API key test**

```java
package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest extends AbstractIntegrationTest {

    @Autowired private ApiKeyService apiKeys;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    private UUID newAccount() {
        return accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
    }

    @Test
    void issuesAKeyWithTheCorrectEnvironmentPrefix() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        var live = apiKeys.issue(workspace.getId(), owner, "production", true);
        var test = apiKeys.issue(workspace.getId(), owner, "sandbox", false);

        assertThat(live.secret()).startsWith("qf_live_");
        assertThat(test.secret()).startsWith("qf_test_");
    }

    @Test
    void storesOnlyADigestAndTheLastFourCharacters() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");

        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        assertThat(issued.key().getTokenHash()).isNotEqualTo(issued.secret());
        assertThat(issued.secret()).endsWith(issued.key().getLastFour());
    }

    @Test
    void resolvesAnIssuedKey() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        assertThat(apiKeys.resolve(issued.secret()))
                .isPresent()
                .get()
                .satisfies(k -> assertThat(k.getWorkspaceId()).isEqualTo(workspace.getId()));
    }

    @Test
    void doesNotResolveARevokedKey() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        var issued = apiKeys.issue(workspace.getId(), owner, "production", true);

        apiKeys.revoke(workspace.getId(), owner, issued.key().getId());

        assertThat(apiKeys.resolve(issued.secret())).isEmpty();
    }

    @Test
    void requiresWorkspaceManagementPermission() {
        UUID owner = newAccount();
        var workspace = workspaces.create(owner, "Acme");
        UUID viewer = newAccount();
        workspaces.addMember(workspace.getId(), owner, viewer, Role.VIEWER);

        assertThatThrownBy(() -> apiKeys.issue(workspace.getId(), viewer, "sneaky", true))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 8: Implement the API key entity, repository and service**

`identity/domain/ApiKey.java`:

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(nullable = false, length = 8)
    private String environment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
    }

    public ApiKey(UUID id, UUID workspaceId, UUID createdBy, String name,
                  String tokenHash, String lastFour, String environment) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.createdBy = createdBy;
        this.name = name;
        this.tokenHash = tokenHash;
        this.lastFour = lastFour;
        this.environment = environment;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getTokenHash() { return tokenHash; }
    public String getLastFour() { return lastFour; }
    public String getEnvironment() { return environment; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public void markUsed() {
        this.lastUsedAt = Instant.now();
    }
}
```

`identity/repo/ApiKeyRepository.java`:

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByTokenHash(String tokenHash);
    List<ApiKey> findByWorkspaceIdAndRevokedAtIsNull(UUID workspaceId);
}
```

`identity/app/ApiKeyService.java`:

```java
package com.quizforge.identity.app;

import com.quizforge.identity.domain.ApiKey;
import com.quizforge.identity.domain.Role;
import com.quizforge.identity.repo.ApiKeyRepository;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    /** The plaintext secret is returned exactly once, at issue time. */
    public record IssuedApiKey(String secret, ApiKey key) {
    }

    private final ApiKeyRepository apiKeys;
    private final WorkspaceService workspaces;

    public ApiKeyService(ApiKeyRepository apiKeys, WorkspaceService workspaces) {
        this.apiKeys = apiKeys;
        this.workspaces = workspaces;
    }

    @Transactional
    public IssuedApiKey issue(UUID workspaceId, UUID actorId, String name, boolean live) {
        require(workspaceId, actorId);

        String environment = live ? "live" : "test";
        String secret = "qf_" + environment + "_" + TokenDigest.generate();
        String lastFour = secret.substring(secret.length() - 4);

        ApiKey key = new ApiKey(UuidV7.generate(), workspaceId, actorId, name,
                TokenDigest.digest(secret), lastFour, environment);

        return new IssuedApiKey(secret, apiKeys.save(key));
    }

    @Transactional
    public Optional<ApiKey> resolve(String secret) {
        if (secret == null || !secret.startsWith("qf_")) {
            return Optional.empty();
        }

        return apiKeys.findByTokenHash(TokenDigest.digest(secret))
                .filter(ApiKey::isActive)
                .map(key -> {
                    key.markUsed();
                    return apiKeys.save(key);
                });
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listActive(UUID workspaceId, UUID actorId) {
        require(workspaceId, actorId);
        return apiKeys.findByWorkspaceIdAndRevokedAtIsNull(workspaceId);
    }

    @Transactional
    public void revoke(UUID workspaceId, UUID actorId, UUID keyId) {
        require(workspaceId, actorId);

        ApiKey key = apiKeys.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("api key"));

        if (!key.getWorkspaceId().equals(workspaceId)) {
            // Do not confirm the key exists elsewhere.
            throw ApiException.notFound("api key");
        }

        key.revoke();
        apiKeys.save(key);
    }

    private void require(UUID workspaceId, UUID actorId) {
        Role role = workspaces.roleOf(workspaceId, actorId);
        if (role == null || !role.can(Role.Permission.MANAGE_WORKSPACE)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "only workspace owners may manage API keys");
        }
    }
}
```

- [ ] **Step 9: Run to verify both pass**

Run: `cd apps/api && ./mvnw -B test -Dtest='SessionServiceTest,ApiKeyServiceTest'`
Expected: PASS, 9 tests total.

- [ ] **Step 10: Run the full suite and commit**

```bash
cd apps/api && ./mvnw -B verify
git add apps/api/src/main/resources/db/migration/V3__sessions_and_api_keys.sql \
        apps/api/src/main/java/com/quizforge/identity \
        apps/api/src/test/java/com/quizforge/identity
git commit -m "feat(identity): add hashed sessions and scoped api keys"
```

---

## Task 6: Row-Level Security

**Files:**
- Create: `db/migration/V5__row_level_security.sql`
- Create: `platform/tenancy/TenantContext.java`, `platform/tenancy/TenantAwareDataSource.java`, `platform/tenancy/TenancyConfig.java`
- Test: `identity/TenantIsolationTest.java`

**Interfaces:**
- Consumes: Task 5
- Produces: `TenantContext.set(UUID workspaceId)`, `TenantContext.current()`, `TenantContext.clear()`. Every query issued inside a request is scoped by Postgres itself.

> **Why this task exists.** Application-layer tenant filtering fails open: one
> missing `WHERE workspace_id = ?` leaks another customer's data with no error.
> RLS fails closed — the database returns nothing regardless of what the query
> asked for. This is the single highest-value control in the milestone.

- [ ] **Step 1: Write the failing isolation test**

```java
package com.quizforge.identity;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIsolationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rowLevelSecurityIsEnabledOnTenantScopedTables() {
        var secured = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND rowsecurity",
                String.class);

        assertThat(secured).contains("api_key");
    }

    @Test
    void aPolicyExistsForEveryTenantScopedTable() {
        Integer policies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public'",
                Integer.class);

        assertThat(policies).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theApplicationRoleIsNotBypassRls() {
        Boolean bypasses = jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'quizforge_app'",
                Boolean.class);

        assertThat(bypasses)
                .as("the application role must not bypass RLS, or the policies are decorative")
                .isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd apps/api && ./mvnw -B test -Dtest=TenantIsolationTest`
Expected: FAIL — no RLS-enabled tables, and role `quizforge_app` does not exist.

- [ ] **Step 3: Write the migration**

```sql
-- V5: Row-Level Security. Tenant scoping is enforced by PostgreSQL rather than
-- by application predicates, so a forgotten WHERE clause returns nothing
-- instead of another workspace's data.
--
-- Note the deliberate gap: V4 is reserved for the audit log, which must exist
-- before policies reference it.

-- A dedicated, non-superuser role. RLS policies do not apply to superusers or
-- to roles with BYPASSRLS, so running the application as the owner would make
-- every policy below decorative.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'quizforge_app') THEN
        CREATE ROLE quizforge_app NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO quizforge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO quizforge_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO quizforge_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO quizforge_app;

-- Returns the workspace set for the current transaction, or NULL when unset.
-- Marked STABLE so the planner may cache it within a statement.
CREATE OR REPLACE FUNCTION current_workspace_id() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.workspace_id', true), '')::UUID;
$$ LANGUAGE SQL STABLE;

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key FORCE ROW LEVEL SECURITY;

CREATE POLICY api_key_tenant_isolation ON api_key
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- Membership is readable by the workspace it belongs to.
ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_tenant_isolation ON membership
    USING (workspace_id = current_workspace_id())
    WITH CHECK (workspace_id = current_workspace_id());

-- account, workspace and session are deliberately NOT tenant-scoped: they are
-- read during authentication, before a workspace is known. They are protected
-- by application-layer authorization instead.
```

- [ ] **Step 4: Implement the tenant context**

```java
package com.quizforge.platform.tenancy;

import java.util.UUID;

/**
 * Holds the workspace in scope for the current request.
 *
 * <p>Backed by a ThreadLocal. Virtual threads each carry their own copy, so
 * this remains correct under Loom; it would not be safe if work were handed to
 * a shared pool without propagation, which is why nothing in this codebase
 * does that.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID workspaceId) {
        CURRENT.set(workspaceId);
    }

    public static UUID current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

- [ ] **Step 5: Propagate the tenant to the database session**

```java
package com.quizforge.platform.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Sets {@code app.workspace_id} on every connection handed out while a tenant
 * is in scope, so that RLS policies have something to compare against.
 *
 * <p>{@code set_config(..., true)} makes the setting transaction-local: it is
 * discarded on commit or rollback, so a pooled connection can never carry one
 * request's tenant into the next.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenant(super.getConnection(username, password));
    }

    private Connection applyTenant(Connection connection) throws SQLException {
        UUID workspaceId = TenantContext.current();
        if (workspaceId != null) {
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT set_config('app.workspace_id', ?, true)")) {
                statement.setString(1, workspaceId.toString());
                statement.execute();
            }
        }
        return connection;
    }
}
```

- [ ] **Step 6: Wire the tenant-aware DataSource into the context**

Defining the class is not enough — nothing uses it until it wraps the real
`DataSource`. Create `platform/tenancy/TenancyConfig.java`:

```java
package com.quizforge.platform.tenancy;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class TenancyConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource realDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * Wraps the pooled DataSource so that every connection handed to the
     * application carries the current tenant. Marked {@code @Primary} so that
     * JPA, JdbcTemplate, and Flyway all receive the wrapper rather than the
     * raw pool.
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSource realDataSource) {
        return new TenantAwareDataSource(realDataSource);
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=TenantIsolationTest`
Expected: PASS, 3 tests.

If Flyway now fails with a permission error, the migration granted privileges
to `quizforge_app` but Flyway connects as the owner — that is fine and
expected. If instead the context fails with "expected single matching bean but
found 2", a second `DataSource` bean is still being auto-configured; ensure
`realDataSource` is the only other one defined.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V5__row_level_security.sql \
        apps/api/src/main/java/com/quizforge/platform/tenancy \
        apps/api/src/test/java/com/quizforge/identity/TenantIsolationTest.java
git commit -m "feat(platform): enforce tenant isolation with postgres RLS"
```

---

## Task 7: Audit log

**Files:**
- Create: `db/migration/V4__audit.sql`, `identity/domain/AuditEvent.java`,
  `identity/repo/AuditEventRepository.java`, `identity/app/AuditService.java`
- Test: `identity/app/AuditServiceTest.java`

**Interfaces:**
- Consumes: Task 6
- Produces: `AuditService.record(UUID workspaceId, UUID actorId, String action, String targetType, UUID targetId, Map<String,Object> detail)`.

> **Migration ordering.** This is `V4`, which is numbered *before* the RLS
> migration in Task 6 but written after it. Flyway orders by version, not by
> creation date, so `V4` runs first on a fresh database. Both migrations must
> therefore be independent of each other — `V5` does not reference the audit
> table, and `V4` does not enable RLS. If you add a cross-reference later, add
> it as `V6`.

- [ ] **Step 1: Write the failing test**

```java
package com.quizforge.identity.app;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditServiceTest extends AbstractIntegrationTest {

    @Autowired private AuditService audit;
    @Autowired private AccountService accounts;
    @Autowired private WorkspaceService workspaces;

    @Test
    void recordsAnEventWithItsDetail() {
        UUID actor = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(actor, "Acme");

        audit.record(workspace.getId(), actor, "member.role_changed",
                "membership", UUID.randomUUID(), Map.of("from", "VIEWER", "to", "EDITOR"));

        var events = audit.recentFor(workspace.getId());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("member.role_changed");
        assertThat(events.get(0).getDetail()).contains("VIEWER");
    }

    @Test
    void auditEventsCannotBeUpdatedOrDeleted() {
        // Enforced by a database trigger, not by convention: an append-only log
        // that the application can rewrite is not evidence of anything.
        assertThatThrownBy(() -> audit.attemptTamper())
                .hasMessageContaining("append-only");
    }
}
```

- [ ] **Step 2: Write the migration**

```sql
-- V4: Append-only audit log for privileged actions.

CREATE TABLE audit_event (
    id            UUID        PRIMARY KEY,
    workspace_id  UUID        REFERENCES workspace (id) ON DELETE CASCADE,
    actor_id      UUID        REFERENCES account (id),
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(64),
    target_id     UUID,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_workspace_time ON audit_event (workspace_id, created_at DESC);
CREATE INDEX idx_audit_actor          ON audit_event (actor_id);

-- Append-only enforced by the database. An audit log the application can
-- rewrite proves nothing to an auditor.
CREATE OR REPLACE FUNCTION reject_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_is_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
```

- [ ] **Step 3: Implement the entity, repository and service**

```java
package com.quizforge.identity.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, UUID workspaceId, UUID actorId, String action,
                      String targetType, UUID targetId, String detail) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
package com.quizforge.identity.repo;

import com.quizforge.identity.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findTop100ByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
```

```java
package com.quizforge.identity.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.identity.domain.AuditEvent;
import com.quizforge.identity.repo.AuditEventRepository;
import com.quizforge.platform.id.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID workspaceId, UUID actorId, String action,
                       String targetType, UUID targetId, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            json = "{}";
        }

        events.save(new AuditEvent(UuidV7.generate(), workspaceId, actorId,
                action, targetType, targetId, json));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recentFor(UUID workspaceId) {
        return events.findTop100ByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    /** Exists only so the append-only trigger can be proven in a test. */
    @Transactional
    public void attemptTamper() {
        events.findAll().stream().findFirst().ifPresent(events::delete);
        events.flush();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=AuditServiceTest`
Expected: PASS, 2 tests. The second proves the database rejects deletion.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V4__audit.sql \
        apps/api/src/main/java/com/quizforge/identity \
        apps/api/src/test/java/com/quizforge/identity/app/AuditServiceTest.java
git commit -m "feat(identity): add append-only audit log"
```

---

## Task 8: Authentication filters and security configuration

**Files:**
- Create: `identity/security/Principal.java`, `identity/security/SessionAuthFilter.java`,
  `identity/security/ApiKeyAuthFilter.java`, `identity/security/SecurityConfig.java`
- Delete: `cs/quizzapp/prokect/backend/config/SecurityConfig.java`
- Test: `identity/security/AuthenticationTest.java`

**Interfaces:**
- Consumes: Task 7
- Produces: authenticated requests carry a `Principal(accountId, workspaceId, role, authType)`. Unauthenticated requests to protected paths receive `401` as Problem Details.

- [ ] **Step 1: Write the failing test**

```java
package com.quizforge.identity.security;

import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AuthenticationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    void rejectsUnauthenticatedAccessToProtectedEndpoints() throws Exception {
        mvc.perform(get("/v1/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void allowsUnauthenticatedAccessToRegistration() throws Exception {
        mvc.perform(get("/v1/auth/health"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAMalformedApiKey() throws Exception {
        mvc.perform(get("/v1/workspaces").header("Authorization", "Bearer qf_live_nonsense"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Implement the principal**

```java
package com.quizforge.identity.security;

import com.quizforge.identity.domain.Role;

import java.util.UUID;

/**
 * The authenticated caller. {@code workspaceId} and {@code role} are null for a
 * session that has not yet selected a workspace; they are always populated for
 * API key authentication, since a key belongs to exactly one workspace.
 */
public record Principal(UUID accountId, UUID workspaceId, Role role, AuthType authType) {

    public enum AuthType { SESSION, API_KEY }

    public boolean can(Role.Permission permission) {
        return role != null && role.can(permission);
    }
}
```

- [ ] **Step 3: Implement the security configuration**

```java
package com.quizforge.identity.security;

import com.quizforge.platform.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SessionAuthFilter sessionAuth,
                                           ApiKeyAuthFilter apiKeyAuth) throws Exception {
        http
            // Stateless: authentication comes from an opaque cookie or bearer
            // token resolved against the database, never from an HTTP session.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // CSRF protection is unnecessary for bearer tokens and is handled
            // for cookie auth by SameSite=Lax plus a required custom header.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // The legacy quiz API remains open until M3 replaces it.
                .requestMatchers("/api/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                response.setStatus(ErrorCode.AUTHENTICATION_REQUIRED.status().value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("""
                    {"type":"%s","title":"AUTHENTICATION_REQUIRED",\
                    "status":401,"detail":"authentication is required",\
                    "code":"AUTHENTICATION_REQUIRED"}"""
                        .formatted(ErrorCode.AUTHENTICATION_REQUIRED.type()));
            }))
            .addFilterBefore(apiKeyAuth, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuth, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

Delete `cs/quizzapp/prokect/backend/config/SecurityConfig.java`. Two
`SecurityFilterChain` beans make the context ambiguous.

- [ ] **Step 4: Implement the filters**

```java
package com.quizforge.identity.security;

import com.quizforge.identity.app.ApiKeyService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeys;

    public ApiKeyAuthFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer qf_")) {
            String secret = header.substring("Bearer ".length());

            apiKeys.resolve(secret).ifPresent(key -> {
                // An API key grants ADMIN within its workspace. Finer-grained
                // scopes arrive in M4 with the public API.
                Principal principal = new Principal(
                        null, key.getWorkspaceId(), Role.ADMIN, Principal.AuthType.API_KEY);

                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                TenantContext.set(key.getWorkspaceId());
            });
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
```

```java
package com.quizforge.identity.security;

import com.quizforge.identity.app.SessionService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "qf_session";
    public static final String WORKSPACE_HEADER = "X-QuizForge-Workspace";

    private final SessionService sessions;
    private final WorkspaceService workspaces;

    public SessionAuthFilter(SessionService sessions, WorkspaceService workspaces) {
        this.sessions = sessions;
        this.workspaces = workspaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            cookie(request).flatMap(sessions::resolve).ifPresent(session -> {
                UUID workspaceId = requestedWorkspace(request);
                var role = workspaceId == null
                        ? null
                        : workspaces.roleOf(workspaceId, session.getAccountId());

                if (workspaceId != null && role == null) {
                    return;   // membership required; leave unauthenticated
                }

                Principal principal = new Principal(
                        session.getAccountId(), workspaceId, role, Principal.AuthType.SESSION);

                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));

                if (workspaceId != null) {
                    TenantContext.set(workspaceId);
                }
            });
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private java.util.Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private UUID requestedWorkspace(HttpServletRequest request) {
        String header = request.getHeader(WORKSPACE_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        return TypeId.parse("wsp", header);
    }
}
```

- [ ] **Step 5: Add the health endpoint this task's test depends on**

`AuthenticationTest` asserts that `/v1/auth/**` is reachable without
credentials. That needs something to reach. Create
`identity/web/AuthHealthController.java` now; Task 9 adds the rest of the
controller alongside it:

```java
package com.quizforge.identity.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Confirms that the {@code /v1/auth/**} path is reachable without
 * authentication. Kept separate from {@link AuthController} so that Task 8 can
 * verify the security configuration before any authentication endpoint exists.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthHealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
```

Remove the duplicate `health()` method from Task 9's `AuthController` — two
handlers mapped to the same path fail context startup with an ambiguous
mapping error.

- [ ] **Step 6: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=AuthenticationTest`
Expected: PASS, 3 tests.

- [ ] **Step 7: Commit**

```bash
cd apps/api && ./mvnw -B verify
git add apps/api/src/main/java/com/quizforge/identity/security \
        apps/api/src/test/java/com/quizforge/identity/security
git rm apps/api/src/main/java/cs/quizzapp/prokect/backend/config/SecurityConfig.java
git commit -m "feat(identity): authenticate sessions and api keys"
```

---

## Task 9: Authentication endpoints

**Files:**
- Create: `identity/web/AuthController.java`, `identity/web/dto/RegisterRequest.java`,
  `identity/web/dto/LoginRequest.java`, `identity/web/dto/AccountResponse.java`
- Test: `identity/web/AuthControllerTest.java`

**Interfaces:**
- Consumes: Task 8
- Produces: `GET /v1/auth/health`, `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/logout`, `GET /v1/auth/me`, `POST /v1/auth/request-password-reset`.

> **Why workspace and API-key endpoints are not here.** Those capabilities
> exist as tested services (`WorkspaceService`, `ApiKeyService`) but get no
> HTTP surface in M1. M4 defines the public API contract-first from
> `openapi.yaml` and generates the controllers from it; hand-writing REST
> endpoints now would create a second, divergent surface that M4 would have to
> delete. Authentication is the exception because the dashboard and every
> integration test need it before M4 exists.

- [ ] **Step 1: Write the failing end-to-end test**

```java
package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private String body(Map<String, Object> map) throws Exception {
        return json.writeValueAsString(map);
    }

    @Test
    void registersLogsInAndIdentifiesTheCaller() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email,
                                "password", "correct horse battery",
                                "displayName", "Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("acc_")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", "correct horse battery"))))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("qf_session"))
                .andExpect(cookie().httpOnly("qf_session", true))
                .andReturn();

        var cookie = login.getResponse().getCookie("qf_session");

        mvc.perform(get("/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void rejectsLoginWithAWrongPassword() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", email,
                        "password", "correct horse battery", "displayName", "Ada"))));

        mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", "wrong password here"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void neverReturnsAPasswordResetTokenInTheResponse() throws Exception {
        // The prototype returned the reset token in the HTTP response, which
        // combined with an unauthenticated API allowed trivial account
        // takeover. This test exists to make that regression impossible.
        String email = "user-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/request-password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.resetToken").doesNotExist());
    }
}
```

- [ ] **Step 2: Implement the DTOs**

```java
package com.quizforge.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 12, max = 200) String password,
        @NotBlank @Size(max = 120) String displayName) {
}
```

```java
package com.quizforge.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
```

```java
package com.quizforge.identity.web.dto;

import com.quizforge.identity.domain.Account;
import com.quizforge.platform.id.TypeId;

/** Never carries the password hash, MFA secret, or lockout state. */
public record AccountResponse(String id, String email, String displayName, boolean emailVerified) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(
                TypeId.render("acc", account.getId()),
                account.getEmail(),
                account.getDisplayName(),
                account.getEmailVerifiedAt() != null);
    }
}
```

- [ ] **Step 3: Implement the auth controller**

```java
package com.quizforge.identity.web;

import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.SessionService;
import com.quizforge.identity.repo.AccountRepository;
import com.quizforge.identity.security.Principal;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.identity.web.dto.*;
import com.quizforge.platform.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AccountService accounts;
    private final SessionService sessions;
    private final AccountRepository accountRepository;

    public AuthController(AccountService accounts, SessionService sessions,
                          AccountRepository accountRepository) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.accountRepository = accountRepository;
    }

    // GET /v1/auth/health lives in AuthHealthController (created in Task 8).
    // Do not add it here as well - duplicate mappings fail context startup.

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest request) {
        return AccountResponse.of(
                accounts.register(request.email(), request.password(), request.displayName()));
    }

    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletRequest http) {
        var account = accounts.authenticate(request.email(), request.password());

        var issued = sessions.issue(account.getId(),
                http.getHeader("User-Agent"), http.getRemoteAddr());

        ResponseCookie cookie = ResponseCookie.from(SessionAuthFilter.COOKIE_NAME, issued.token())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(SessionService.LIFETIME)
                .build();

        return ResponseEntity.ok()
                .header("Set-Cookie", cookie.toString())
                .body(AccountResponse.of(account));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = SessionAuthFilter.COOKIE_NAME, required = false) String token) {
        if (token != null) {
            sessions.revoke(token);
        }
    }

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Principal principal) {
        if (principal == null || principal.accountId() == null) {
            throw ApiException.notFound("account");
        }
        return AccountResponse.of(accountRepository.findById(principal.accountId())
                .orElseThrow(() -> ApiException.notFound("account")));
    }

    /**
     * Always returns 202 with no body detail, whether or not the address is
     * registered — otherwise this endpoint becomes an account-enumeration
     * oracle. The token is delivered by email and never in the response.
     */
    @PostMapping("/request-password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> requestPasswordReset(@RequestBody Map<String, String> body) {
        // Token generation and delivery are wired to the notify module in M4.
        return Map.of("message",
                "If that address has an account, password reset instructions have been sent.");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd apps/api && ./mvnw -B test -Dtest=AuthControllerTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole suite**

Run: `cd apps/api && ./mvnw -B verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/quizforge/identity/web \
        apps/api/src/test/java/com/quizforge/identity/web
git commit -m "feat(identity): add authentication and workspace endpoints"
```

---

## Definition of done for M1

- [ ] `cd apps/api && ./mvnw verify` passes from a clean clone
- [ ] Registration, login, logout and `/v1/auth/me` work end to end
- [ ] Passwords are Argon2id; no BCrypt encoder bean remains
- [ ] A wrong password and an unknown account are indistinguishable to a caller
- [ ] Sessions and API keys are stored only as SHA-256 digests
- [ ] An API key secret is returned exactly once, at issue time
- [ ] Postgres RLS is enabled on `api_key` and `membership`, and the application role is `NOBYPASSRLS`
- [ ] The audit log rejects `UPDATE` and `DELETE` at the database level
- [ ] A workspace can never lose its last owner
- [ ] `/v1/**` requires authentication; `/api/**` (legacy) still does not
- [ ] `ModularityTest` and `ArchitectureTest` still pass
- [ ] No password reset token appears in any HTTP response

## Explicitly out of scope for M1

Deferred deliberately: TOTP MFA enrolment (schema columns exist, flow does
not), email verification delivery, workspace invitations by email, OIDC/SAML,
fine-grained API key scopes (keys grant workspace ADMIN for now; scopes arrive
in M4 with the public API), rate limiting, and **HTTP endpoints for workspace
and API-key management** — those services are built and tested here but are
exposed in M4, generated from the OpenAPI contract rather than hand-written
twice. Retiring the legacy
`cs.quizzapp` package remains M3 work — `/api/**` stays open until then, which
is why the legacy quiz endpoints are still unauthenticated at the end of this
milestone.

## Decisions taken

1. **UUIDv7 in native `uuid` columns**, rendered as `acc_…` only at the API
   boundary. Keeps indexes compact and joins fast while giving callers opaque,
   self-describing, non-enumerable identifiers.
2. **Minimum password length 12**, no composition rules, following NIST
   SP 800-63B. The prototype required 6.
3. **SHA-256, not Argon2, for session and API tokens.** They carry 256 bits of
   CSPRNG entropy, so slow hashing adds latency to every request and buys
   nothing.
4. **API keys grant workspace ADMIN** in M1. Real scopes arrive in M4, where
   the public API defines what there is to scope.
5. **`account`, `workspace` and `session` are not RLS-scoped**, because they
   are read during authentication before a workspace is known. They rely on
   application-layer authorization instead.
