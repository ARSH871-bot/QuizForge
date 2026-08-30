/**
 * The quickstart from the README, as a runnable script.
 *
 *     ./examples/bootstrap.sh              # creates a workspace and a key
 *     export QUIZFORGE_API_KEY=qf_test_…
 *     npm run build && node examples/quickstart.ts
 *
 * Reads content, follows cursors, and shows what a failure looks like — both
 * a missing resource and the write restriction an API key runs into.
 */
import { QuizForge, QuizForgeError } from "../dist/index.js";

const apiKey = process.env["QUIZFORGE_API_KEY"];
if (!apiKey) {
  console.error("Set QUIZFORGE_API_KEY. Run ./examples/bootstrap.sh to mint one.");
  process.exit(1);
}

const qf = new QuizForge({
  apiKey,
  baseUrl: process.env["QUIZFORGE_BASE_URL"] ?? "http://localhost:8080",
});

// 1. The key already names a workspace, so nothing here takes one.
const workspace = await qf.workspaces.current();
console.log(`workspace:  ${workspace.name} (${workspace.role})`);

// 2. List. `list` returns an async generator, not a page: it requests `limit`
//    at a time and follows `nextCursor` until there is none.
const banks: string[] = [];
for await (const bank of qf.questionBanks.list({ limit: 25 })) {
  banks.push(bank.id);
  console.log(`bank:       ${bank.id}  ${bank.name}`);
}

const bankId = banks[0];
if (!bankId) {
  console.error("No question banks. Run ./examples/bootstrap.sh first.");
  process.exit(1);
}

// 3. Page through the questions two at a time, to show the cursors working.
//    Inserting a question mid-iteration neither duplicates nor skips one —
//    that is what keyset cursors buy over offsets.
let questions = 0;
for await (const question of qf.questions.list(bankId, { limit: 2 })) {
  questions += 1;
  console.log(`question:   ${question.type.padEnd(13)} ${question.prompt}`);

  // The payload is a discriminated union. Narrowing on `kind` is exhaustive:
  // add a shape to the contract and TypeScript fails this switch until it is
  // handled.
  const payload = question.payload;
  if (payload) {
    switch (payload.kind) {
      case "choice":
        console.log(`              ${payload.options.length} options`);
        break;
      case "numeric":
        console.log(`              ${payload.value} ±${payload.tolerance}`);
        break;
      case "shortText":
        console.log(`              accepts ${payload.accepted.join(", ")}`);
        break;
    }
  }
}
console.log(`questions:  ${questions}, in pages of 2`);

// 4. Members, also paginated. Same shape, different collection.
for await (const member of qf.members.list()) {
  console.log(`member:     ${member.role.padEnd(7)} ${member.email}`);
}

// 5. A write, which a key cannot do today: every write is audited against the
//    account that made it, and a key names a workspace rather than a person.
//    The error says so, with a code worth branching on rather than prose.
try {
  await qf.tournaments.create({
    name: "Geography Weekly",
    bankId,
    opensAt: new Date(Date.now() + 60_000).toISOString(),
    closesAt: new Date(Date.now() + 7 * 24 * 60 * 60_000).toISOString(),
    questions: 3,
    maxAttempts: 2,
    scoringPolicy: "BEST",
  });
  console.log("tournament: created");
} catch (error) {
  if (error instanceof QuizForgeError && error.code === "PERMISSION_DENIED") {
    console.log(`write:      ${error.status} ${error.code} — ${error.message}`);
  } else {
    throw error;
  }
}

// 6. A missing resource is typed the same way. Branch on `code`, which is stable; `detail` is prose
//    written for people and gets reworded.
try {
  await qf.questionBanks.get("bnk_00000000000000000000000000000000");
} catch (error) {
  if (error instanceof QuizForgeError && error.code === "NOT_FOUND") {
    console.log(`expected:   ${error.status} ${error.code} — ${error.message}`);
  } else {
    throw error;
  }
}

console.log("\nquickstart complete.");
