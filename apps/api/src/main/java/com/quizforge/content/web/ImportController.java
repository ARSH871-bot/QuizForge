package com.quizforge.content.web;

import com.quizforge.api.ImportsApi;
import com.quizforge.api.model.ImportFailure;
import com.quizforge.api.model.ImportReport;
import com.quizforge.api.model.OpenTdbImportRequest;
import com.quizforge.content.importer.CsvImporter;
import com.quizforge.content.importer.OpenTdbImporter;
import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.id.TypeId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Bulk loading questions into a bank.
 *
 * <p>Both imports answer {@code 200} with a report rather than an error when
 * some rows fail. Partial success is the normal case: an author fixing a typo on
 * line 300 should not lose the 299 rows above it, and a client that has to
 * choose between "everything worked" and "nothing worked" cannot help them.
 */
@RestController
public class ImportController implements ImportsApi {

    private final CsvImporter csv;
    private final OpenTdbImporter openTdb;

    public ImportController(CsvImporter csv, OpenTdbImporter openTdb) {
        this.csv = csv;
        this.openTdb = openTdb;
    }

    @Override
    public ResponseEntity<ImportReport> importCsv(String bankId, String body,
                                                  String workspaceHeader) {
        Principal principal = requireWorkspace();
        UUID bank = TypeId.parse("bnk", bankId);

        return ResponseEntity.ok(represent(csv.importInto(bank, principal.accountId(), body)));
    }

    /**
     * The importer takes {@code categoryId:difficulty:amount}, which is a
     * reasonable internal shape and a poor public one. The contract accepts a
     * JSON object and this composes the string, so the wire format is not
     * dictated by an internal convention nobody outside can see.
     */
    @Override
    public ResponseEntity<ImportReport> importOpenTdb(String bankId,
                                                      OpenTdbImportRequest request,
                                                      String workspaceHeader) {
        Principal principal = requireWorkspace();
        UUID bank = TypeId.parse("bnk", bankId);

        // Read each nullable value once. Calling the getter twice - to test and
        // then to use - is two separate calls that need not agree, which is
        // what SpotBugs objects to and is right about.
        Integer category = request.getCategory();
        OpenTdbImportRequest.DifficultyEnum difficulty = request.getDifficulty();

        String source = (category == null ? "" : category.toString())
                + ":" + (difficulty == null ? "" : difficulty.getValue())
                + ":" + request.getAmount();

        return ResponseEntity.ok(represent(
                openTdb.importInto(bank, principal.accountId(), source)));
    }

    /**
     * Carries each failure's line number as a field.
     *
     * <p>It used to be embedded in the message — {@code "row 3: ..."} — so any
     * client wanting to point a user at the offending row had to parse an
     * English sentence that was free to be reworded.
     */
    private ImportReport represent(com.quizforge.content.importer.ImportReport report) {
        return new ImportReport(
                report.imported(),
                report.skipped(),
                report.failed(),
                report.failures().stream()
                        .map(f -> {
                            ImportFailure failure = new ImportFailure(f.message());
                            failure.setLine(f.line());
                            return failure;
                        })
                        .toList());
    }

    private Principal requireWorkspace() {
        Principal principal = CurrentPrincipal.get();
        if (principal == null || principal.workspaceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "select a workspace with the X-QuizForge-Workspace header");
        }
        if (principal.accountId() == null) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "importing requires a signed-in account, not an API key");
        }
        return principal;
    }
}
