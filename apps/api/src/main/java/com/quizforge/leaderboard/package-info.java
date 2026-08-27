@org.springframework.modulith.ApplicationModule(
        displayName = "Leaderboard",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "platform::web", "api", "api::model",
                "identity", "tournament", "play"}
)
package com.quizforge.leaderboard;
