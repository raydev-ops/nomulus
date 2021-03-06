// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistrarUtils}. */
@RunWith(JUnit4.class)
public class RegistrarUtilsTest {

  @Test
  public void testNormalizeRegistrarName_letterOrDigitOnly() {
    assertThat(RegistrarUtils.normalizeRegistrarName("129abzAZ")).isEqualTo("129abzaz");
  }

  @Test
  public void testNormalizeRegistrarName_hasSymbols() {
    assertThat(RegistrarUtils.normalizeRegistrarName("^}129a(bzAZ/:")).isEqualTo("129abzaz");
  }
}
