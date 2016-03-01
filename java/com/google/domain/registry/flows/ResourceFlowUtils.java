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

import static com.google.common.base.Preconditions.checkState;
import static com.google.domain.registry.model.domain.DomainResource.extendRegistrationWithCap;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.domain.registry.flows.EppException.AuthorizationErrorException;
import com.google.domain.registry.flows.EppException.InvalidAuthorizationInformationErrorException;
import com.google.domain.registry.model.EppResource;
import com.google.domain.registry.model.EppResource.Builder;
import com.google.domain.registry.model.EppResource.ForeignKeyedEppResource;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.eppcommon.AuthInfo;
import com.google.domain.registry.model.eppcommon.AuthInfo.BadAuthInfoException;
import com.google.domain.registry.model.eppcommon.StatusValue;
import com.google.domain.registry.model.eppcommon.Trid;
import com.google.domain.registry.model.index.ForeignKeyIndex;
import com.google.domain.registry.model.poll.PendingActionNotificationResponse;
import com.google.domain.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import com.google.domain.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.transfer.TransferData;
import com.google.domain.registry.model.transfer.TransferResponse;
import com.google.domain.registry.model.transfer.TransferResponse.ContactTransferResponse;
import com.google.domain.registry.model.transfer.TransferResponse.DomainTransferResponse;
import com.google.domain.registry.model.transfer.TransferStatus;

import org.joda.time.DateTime;

/** Static utility functions for resource transfer flows. */
public class ResourceFlowUtils {

  /** Statuses for which an exDate should be added to transfer responses. */
  private static final ImmutableSet<TransferStatus> ADD_EXDATE_STATUSES = Sets.immutableEnumSet(
      TransferStatus.PENDING, TransferStatus.CLIENT_APPROVED, TransferStatus.SERVER_APPROVED);

  /**
   * Create a transfer response using the id and type of this resource and the specified
   * {@link TransferData}.
   */
  public static TransferResponse createTransferResponse(
      EppResource eppResource, TransferData transferData, DateTime now) {
    assertIsContactOrDomain(eppResource);
    TransferResponse.Builder<? extends TransferResponse, ?> builder;
    if (eppResource instanceof ContactResource) {
      builder = new ContactTransferResponse.Builder().setContactId(eppResource.getForeignKey());
    } else {
      DomainResource domain = (DomainResource) eppResource;
      builder = new DomainTransferResponse.Builder()
          .setFullyQualifiedDomainNameName(eppResource.getForeignKey())
          .setExtendedRegistrationExpirationTime(
              ADD_EXDATE_STATUSES.contains(transferData.getTransferStatus())
                  ? extendRegistrationWithCap(
                      now,
                      domain.getRegistrationExpirationTime(),
                      transferData.getExtendedRegistrationYears())
                  : null);
    }
    builder.setGainingClientId(transferData.getGainingClientId())
        .setLosingClientId(transferData.getLosingClientId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus());
    return builder.build();
  }

  /**
   * Create a pending action notification response indicating the resolution of a transfer.
   * <p>
   * The returned object will use the id and type of this resource, the trid of the resource's last
   * transfer request, and the specified status and date.
   */
  public static PendingActionNotificationResponse createPendingTransferNotificationResponse(
      EppResource eppResource,
      Trid transferRequestTrid,
      boolean actionResult,
      DateTime processedDate) {
    assertIsContactOrDomain(eppResource);
    return eppResource instanceof ContactResource
        ? ContactPendingActionNotificationResponse.create(
            eppResource.getForeignKey(), actionResult, transferRequestTrid, processedDate)
        : DomainPendingActionNotificationResponse.create(
            eppResource.getForeignKey(), actionResult, transferRequestTrid, processedDate);
  }

  private static void assertIsContactOrDomain(EppResource eppResource) {
    checkState(eppResource instanceof ContactResource || eppResource instanceof DomainResource);
  }

  /** Check that the given clientId corresponds to the owner of given resource. */
  public static void verifyResourceOwnership(String myClientId, EppResource resource)
      throws EppException {
    if (!myClientId.equals(resource.getCurrentSponsorClientId())) {
      throw new ResourceNotOwnedException();
    }
  }

  /**
   * Performs common deletion operations on an EPP resource and returns a builder for further
   * modifications. This is broken out into ResourceFlowUtils in order to expose the functionality
   * to async flows (i.e. mapreduces).
   */
  @SuppressWarnings("unchecked")
  public static <R extends EppResource> Builder<R, ? extends Builder<R, ?>>
      prepareDeletedResourceAsBuilder(R existingResource, DateTime now) {
    Builder<R, ? extends Builder<R, ?>> builder =
        (Builder<R, ? extends Builder<R, ?>>) existingResource.asBuilder()
            .setDeletionTime(now)
            .setStatusValues(null)
            .setTransferData(
                existingResource.getStatusValues().contains(StatusValue.PENDING_TRANSFER)
                    ? existingResource.getTransferData().asBuilder()
                        .setTransferStatus(TransferStatus.SERVER_CANCELLED)
                        .setServerApproveEntities(null)
                        .setServerApproveBillingEvent(null)
                        .setServerApproveAutorenewEvent(null)
                        .setServerApproveAutorenewPollMessage(null)
                        .setPendingTransferExpirationTime(null)
                        .build()
                    : existingResource.getTransferData())
            .wipeOut();
    return builder;
  }

  /** Update the relevant {@link ForeignKeyIndex} to cache the new deletion time. */
  public static <R extends EppResource> void updateForeignKeyIndexDeletionTime(R resource) {
    if (resource instanceof ForeignKeyedEppResource) {
      ofy().save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
    }
  }

  /** If there is a transfer out, delete the server-approve entities and enqueue a poll message. */
  public static <R extends EppResource> void handlePendingTransferOnDelete(
      R existingResource, R newResource, DateTime now, HistoryEntry historyEntry) {
    if (existingResource.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      TransferData oldTransferData = existingResource.getTransferData();
      ofy().delete().keys(oldTransferData.getServerApproveEntities());
      ofy().save().entity(new PollMessage.OneTime.Builder()
          .setClientId(oldTransferData.getGainingClientId())
          .setEventTime(now)
          .setMsg(TransferStatus.SERVER_CANCELLED.getMessage())
          .setResponseData(ImmutableList.of(
              createTransferResponse(newResource, newResource.getTransferData(), now),
              createPendingTransferNotificationResponse(
                  existingResource, oldTransferData.getTransferRequestTrid(), false, now)))
          .setParent(historyEntry)
          .build());
    }
  }

  /** The specified resource belongs to another client. */
  public static class ResourceNotOwnedException extends AuthorizationErrorException {
    public ResourceNotOwnedException() {
      super("The specified resource belongs to another client");
    }
  }

  /** Check that the given AuthInfo is valid for the given resource. */
  public static void verifyAuthInfoForResource(AuthInfo authInfo, EppResource resource)
      throws EppException {
    try {
      authInfo.verifyAuthorizedFor(resource);
    } catch (BadAuthInfoException e) {
      throw new BadAuthInfoForResourceException();
    }
  }

  /** Authorization information for accessing resource is invalid. */
  public static class BadAuthInfoForResourceException
      extends InvalidAuthorizationInformationErrorException {
    public BadAuthInfoForResourceException() {
      super("Authorization information for accessing resource is invalid");
    }
  }
}
