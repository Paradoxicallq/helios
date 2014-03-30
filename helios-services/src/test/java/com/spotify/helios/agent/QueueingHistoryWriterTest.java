package com.spotify.helios.agent;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.spotify.helios.Polling;
import com.spotify.helios.ZooKeeperStandaloneServerManager;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.descriptors.TaskStatus.State;
import com.spotify.helios.common.descriptors.TaskStatusEvent;
import com.spotify.helios.master.ZooKeeperMasterModel;
import com.spotify.helios.servicescommon.coordination.DefaultZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.Paths;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClientProvider;
import com.spotify.helios.servicescommon.coordination.ZooKeeperModelReporter;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.spotify.helios.Polling.await;
import static com.spotify.helios.agent.QueueingHistoryWriter.MAX_NUMBER_STATUS_EVENTS_TO_RETAIN;
import static com.spotify.helios.common.descriptors.Goal.START;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

public class QueueingHistoryWriterTest {

  private static final long TIMESTAMP = 8675309L;
  private static final String HOSTNAME = "hostname";
  private static final Job JOB = Job.newBuilder()
      .setCommand(ImmutableList.<String>of())
      .setImage("image")
      .setName("foo")
      .setVersion("version")
      .build();
  private static final JobId JOB_ID = JOB.getId();
  private static final TaskStatus TASK_STATUS =  TaskStatus.newBuilder()
      .setState(State.CREATING)
      .setJob(JOB)
      .setGoal(START)
      .setContainerId("containerId")
      .build();

  private ZooKeeperStandaloneServerManager zk;
  private DefaultZooKeeperClient client;
  private QueueingHistoryWriter writer;
  private ZooKeeperMasterModel masterModel;
  private Path agentStateDirs;

  @Before
  public void setUp() throws Exception {
    zk = new ZooKeeperStandaloneServerManager();
    agentStateDirs = Files.createTempDirectory("helios-agents");

    client = new DefaultZooKeeperClient(zk.curator());
    makeWriter(client);
    masterModel = new ZooKeeperMasterModel(new ZooKeeperClientProvider(client,
        ZooKeeperModelReporter.noop()));
    client.ensurePath(Paths.configJobs());
    client.ensurePath(Paths.configJobRefs());
    client.ensurePath(Paths.historyJobHostEvents(JOB_ID, HOSTNAME));
    masterModel.registerHost(HOSTNAME);
    masterModel.addJob(JOB);
  }

  @After
  public void tearDown() throws Exception {
    writer.stopAsync().awaitTerminated();
    zk.stop();
  }

  private void makeWriter(ZooKeeperClient client) throws IOException {
    writer = new QueueingHistoryWriter(HOSTNAME,
        client, agentStateDirs.resolve("task-history.json"));
    writer.start();
  }

  @Test
  public void testZooKeeperErrorDoesntLoseItemsReally() throws Exception {
    final ZooKeeperClient mockClient = mock(ZooKeeperClient.class);
    makeWriter(mockClient);
    final String path = Paths.historyJobHostEventsTimestamp(JOB_ID, HOSTNAME, TIMESTAMP);
    final KeeperException exc = new KeeperException(Code.APIERROR){};
    // make save operations fail
    doThrow(exc).when(mockClient).createAndSetData(path, TASK_STATUS.toJsonBytes());

    writer.saveHistoryItem(JOB_ID, TASK_STATUS, TIMESTAMP);
    // wait up to 10s for it to fail twice -- and make sure I mocked it correctly.
    verify(mockClient, timeout(10000).atLeast(2)).createAndSetData(path, TASK_STATUS.toJsonBytes());

    // now make the client work
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        client.createAndSetData(
          (String) invocation.getArguments()[0],
          (byte[]) invocation.getArguments()[1]);
        return null;
      }
    }).when(mockClient).createAndSetData(path, TASK_STATUS.toJsonBytes());

    awaitHistoryItems();
  }

  @Test
  public void testSimpleWorkage() throws Exception {
    writer.saveHistoryItem(JOB_ID, TASK_STATUS, TIMESTAMP);

    final TaskStatusEvent historyItem = Iterables.getOnlyElement(awaitHistoryItems());
    assertEquals(JOB_ID, historyItem.getStatus().getJob().getId());
  }

  private Iterable<TaskStatusEvent> awaitHistoryItems() throws Exception {
    return await(10L, TimeUnit.SECONDS, new Callable<Iterable<TaskStatusEvent>>() {
      @Override
      public Iterable<TaskStatusEvent> call() throws Exception {
        final List<TaskStatusEvent> items = masterModel.getJobHistory(JOB_ID);
        return items.isEmpty() ? null : items;
      }
    });
  }

  @Test
  public void testWriteWithZooKeeperDown() throws Exception {
    zk.stop();
    writer.saveHistoryItem(JOB_ID, TASK_STATUS, TIMESTAMP);
    zk.start();
    final TaskStatusEvent historyItem = Iterables.getOnlyElement(awaitHistoryItems());
    assertEquals(JOB_ID, historyItem.getStatus().getJob().getId());
  }

  @Test
  public void testWriteWithZooKeeperDownAndInterveningCrash() throws Exception {
    zk.stop();
    writer.saveHistoryItem(JOB_ID, TASK_STATUS, TIMESTAMP);
    // simulate a crash by recreating the writer
    writer.stopAsync().awaitTerminated();
    makeWriter(client);
    zk.start();
    final TaskStatusEvent historyItem = Iterables.getOnlyElement(awaitHistoryItems());
    assertEquals(JOB_ID, historyItem.getStatus().getJob().getId());
  }

  @Test
  public void testKeepsNoMoreThanMaxHistoryItems() throws Exception {
    // And that it keeps the correct items!

    // Save a superflouous number of events
    for (int i = 0; i < MAX_NUMBER_STATUS_EVENTS_TO_RETAIN + 20; i++) {
      writer.saveHistoryItem(JOB_ID, TASK_STATUS, TIMESTAMP + i);
      Thread.sleep(50);  // just to allow other stuff a chance to run in the background
    }
    // Should converge to 30 items
    List<TaskStatusEvent> events = Polling.await(1, TimeUnit.MINUTES,
      new Callable<List<TaskStatusEvent>>() {
      @Override
      public List<TaskStatusEvent> call() throws Exception {
        if (!writer.isEmpty()) {
          return null;
        }
        final List<TaskStatusEvent> events = masterModel.getJobHistory(JOB_ID);
        if (events.size() == MAX_NUMBER_STATUS_EVENTS_TO_RETAIN) {
          return events;
        }
        return null;
      }
    });
    assertEquals(TIMESTAMP + MAX_NUMBER_STATUS_EVENTS_TO_RETAIN + 19,
        Iterables.getLast(events).getTimestamp());
    assertEquals(TIMESTAMP + 20, Iterables.get(events, 0).getTimestamp());
  }
}