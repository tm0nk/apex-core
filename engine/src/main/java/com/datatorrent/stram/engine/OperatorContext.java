/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.stram.engine;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.AttributeMap;
import com.datatorrent.api.Context;
import com.datatorrent.api.Stats.OperatorStats.CustomStats;
import com.datatorrent.api.StatsListener.OperatorCommand;
import com.datatorrent.netlet.util.CircularBuffer;
import com.datatorrent.stram.api.BaseContext;
import com.datatorrent.stram.api.StreamingContainerUmbilicalProtocol.ContainerStats;

/**
 * The for context for all of the operators<p>
 * <br>
 *
 * @author Chetan Narsude <chetan@datatorrent.com>
 * @since 0.3.2
 */
public class OperatorContext extends BaseContext implements Context.OperatorContext
{
  private static final long serialVersionUID = 2013060671427L;
  private final Thread thread;

  /**
   * @return the thread
   */
  public Thread getThread()
  {
    return thread;
  }

  private long lastProcessedWindowId = -1;
  private final int id;
  // the size of the circular queue should be configurable. hardcoded to 1024 for now.
  private final CircularBuffer<ContainerStats.OperatorStats> statsBuffer = new CircularBuffer<ContainerStats.OperatorStats>(1024);
  private final CircularBuffer<OperatorCommand> requests = new CircularBuffer<OperatorCommand>(1024);
  private CustomStats customStats;

  /**
   * The operator to which this context is passed, will timeout after the following milliseconds if no new tuple has been received by it.
   */
  // we should make it configurable somehow.
  private long idleTimeout = 1000L;

  @SuppressWarnings("ReturnOfCollectionOrArrayField")
  public BlockingQueue<OperatorCommand> getRequests()
  {
    return requests;
  }

  /**
   * @return the idleTimeout
   */
  public long getIdleTimeout()
  {
    return idleTimeout;
  }

  /**
   * @param idleTimeout the idleTimeout to set
   */
  public void setIdleTimeout(long idleTimeout)
  {
    this.idleTimeout = idleTimeout;
  }

  /**
   *
   * @param id the value of id
   * @param worker
   * @param attributes the value of attributes
   * @param parentContext
   */
  public OperatorContext(int id, Thread worker, AttributeMap attributes, Context parentContext)
  {
    super(attributes, parentContext);
    this.id = id;
    this.thread = worker;
  }

  @Override
  public int getId()
  {
    return id;
  }

  @Override
  public void setCustomStats(CustomStats stats)
  {
    this.customStats = stats;
  }

  /**
   * Reset counts for next heartbeat interval and return current counts. This is called as part of the heartbeat processing.
   *
   * @param stats
   * @return int
   */
  public final synchronized int drainStats(Collection<? super ContainerStats.OperatorStats> stats)
  {
    //logger.debug("{} draining {}", counters);
    return statsBuffer.drainTo(stats);
  }

  public final synchronized long getLastProcessedWindowId()
  {
    return lastProcessedWindowId;
  }

  synchronized void report(ContainerStats.OperatorStats stats, long windowId)
  {
    lastProcessedWindowId = windowId;
    stats.windowId = windowId;

    stats.customStats = this.customStats;
    this.customStats = null;

    if (!statsBuffer.offer(stats)) {
      statsBuffer.poll();
      statsBuffer.offer(stats);
    }
  }

  public void request(OperatorCommand request)
  {
    //logger.debug("Received request {} for (node={})", request, id);
    requests.add(request);
  }

  @SuppressWarnings("unused")
  private static final Logger logger = LoggerFactory.getLogger(OperatorContext.class);
}