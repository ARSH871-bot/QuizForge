package com.quizforge.identity.web;

import com.quizforge.api.ApiKeysApi;
import com.quizforge.api.model.ApiKeyPage;
import com.quizforge.api.model.ApiKeySummary;
import com.quizforge.api.model.CreateApiKeyRequest;
import com.quizforge.api.model.IssuedApiKey;
import com.quizforge.api.model.KeyEnvironment;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.identity.app.ApiKeyService;
import com.quizforge.identity.domain.ApiKey;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.web.PageWindow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * API keys — the credential that lets a server call this API.
 *
 * <p>Closing the gap that made everything else unreachable: {@code ApiKeyService}
 * has been able to mint keys since M1, and until now nothing exposed it, so an
 * API-first product had no way to issue the credential its primary
 * authentication mechanism depends on.
 */
@RestController
public class ApiKeyController implements ApiKeysApi {

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    public ResponseEntity<ApiKeyPage> listApiKeys(String workspaceHeader, Integer limit,
                                                  String cursor) {
        Principal principal = requireWorkspace();
        PageWindow window = PageWindow.of(limit, cursor);

        var slice = window.slice(
                apiKeys.listActive(principal.workspaceId(), principal.accountId(), window),
                ApiKey::getId);

        List<ApiKeySummary> data = slice.data().stream().map(this::summarise).toList();

        ApiKeyPage page = new ApiKeyPage(data);
        page.setNextCursor(slice.nextCursor());
        return ResponseEntity.ok(page);
    }

    /**
     * The only response in the whole API that contains a key's secret.
     *
     * <p>Only a SHA-256 digest is stored, so the plaintext cannot be recovered
     * afterwards — not by another endpoint, not from a database dump, not by
     * asking. A client that does not persist it must mint another key.
     */
    @Override
    public ResponseEntity<IssuedApiKey> createApiKey(CreateApiKeyRequest request,
                                                     String idempotencyKey,
                                                     String workspaceHeader) {
        Principal principal = requireWorkspace();

        KeyEnvironment environment = request.getEnvironment() == null
                ? KeyEnvironment.LIVE
                : request.getEnvironment();

        var issued = apiKeys.issue(principal.workspaceId(), principal.accountId(),
                request.getName(), environment == KeyEnvironment.LIVE);

        ApiKey key = issued.key();
        IssuedApiKey body = new IssuedApiKey(
                TypeId.render("key", key.getId()),
                key.getName(),
                KeyEnvironment.fromValue(key.getEnvironment()),
                key.getLastFour(),
                key.getCreatedAt().atOffset(ZoneOffset.UTC),
                issued.secret());

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Revocation takes effect on the next request. Keys are resolved against
     * the database every time one is presented, so there is no cache to expire
     * and no window in which a revoked key still works.
     */
    @Override
    public ResponseEntity<Void> revokeApiKey(String keyId, String idempotencyKey,
        String workspaceHeader) {
        Principal principal = requireWorkspace();
        apiKeys.revoke(principal.workspaceId(), principal.accountId(),
                TypeId.parse("key", keyId));
        return ResponseEntity.noContent().build();
    }

    /** Metadata only. Never carries the secret or its digest. */
    private ApiKeySummary summarise(ApiKey key) {
        ApiKeySummary summary = new ApiKeySummary(
                TypeId.render("key", key.getId()),
                key.getName(),
                KeyEnvironment.fromValue(key.getEnvironment()),
                key.getLastFour(),
                key.getCreatedAt().atOffset(ZoneOffset.UTC));
        summary.setLastUsedAt(key.getLastUsedAt() == null
                ? null
                : key.getLastUsedAt().atOffset(ZoneOffset.UTC));
        return summary;
    }

    /**
     * Managing keys requires a signed-in account. A key cannot mint or revoke
     * another key: that would let a leaked credential extend its own foothold
     * and outlive the revocation of the key that created it.
     */
    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        if (principal.accountId() == null) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "an API key cannot manage API keys");
        }
        return principal;
    }
}
