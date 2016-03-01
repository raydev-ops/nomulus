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

package com.google.domain.registry.tools;

import com.google.common.base.Optional;
import com.google.common.collect.Ordering;
import com.google.domain.registry.model.registry.Registries;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.registry.Registry.TldType;
import com.google.domain.registry.model.registry.RegistryCursor;
import com.google.domain.registry.model.registry.RegistryCursor.CursorType;
import com.google.domain.registry.tools.Command.RemoteApiCommand;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;

/** Lists {@link RegistryCursor} timestamps used by locking rolling cursor tasks, like in RDE. */
@Parameters(separators = " =", commandDescription = "Lists cursor timestamps used by LRC tasks")
final class ListCursorsCommand implements RemoteApiCommand {

  @Parameter(
      names = "--type",
      description = "Which cursor to list.",
      required = true)
  private CursorType cursorType;

  @Parameter(
      names = "--tld_type",
      description = "Filter TLDs of a certain type (REAL or TEST.)")
  private TldType filterTldType = TldType.REAL;

  @Parameter(
      names = "--escrow_enabled",
      description = "Filter TLDs to only include those with RDE escrow enabled.")
  private boolean filterEscrowEnabled;

  @Override
  public void run() throws Exception {
    List<String> lines = new ArrayList<>();
    for (String tld : Registries.getTlds()) {
      Registry registry = Registry.get(tld);
      if (filterTldType != registry.getTldType()) {
        continue;
      }
      if (filterEscrowEnabled && !registry.getEscrowEnabled()) {
        continue;
      }
      Optional<DateTime> cursor = RegistryCursor.load(registry, cursorType);
      lines.add(String.format("%-25s%s", cursor.isPresent() ? cursor.get() : "absent", tld));
    }
    for (String line : Ordering.natural().sortedCopy(lines)) {
      System.out.println(line);
    }
  }
}
