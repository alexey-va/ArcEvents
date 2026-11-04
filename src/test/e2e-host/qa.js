import assert from 'node:assert/strict';
import { expect } from '@drownek/plugwright';

export async function readQa(admin, subject = 'status') {
  const marker = admin.getMessageBufferIndex();
  admin.chat(`/arcevents qa ${subject}`);
  await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_QA /, { since: marker, timeout: 5000 });
  const line = admin.messageBuffer.slice(marker).find((message) => message.startsWith('ARCEVENTS_QA '));
  return Object.fromEntries([...line.matchAll(/ ([a-z0-9_.-]+)=("(?:\\.|[^"\\])*"|\S+)/g)]
    .map(([, key, value]) => [key, value.startsWith('"') ? JSON.parse(value) : value]));
}

export async function waitForQa(admin, subject, expected, timeout = 20000) {
  const deadline = Date.now() + timeout;
  let state;
  do {
    state = await readQa(admin, subject);
    if (Object.entries(expected).every(([key, value]) => state[key] === String(value))) return state;
    await new Promise((resolve) => setTimeout(resolve, 200));
  } while (Date.now() < deadline);
  assert.fail(`QA ${subject} did not reach ${JSON.stringify(expected)}; last state: ${JSON.stringify(state)}`);
}
