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

package com.google.domain.registry.flows.contact;

import com.google.domain.registry.config.RegistryEnvironment;
import com.google.domain.registry.flows.ResourceTransferRequestFlow;
import com.google.domain.registry.model.contact.ContactCommand.Transfer;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.reporting.HistoryEntry;

import org.joda.time.Duration;

/**
 * An EPP flow that requests a transfer on a {@link ContactResource}.
 *
 * @error {@link com.google.domain.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link com.google.domain.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException}
 * @error {@link com.google.domain.registry.flows.ResourceTransferRequestFlow.AlreadyPendingTransferException}
 * @error {@link com.google.domain.registry.flows.ResourceTransferRequestFlow.MissingTransferRequestAuthInfoException}
 * @error {@link com.google.domain.registry.flows.ResourceTransferRequestFlow.ObjectAlreadySponsoredException}
 */
public class ContactTransferRequestFlow
    extends ResourceTransferRequestFlow<ContactResource, Transfer> {

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.CONTACT_TRANSFER_REQUEST;
  }

  @Override
  protected Duration getAutomaticTransferLength() {
    return RegistryEnvironment.get().config().getContactAutomaticTransferLength();
  }
}
