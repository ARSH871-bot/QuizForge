package com.quizforge.content.importer;

import java.util.UUID;

public interface QuestionImporter {

    /** Imports into an existing bank. Never creates one. */
    ImportReport importInto(UUID bankId, UUID actorId, String source);
}
