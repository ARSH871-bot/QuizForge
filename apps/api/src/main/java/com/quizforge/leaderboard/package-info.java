@org.springframework.modulith.ApplicationModule(
        displayName = "Leaderboard",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy",
                "identity", "tournament", "play"}
)
package com.quizforge.leaderboard;
