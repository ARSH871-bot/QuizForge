@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "api", "api::model"}
)
package com.quizforge.identity;
