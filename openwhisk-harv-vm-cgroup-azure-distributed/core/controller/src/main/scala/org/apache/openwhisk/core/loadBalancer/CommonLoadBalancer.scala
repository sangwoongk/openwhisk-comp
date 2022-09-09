/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.loadBalancer

import akka.actor.ActorRef
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.LongAdder

import akka.actor.{ActorSystem, Actor, Props}
import akka.event.Logging.InfoLevel
import akka.stream.ActorMaterializer
import org.apache.kafka.clients.producer.RecordMetadata
import pureconfig._
import org.apache.openwhisk.common.LoggingMarkers._
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{Map=>MMap}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Random}
import scala.math

import java.time.Instant
import org.apache.openwhisk.core.containerpool.Interval

/**
 * Abstract class which provides common logic for all LoadBalancer implementations.
 */
abstract class CommonLoadBalancer(config: WhiskConfig,
                                  feedFactory: FeedFactory,
                                  controllerInstance: ControllerInstanceId)(implicit val actorSystem: ActorSystem,
                                                                            logging: Logging,
                                                                            materializer: ActorMaterializer,
                                                                            messagingProvider: MessagingProvider)
    extends LoadBalancer {

  protected implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  // val lbConfig: ShardingContainerPoolBalancerConfig =
  //   loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer)
  val lbConfig: HarvestVMContainerPoolBalancerConfig =
    loadConfigOrThrow[HarvestVMContainerPoolBalancerConfig](ConfigKeys.loadbalancer)
  protected val invokerPool: ActorRef

  /** State related to invocations and throttling */
  protected[loadBalancer] val activationSlots = TrieMap[ActivationId, ActivationEntry]()
  protected[loadBalancer] val activationPromises =
    TrieMap[ActivationId, Promise[Either[ActivationId, WhiskActivation]]]()
  protected val activationsPerNamespace = TrieMap[UUID, LongAdder]()
  protected val totalActivations = new LongAdder()
  protected val totalBlackBoxActivationMemory = new LongAdder()
  protected val totalManagedActivationMemory = new LongAdder()
  
  /* states related to load estimation, yanqi*/
  // keeps the estimated load of next epoch
  protected[loadBalancer] val functionLoad = TrieMap[FullyQualifiedEntityName, Double]()
  // keeps the data structure used to estimate load of each function
  // only accessed in loadProcessor
  protected[loadBalancer] val functionLoadRecords = MMap[FullyQualifiedEntityName, ActionLoadRecord]()
  protected val loadMaxHistoryLen: Int = 10
  protected val loadInitUpdateInterval: Double = 10.0  // seconds
  protected val loadUpdateInterval: Double = 60.0     // seconds
  case class LoadSample(actionId: FullyQualifiedEntityName)
  // use another process for updating load records
  private[loadBalancer] val loadProcessor = actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = {
      case sample: LoadSample =>
        // logging.info(this, s"In CommonLoadBalancer dataProcessor") 
        val estimated_rps = functionLoadRecords.getOrElseUpdate(sample.actionId, 
                new ActionLoadRecord(loadMaxHistoryLen, loadInitUpdateInterval, loadUpdateInterval))
                .addInvocation()
        // logging.info(this, s"loadProcessor record set up") 
        if(estimated_rps >= 0) {
          functionLoad.update(sample.actionId, estimated_rps)
          logging.info(this, s"function ${sample.actionId.asString} estimated_rps = ${estimated_rps}")
        }
    }
  }))

  /******** functions related to consistent hash ring ***********/
  // return the next index in the consistent hashing ring
  def hashRingNextIndex(currId: Int, maxId: Int): Int = {
    var nextId: Int = -1
    if(currId >= maxId)
      nextId = 0
    else
      nextId = currId + 1
    nextId
  }
  // return the previous index in the consistent hashing ring
  def hashRingPrevIndex(currId: Int, maxId: Int): Int = {
    var prevId: Int = -1
    if(currId <= 0)
      prevId = maxId
    else
      prevId = currId - 1
    prevId
  }
  // find the next usable node in the ring
  def findHashRingNextNode(invokerRing: IndexedSeq[InvokerHealth], 
      invokerId: Int, maxInvokerId: Int): Int = {
    // invokerId should be identical to its index in invokerRing
    if(invokerId != invokerRing(invokerId).id.toInt) {
      logging.error(this, s"Invoker ${invokerId} & index in ring ${invokerRing(invokerId).id.toInt} does not match")
    }
    var nextInvokerId: Int = -1   // return data, id of next usable invoker
    var nextId: Int = hashRingNextIndex(invokerId, maxInvokerId)   // next index to search
    var nextFound: Boolean = false
    while(!nextFound && nextId != invokerId) {
      if(invokerRing(nextId).status.isUsable) {
        nextInvokerId = nextId
        nextFound = true
      }
      nextId = hashRingNextIndex(nextId, maxInvokerId)
    }
    nextInvokerId
  } 
  // find the previous usable node in the ring 
  def findHashRingPrevNode(invokerRing: IndexedSeq[InvokerHealth], 
      invokerId: Int, maxInvokerId: Int): Int = {
    // invokerId should be identical to its index in invokerRing
    if(invokerId != invokerRing(invokerId).id.toInt) {
      logging.error(this, s"Invoker ${invokerId} & index in ring ${invokerRing(invokerId).id.toInt} does not match")
    }
    var prevInvokerId: Int = -1   // return data, id of previous usable invoker
    var prevId: Int = hashRingPrevIndex(invokerId, maxInvokerId)
    var prevFound: Boolean = false
    while(!prevFound && prevId != invokerId) {
      if(invokerRing(prevId).status.isUsable) {
        prevFound = true
        prevInvokerId = prevId
      }
      prevId = hashRingPrevIndex(prevId, maxInvokerId)
    }
    prevInvokerId
  }
  /* states related to invoker set of each function, yanqi*/
  // maxInvokerId to form the end point of ring in consistent hashing
  protected[loadBalancer] val maxInvokerId: Int = 499
  // keeps the (home_invoker, hashsed_function_id) for each function
  // home_invoker is the real id (not index) of the home invoker of the function
  protected[loadBalancer] val functionHomeInvoker = TrieMap[FullyQualifiedEntityName, (Int, Int)]()
  // mapping from hash_id to function, only updated when new function arrives
  // since we always use the same function
  protected[loadBalancer] val hashFunctionMap = MMap[Int, IndexedSeq[FullyQualifiedEntityName]]()
  // keeps the (num_invokers, size_version) for each function
  protected[loadBalancer] val functionInvokerSet = TrieMap[FullyQualifiedEntityName, (Int, Long)]()
  // keeps the data structure used to estimate load of each function
  // only accessed in loadProcessor
  protected[loadBalancer] val functionInvokerSetState = MMap[FullyQualifiedEntityName, ActionInvokerSetState]()
  protected val invokerSetMinShrinkInterval: Long = 30 * 1000 // in ms
  // request for seeing a new function, insert (home, hased_id) to records
  case class InvokerSetNewFunctionRequest(actionId: FullyQualifiedEntityName,
    homeInvoker: Int, hashId: Int)
  // request for invoker departure/arrival 
  // function homes might need to be reshuffled accordingly
  case class InvokerSetInvokerStatusRequest(invokerRing: IndexedSeq[InvokerHealth],
    invokerId: Int, becomeUsable: Boolean)
  // request for changing size of invoker set
  case class InvokerSetSizeRequest(actionId: FullyQualifiedEntityName, 
    isShrink: Boolean, numInvokers: Int, version: Long)
  // use another process for updating load records
  private[loadBalancer] val invokerSetProcessor = actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = {
      case req: InvokerSetNewFunctionRequest =>
        if(!functionHomeInvoker.contains(req.actionId)) {
          // create record only for first invocation
          functionHomeInvoker.update(req.actionId, (req.homeInvoker, req.hashId))
          logging.info(this, s"function (new) ${req.actionId.asString} homeInvoker=${req.homeInvoker} hashId=${req.hashId}")
          // add the function to hashId->function map
          if(!hashFunctionMap.contains(req.hashId))
            hashFunctionMap(req.hashId) = IndexedSeq[FullyQualifiedEntityName]()
          hashFunctionMap(req.hashId) = hashFunctionMap(req.hashId) ++ IndexedSeq(req.actionId)
        }
      case req: InvokerSetInvokerStatusRequest =>
        // in both case (becomeUsable or not), InvokerId's previous usable node needs to be found
        val usableInvokers = req.invokerRing.filter(_.status.isUsable)
        // ignore numInvokers == 0 since reshuffling is meaningless
        if(usableInvokers.size == 1) {
          // reshuffle all functions to the only usable invoker
          var usableInvokerId: Int = usableInvokers(0).id.toInt
          // invokerId should be identical to its index in invokerRing
          if(usableInvokerId != req.invokerRing(usableInvokerId).id.toInt) {
            logging.error(this, s"Invoker ${req.invokerRing(usableInvokerId).id.toInt} & index in ring ${usableInvokerId} does not match")
          }
          // reshuffle all witnessed functions
          var hashId: Int = 0
          while(hashId <= maxInvokerId) {
            if(hashFunctionMap.contains(hashId)) {
              hashFunctionMap(hashId).foreach { functionName =>
                 functionHomeInvoker.update(functionName, (usableInvokerId, hashId))
                 logging.info(this, s"function (changed) ${functionName.asString} homeInvoker=${usableInvokerId} hashId=${hashId}")
                 functionInvokerSetState.getOrElseUpdate(functionName, 
                   new ActionInvokerSetState(invokerSetMinShrinkInterval))
                   .setHomeUpdated()
              }
            }
            hashId = hashId + 1
          }
        } else if (usableInvokers.size > 1) {
          if(req.becomeUsable) {
            // remap functions with id between previous & this invoker to this invoker
            var prevInvokerId: Int = findHashRingPrevNode(req.invokerRing, req.invokerId, maxInvokerId)
            if(prevInvokerId < 0) {
              logging.error(this, s"Invoker ${req.invokerId} prevNode doesn't exist, becomeUsable=${req.becomeUsable}, numUsableInvokers=${usableInvokers.size}(>1)")
            } else {
              var hashId: Int = hashRingNextIndex(prevInvokerId, maxInvokerId)
              var endHashId: Int = hashRingNextIndex(req.invokerId, maxInvokerId)
              while(hashId != endHashId) {
                if(hashFunctionMap.contains(hashId)) {
                  hashFunctionMap(hashId).foreach { functionName =>
                    functionHomeInvoker.update(functionName, (req.invokerId, hashId))
                    logging.info(this, s"function (changed) ${functionName.asString} homeInvoker=${req.invokerId} hashId=${hashId}")
                    functionInvokerSetState.getOrElseUpdate(functionName, 
                      new ActionInvokerSetState(invokerSetMinShrinkInterval))
                      .setHomeUpdated()
                  }
                }
                hashId = hashRingNextIndex(hashId, maxInvokerId)
              }
            }
          } else {
            // remap functions with id between previous & this invoker to next invoker
            var prevInvokerId: Int = findHashRingPrevNode(req.invokerRing, req.invokerId, maxInvokerId)
            var nextInvokerId: Int = findHashRingNextNode(req.invokerRing, req.invokerId, maxInvokerId)
            if(prevInvokerId < 0 || nextInvokerId < 0) {
              logging.error(this, s"Invoker ${req.invokerId} either prevNode=${prevInvokerId} or nextNode=${nextInvokerId} doesn't exist, becomeUsable=${req.becomeUsable}, numUsableInvokers=${usableInvokers.size}(>1)")
            } else {
              var hashId: Int = hashRingNextIndex(prevInvokerId, maxInvokerId)
              var endHashId: Int = hashRingNextIndex(req.invokerId, maxInvokerId)
              while(hashId != endHashId) {
                if(hashFunctionMap.contains(hashId)) {
                  hashFunctionMap(hashId).foreach { functionName =>
                    functionHomeInvoker.update(functionName, (nextInvokerId, hashId))
                    logging.info(this, s"function (changed) ${functionName.asString} homeInvoker=${nextInvokerId} hashId=${hashId}")
                    functionInvokerSetState.getOrElseUpdate(functionName, 
                      new ActionInvokerSetState(invokerSetMinShrinkInterval))
                      .setHomeUpdated()
                  }
                }
                hashId = hashRingNextIndex(hashId, maxInvokerId)
              }
            }
          }
        }
      case req: InvokerSetSizeRequest =>
        // logging.info(this, s"In CommonLoadBalancer dataProcessor") 
        val (update, num_invokers, version) 
            = functionInvokerSetState.getOrElseUpdate(req.actionId, 
                new ActionInvokerSetState(invokerSetMinShrinkInterval))
                .update(req.numInvokers, req.isShrink, req.version)
        // logging.info(this, s"loadProcessor record set up") 
        if(update) {
          functionInvokerSet.update(req.actionId, (num_invokers, version))
          logging.info(this, s"function ${req.actionId.asString} num_invokers=${num_invokers} version=${version}")
        }
        // logging.info(this, s"loadProcessor functionLoad updated") 
    }
  }))
  
  /* distribution of cpu usage of functions, yanqi*/
  protected[loadBalancer] val functionCpuUtil = TrieMap[FullyQualifiedEntityName, Double]()
  // value for docker --cpus limit
  protected[loadBalancer] val functionCpuLimit = TrieMap[FullyQualifiedEntityName, Double]()
  // execution time estimation (in ms)
  protected[loadBalancer] val functionExeTime = TrieMap[FullyQualifiedEntityName, Long]()
    // distribution is only accessed in dataProcessor
  protected[loadBalancer] val functionCpuTimeDistr = MMap[FullyQualifiedEntityName, Distribution]()

  // protected val cpuUtilNumCores: Int = 40
  protected val cpuUtilNumCores: Int = 16
  protected val cpuUtilUpdatBatch: Int = 5
  protected val cpuUtilPercentile: Double = 0.75
  protected val cpuLimitPercentile: Double = 0.99
  protected val exeTimePercentile: Double = 0.75
  protected val functionMaxTime: Long = 10*60*1000 // 10 min transformed to unit of ms
  protected val functionSampleRate: Double = 1.0
  protected val functionSampleUseExpectation: Boolean = true
  // protected val cpuUtilWindow:Int = 10
  protected val redundantRatio: Double = 1.5  // general over-provision ratio for cpu limit
  protected val provisionRatio: Double = 2.0  // cpu limit overprovision ratio for the 1st invocation of a function
  protected val randomGen = Random
  protected val maxCpuLimit: Double = 4.0
  protected val minCpuLimit: Double = 1.0

  // exeTime and totalTime unit is ms
  case class InvocationSample(actionId: FullyQualifiedEntityName, cpuUtil: Double, 
    exeTime: Long, totalTime:Long, updateCpuLimit: Boolean)
  // use another process for proecssing data wrt function cpu util distribution
  private val dataProcessor = actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = {
      case sample: InvocationSample =>
        // logging.info(this, s"In CommonLoadBalancer dataProcessor") 
        val (estimated_cpu, cpu_limit, estimated_time) = functionCpuTimeDistr.getOrElseUpdate(sample.actionId, 
                // new Distribution(cpuUtilNumCores, cpuUtilUpdatBatch, cpuUtilPercentile, cpuLimitPercentile, cpuUtilWindow))
                new Distribution(cpuUtilNumCores, functionMaxTime, 
                  cpuUtilUpdatBatch, cpuUtilPercentile, cpuLimitPercentile,
                  exeTimePercentile))
                .addSample(sample.cpuUtil, sample.exeTime, functionSampleUseExpectation)
        // logging.info(this, s"dataProcessor distribution set up") 
        if(estimated_cpu > 0)
          functionCpuUtil.update(sample.actionId, estimated_cpu)
        if(estimated_time > 0)
          functionExeTime.update(sample.actionId, estimated_time)
        // logging.info(this, s"dataProcessor functionCpuUtil updated") 
        val estimated_limit = math.max(minCpuLimit, math.min(maxCpuLimit, math.ceil(cpu_limit * redundantRatio)))
        if(sample.updateCpuLimit) {
          val provision_limit = math.max(minCpuLimit, math.min(maxCpuLimit, math.ceil(sample.cpuUtil * provisionRatio)))
          val provision_usage = math.max(minCpuLimit, math.min(maxCpuLimit, math.ceil(sample.cpuUtil * redundantRatio)))
          val provision_time = (sample.exeTime * redundantRatio).toLong
          // force update when the function is invoked the first time
          functionCpuLimit.update(sample.actionId, provision_limit)
          functionCpuUtil.update(sample.actionId, provision_usage)
          functionExeTime.update(sample.actionId, provision_time)
          logging.info(this, s"function ${sample.actionId.asString} cpu_limit = ${functionCpuLimit.get(sample.actionId)}, cpu_usage = ${functionCpuUtil.get(sample.actionId)}, exe_time = ${functionExeTime.get(sample.actionId)}ms")
        } else {
          // update limit according to redundantRatio 
          if(cpu_limit > 0)
            functionCpuLimit.update(sample.actionId, estimated_limit)
          if(estimated_cpu > 0)
            functionCpuUtil.update(sample.actionId, estimated_cpu)
          if(estimated_time > 0)
            functionExeTime.update(sample.actionId, estimated_time)
          // val curr_limit = functionCpuLimit.getOrElse(sample.actionId, 0.0)
          // if(curr_limit < estimated_limit)
          //   functionCpuLimit.update(sample.actionId, estimated_limit)
          // // else if(cpu_limit < math.floor(curr_limit/(redundantRatio*redundantRatio)))
          // //   functionCpuLimit.update(sample.actionId, math.ceil(curr_limit/redundantRatio) )
          logging.info(this, s"function ${sample.actionId.asString} cpu_limit = ${functionCpuLimit.get(sample.actionId)}, cpu_usage = ${functionCpuUtil.get(sample.actionId)}, exe_time = ${functionExeTime.get(sample.actionId)}ms") 
        }
    }
  }))

  protected def emitMetrics() = {
    MetricEmitter.emitGaugeMetric(LOADBALANCER_ACTIVATIONS_INFLIGHT(controllerInstance), totalActivations.longValue)
    MetricEmitter.emitGaugeMetric(
      LOADBALANCER_MEMORY_INFLIGHT(controllerInstance, ""),
      totalBlackBoxActivationMemory.longValue + totalManagedActivationMemory.longValue)
    MetricEmitter.emitGaugeMetric(
      LOADBALANCER_MEMORY_INFLIGHT(controllerInstance, "Blackbox"),
      totalBlackBoxActivationMemory.longValue)
    MetricEmitter.emitGaugeMetric(
      LOADBALANCER_MEMORY_INFLIGHT(controllerInstance, "Managed"),
      totalManagedActivationMemory.longValue)
  }

  actorSystem.scheduler.schedule(10.seconds, 10.seconds)(emitMetrics())

  override def activeActivationsFor(namespace: UUID): Future[Int] =
    Future.successful(activationsPerNamespace.get(namespace).map(_.intValue()).getOrElse(0))
  override def totalActiveActivations: Future[Int] = Future.successful(totalActivations.intValue())

  /**
   * Calculate the duration within which a completion ack must be received for an activation.
   *
   * Calculation is based on the passed action time limit. If the passed action time limit is shorter than
   * the configured standard action time limit, the latter is used to avoid too tight timeouts.
   *
   * The base timeout is multiplied with a configurable timeout factor. This dilution controls how much slack you
   * want to allow in your system before you start reporting failed activations. The default value of 2 bases
   * on invoker behavior that a cold invocation's init duration may be as long as its run duration. Higher factors
   * may account for additional wait times.
   *
   * Finally, a constant duration is added to the diluted timeout to be lenient towards general delays / wait times.
   *
   * @param actionTimeLimit the action's time limit
   * @return the calculated time duration within which a completion ack must be received
   */
  private def calculateCompletionAckTimeout(actionTimeLimit: FiniteDuration): FiniteDuration = {
    (actionTimeLimit.max(TimeLimit.STD_DURATION) * lbConfig.timeoutFactor) + 1.minute
  }

  // yanqi, use estimated cpu limit for new entry, and updateCpuLimit for first invocation
  /**
   * 2. Update local state with the activation to be executed scheduled.
   *
   * All activations are tracked in the activationSlots map. Additionally, blocking invokes
   * are tracked in the activationPromises map. When a result is received via result ack, it
   * will cause the result to be forwarded to the caller waiting on the result, and cancel
   * the DB poll which is also trying to do the same.
   * Once the completion ack arrives, activationSlots entry will be removed.
   */
  protected def setupActivation(msg: ActivationMessage,
                                action: ExecutableWhiskActionMetaData,
                                instance: InvokerInstanceId, 
                                cpuUtil: Double,
                                updateCpuLimit: Boolean): Future[Either[ActivationId, WhiskActivation]] = {

    // Needed for emitting metrics.
    totalActivations.increment()
    val isBlackboxInvocation = action.exec.pull
    val totalActivationMemory =
      if (isBlackboxInvocation) totalBlackBoxActivationMemory else totalManagedActivationMemory
    totalActivationMemory.add(action.limits.memory.megabytes)

    activationsPerNamespace.getOrElseUpdate(msg.user.namespace.uuid, new LongAdder()).increment()

    // Completion Ack must be received within the calculated time.
    val completionAckTimeout = calculateCompletionAckTimeout(action.limits.timeout.duration)

    // If activation is blocking, store a promise that we can mark successful later on once the result ack
    // arrives. Return a Future representing the promise to caller.
    // If activation is non-blocking, return a successfully completed Future to caller.
    val resultPromise = if (msg.blocking) {
      activationPromises.getOrElseUpdate(msg.activationId, Promise[Either[ActivationId, WhiskActivation]]()).future
    } else Future.successful(Left(msg.activationId))

    // Install a timeout handler for the catastrophic case where a completion ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the completion ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    //
    // Attention: a significantly delayed completion ack means that the invoker is still busy or will be busy in future
    // with running the action. So the current strategy of freeing up the activation's memory in invoker
    // book-keeping will allow the load balancer to send more activations to the invoker. This can lead to
    // invoker overloads so that activations need to wait until other activations complete.
    activationSlots.getOrElseUpdate(
      msg.activationId, {
        val timeoutHandler = actorSystem.scheduler.scheduleOnce(completionAckTimeout) {
          processCompletion(msg.activationId, msg.transid, forced = true, isSystemError = false, invoker = instance, 
            cpuUtil = cpuUtil, exeTime = 0, totalTime = 0) // yanqi, keep cpuUtil for timeout for invoker stats keeping
        }

        // please note: timeoutHandler.cancel must be called on all non-timeout paths, e.g. Success
        // yanqi, add cpu to ActivationEntry
        ActivationEntry(
          msg.activationId,
          msg.user.namespace.uuid,
          instance,
          // action.limits.cpu.cores,
          cpuUtil,
          action.limits.memory.megabytes.MB,
          action.limits.timeout.duration,
          action.limits.concurrency.maxConcurrent,
          action.fullyQualifiedName(true),
          timeoutHandler,
          isBlackboxInvocation,
          msg.blocking,
          updateCpuLimit)
      })

    resultPromise
  }

  protected val messageProducer =
    messagingProvider.getProducer(config, Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT))

  /** 3. Send the activation to the invoker */
  protected def sendActivationToInvoker(producer: MessageProducer,
                                        msg: ActivationMessage,
                                        invoker: InvokerInstanceId): Future[RecordMetadata] = {
    implicit val transid: TransactionId = msg.transid

    val topic = s"invoker${invoker.toInt}"

    MetricEmitter.emitCounterMetric(LoggingMarkers.LOADBALANCER_ACTIVATION_START)
    val start = transid.started(
      this,
      LoggingMarkers.CONTROLLER_KAFKA,
      s"posting topic '$topic' with activation id '${msg.activationId}'",
      logLevel = InfoLevel)

    // [pickme]
    val sendStart = Instant.now
    logging.info(this, s"[pickme] ${msg.activationId} sendStart: ${sendStart.toEpochMilli()}")
    logging.info(this, s"[pickme] ${msg.activationId} start~send: ${Interval(msg.transid.meta.start, sendStart).duration.toMillis}")

    producer.send(topic, msg).andThen {
      case Success(status) =>
        val longSendStart = Instant.now
        logging.info(this, s"[pickme] ${msg.activationId} longStart: ${longSendStart.toEpochMilli()}")
        transid.finished(
          this,
          start,
          s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]",
          logLevel = InfoLevel)
      case Failure(_) => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  /** Subscribes to ack messages from the invokers (result / completion) and registers a handler for these messages. */
  private val activationFeed: ActorRef =
    feedFactory.createFeed(actorSystem, messagingProvider, processAcknowledgement)

  /** 4. Get the ack message and parse it */
  protected[loadBalancer] def processAcknowledgement(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    AcknowledegmentMessage.parse(raw) match {
      case Success(m: CompletionMessage) =>
        processCompletion(
          m.activationId,
          m.transid,
          forced = false,
          isSystemError = m.isSystemError,
          invoker = m.invoker,
          m.cpuUtil,
          m.exeTime,
          m.totalTime)  // yanqi, add cpu util & exe Time & totalTime
        activationFeed ! MessageFeed.Processed

      case Success(m: ResultMessage) =>
        processResult(m.response, m.transid)
        activationFeed ! MessageFeed.Processed

      case Failure(t) =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw")

      case _ =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"Unexpected Acknowledgment message received by loadbalancer: $raw")
    }
  }

  /** 5. Process the result ack and return it to the user */
  protected def processResult(response: Either[ActivationId, WhiskActivation], tid: TransactionId): Unit = {
    val aid = response.fold(l => l, r => r.activationId)

    // Resolve the promise to send the result back to the user.
    // The activation will be removed from the activation slots later, when the completion message
    // is received (because the slot in the invoker is not yet free for new activations).
    activationPromises.remove(aid).foreach(_.trySuccess(response))
    logging.info(this, s"received result ack for '$aid'")(tid)
  }

  protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry)

  // Singletons for counter metrics related to completion acks
  protected val LOADBALANCER_COMPLETION_ACK_REGULAR =
    LoggingMarkers.LOADBALANCER_COMPLETION_ACK(controllerInstance, RegularCompletionAck)
  protected val LOADBALANCER_COMPLETION_ACK_FORCED =
    LoggingMarkers.LOADBALANCER_COMPLETION_ACK(controllerInstance, ForcedCompletionAck)
  protected val LOADBALANCER_COMPLETION_ACK_HEALTHCHECK =
    LoggingMarkers.LOADBALANCER_COMPLETION_ACK(controllerInstance, HealthcheckCompletionAck)
  protected val LOADBALANCER_COMPLETION_ACK_REGULAR_AFTER_FORCED =
    LoggingMarkers.LOADBALANCER_COMPLETION_ACK(controllerInstance, RegularAfterForcedCompletionAck)
  protected val LOADBALANCER_COMPLETION_ACK_FORCED_AFTER_REGULAR =
    LoggingMarkers.LOADBALANCER_COMPLETION_ACK(controllerInstance, ForcedAfterRegularCompletionAck)

  /** 6. Process the completion ack and update the state */
  // yanqi, add cpu util & execution time & total time (including cold start)
  // exeTime & totalTime unit is us
  protected[loadBalancer] def processCompletion(aid: ActivationId,
                                                tid: TransactionId,
                                                forced: Boolean,
                                                isSystemError: Boolean,
                                                invoker: InvokerInstanceId,
                                                cpuUtil: Double,
                                                exeTime: Long,
                                                totalTime: Long): Unit = {

    val invocationResult = if (forced) {
      InvocationFinishedResult.Timeout
    } else {
      // If the response contains a system error, report that, otherwise report Success
      // Left generally is considered a Success, since that could be a message not fitting into Kafka
      if (isSystemError) {
        InvocationFinishedResult.SystemError
      } else {
        InvocationFinishedResult.Success
      }
    }

    activationSlots.remove(aid) match {
      case Some(entry) =>
        totalActivations.decrement()
        val totalActivationMemory =
          if (entry.isBlackbox) totalBlackBoxActivationMemory else totalManagedActivationMemory
        totalActivationMemory.add(entry.memoryLimit.toMB * (-1))
        activationsPerNamespace.get(entry.namespaceId).foreach(_.decrement())

        releaseInvoker(invoker, entry)

        // yanqi, update cpu usage
        if(cpuUtil > 0 && randomGen.nextDouble <= functionSampleRate)
          dataProcessor ! InvocationSample(entry.fullyQualifiedEntityName, cpuUtil, exeTime, totalTime, entry.updateCpuLimit)
        logging.info(this, s"function ${entry.fullyQualifiedEntityName.asString}, activation id ${aid}, cpu usage=${cpuUtil}, exe time=${exeTime}, total time=${totalTime}")(tid)

        if (!forced) {
          entry.timeoutHandler.cancel()
          // notice here that the activationPromises is not touched, because the expectation is that
          // the active ack is received as expected, and processing that message removed the promise
          // from the corresponding map
          logging.info(this, s"received completion ack for '$aid', system error=$isSystemError")(tid)

          MetricEmitter.emitCounterMetric(LOADBALANCER_COMPLETION_ACK_REGULAR)

        } else {
          // the entry has timed out; if the active ack is still around, remove its entry also
          // and complete the promise with a failure if necessary
          activationPromises
            .remove(aid)
            .foreach(_.tryFailure(new Throwable("no completion or active ack received yet")))
          val actionType = if (entry.isBlackbox) "blackbox" else "managed"
          val blockingType = if (entry.isBlocking) "blocking" else "non-blocking"
          val completionAckTimeout = calculateCompletionAckTimeout(entry.timeLimit)
          logging.warn(
            this,
            s"forced completion ack for '$aid', action '${entry.fullyQualifiedEntityName}' ($actionType), $blockingType, mem limit ${entry.memoryLimit.toMB} MB, time limit ${entry.timeLimit.toMillis} ms, completion ack timeout $completionAckTimeout from $invoker")(
            tid)

          MetricEmitter.emitCounterMetric(LOADBALANCER_COMPLETION_ACK_FORCED)
        }

        // Completion acks that are received here are strictly from user actions - health actions are not part of
        // the load balancer's activation map. Inform the invoker pool supervisor of the user action completion.
        // guard this
        invokerPool ! InvocationFinishedMessage(invoker, invocationResult)
      case None if tid == TransactionId.invokerHealth =>
        // Health actions do not have an ActivationEntry as they are written on the message bus directly. Their result
        // is important to pass to the invokerPool because they are used to determine if the invoker can be considered
        // healthy again.
        logging.info(this, s"received completion ack for health action on $invoker")(tid)

        MetricEmitter.emitCounterMetric(LOADBALANCER_COMPLETION_ACK_HEALTHCHECK)

        // guard this
        invokerPool ! InvocationFinishedMessage(invoker, invocationResult)
      case None if !forced =>
        // Received a completion ack that has already been taken out of the state because of a timeout (forced ack).
        // The result is ignored because a timeout has already been reported to the invokerPool per the force.
        // Logging this condition as a warning because the invoker processed the activation and sent a completion
        // message - but not in time.
        logging.warn(
          this,
          s"received completion ack for '$aid' from $invoker which has no entry, system error=$isSystemError")(tid)

        MetricEmitter.emitCounterMetric(LOADBALANCER_COMPLETION_ACK_REGULAR_AFTER_FORCED)
      case None =>
        // The entry has already been removed by a completion ack. This part of the code is reached by the timeout and can
        // happen if completion ack and timeout happen roughly at the same time (the timeout was triggered before the completion
        // ack canceled the timer). As the completion ack is already processed we don't have to do anything here.
        logging.debug(this, s"forced completion ack for '$aid' which has no entry")(tid)

        MetricEmitter.emitCounterMetric(LOADBALANCER_COMPLETION_ACK_FORCED_AFTER_REGULAR)
    }
  }
}
