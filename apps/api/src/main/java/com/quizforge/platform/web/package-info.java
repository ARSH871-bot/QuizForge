/**
 * Shapes shared by every module's HTTP layer.
 *
 * <p>Deliberately small: the handful of types that must behave identically
 * across the whole API, because a client should not have to learn a different
 * pagination scheme per endpoint.
 */
@org.springframework.modulith.NamedInterface("web")
package com.quizforge.platform.web;
