// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.flows;

import static com.google.domain.registry.flows.ResourceFlowUtils.verifyAuthInfoForResource;

import com.google.domain.registry.flows.EppException.ObjectDoesNotExistException;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import com.google.domain.registry.util.TypeUtils.TypeInstantiator;

/**
 * An EPP flow that mutates a single stored resource.
 *
 * @param <R> the resource type being changed
 * @param <C> the command type, marshalled directly from the epp xml
 */
public abstract class ResourceMutateFlow<R extends EppResource, C extends SingleResourceCommand>
    extends ResourceCreateOrMutateFlow<R, C> {

  @Override
  protected void initRepoId() {
    // existingResource could be null here if the flow is being called to mutate a resource that
    // does not exist, in which case don't throw NPE here and allow the non-existence to be handled
    // later.
    repoId = (existingResource == null) ? null : existingResource.getRepoId();
  }

  /** Fail if the object doesn't exist or was deleted. */
  @Override
  protected final void verifyIsAllowed() throws EppException {
    if (existingResource == null) {
      throw new ResourceToMutateDoesNotExistException(
          new TypeInstantiator<R>(getClass()){}.getExactType(), targetId);
    }
    if (command.getAuthInfo() != null) {
      verifyAuthInfoForResource(command.getAuthInfo(), existingResource);
    }
    verifyMutationAllowed();
  }

  /** Check invariants before allowing the command to proceed. */
  @SuppressWarnings("unused")
  protected void verifyMutationAllowed() throws EppException {}

  /** Resource with this id does not exist. */
  public static class ResourceToMutateDoesNotExistException extends ObjectDoesNotExistException {
    public ResourceToMutateDoesNotExistException(Class<?> type, String targetId) {
      super(type, targetId);
    }
  }
}
