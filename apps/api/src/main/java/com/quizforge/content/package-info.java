@org.springframework.modulith.ApplicationModule(
        displayName = "Content",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "api", "api::model", "identity"}
)
package com.quizforge.content;
