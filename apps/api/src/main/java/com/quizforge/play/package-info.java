@org.springframework.modulith.ApplicationModule(
        displayName = "Play",
        allowedDependencies = {"platform::id", "platform::error", "platform::tenancy",
                "identity", "content", "tournament"}
)
package com.quizforge.play;
