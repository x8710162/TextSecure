package org.whispersystems.axolotl;

import org.whispersystems.axolotl.ecc.ECPublicKey;
import org.whispersystems.axolotl.ratchet.ChainKey;

public interface AxolotolSessionState {

  public ChainKey getSenderChainKey();
  public ECPublicKey getSenderEphemeral();
  public int getPreviousCounter();

}
