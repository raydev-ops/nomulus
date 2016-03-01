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

package com.google.domain.registry.model;

import static com.google.domain.registry.flows.EppXmlTransformer.marshalInput;
import static com.google.domain.registry.xml.ValidationMode.STRICT;
import static com.google.domain.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.domain.registry.testing.EppLoader;

/** Unit tests for {@code ResourceCommand}. */
public abstract class ResourceCommandTestCase extends EntityTestCase {
  protected void doXmlRoundtripTest(String inputFilename, String... ignoredPaths)
      throws Exception {
    EppLoader eppLoader = new EppLoader(this, inputFilename);
    assertXmlEquals(
        eppLoader.getEppXml(),
        new String(marshalInput(eppLoader.getEpp(), STRICT), UTF_8),
        ignoredPaths);
  }
}
