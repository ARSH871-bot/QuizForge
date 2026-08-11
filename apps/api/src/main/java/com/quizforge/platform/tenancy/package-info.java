/**
 * Multi-tenancy plumbing. Exposed as a named interface so other modules may
 * set and read the active workspace; everything else under {@code platform}
 * stays internal by default.
 */
@org.springframework.modulith.NamedInterface("tenancy")
package com.quizforge.platform.tenancy;
