import fs from "node:fs";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "..");
const backend = path.resolve(root, "..");
const result = [];
for (const file of ["auth", "profile", "trip", "attraction"]) {
  const source = fs.readFileSync(
    path.join(backend, "docs/api", file + ".md"),
    "utf8",
  );
  for (const match of source.matchAll(
    /^### (\d+-\d+)\. ([^\r\n]+)\r?\n([\s\S]*?)(?=^### |^# |$(?![\s\S]))/gm,
  )) {
    const [, section, title, doc] = match;
    const firstFence = doc.match(/```\s*\r?\n([\s\S]*?)```/);
    if (!firstFence) continue;
    const paths = [
      ...firstFence[1].matchAll(
        /^(GET|POST|PATCH|DELETE|PUT)\s+(\/api\/[^\s\r\n]+)/gm,
      ),
    ];
    const req = doc.match(
      /\*\*Request Body\*\*[\s\S]*?```json\s*([\s\S]*?)```/,
    );
    let body;
    if (req) {
      try {
        body = JSON.parse(req[1]);
      } catch {}
    }
    for (const p of paths) {
      const method = p[1],
        url = p[2].replace("/api", "");
      if (
        result.some(
          (r) =>
            r.method === method && r.path.split("?")[0] === url.split("?")[0],
        )
      )
        continue;
      result.push({
        id: section + "-" + method,
        section,
        title,
        method,
        path: url,
        body: ["GET", "DELETE"].includes(method) ? undefined : body,
        source: file,
        doc: doc.trim(),
        multipart: doc.includes("multipart/form-data"),
        implemented: doc.includes("✅ 구현"),
      });
    }
  }
}
fs.mkdirSync(path.join(root, "src/generated"), {
  recursive: true,
});
fs.writeFileSync(
  path.join(root, "src/generated/contract.json"),
  JSON.stringify(result, null, 2),
);
console.log(`Generated ${result.length} documented operations.`);
