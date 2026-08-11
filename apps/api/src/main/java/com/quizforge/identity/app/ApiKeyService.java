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
