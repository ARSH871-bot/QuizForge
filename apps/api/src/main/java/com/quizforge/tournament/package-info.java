@org.springframework.modulith.ApplicationModule(
        displayName = "Tournament",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "api", "api::model",
                "identity", "content"}
)
package com.quizforge.tournament;
