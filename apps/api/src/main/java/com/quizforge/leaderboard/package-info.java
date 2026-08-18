@org.springframework.modulith.ApplicationModule(
        displayName = "Leaderboard",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy", "api", "api::model",
                "identity", "tournament", "play"}
)
package com.quizforge.leaderboard;
