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

package com.google.domain.registry.flows.domain;

import static com.google.domain.registry.model.domain.launch.LaunchPhase.CLAIMS;
import static com.google.domain.registry.util.DateTimeUtils.isAtOrAfter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import com.google.domain.registry.flows.EppException;
import com.google.domain.registry.model.domain.launch.LaunchCheckExtension;
import com.google.domain.registry.model.domain.launch.LaunchCheckResponseExtension;
import com.google.domain.registry.model.domain.launch.LaunchCheckResponseExtension.LaunchCheck;
import com.google.domain.registry.model.domain.launch.LaunchCheckResponseExtension.LaunchCheckName;
import com.google.domain.registry.model.eppoutput.CheckData;
import com.google.domain.registry.model.eppoutput.Response.ResponseExtension;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.registry.Registry.TldState;
import com.google.domain.registry.model.tmch.ClaimsListShard;

import java.util.Map.Entry;

/**
 * An EPP flow that checks whether strings are trademarked.
 *
 * @error {@link com.google.domain.registry.flows.ResourceCheckFlow.TooManyResourceChecksException}
 * @error {@link com.google.domain.registry.flows.ResourceFlow.BadCommandForRegistryPhaseException}
 * @error {@link com.google.domain.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 */
public class ClaimsCheckFlow extends BaseDomainCheckFlow {

  public static final ImmutableSet<TldState> DISALLOWED_TLD_STATES = Sets.immutableEnumSet(
      TldState.PREDELEGATION, TldState.SUNRISE);

  @Override
  protected void initDomainCheckFlow() throws EppException {
    registerExtensions(LaunchCheckExtension.class);
  }

  @Override
  protected CheckData getCheckData() {
    return null;
  }

  @Override
  protected ImmutableList<? extends ResponseExtension> getResponseExtensions() throws EppException {
    ImmutableList.Builder<LaunchCheck> launchChecksBuilder = new ImmutableList.Builder<>();
    for (Entry<String, InternetDomainName> entry : domainNames.entrySet()) {
      InternetDomainName domainName = entry.getValue();
      if (isAtOrAfter(now, Registry.get(domainName.parent().toString()).getClaimsPeriodEnd())) {
        throw new BadCommandForRegistryPhaseException();
      }
      String claimKey = ClaimsListShard.get().getClaimKey(domainName.parts().get(0));
      launchChecksBuilder.add(LaunchCheck.create(
          LaunchCheckName.create(claimKey != null, entry.getKey()), claimKey));
    }
    return ImmutableList.of(
        LaunchCheckResponseExtension.create(CLAIMS, launchChecksBuilder.build()));
  }

  @Override
  protected final ImmutableSet<TldState> getDisallowedTldStates() {
    return DISALLOWED_TLD_STATES;
  }
}
