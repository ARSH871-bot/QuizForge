package com.quizforge.content.importer;

/**
 * One row an import could not take, and why.
 *
 * <p>The line number is a field rather than part of the message. It used to be
 * embedded in prose — {@code "row 3: ..."} — which meant any client wanting to
 * point a user at the offending row had to parse an English sentence that was
 * free to be reworded.
 *
 * @param line    the 1-indexed line in the source file, counting the header, so
 *                it matches what a spreadsheet shows. {@code null} for sources
 *                that have no lines, such as the OpenTDB import.
 * @param message why the row was rejected. Written for a human; do not parse it.
 */
public record ImportFailure(Integer line, String message) {
}
