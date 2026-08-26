// Reference validation of the offline recognition pipeline (Node + onnxruntime-node).
// This is NOT needed to build the Android app; it documents how we proved that
// feeding 16 kHz float32 PCM into the Tilawa model yields the correct surah:ayah
// and word-level tokens. Run with:  npm i @tilawa/core onnxruntime-node
import * as ort from "onnxruntime-node";
import { createTilawaSession } from "@tilawa/core";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";

const dir = new URL("./", import.meta.url).pathname;
const modelPath = dir + "model.onnx";

const sess = await ort.InferenceSession.create(modelPath);
console.log("MODEL inputs :", sess.inputNames);
console.log("MODEL outputs:", sess.outputNames);

const audioBuf = readFileSync(dir + "sample_16k.raw");
const audio = new Float32Array(audioBuf.buffer, audioBuf.byteOffset, audioBuf.byteLength / 4);

async function run(a) {
  const N = a.length;
  const feeds = {};
  feeds[sess.inputNames[0]] = new ort.Tensor("float32", a, [1, N]);
  try {
    feeds[sess.inputNames[1]] = new ort.Tensor("int64", BigInt64Array.from([BigInt(N)]), [1]);
  } catch {
    feeds[sess.inputNames[1]] = new ort.Tensor("int32", Int32Array.from([N]), [1]);
  }
  const out = await sess.run(feeds);
  const o = out[sess.outputNames[0]];
  return { logprobs: o.data, timeSteps: o.dims[1], vocabSize: o.dims[2] };
}

const vocab = JSON.parse(readFileSync(dir + "vocab.json", "utf8"));
const quranCtcTokens = JSON.parse(readFileSync(dir + "quran_ctc_tokens.json", "utf8"));
const quran = JSON.parse(readFileSync(dir + "quran.json", "utf8"));

const session = createTilawaSession({ run }, { vocab, quranCtcTokens, quran, blankId: 1024 }, {
  onOutput: (m) => console.log("  >>", JSON.stringify(m)),
});

console.log("--- transcribe ---");
const res = await session.transcribe(audio);
console.log("RESULT:", JSON.stringify(res));
