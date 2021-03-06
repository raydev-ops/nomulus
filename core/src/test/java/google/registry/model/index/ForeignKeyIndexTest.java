// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.index;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.testing.TestCacheRule;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link ForeignKeyIndex}. */
public class ForeignKeyIndexTest extends EntityTestCase {

  @Rule
  public final TestCacheRule testCacheRule =
      new TestCacheRule.Builder().withForeignIndexKeyCache(Duration.standardDays(1)).build();

  @Before
  public void setUp() {
    createTld("com");
  }

  @Test
  public void testPersistence() {
    // Persist a host and implicitly persist a ForeignKeyIndex for it.
    HostResource host = persistActiveHost("ns1.example.com");
    ForeignKeyIndex<HostResource> fki =
        ForeignKeyIndex.load(HostResource.class, "ns1.example.com", clock.nowUtc());
    assertThat(ofy().load().key(fki.getResourceKey()).now()).isEqualTo(host);
    assertThat(fki.getDeletionTime()).isEqualTo(END_OF_TIME);
  }

  @Test
  public void testIndexing() throws Exception {
    // Persist a host and implicitly persist a ForeignKeyIndex for it.
    persistActiveHost("ns1.example.com");
    verifyIndexing(
        ForeignKeyIndex.load(HostResource.class, "ns1.example.com", clock.nowUtc()),
        "deletionTime");
  }

  @Test
  public void testLoadForNonexistentForeignKey_returnsNull() {
    assertThat(ForeignKeyIndex.load(HostResource.class, "ns1.example.com", clock.nowUtc()))
        .isNull();
  }

  @Test
  public void testLoadForDeletedForeignKey_returnsNull() {
    HostResource host = persistActiveHost("ns1.example.com");
    persistResource(ForeignKeyIndex.create(host, clock.nowUtc().minusDays(1)));
    assertThat(ForeignKeyIndex.load(HostResource.class, "ns1.example.com", clock.nowUtc()))
        .isNull();
  }

  @Test
  public void testLoad_newerKeyHasBeenSoftDeleted() {
    HostResource host1 = persistActiveHost("ns1.example.com");
    clock.advanceOneMilli();
    ForeignKeyHostIndex fki = new ForeignKeyHostIndex();
    fki.foreignKey = "ns1.example.com";
    fki.topReference = Key.create(host1);
    fki.deletionTime = clock.nowUtc();
    persistResource(fki);
    assertThat(ForeignKeyIndex.load(
        HostResource.class, "ns1.example.com", clock.nowUtc())).isNull();
  }

  @Test
  public void testBatchLoad_skipsDeletedAndNonexistent() {
    persistActiveHost("ns1.example.com");
    HostResource host = persistActiveHost("ns2.example.com");
    persistResource(ForeignKeyIndex.create(host, clock.nowUtc().minusDays(1)));
    assertThat(ForeignKeyIndex.load(
        HostResource.class,
        ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
        clock.nowUtc()).keySet())
            .containsExactly("ns1.example.com");
  }

  @Test
  public void testDeadCodeThatDeletedScrapCommandsReference() {
    persistActiveHost("omg");
    assertThat(ForeignKeyIndex.load(HostResource.class, "omg", clock.nowUtc()).getForeignKey())
        .isEqualTo("omg");
  }

  private ForeignKeyIndex<HostResource> loadHostFki(String hostname) {
    return ForeignKeyIndex.load(HostResource.class, hostname, clock.nowUtc());
  }

  private ForeignKeyIndex<ContactResource> loadContactFki(String contactId) {
    return ForeignKeyIndex.load(ContactResource.class, contactId, clock.nowUtc());
  }

  @Test
  public void test_loadCached_cachesNonexistenceOfHosts() {
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class,
                ImmutableList.of("ns5.example.com", "ns6.example.com"),
                clock.nowUtc()))
        .isEmpty();
    persistActiveHost("ns4.example.com");
    persistActiveHost("ns5.example.com");
    persistActiveHost("ns6.example.com");
    clock.advanceOneMilli();
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class,
                ImmutableList.of("ns6.example.com", "ns5.example.com", "ns4.example.com"),
                clock.nowUtc()))
        .containsExactly("ns4.example.com", loadHostFki("ns4.example.com"));
  }

  @Test
  public void test_loadCached_cachesExistenceOfHosts() {
    HostResource host1 = persistActiveHost("ns1.example.com");
    HostResource host2 = persistActiveHost("ns2.example.com");
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com"),
                clock.nowUtc()))
        .containsExactly(
            "ns1.example.com",
            loadHostFki("ns1.example.com"),
            "ns2.example.com",
            loadHostFki("ns2.example.com"));
    deleteResource(host1);
    deleteResource(host2);
    persistActiveHost("ns3.example.com");
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class,
                ImmutableList.of("ns3.example.com", "ns2.example.com", "ns1.example.com"),
                clock.nowUtc()))
        .containsExactly(
            "ns1.example.com", loadHostFki("ns1.example.com"),
            "ns2.example.com", loadHostFki("ns2.example.com"),
            "ns3.example.com", loadHostFki("ns3.example.com"));
  }

  @Test
  public void test_loadCached_doesntSeeHostChangesWhileCacheIsValid() {
    HostResource originalHost = persistActiveHost("ns1.example.com");
    ForeignKeyIndex<HostResource> originalFki = loadHostFki("ns1.example.com");
    clock.advanceOneMilli();
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class, ImmutableList.of("ns1.example.com"), clock.nowUtc()))
        .containsExactly("ns1.example.com", originalFki);
    HostResource modifiedHost =
        persistResource(
            originalHost.asBuilder().setPersistedCurrentSponsorClientId("OtherRegistrar").build());
    clock.advanceOneMilli();
    ForeignKeyIndex<HostResource> newFki = loadHostFki("ns1.example.com");
    assertThat(newFki).isNotEqualTo(originalFki);
    assertThat(loadByForeignKey(HostResource.class, "ns1.example.com", clock.nowUtc()))
        .hasValue(modifiedHost);
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class, ImmutableList.of("ns1.example.com"), clock.nowUtc()))
        .containsExactly("ns1.example.com", originalFki);
  }

  @Test
  public void test_loadCached_filtersOutSoftDeletedHosts() {
    persistActiveHost("ns1.example.com");
    persistDeletedHost("ns2.example.com", clock.nowUtc().minusDays(1));
    assertThat(
            ForeignKeyIndex.loadCached(
                HostResource.class,
                ImmutableList.of("ns1.example.com", "ns2.example.com"),
                clock.nowUtc()))
        .containsExactly("ns1.example.com", loadHostFki("ns1.example.com"));
  }

  @Test
  public void test_loadCached_cachesContactFkis() {
    persistActiveContact("contactid1");
    ForeignKeyIndex<ContactResource> fki1 = loadContactFki("contactid1");
    assertThat(
        ForeignKeyIndex.loadCached(
            ContactResource.class,
            ImmutableList.of("contactid1", "contactid2"),
            clock.nowUtc()))
        .containsExactly("contactid1", fki1);
    persistActiveContact("contactid2");
    deleteResource(fki1);
    assertThat(
        ForeignKeyIndex.loadCached(
            ContactResource.class,
            ImmutableList.of("contactid1", "contactid2"),
            clock.nowUtc()))
        .containsExactly("contactid1", fki1);
    assertThat(
        ForeignKeyIndex.load(
            ContactResource.class,
            ImmutableList.of("contactid1", "contactid2"),
            clock.nowUtc()))
        .containsExactly("contactid2", loadContactFki("contactid2"));
  }
}
