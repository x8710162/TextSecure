package org.whispersystems.libaxolotl.kdf;

public class HKDFv3 extends HKDF {
  @Override
  protected int getIterationStartOffset() {
    return 1;
  }

  @Override
  protected int getIterationEndOffset() {
    return 1;
  }
}
