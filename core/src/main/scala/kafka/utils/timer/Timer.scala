/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.utils.timer

import java.util.concurrent.{DelayQueue, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

import kafka.utils.threadsafe
import org.apache.kafka.common.utils.{KafkaThread, Time}

/**
 * 时间轮接口
 */
trait Timer {
  /**
    * Add a new task to this executor. It will be executed after the task's delay
    * (beginning from the time of submission)
    * @param timerTask the task to add
    */
  def add(timerTask: TimerTask): Unit

  /**
    * Advance the internal clock, executing any tasks whose expiration has been
    * reached within the duration of the passed timeout.
    * @param timeoutMs
    * @return whether or not any tasks were executed
    */
  def advanceClock(timeoutMs: Long): Boolean

  /**
    * Get the number of tasks pending execution
    * @return the number of tasks
    */
  def size: Int

  /**
    * Shutdown the timer service, leaving pending tasks unexecuted
    */
  def shutdown(): Unit
}
/**
 * 时间轮具体实现 参考：https://www.jianshu.com/p/87240220097b
 */
@threadsafe
class SystemTimer(executorName: String,
                  tickMs: Long = 1,
                  wheelSize: Int = 20,
                  startMs: Long = Time.SYSTEM.hiResClockMs) extends Timer {

  // timeout timer
  private[this] val taskExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
    def newThread(runnable: Runnable): Thread =
      KafkaThread.nonDaemon("executor-"+executorName, runnable)
  })
  //保存过期bucket
  private[this] val delayQueue = new DelayQueue[TimerTaskList]()
  private[this] val taskCounter = new AtomicInteger(0)
  private[this] val timingWheel = new TimingWheel(
    tickMs = tickMs,
    wheelSize = wheelSize,
    startMs = startMs,
    taskCounter = taskCounter,
    delayQueue
  )

  // Locks used to protect data structures while ticking
  private[this] val readWriteLock = new ReentrantReadWriteLock()
  private[this] val readLock = readWriteLock.readLock()
  private[this] val writeLock = readWriteLock.writeLock()

  def add(timerTask: TimerTask): Unit = {
    readLock.lock()
    try {
      addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + Time.SYSTEM.hiResClockMs))
    } finally {
      readLock.unlock()
    }
  }

  /**
   * 添加任务
   * @param timerTaskEntry
   */
  private def addTimerTaskEntry(timerTaskEntry: TimerTaskEntry): Unit = {
    if (!timingWheel.add(timerTaskEntry)) {
      // Already expired or cancelled
      if (!timerTaskEntry.cancelled)
        //到期的任务，使用taskExecutor执行
        taskExecutor.submit(timerTaskEntry.timerTask)
    }
  }

  private[this] val reinsert = (timerTaskEntry: TimerTaskEntry) => addTimerTaskEntry(timerTaskEntry)

  /*
   * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
   * waits up to timeoutMs before giving up.
   */
  def advanceClock(timeoutMs: Long): Boolean = {
    var bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    if (bucket != null) {
      writeLock.lock()
      try {
        while (bucket != null) {
          //时间轮时钟向前推进
          timingWheel.advanceClock(bucket.getExpiration())
          //已过期的bucket,清空上面的任务，并将任务重新加入到下层时间轮，当下层时间轮转完一圈时，上层时间轮向前推进一格
          bucket.flush(reinsert)
          bucket = delayQueue.poll()
        }
      } finally {
        writeLock.unlock()
      }
      true
    } else {
      false
    }
  }

  def size: Int = taskCounter.get

  override def shutdown(): Unit = {
    taskExecutor.shutdown()
  }

}

