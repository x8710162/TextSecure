package org.whispersystems.axolotl;

import org.whispersystems.axolotl.ecc.ECPublicKey;
import org.whispersystems.axolotl.protocol.CiphertextMessage;
import org.whispersystems.axolotl.protocol.WhisperMessageV2;
import org.whispersystems.axolotl.ratchet.ChainKey;
import org.whispersystems.axolotl.ratchet.MessageKeys;

public class AxolotolCipher {

  private final AxolotolSession session;

  public AxolotolCipher(AxolotolSession session) {
    this.session = session;
  }

  public CiphertextMessage encrypt(byte[] plaintext) {
    AxolotolSessionState sessionState    = session.getCurrentSessionState();
    ChainKey             chainKey        = sessionState.getSenderChainKey();
    MessageKeys          messageKeys     = chainKey.getMessageKeys();
    ECPublicKey          senderEphemeral = sessionState.getSenderEphemeral();
    int                  previousCounter = sessionState.getPreviousCounter();

    byte[]            ciphertextBody    = getCiphertext(messageKeys, plaintext);
    CiphertextMessage ciphertextMessage = new WhisperMessageV2(messageKeys.getMacKey(),
                                                               senderEphemeral, chainKey.getIndex(),
                                                               previousCounter, ciphertextBody);

    if (sessionState.hasPendingPreKey()) {
      Pair<Integer, ECPublicKey> pendingPreKey       = sessionState.getPendingPreKey();
      int                        localRegistrationId = sessionState.getLocalRegistrationId();

      ciphertextMessage = new PreKeyWhisperMessage(localRegistrationId, pendingPreKey.first,
                                                   pendingPreKey.second,
                                                   sessionState.getLocalIdentityKey(),
                                                   (WhisperMessageV2) ciphertextMessage);
    }

    sessionState.setSenderChainKey(chainKey.getNextChainKey());
    sessionRecord.save();

    return ciphertextMessage;

  }

  public byte[] decrypt(byte[]  message) {

  }
}
