package org.whispersystems.axolotl;

import java.util.List;

public interface AxolotolSession {

  public AxolotolSessionState getCurrentSessionState();
  public void setCurrentSessionState(AxolotolSessionState state);

  public List<AxolotolSessionState> getPreviousSessionStates();
  public void setPreviousSessionStates(List<AxolotolSessionState> sessionStates);

}
