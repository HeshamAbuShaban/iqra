// Scores backbone shootout runs. Usage:
//   node score.mjs clips.manifest.json results-tilawa.json [results-zipformer.json ...]
// results file: { backbone, msPerFrame: {p50, p95}, modelMB, clips:
//   { [clipId]: { lockedAyah: "S:A"|null, wordFlags: [{ayah, word, flagged}] } } }
import { readFileSync } from "node:fs";

const [manifestPath, ...resultPaths] = process.argv.slice(2);
if (!manifestPath || resultPaths.length === 0) {
  console.error("usage: node score.mjs clips.manifest.json results-<id>.json ...");
  process.exit(1);
}
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));

function key(m) {
  return `${m.ayah}:${m.word}`;
}

for (const rp of resultPaths) {
  const r = JSON.parse(readFileSync(rp, "utf8"));
  let lockOk = 0, lockN = 0, tp = 0, fp = 0, fn = 0, haraTp = 0, haraN = 0;
  for (const clip of manifest.clips) {
    const got = r.clips?.[clip.id];
    if (!got) continue;
    lockN++;
    if (got.lockedAyah && clip.ayahs.includes(got.lockedAyah)) lockOk++;
    const labeled = new Map(clip.mistakes.map((m) => [key(m), m]));
    const flagged = new Map((got.wordFlags || []).filter((f) => f.flagged).map((f) => [key(f), f]));
    for (const [k, m] of labeled) {
      if (flagged.has(k)) {
        tp++;
        if (m.type === "harakat") haraTp++;
      } else fn++;
      if (m.type === "harakat") haraN++;
    }
    for (const k of flagged.keys()) {
      if (!labeled.has(k)) fp++;
    }
  }
  const prec = tp + fp === 0 ? 1 : tp / (tp + fp);
  const rec = tp + fn === 0 ? 1 : tp / (tp + fn);
  console.log(`== ${r.backbone} ==`);
  console.log(`lock accuracy : ${lockN ? (100 * lockOk / lockN).toFixed(1) : "n/a"}% (${lockOk}/${lockN})`);
  console.log(`flag precision: ${(100 * prec).toFixed(1)}%  recall: ${(100 * rec).toFixed(1)}% (tp=${tp} fp=${fp} fn=${fn})`);
  console.log(`harakat catch : ${haraN ? (100 * haraTp / haraN).toFixed(1) : "n/a"}% (${haraTp}/${haraN})`);
  console.log(`latency p50/p95: ${r.msPerFrame?.p50 ?? "?"} / ${r.msPerFrame?.p95 ?? "?"} ms   model: ${r.modelMB ?? "?"} MB`);
}
