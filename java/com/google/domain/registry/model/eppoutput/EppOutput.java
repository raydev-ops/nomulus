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

package com.google.domain.registry.model.eppoutput;

import com.google.common.annotations.VisibleForTesting;
import com.google.domain.registry.model.ImmutableObject;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;

/** This class represents the root EPP XML element for output. */
@XmlRootElement(name = "epp")
public class EppOutput extends ImmutableObject {
  @XmlElements({
      @XmlElement(name = "response", type = Response.class),
      @XmlElement(name = "greeting", type = Greeting.class) })
  ResponseOrGreeting responseOrGreeting;

  public static EppOutput create(ResponseOrGreeting responseOrGreeting) {
    EppOutput instance = new EppOutput();
    instance.responseOrGreeting = responseOrGreeting;
    return instance;
  }

  @VisibleForTesting
  public boolean isSuccess() {
    return ((Response) responseOrGreeting).result.getCode().isSuccess();
  }

  public Response getResponse() {
    return (Response) responseOrGreeting;
  }

  public boolean isResponse() {
    return responseOrGreeting instanceof Response;
  }

  /** Marker interface for types allowed inside of an {@link EppOutput}. */
  public interface ResponseOrGreeting {}
}
