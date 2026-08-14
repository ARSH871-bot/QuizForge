@org.springframework.modulith.ApplicationModule(
        displayName = "Tournament",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy",
                "identity", "content"}
)
package com.quizforge.tournament;
