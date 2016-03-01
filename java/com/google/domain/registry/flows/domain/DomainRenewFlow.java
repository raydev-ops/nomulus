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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.newAutorenewBillingEvent;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.newAutorenewPollMessage;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.updateAutorenewRecurrenceEndTime;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.validateFeeChallenge;
import static com.google.domain.registry.flows.domain.DomainFlowUtils.verifyUnitIsYears;
import static com.google.domain.registry.model.domain.DomainResource.MAX_REGISTRATION_YEARS;
import static com.google.domain.registry.model.eppoutput.Result.Code.Success;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.util.DateTimeUtils.leapSafeAddYears;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.domain.registry.flows.EppException;
import com.google.domain.registry.flows.EppException.ObjectPendingTransferException;
import com.google.domain.registry.flows.EppException.ParameterValueRangeErrorException;
import com.google.domain.registry.flows.OwnedResourceMutateFlow;
import com.google.domain.registry.model.billing.BillingEvent;
import com.google.domain.registry.model.billing.BillingEvent.Reason;
import com.google.domain.registry.model.domain.DomainCommand.Renew;
import com.google.domain.registry.model.domain.DomainRenewData;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.domain.GracePeriod;
import com.google.domain.registry.model.domain.Period;
import com.google.domain.registry.model.domain.fee.Fee;
import com.google.domain.registry.model.domain.fee.FeeRenewExtension;
import com.google.domain.registry.model.domain.fee.FeeRenewResponseExtension;
import com.google.domain.registry.model.domain.rgp.GracePeriodStatus;
import com.google.domain.registry.model.eppcommon.StatusValue;
import com.google.domain.registry.model.eppoutput.EppOutput;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.transfer.TransferStatus;

import com.googlecode.objectify.Ref;

import org.joda.money.Money;
import org.joda.time.DateTime;

import java.util.Set;

/**
 * An EPP flow that updates a domain resource.
 *
 * @error {@link com.google.domain.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link com.google.domain.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link com.google.domain.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException}
 * @error {@link com.google.domain.registry.flows.SingleResourceFlow.ResourceStatusProhibitsOperationException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.CurrencyValueScaleException}
 * @error {@link DomainFlowUtils.FeesMismatchException}
 * @error {@link DomainFlowUtils.FeesRequiredForPremiumNameException}
 * @error {@link DomainFlowUtils.UnsupportedFeeAttributeException}
 * @error {@link DomainRenewFlow.DomainHasPendingTransferException}
 * @error {@link DomainRenewFlow.ExceedsMaxRegistrationYearsException}
 * @error {@link DomainRenewFlow.IncorrectCurrentExpirationDateException}
 */
public class DomainRenewFlow extends OwnedResourceMutateFlow<DomainResource, Renew> {

  private static final Set<StatusValue> RENEW_DISALLOWED_STATUSES = ImmutableSet.of(
      StatusValue.CLIENT_RENEW_PROHIBITED,
      StatusValue.PENDING_DELETE,
      StatusValue.SERVER_RENEW_PROHIBITED);

  protected FeeRenewExtension feeRenew;
  protected Money renewCost;

  @Override
  protected Set<StatusValue> getDisallowedStatuses() {
    return RENEW_DISALLOWED_STATUSES;
  }

  @Override
  public final void initResourceCreateOrMutateFlow() throws EppException {
    registerExtensions(FeeRenewExtension.class);
    feeRenew = eppInput.getSingleExtension(FeeRenewExtension.class);
  }

  @Override
  protected void verifyMutationOnOwnedResourceAllowed() throws EppException {
    checkAllowedAccessToTld(getAllowedTlds(), existingResource.getTld());
    // Verify that the resource does not have a pending transfer on it.
    if (existingResource.getTransferData().getTransferStatus() == TransferStatus.PENDING) {
      throw new DomainHasPendingTransferException(targetId);
    }
    verifyUnitIsYears(command.getPeriod());
    // If the date they specify doesn't match the expiration, fail. (This is an idempotence check).
    if (!command.getCurrentExpirationDate().equals(
        existingResource.getRegistrationExpirationTime().toLocalDate())) {
      throw new IncorrectCurrentExpirationDateException();
    }
    renewCost = Registry.get(existingResource.getTld())
        .getDomainRenewCost(targetId, command.getPeriod().getValue(), now);
    validateFeeChallenge(targetId, existingResource.getTld(), feeRenew, renewCost);
  }

  @Override
  protected DomainResource createOrMutateResource() {
    DateTime newExpirationTime = leapSafeAddYears(
        existingResource.getRegistrationExpirationTime(), command.getPeriod().getValue());
    // Bill for this explicit renew itself.
    BillingEvent.OneTime explicitRenewEvent = new BillingEvent.OneTime.Builder()
        .setReason(Reason.RENEW)
        .setTargetId(targetId)
        .setClientId(getClientId())
        .setPeriodYears(command.getPeriod().getValue())
        .setCost(checkNotNull(renewCost))
        .setEventTime(now)
        .setBillingTime(
            now.plus(Registry.get(existingResource.getTld()).getRenewGracePeriodLength()))
        .setParent(historyEntry)
        .build();
    // End the old autorenew billing event and poll message now. This may delete the poll message.
    updateAutorenewRecurrenceEndTime(existingResource, now);
    // Create a new autorenew billing event and poll message starting at the new expiration time.
    BillingEvent.Recurring newAutorenewEvent = newAutorenewBillingEvent(existingResource)
        .setEventTime(newExpirationTime)
        .setParent(historyEntry)
        .build();
    PollMessage.Autorenew newAutorenewPollMessage = newAutorenewPollMessage(existingResource)
        .setEventTime(newExpirationTime)
        .setParent(historyEntry)
        .build();
    ofy().save().<Object>entities(explicitRenewEvent, newAutorenewEvent, newAutorenewPollMessage);
    return existingResource.asBuilder()
        .setRegistrationExpirationTime(newExpirationTime)
        .setAutorenewBillingEvent(Ref.create(newAutorenewEvent))
        .setAutorenewPollMessage(Ref.create(newAutorenewPollMessage))
        .addGracePeriod(GracePeriod.forBillingEvent(GracePeriodStatus.RENEW, explicitRenewEvent))
        .build();
  }

  @Override
  protected void verifyNewStateIsAllowed() throws EppException {
    if (leapSafeAddYears(now, MAX_REGISTRATION_YEARS)
        .isBefore(newResource.getRegistrationExpirationTime())) {
      throw new ExceedsMaxRegistrationYearsException();
    }
  }

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.DOMAIN_RENEW;
  }

  @Override
  protected final Period getCommandPeriod() {
    return command.getPeriod();
  }

  @Override
  protected final EppOutput getOutput() {
    return createOutput(
        Success,
        DomainRenewData.create(
            newResource.getFullyQualifiedDomainName(),
            newResource.getRegistrationExpirationTime()),
        (feeRenew == null) ? null : ImmutableList.of(
            new FeeRenewResponseExtension.Builder()
                .setCurrency(renewCost.getCurrencyUnit())
                .setFee(ImmutableList.of(Fee.create(renewCost.getAmount(), "renew")))
                .build()));
  }

  /** The domain has a pending transfer on it and so can't be explicitly renewed. */
  public static class DomainHasPendingTransferException extends ObjectPendingTransferException {
    public DomainHasPendingTransferException(String targetId) {
      super(targetId);
    }
  }

  /** The current expiration date is incorrect. */
  static class IncorrectCurrentExpirationDateException extends ParameterValueRangeErrorException {
    public IncorrectCurrentExpirationDateException() {
      super("The current expiration date is incorrect");
    }
  }

  /** New registration period exceeds maximum number of years. */
  static class ExceedsMaxRegistrationYearsException extends ParameterValueRangeErrorException {
    public ExceedsMaxRegistrationYearsException() {
      super(String.format(
          "Registrations cannot extend for more than %d years into the future",
          MAX_REGISTRATION_YEARS));
    }
  }
}
