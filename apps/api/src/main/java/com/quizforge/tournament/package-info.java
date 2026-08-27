@org.springframework.modulith.ApplicationModule(
        displayName = "Tournament",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "api", "api::model",
                "identity", "content"}
)
package com.quizforge.tournament;
