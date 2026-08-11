/**
 * The shared error model. Exposed as a named interface so other modules may
 * throw {@code ApiException} and reference {@code ErrorCode}; everything else
 * under {@code platform} stays internal by default.
 */
@org.springframework.modulith.NamedInterface("error")
package com.quizforge.platform.error;
