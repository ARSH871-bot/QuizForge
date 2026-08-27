@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "platform::idempotency", "api", "api::model"}
)
package com.quizforge.identity;
