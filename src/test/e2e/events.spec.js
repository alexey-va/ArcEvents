import { expect, test } from '@drownek/plugwright';

test('relay refuses queue admission without a host arena and reports the stable state', async ({ player }) => {
  let marker = player.getMessageBufferIndex();
  player.chat('/arcevents join');
  await expect(player).toHaveReceivedMessage(/The event is unavailable\.\s+Try again a little later\./, { since: marker });
  marker = player.getMessageBufferIndex();
  player.chat('/arcevents status');
  await expect(player).toHaveReceivedMessage(/You are not participating in a match\./, { since: marker });
  marker = player.getMessageBufferIndex();
  player.chat('/arcevents leave');
  await expect(player).toHaveReceivedMessage(/You have not joined a queue yet\.\s+Choose an event: \/arcevents/, { since: marker });
});

test('event help is available through the player command route', async ({ player }) => {
  player.chat('/arcevents help');
  await expect(player).toHaveReceivedMessage(/arcevents|событ|event/i);
});
