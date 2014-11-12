/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class ListenableFutureTask<V> extends FutureTask<V> {

  private FutureTaskListener<V> listener;

  public ListenableFutureTask(Callable<V> callable, FutureTaskListener<V> listener) {
    super(callable);
    this.listener = listener;
  }

  public synchronized void setListener(FutureTaskListener<V> listener) {
    this.listener = listener;

    if (this.isDone()) {
      callback();
    }
  }

  @Override
  protected synchronized void done() {
    callback();
  }

  private void callback() {
    if (this.listener != null) {
      FutureTaskListener<V> nestedListener = this.listener;
      try {
        nestedListener.onSuccess(get());
      } catch (ExecutionException ee) {
        nestedListener.onFailure(ee);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }
}
