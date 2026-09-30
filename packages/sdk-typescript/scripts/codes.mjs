// Generates src/codes.ts from the error codes declared in openapi.yaml.
//
// ErrorCode is an extensible enum, which openapi-typescript renders as plain
// `string`. That is correct - new codes are allowed - but it would throw away
// the codes that exist today. This keeps them, typed, with the status and
// meaning the contract gives each one.
import { readFileSync, writeFileSync } from "node:fs";
import { load } from "js-yaml";

const contract = load(readFileSync(new URL("../../../openapi.yaml", import.meta.url), "utf8"));
const schema = contract.components.schemas.ErrorCode;
const codes = schema["x-extensible-enum"];
const described = schema["x-error-codes"] ?? {};

for (const code of codes) {
  if (!described[code]) throw new Error(`openapi.yaml: ${code} has no entry in x-error-codes`);
}

const entries = codes
  .map((code) => {
    const { status, meaning } = described[code];
    return `  ${code}: { status: ${status}, meaning: ${JSON.stringify(meaning)} },`;
  })
  .join("\n");

writeFileSync(
  new URL("../src/codes.ts", import.meta.url),
  `// Generated from openapi.yaml by scripts/codes.mjs. Do not edit.

/** The error codes the API documents today, with their HTTP status and meaning. */
export const KNOWN_ERROR_CODES = {
${entries}
} as const;

/** A code the API documents today. */
export type KnownErrorCode = keyof typeof KNOWN_ERROR_CODES;
`,
);
console.log(`src/codes.ts: ${codes.length} codes`);
