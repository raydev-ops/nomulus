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

package com.google.domain.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.model.ofy.CommitLogCheckpointRoot.loadRoot;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static com.google.domain.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static com.google.domain.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.domain.registry.model.ofy.CommitLogCheckpoint;
import com.google.domain.registry.model.ofy.CommitLogCheckpointRoot;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.FakeClock;
import com.google.domain.registry.testing.TaskQueueHelper.TaskMatcher;
import com.google.domain.registry.util.Retrier;
import com.google.domain.registry.util.TaskEnqueuer;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link CommitLogCheckpointAction}. */
@RunWith(MockitoJUnitRunner.class)
public class CommitLogCheckpointActionTest {

  private static final String QUEUE_NAME = "export-commits";

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Mock
  CommitLogCheckpointStrategy strategy;

  DateTime now = DateTime.now(UTC);
  CommitLogCheckpointAction task = new CommitLogCheckpointAction();

  @Before
  public void before() throws Exception {
    task.clock = new FakeClock(now);
    task.strategy = strategy;
    task.taskEnqueuer = new TaskEnqueuer(new Retrier(null, 1));
    when(strategy.computeCheckpoint()).thenReturn(
        CommitLogCheckpoint.create(now, ImmutableMap.of(1, START_OF_TIME)));
  }

  @Test
  public void testRun_noCheckpointEverWritten_writesCheckpointAndEnqueuesTask() throws Exception {
    task.run();
    assertTasksEnqueued(
        QUEUE_NAME,
        new TaskMatcher()
            .url(ExportCommitLogDiffAction.PATH)
            .param(ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM, START_OF_TIME.toString())
            .param(ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM, now.toString()));
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(now);
  }

  @Test
  public void testRun_checkpointWrittenBeforeNow_writesCheckpointAndEnqueuesTask()
      throws Exception {
    DateTime oneMinuteAgo = now.minusMinutes(1);
    persistResource(CommitLogCheckpointRoot.create(oneMinuteAgo));
    task.run();
    assertTasksEnqueued(
        QUEUE_NAME,
        new TaskMatcher()
            .url(ExportCommitLogDiffAction.PATH)
            .param(ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM, oneMinuteAgo.toString())
            .param(ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM, now.toString()));
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(now);
  }

  @Test
  public void testRun_checkpointWrittenAfterNow_doesntOverwrite_orEnqueueTask() throws Exception {
    DateTime oneMinuteFromNow = now.plusMinutes(1);
    persistResource(CommitLogCheckpointRoot.create(oneMinuteFromNow));
    task.run();
    assertNoTasksEnqueued(QUEUE_NAME);
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(oneMinuteFromNow);
  }
}
