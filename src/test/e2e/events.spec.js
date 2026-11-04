import { expect, test } from '@drownek/plugwright';

test('relay refuses queue admission without a host arena and reports the stable state', async ({ player }) => {
  let marker = player.getMessageBufferIndex();
  player.chat('/arcevents join');
  await expect(player).toHaveReceivedMessage(/Очередь недоступна\.\s+Попробуйте позже\./, { since: marker });
  marker = player.getMessageBufferIndex();
  player.chat('/arcevents status');
  await expect(player).toHaveReceivedMessage(/Вы сейчас не участвуете в матче\./, { since: marker });
  marker = player.getMessageBufferIndex();
  player.chat('/arcevents leave');
  await expect(player).toHaveReceivedMessage(/Вы не в очереди\.\s+Меню: \/arcevents/, { since: marker });
});

test('event help is available through the player command route', async ({ player }) => {
  player.chat('/arcevents help');
  await expect(player).toHaveReceivedMessage(/arcevents|событ|event/i);
});
