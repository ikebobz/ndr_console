import path from "node:path";
import { SmashUploader } from "@smash-sdk/uploader";

const filePath = process.argv[2];
const token = process.env.SMASH_API_KEY;
const region = process.env.SMASH_REGION;

if (!filePath) {
  console.error("Missing file path argument.");
  process.exit(1);
}

if (!token || !region) {
  console.error("Missing SMASH_API_KEY or SMASH_REGION.");
  process.exit(1);
}

try {
  const uploader = new SmashUploader({ region, token });

  const result = await uploader.upload({
    files: [filePath],
    title: path.basename(filePath),
    delivery: {
      type: "Link"
    },
    preview: "None"
  });

  const transfer = result.transfer ?? result;

  console.log(JSON.stringify({
    transferId: transfer.id,
    status: transfer.status,
    transferUrl: transfer.transferUrl,
    uploadState: transfer.uploadState
  }));
} catch (error) {
  console.error(error?.stack || String(error));
  process.exit(1);
}
