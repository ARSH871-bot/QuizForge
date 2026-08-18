@org.springframework.modulith.ApplicationModule(
        displayName = "Content",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "api", "api::model", "identity"}
)
package com.quizforge.content;
