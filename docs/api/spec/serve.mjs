// Serves the rendered API reference locally, with no dependencies.
//
// `npm run spec` from the repository root, then open http://localhost:8090.
//
// The page and the contract live in different directories, and the renderer
// fetches the contract over HTTP — opening index.html from the filesystem does
// not work, because a file:// page may not fetch a sibling file. Two routes are
// cheaper than a build step that copies things around.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..", "..");
const port = Number(process.env.PORT ?? 8090);

const routes = {
  "/": [join(here, "index.html"), "text/html; charset=utf-8"],
  "/index.html": [join(here, "index.html"), "text/html; charset=utf-8"],
  "/openapi.yaml": [join(repoRoot, "openapi.yaml"), "text/yaml; charset=utf-8"],
};

createServer(async (request, response) => {
  const route = routes[new URL(request.url, "http://localhost").pathname];
  if (!route) {
    response.writeHead(404, { "Content-Type": "text/plain" });
    response.end("Not found. This server has two routes: / and /openapi.yaml\n");
    return;
  }

  const [path, type] = route;
  try {
    // Read per request rather than once at startup, so editing the contract
    // and reloading shows the change.
    const body = await readFile(path);
    response.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" });
    response.end(body);
  } catch (cause) {
    response.writeHead(500, { "Content-Type": "text/plain" });
    response.end(`Could not read ${path}: ${cause.message}\n`);
  }
}).listen(port, () => {
  console.log(`QuizForge API reference: http://localhost:${port}`);
});
