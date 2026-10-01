@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "platform::idempotency", "platform::ratelimit", "api", "api::model", "notify"}
)
package com.quizforge.identity;
