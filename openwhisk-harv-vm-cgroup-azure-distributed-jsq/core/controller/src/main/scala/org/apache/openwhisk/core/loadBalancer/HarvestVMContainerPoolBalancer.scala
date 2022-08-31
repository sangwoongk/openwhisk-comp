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
import akka.actor.ActorRefFactory
// import java.util.concurrent.ThreadLocalRandom

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.stream.ActorMaterializer
import org.apache.kafka.clients.producer.RecordMetadata
import pureconfig._
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeLong
import org.apache.openwhisk.common.LoggingMarkers._
import org.apache.openwhisk.core.loadBalancer.InvokerState.{Healthy, Offline, Unhealthy, Unresponsive}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.spi.SpiLoader

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.math.max
// yanqi, needs to be renamed, otherwise conflit with immutable collection
import scala.collection.mutable.{Map=>MMap}
// import util.control.Breaks._

/**
 * A loadbalancer that schedules workload based on a hashing-algorithm.
 *
 * ## Algorithm
 *
 * At first, for every namespace + action pair a hash is calculated and then an invoker is picked based on that hash
 * (`hash % numInvokers`). The determined index is the so called "home-invoker". This is the invoker where the following
 * progression will **always** start. If this invoker is healthy (see "Invoker health checking") and if there is
 * capacity on that invoker (see "Capacity checking"), the request is scheduled to it.
 *
 * If one of these prerequisites is not true, the index is incremented by a step-size. The step-sizes available are the
 * all coprime numbers smaller than the amount of invokers available (coprime, to minimize collisions while progressing
 * through the invokers). The step-size is picked by the same hash calculated above (`hash & numStepSizes`). The
 * home-invoker-index is now incremented by the step-size and the checks (healthy + capacity) are done on the invoker
 * we land on now.
 *
 * This procedure is repeated until all invokers have been checked at which point the "overload" strategy will be
 * employed, which is to choose a healthy invoker randomly. In a steadily running system, that overload means that there
 * is no capacity on any invoker left to schedule the current request to.
 *
 * If no invokers are available or if there are no healthy invokers in the system, the loadbalancer will return an error
 * stating that no invokers are available to take any work. Requests are not queued anywhere in this case.
 *
 * An example:
 * - availableInvokers: 10 (all healthy)
 * - hash: 13
 * - homeInvoker: hash % availableInvokers = 13 % 10 = 3
 * - stepSizes: 1, 3, 7 (note how 2 and 5 is not part of this because it's not coprime to 10)
 * - stepSizeIndex: hash % numStepSizes = 13 % 3 = 1 => stepSize = 3
 *
 * Progression to check the invokers: 3, 6, 9, 2, 5, 8, 1, 4, 7, 0 --> done
 *
 * This heuristic is based on the assumption, that the chance to get a warm container is the best on the home invoker
 * and degrades the more steps you make. The hashing makes sure that all loadbalancers in a cluster will always pick the
 * same home invoker and do the same progression for a given action.
 *
 * Known caveats:
 * - This assumption is not always true. For instance, two heavy workloads landing on the same invoker can override each
 *   other, which results in many cold starts due to all containers being evicted by the invoker to make space for the
 *   "other" workload respectively. Future work could be to keep a buffer of invokers last scheduled for each action and
 *   to prefer to pick that one. Then the second-last one and so forth.
 *
 * ## Capacity checking
 *
 * The maximum capacity per invoker is configured using `user-memory`, which is the maximum amount of memory of actions
 * running in parallel on that invoker.
 *
 * Spare capacity is determined by what the loadbalancer thinks it scheduled to each invoker. Upon scheduling, an entry
 * is made to update the books and a slot for each MB of the actions memory limit in a Semaphore is taken. These slots
 * are only released after the response from the invoker (active-ack) arrives **or** after the active-ack times out.
 * The Semaphore has as many slots as MBs are configured in `user-memory`.
 *
 * Known caveats:
 * - In an overload scenario, activations are queued directly to the invokers, which makes the active-ack timeout
 *   unpredictable. Timing out active-acks in that case can cause the loadbalancer to prematurely assign new load to an
 *   overloaded invoker, which can cause uneven queues.
 * - The same is true if an invoker is extraordinarily slow in processing activations. The queue on this invoker will
 *   slowly rise if it gets slow to the point of still sending pings, but handling the load so slowly, that the
 *   active-acks time out. The loadbalancer again will think there is capacity, when there is none.
 *
 * Both caveats could be solved in future work by not queueing to invoker topics on overload, but to queue on a
 * centralized overflow topic. Timing out an active-ack can then be seen as a system-error, as described in the
 * following.
 *
 * ## Invoker health checking
 *
 * Invoker health is determined via a kafka-based protocol, where each invoker pings the loadbalancer every second. If
 * no ping is seen for a defined amount of time, the invoker is considered "Offline".
 *
 * Moreover, results from all activations are inspected. If more than 3 out of the last 10 activations contained system
 * errors, the invoker is considered "Unhealthy". If an invoker is unhealty, no user workload is sent to it, but
 * test-actions are sent by the loadbalancer to check if system errors are still happening. If the
 * system-error-threshold-count in the last 10 activations falls below 3, the invoker is considered "Healthy" again.
 *
 * To summarize:
 * - "Offline": Ping missing for > 10 seconds
 * - "Unhealthy": > 3 **system-errors** in the last 10 activations, pings arriving as usual
 * - "Healthy": < 3 **system-errors** in the last 10 activations, pings arriving as usual
 *
 * ## Horizontal sharding
 *
 * Sharding is employed to avoid both loadbalancers having to share any data, because the metrics used in scheduling
 * are very fast changing.
 *
 * Horizontal sharding means, that each invoker's capacity is evenly divided between the loadbalancers. If an invoker
 * has at most 16 slots available (invoker-busy-threshold = 16), those will be divided to 8 slots for each loadbalancer
 * (if there are 2).
 *
 * If concurrent activation processing is enabled (and concurrency limit is > 1), accounting of containers and
 * concurrency capacity per container will limit the number of concurrent activations routed to the particular
 * slot at an invoker. Default max concurrency is 1.
 *
 * Known caveats:
 * - If a loadbalancer leaves or joins the cluster, all state is removed and created from scratch. Those events should
 *   not happen often.
 * - If concurrent activation processing is enabled, it only accounts for the containers that the current loadbalancer knows.
 *   So the actual number of containers launched at the invoker may be less than is counted at the loadbalancer, since
 *   the invoker may skip container launch in case there is concurrent capacity available for a container launched via
 *   some other loadbalancer.
 */
class HarvestVMContainerPoolBalancer(
  config: WhiskConfig,
  controllerInstance: ControllerInstanceId,
  feedFactory: FeedFactory,
  val invokerPoolFactory: InvokerPoolFactory,
  implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
  implicit actorSystem: ActorSystem,
  logging: Logging,
  materializer: ActorMaterializer)
    extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  /** Build a cluster of all loadbalancers */
  private val cluster: Option[Cluster] = if (loadConfigOrThrow[ClusterConfig](ConfigKeys.cluster).useClusterBootstrap) {
    AkkaManagement(actorSystem).start()
    ClusterBootstrap(actorSystem).start()
    Some(Cluster(actorSystem))
  } else if (loadConfigOrThrow[Seq[String]]("akka.cluster.seed-nodes").nonEmpty) {
    Some(Cluster(actorSystem))
  } else {
    None
  }

  override protected def emitMetrics() = {
    super.emitMetrics()
    MetricEmitter.emitGaugeMetric(
      INVOKER_TOTALMEM_BLACKBOX,
      schedulingState.blackboxInvokers.foldLeft(0L) { (total, curr) =>
        if (curr.status.isUsable) {
          curr.id.userMemory.toMB + total
        } else {
          total
        }
      })
    MetricEmitter.emitGaugeMetric(
      INVOKER_TOTALMEM_MANAGED,
      schedulingState.managedInvokers.foldLeft(0L) { (total, curr) =>
        if (curr.status.isUsable) {
          curr.id.userMemory.toMB + total
        } else {
          total
        }
      })
    MetricEmitter.emitGaugeMetric(HEALTHY_INVOKER_MANAGED, schedulingState.managedInvokers.count(_.status == Healthy))
    MetricEmitter.emitGaugeMetric(
      UNHEALTHY_INVOKER_MANAGED,
      schedulingState.managedInvokers.count(_.status == Unhealthy))
    MetricEmitter.emitGaugeMetric(
      UNRESPONSIVE_INVOKER_MANAGED,
      schedulingState.managedInvokers.count(_.status == Unresponsive))
    MetricEmitter.emitGaugeMetric(OFFLINE_INVOKER_MANAGED, schedulingState.managedInvokers.count(_.status == Offline))
    MetricEmitter.emitGaugeMetric(HEALTHY_INVOKER_BLACKBOX, schedulingState.blackboxInvokers.count(_.status == Healthy))
    MetricEmitter.emitGaugeMetric(
      UNHEALTHY_INVOKER_BLACKBOX,
      schedulingState.blackboxInvokers.count(_.status == Unhealthy))
    MetricEmitter.emitGaugeMetric(
      UNRESPONSIVE_INVOKER_BLACKBOX,
      schedulingState.blackboxInvokers.count(_.status == Unresponsive))
    MetricEmitter.emitGaugeMetric(OFFLINE_INVOKER_BLACKBOX, schedulingState.blackboxInvokers.count(_.status == Offline))
  }

  /** State needed for scheduling. */
  val schedulingState = HarvestVMContainerPoolBalancerState()(lbConfig)

  /**
   * Monitors invoker supervision and the cluster to update the state sequentially
   *
   * All state updates should go through this actor to guarantee that
   * [[HarvestVMContainerPoolBalancerState.updateInvokers]] and [[HarvestVMContainerPoolBalancerState.updateCluster]]
   * are called exclusive of each other and not concurrently.
   */
  private val monitor = actorSystem.actorOf(Props(new Actor {
    override def preStart(): Unit = {
      cluster.foreach(_.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent]))
    }

    // all members of the cluster that are available
    var availableMembers = Set.empty[Member]

    override def receive: Receive = {
      case CurrentInvokerPoolState(newState) =>
        schedulingState.updateInvokers(newState)

      // yanqi, can't find in other places 
      // State of the cluster as it is right now
      case CurrentClusterState(members, _, _, _, _) =>
        availableMembers = members.filter(_.status == MemberStatus.Up)
        schedulingState.updateCluster(availableMembers.size)

      // General lifecycle events and events concerning the reachability of members. Split-brain is not a huge concern
      // in this case as only the invoker-threshold is adjusted according to the perceived cluster-size.
      // Taking the unreachable member out of the cluster from that point-of-view results in a better experience
      // even under split-brain-conditions, as that (in the worst-case) results in premature overloading of invokers vs.
      // going into overflow mode prematurely.
      case event: ClusterDomainEvent =>
        availableMembers = event match {
          case MemberUp(member)          => availableMembers + member
          case ReachableMember(member)   => availableMembers + member
          case MemberRemoved(member, _)  => availableMembers - member
          case UnreachableMember(member) => availableMembers - member
          case _                         => availableMembers
        }

        schedulingState.updateCluster(availableMembers.size)
    }
  }))

  /** Loadbalancer interface methods */
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(schedulingState.invokers)
  override def clusterSize: Int = schedulingState.clusterSize

  /** 1. Publish a message to the loadbalancer */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val isBlackboxInvocation = action.exec.pull
    val actionType = if (!isBlackboxInvocation) "managed" else "blackbox"
    val (invokersToUse, stepSizes) =
      if (!isBlackboxInvocation) (schedulingState.managedInvokers, schedulingState.managedStepSizes)
      else (schedulingState.blackboxInvokers, schedulingState.blackboxStepSizes)
    var updateCpuLimit: Boolean = false
    var cpuUtil = functionCpuUtil.getOrElse(action.fullyQualifiedName(true), 0.0)
    val chosen = if (invokersToUse.nonEmpty) {
      val hash = HarvestVMContainerPoolBalancer.generateHash(msg.user.namespace.name, action.fullyQualifiedName(false))
      val homeInvoker = hash % invokersToUse.size
      // val stepSize = stepSizes(hash % stepSizes.size)
      val stepSize: Int = 1 // [pickme]

      // yanqi, check if we can use the distribution
      var cpuLimit: Double = functionCpuLimit.getOrElse(action.fullyQualifiedName(true), 0.0)
      // if(functionCpuUtil.contains(action.fullyQualifiedName(true)))
      //   cpuLimit = functionCpuUtil(action.fullyQualifiedName(true)).query()
      if(cpuLimit <= 0.0) {
        cpuLimit = action.limits.cpu.cores
        updateCpuLimit = true
      }
      msg.cpuLimit = cpuLimit

      if(cpuUtil <= 0.0)
        cpuUtil = cpuLimit

      // val invoker: Option[(InvokerInstanceId, Boolean)] = HarvestVMContainerPoolBalancer.schedule(
      //   action.limits.concurrency.maxConcurrent,
      //   action.fullyQualifiedName(true),
      //   invokersToUse,
      //   schedulingState.usedResources,
      //   // action.limits.cpu.cores,
      //   cpuUtil, // replace with estimation
      //   action.limits.memory.megabytes,
      //   homeInvoker,
      //   stepSize,
      //   schedulingState.clusterSize)

      val invoker: Option[(InvokerInstanceId, Boolean)] = HarvestVMContainerPoolBalancer.schedule(
        action.limits.concurrency.maxConcurrent,
        action.fullyQualifiedName(true),
        invokersToUse,
        schedulingState.usedResources,
        // action.limits.cpu.cores,
        cpuUtil, // replace with estimation
        cpuLimit, // used to check if an invoker can hold the container of the function
        action.limits.memory.megabytes,
        schedulingState.clusterSize)

      invoker.foreach {
        case (_, true) =>
          val metric =
            if (isBlackboxInvocation)
              LoggingMarkers.BLACKBOX_SYSTEM_OVERLOAD
            else
              LoggingMarkers.MANAGED_SYSTEM_OVERLOAD
          MetricEmitter.emitCounterMetric(metric)
        case _ =>
      }
      invoker.map(_._1)
    } else {
      None
    }

    chosen
      .map { invoker =>
        // MemoryLimit() and TimeLimit() return singletons - they should be fast enough to be used here
        // val cpuLimit = action.limits.cpu
        val memoryLimit = action.limits.memory
        val memoryLimitInfo = if (memoryLimit == MemoryLimit()) { "std" } else { "non-std" }
        val timeLimit = action.limits.timeout
        val timeLimitInfo = if (timeLimit == TimeLimit()) { "std" } else { "non-std" }
        logging.info(
          this,
          s"scheduled activation ${msg.activationId}, action '${msg.action.asString}' ($actionType), ns '${msg.user.namespace.name.asString}', mem limit ${memoryLimit.megabytes} MB (${memoryLimitInfo}), time limit ${timeLimit.duration.toMillis} ms cpu limit ${msg.cpuLimit} cpu util ${cpuUtil} (${timeLimitInfo}) to ${invoker}")
        val activationResult = setupActivation(msg, action, invoker, cpuUtil, updateCpuLimit)
        sendActivationToInvoker(messageProducer, msg, invoker).map(_ => activationResult)
      }
      .getOrElse {
        // report the state of all invokers
        val invokerStates = invokersToUse.foldLeft(Map.empty[InvokerState, Int]) { (agg, curr) =>
          val count = agg.getOrElse(curr.status, 0) + 1
          agg + (curr.status -> count)
        }

        logging.error(
          this,
          s"failed to schedule activation ${msg.activationId}, action '${msg.action.asString}' ($actionType), ns '${msg.user.namespace.name.asString}' - invokers to use: $invokerStates")
        Future.failed(LoadBalancerException("No invokers available"))
      }
  }

  override val invokerPool =
    invokerPoolFactory.createInvokerPool(
      actorSystem,
      messagingProvider,
      messageProducer,
      sendActivationToInvoker,
      Some(monitor))

  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    // schedulingState.invokerSlots
    //   .lift(invoker.toInt)
    //   .foreach(_.releaseConcurrent(entry.fullyQualifiedEntityName, entry.maxConcurrent, entry.memoryLimit.toMB.toLong))

    // logging.info(
    //       this,
    //       s"In releaseInvoker (for grep in-use) invoker id = ${invoker.toInt}")
    
    if(schedulingState.usedResources.contains(invoker.toInt)) {
      schedulingState.usedResources(invoker.toInt).release(entry.cpuUtil, entry.memoryLimit.toMB.toLong, entry.maxConcurrent, entry.fullyQualifiedEntityName)
    }
    // schedulingState.usedResources
    //   .lift(invoker.toInt)
    //   .foreach(_.release(entry.cpuUtil, entry.memoryLimit.toMB.toLong, entry.maxConcurrent, entry.fullyQualifiedEntityName))
  }
}

object HarvestVMContainerPoolBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    val invokerPoolFactory = new InvokerPoolFactory {
      override def createInvokerPool(
        actorRefFactory: ActorRefFactory,
        messagingProvider: MessagingProvider,
        messagingProducer: MessageProducer,
        sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
        monitor: Option[ActorRef]): ActorRef = {

        InvokerPool.prepare(instance, WhiskEntityStore.datastore())

        actorRefFactory.actorOf(
          InvokerPool.props(
            (f, i) => f.actorOf(InvokerActor.props(i, instance)),
            (m, i) => sendActivationToInvoker(messagingProducer, m, i),
            messagingProvider.getConsumer(whiskConfig, s"health${instance.asString}", "health", maxPeek = 128),
            monitor))
      }

    }
    new HarvestVMContainerPoolBalancer(
      whiskConfig,
      instance,
      createFeedFactory(whiskConfig, instance),
      invokerPoolFactory)
  }

  def requiredProperties: Map[String, String] = kafkaHosts

  /** Generates a hash based on the string representation of namespace and action */
  def generateHash(namespace: EntityName, action: FullyQualifiedEntityName): Int = {
    (namespace.asString.hashCode() ^ action.asString.hashCode()).abs
  }

  /** Euclidean algorithm to determine the greatest-common-divisor */
  @tailrec
  def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** Returns pairwise coprime numbers until x. Result is memoized. */
  def pairwiseCoprimeNumbersUntil(x: Int): IndexedSeq[Int] =
    (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
      if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
        primes :+ cur
      } else primes
    })


  // /**
  //  * Scans through all invokers and searches for an invoker tries to get a free slot on an invoker. If no slot can be
  //  * obtained, randomly picks a healthy invoker.
  //  *
  //  * @param maxConcurrent concurrency limit supported by this action
  //  * @param invokers a list of available invokers to search in, including their state
  //  * @param usedResources record of resource usage of inflight invocations
  //  * @param reqMemory amount of memory that need to be acquired (e.g. memory in MB)
  //  * @param reqCpu nubmer of cores that need to be acquired
  //  * @param index the index to start from (initially should be the "homeInvoker"
  //  * @param step stable identifier of the entity to be scheduled
  //  * @param clusterSize number of controllers in the system
  //  * @return an invoker to schedule to or None of no invoker is available
  //  */
  // @tailrec
  // def schedule(
  //   maxConcurrent: Int,
  //   fqn: FullyQualifiedEntityName,
  //   invokers: IndexedSeq[InvokerHealth],
  //   usedResources: Map[Int, InvokerResourceUsage],
  //   reqCpu: Double,
  //   reqMemory: Int,
  //   index: Int,
  //   step: Int,
  //   clusterSize: Int,
  //   stepsDone: Int = 0)(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {
  //   val numInvokers = invokers.size

  //   if (numInvokers > 0) {
  //     val invoker = invokers(index)
  //     val invoker_id = invoker.id.toInt
  //     //test this invoker
  //     // Attention: cannot use index to access usedResources directly!!!
  //     if (invoker.status.isUsable && usedResources(invoker_id).acquire(invoker.cpu.toDouble/clusterSize, invoker.memory/clusterSize, reqCpu, reqMemory, maxConcurrent, fqn)) {
  //       // yanqi, log rsc info for debugging
  //       logging.info(
  //         this,
  //         s"check invoker id: ${invoker.id.toInt} cpu: ${invoker.cpu}, memory '${invoker.memory}'")
  //       Some(invoker.id, false)
  //     } else {
  //       // If we've gone through all invokers
  //       // choose the one doesn't need more resource with most resources available
  //       if (stepsDone == numInvokers + 1) {
  //         var id_running_container: Int = -1 // the invoker that already has a running container for the function
  //         var id_most_rsc_left: Int = -1     // the invoker that has most resources left
  //         var most_left_rsc: Double = 0.0
  //         // yanqi, first try to find an unloaded invoker, otherwise the least loaded one
  //         // val start_time = System.nanoTime
  //         breakable {
  //           for(i <- 0 to invokers.size - 1) {
  //             val this_invoker = invokers(i)
  //             val this_invoker_id = this_invoker.id.toInt
  //             if(this_invoker.status.isUsable) {
  //               val (leftcpu, leftmem, nomorersc) = usedResources(this_invoker_id).reportAvailResources(this_invoker.cpu.toDouble/clusterSize, this_invoker.memory/clusterSize, maxConcurrent, fqn)
                
  //               val this_id = this_invoker.id
  //               logging.warn(this, s"check invoker${this_id.toInt} leftcpu ${leftcpu} leftmem ${leftmem}")

  //               val leftrsc = leftcpu*1.0 + leftmem*0.0
  //               if(nomorersc) {
  //                 // of there is one invoker that has matching container with capacity, return directly
  //                 id_running_container = this_invoker_id
  //                 // logging.warn(this, s"system is overloaded. Chose invoker${this_invoker.id.toInt} which has running container with available capacity for the function.")
  //                 // return Some(this_invoker.id, true)
  //                 break
  //               } else if(i == 0 || leftrsc > most_left_rsc) {
  //                 most_left_rsc = leftrsc
  //                 id_most_rsc_left = this_invoker_id
  //               }
  //             }
  //           }
  //         }
  //         // val duration = (System.nanoTime - start_time) / 1e6d    // ms
  //         // logging.warn(this, s"system overloaded complete check time = ${duration} ms.")

  //         if(id_running_container != -1) {
  //           val chosen_id = invokers(id_running_container).id
  //           usedResources(id_running_container).forceAcquire(reqCpu, reqMemory, maxConcurrent, fqn)
  //           logging.warn(this, s"system is overloaded. Chose invoker${chosen_id.toInt} which has running container with available capacity for the function.")
  //           Some(chosen_id, true)
  //         } else if(id_most_rsc_left == -1) {
  //           // no healthy invokers left
  //           logging.warn(this, s"system is overloaded. No healthy invoker in system.")
  //           None
  //         } else {
  //           val chosen_id = invokers(id_most_rsc_left).id
  //           usedResources(id_most_rsc_left).forceAcquire(reqCpu, reqMemory, maxConcurrent, fqn)
  //           logging.warn(this, s"system is overloaded. Chose invoker${chosen_id.toInt} which has most resources left: ${most_left_rsc}.")
  //           Some(chosen_id, true)
  //         }
  //       } else {
  //         val newIndex = (index + step) % numInvokers
  //         // schedule(maxConcurrent, fqn, invokers, dispatched, slots, newIndex, step, stepsDone + 1)
  //         schedule(maxConcurrent, fqn, invokers, usedResources, reqCpu, reqMemory, newIndex, step, clusterSize, stepsDone + 1)
  //       }
  //     }
  //   } else {
  //     None
  //   }
  // }

  /**
   * Scans through all invokers and searches for an invoker tries to get a free slot on an invoker. If no slot can be
   * obtained, randomly picks a healthy invoker.
   *
   * @param maxConcurrent concurrency limit supported by this action
   * @param invokers a list of available invokers to search in, including their state
   * @param usedResources record of resource usage of inflight invocations
   * @param reqMemory amount of memory that need to be acquired (e.g. memory in MB)
   * @param reqCpu nubmer of cores that need to be acquired
   * @param index the index to start from (initially should be the "homeInvoker"
   * @param step stable identifier of the entity to be scheduled
   * @param clusterSize number of controllers in the system
   * @return an invoker to schedule to or None of no invoker is available
   */
  def schedule(
    maxConcurrent: Int,
    fqn: FullyQualifiedEntityName,
    invokers: IndexedSeq[InvokerHealth],
    usedResources: Map[Int, InvokerResourceUsage],
    reqCpu: Double,
    cpuLimit: Double,
    reqMemory: Long,
    clusterSize: Int)(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {
    val numInvokers = invokers.size

    if (numInvokers > 0) {
      // in case the system is not overloaded, choose the invoker that has enough capacity in terms of both cpu & memory
      var id_unloaded: Int = -1
      var invoker_id_unloaded: InvokerInstanceId = InvokerInstanceId(instance = -1, 
                                                                     uniqueName = Some("fake_ul"), 
                                                                     displayedName = Some("fake_ul"), 
                                                                     userMemory = ByteSize(0, SizeUnits.BYTE))
      var unloaded_cpu_left: Double = 0.0
      var unloaded_mem_left: Long = 0
      var unloaded_score: Double = 0.0  // scored rsc (weighted sum of rsc and memory)

      // in case the sytem is overloaded, choose the invoker that is least loaded after receiving this function
      // (for functions with > 1 max concurrency)
      var id_loaded: Int = -1
      var invoker_id_loaded: InvokerInstanceId = InvokerInstanceId(instance = -2, 
                                                                     uniqueName = Some("fake_l"), 
                                                                     displayedName = Some("fake_l"), 
                                                                     userMemory = ByteSize(0, SizeUnits.BYTE))
      var loaded_cpu_left: Double = 0.0
      var loaded_mem_left: Long = 0
      var loaded_score: Double = 0.0  // scored rsc (weighted sum of rsc and memory)

      // make sure that invoker's total rsc (cpu) must be greater than container's total resource
      for(i <- 0 to invokers.size - 1) {
        val this_invoker = invokers(i)
        val this_invoker_id = this_invoker.id.toInt
        if(this_invoker.status.isUsable && this_invoker.cpu.toDouble >= cpuLimit) {
          val (leftcpu, leftmem, score) = usedResources(this_invoker_id).reportLeftResources(this_invoker.cpu.toDouble/clusterSize, this_invoker.memory/clusterSize, reqCpu, reqMemory, maxConcurrent, fqn)
                
          logging.warn(this, s"check invoker${this_invoker_id} leftcpu ${leftcpu} leftmem ${leftmem} score ${score}")

          if(leftcpu >= 0 && leftmem >= 0) {
            // invoker not overloaded
            if(id_unloaded < 0 || unloaded_score > score) {
              id_unloaded = this_invoker_id
              invoker_id_unloaded = this_invoker.id
              unloaded_score = score
              unloaded_cpu_left = leftcpu
              unloaded_mem_left = leftmem
            }
          } else if(id_unloaded < 0) {
            // invoker overloaded, record score if no invoker is known to be underloaded
            if(id_loaded < 0 || loaded_score > score) {
              id_loaded = this_invoker_id
              invoker_id_loaded = this_invoker.id
              loaded_score = score
              loaded_cpu_left = leftcpu
              loaded_mem_left = leftmem
            }
          }
        }
      }
      // val duration = (System.nanoTime - start_time) / 1e6d    // ms
      // logging.warn(this, s"system overloaded complete check time = ${duration} ms.")

      if(id_unloaded != -1) {
        usedResources(id_unloaded).forceAcquire(reqCpu, reqMemory, maxConcurrent, fqn)
        logging.warn(this, s"system underloaded. Choose invoker ${id_unloaded} (${invoker_id_unloaded.toInt}) leftcpu ${unloaded_cpu_left} leftmem ${unloaded_mem_left} score ${unloaded_score}.")
        Some(invoker_id_unloaded, false)
      } else if(id_loaded == -1) {
        // no healthy invokers left
        logging.warn(this, s"system is overloaded. No healthy invoker in system.")
        None
      } else {
        usedResources(id_loaded).forceAcquire(reqCpu, reqMemory, maxConcurrent, fqn)
        logging.warn(this, s"system is overloaded. Choose invoker ${id_loaded} (${invoker_id_loaded.toInt}) leftcpu ${loaded_cpu_left} leftmem ${loaded_mem_left} score ${loaded_score}.")
        Some(invoker_id_loaded, true)
      }
      
    } else {
      None
    }
  }

}

/**
 * record of available slots for invocations that allows > 1 concurreny in a container
 * should be called within InvokerResourceUsage's synchronized region 
 */
class ConcurrencySlot(val maxConcurrent: Int) {
  var remaining: Int = 0
  var runningInvocations: Int = 0

  /**
   * @return true if no new container needs to be allocated for the invocation (no more resources needed), internal state is not changed
   */
  def try_acquire(): Boolean = {
    remaining > 0
  }

  /**
   * @return true if no new container needs to be allocated for the invocation (no more resources needed)
   */
  def acquire(): Boolean = {
    runningInvocations = runningInvocations + 1
    if(remaining  >= 1) {
      remaining = remaining - 1
      true
    } else {
      remaining = maxConcurrent - 1
      false
    }
  }

  /**
   * @return (if inflight invocations are all completed, if a container is removed)
   */
  def release(): (Boolean, Boolean) = {
    var container_removed = false
    remaining = remaining + 1
    if(remaining % maxConcurrent == 0) {
      remaining = remaining - maxConcurrent
      container_removed = true
    }
    runningInvocations = runningInvocations - 1
    (runningInvocations == 0, container_removed)
  }
}

class InvokerResourceUsage(var _cpu: Double, var _memory: Long, var _id: Int, var _cpu_coeff: Double, var _mem_coeff: Double)(implicit logging: Logging) {
  protected var cpu: Double = _cpu
  protected var memory: Long = _memory
  protected val id: Int = _id

  // coefficients for scoring invokers
  protected val cpuCoeff:  Double = _cpu_coeff
  protected val memCoeff: Double = _mem_coeff

  /**
   * acquire resources for an invocation, return true on success
   *
   * @param totalCpu total nubmer of cores on this invoker
   * @param totalMemory total memory on this invoker
   * @param reqCpu number of cores requried by the invocation
   * @param reqMemory memory size required by the activation
   * @return true if there is enough resource
   */
  protected var actionConcurrentSlotsMap: MMap[FullyQualifiedEntityName, ConcurrencySlot] = MMap.empty[FullyQualifiedEntityName, ConcurrencySlot]
  def acquire(totalCpu: Double, totalMemory: Long, reqCpu:Double, reqMemory:Long, 
              maxConcurrent: Int, actionId: FullyQualifiedEntityName): Boolean = {
    var ret = false
    this.synchronized {
      if(maxConcurrent == 1) {
        // more resources always needed with concurrency disabled
        if(reqCpu + cpu <= totalCpu && memory + reqMemory <= totalMemory) {
          cpu = cpu + reqCpu
          cpu = math.round(cpu*100).toInt/100.0
          memory = memory + reqMemory
          ret = true
        }
      } else {
        val concurrentSlot = actionConcurrentSlotsMap.getOrElseUpdate(actionId, new ConcurrencySlot(maxConcurrent))
        // check if exsiting container is able to accomodate the new container
        if(concurrentSlot.try_acquire()) {
          // no more memory needed
          if(reqCpu + cpu <= totalCpu) {
            concurrentSlot.acquire()
            cpu = cpu + reqCpu
            cpu = math.round(cpu*100).toInt/100.0
            ret = true
          }
        } else if(reqCpu + cpu <= totalCpu && memory + reqMemory <= totalMemory) {
          concurrentSlot.acquire()
          cpu = cpu + reqCpu
          cpu = math.round(cpu*100).toInt/100.0
          memory = memory + reqMemory
          ret = true
        }
      }

      logging.info(
          this,
          s"invoker${id} in-use resources after acquire cpu: ${cpu}, memory '${memory}', reqCpu ${reqCpu}, reqMemory ${reqMemory}, actionId ${actionId.asString} enough rsc ${ret}")
    }
    ret
  }

  /**
   * force to acquire resources for an invocation, even if invoker is overloaded
   */
  def forceAcquire(reqCpu:Double, reqMemory:Long, maxConcurrent: Int, actionId: FullyQualifiedEntityName) {
    this.synchronized {
      if(maxConcurrent == 1) {
        cpu = cpu + reqCpu
        cpu = math.round(cpu*100).toInt/100.0
        memory = memory + reqMemory
      } else {
        val concurrentSlot = actionConcurrentSlotsMap.getOrElseUpdate(actionId, new ConcurrencySlot(maxConcurrent))
        cpu = cpu + reqCpu
        cpu = math.round(cpu*100).toInt/100.0
        if(!concurrentSlot.acquire())
          memory = memory + reqMemory
      }
      logging.info(
          this,
          s"invoker${id} in-use resources after forceAcquire cpu: ${cpu}, memory '${memory}', reqCpu ${reqCpu}, reqMemory ${reqMemory}, actionId ${actionId.asString}")
    }
  }

  /**
   * return (avail_cpu, avail_mem, if_need_no_more_rsc)
   */
  def reportAvailResources(totalCpu: Double, totalMemory: Long, maxConcurrent: Int, actionId: FullyQualifiedEntityName) = {
    this.synchronized {
      var leftcpu = totalCpu - cpu
      var leftmem = totalMemory - memory
      // var nomorersc = (maxConcurrent > 1 && actionConcurrentSlotsMap.contains(actionId) && actionConcurrentSlotsMap(actionId).remaining > 1)
      var nomorersc = false // assume cpu are always additive
      (leftcpu, leftmem, nomorersc)
    }
  }

  /**
   * return (left_cpu, left_mem, node_score) (left resources on the invoker after subtracting the requested)
   */
  def reportLeftResources(totalCpu: Double, totalMemory: Long, reqCpu: Double, reqMemory: Long, maxConcurrent: Int, actionId: FullyQualifiedEntityName) = {
    this.synchronized {
      var leftcpu = totalCpu - cpu - reqCpu // assume that cpu is always additive
      var cpuutil: Double = (cpu + reqCpu)/totalCpu

      var leftmem: Long = 0
      var memutil: Double = 0.0
      if(maxConcurrent > 1 && actionConcurrentSlotsMap.contains(actionId) && actionConcurrentSlotsMap(actionId).remaining > 1) {
        // there's container with spare capacity, no more memory needed 
        leftmem = totalMemory - memory
        memutil = memory.toDouble/totalMemory.toDouble
      } else {
        leftmem = totalMemory - memory - reqMemory
        memutil = (memory.toDouble + reqMemory.toDouble)/totalMemory.toDouble
      }

      var score = cpuutil * cpuCoeff +  memutil * memCoeff

      (leftcpu, leftmem, score)
    }
  }

  /**
   * release resources when invocation completes
   */
  def release(reqCpu: Double, reqMemory: Long, maxConcurrent: Int, actionId: FullyQualifiedEntityName) {
    var release_mem = true
    this.synchronized {
      if(maxConcurrent > 1) {
        val concurrentSlot = actionConcurrentSlotsMap(actionId)
        val (remove_slot, rel_mem) = concurrentSlot.release()
        release_mem = rel_mem
        if(remove_slot) {
          actionConcurrentSlotsMap.remove(actionId)
        }
      } 
      // logging.info(
      //     this,
      //     s"invoker${id} in-use resources before release cpu: ${cpu}, memory '${memory}', reqCpu ${reqCpu}, reqMemory ${reqMemory}, actionId ${actionId.asString}")

      cpu = max(0, cpu - reqCpu)
      cpu = math.round(cpu*100).toInt/100.0
      if(release_mem)
          memory = max(0, memory - reqMemory)
      logging.info(
          this,
          s"invoker${id} in-use resources after release cpu: ${cpu}, memory '${memory}', reqCpu ${reqCpu}, reqMemory ${reqMemory}, actionId ${actionId.asString}")
    }
  }

}

/**
 * Holds the state necessary for scheduling of actions.
 *
 * @param _invokers all of the known invokers in the system
 * @param _managedInvokers all invokers for managed runtimes
 * @param _blackboxInvokers all invokers for blackbox runtimes
 * @param _managedStepSizes the step-sizes possible for the current managed invoker count
 * @param _blackboxStepSizes the step-sizes possible for the current blackbox invoker count
 * /*@param _invokerSlots state of accessible slots of each invoker*/
 * @param _usedResources total amount of core & memory used by in-flight invocations, on each invoker
 */
case class HarvestVMContainerPoolBalancerState(
  private var _invokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _blackboxInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedStepSizes: Seq[Int] = HarvestVMContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  private var _blackboxStepSizes: Seq[Int] = HarvestVMContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  protected[loadBalancer] var _usedResources: Map[Int, InvokerResourceUsage] = Map.empty[Int, InvokerResourceUsage],
  // protected[loadBalancer] var _invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] =
  //   IndexedSeq.empty[NestedSemaphore[FullyQualifiedEntityName]],
  // for scoring nodes
  protected val _cpuCoeff: Double = 3.0,
  protected val _memCoeff: Double = 1.0,
  private var _clusterSize: Int = 1)(
  lbConfig: HarvestVMContainerPoolBalancerConfig =
    loadConfigOrThrow[HarvestVMContainerPoolBalancerConfig](ConfigKeys.loadbalancer))(implicit logging: Logging) {

  // Managed fraction and blackbox fraction can be between 0.0 and 1.0. The sum of these two fractions has to be between
  // 1.0 and 2.0.
  // If the sum is 1.0 that means, that there is no overlap of blackbox and managed invokers. If the sum is 2.0, that
  // means, that there is no differentiation between managed and blackbox invokers.
  // If the sum is below 1.0 with the initial values from config, the blackbox fraction will be set higher than
  // specified in config and adapted to the managed fraction.
  private val managedFraction: Double = Math.max(0.0, Math.min(1.0, lbConfig.managedFraction))
  private val blackboxFraction: Double = Math.max(1.0 - managedFraction, Math.min(1.0, lbConfig.blackboxFraction))
  logging.info(this, s"managedFraction = $managedFraction, blackboxFraction = $blackboxFraction")(
    TransactionId.loadbalancer)

  /** Getters for the variables, setting from the outside is only allowed through the update methods below */
  def invokers: IndexedSeq[InvokerHealth] = _invokers
  def managedInvokers: IndexedSeq[InvokerHealth] = _managedInvokers
  def blackboxInvokers: IndexedSeq[InvokerHealth] = _blackboxInvokers
  def managedStepSizes: Seq[Int] = _managedStepSizes
  def blackboxStepSizes: Seq[Int] = _blackboxStepSizes
  // def invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] = _invokerSlots
  def usedResources: Map[Int, InvokerResourceUsage] = _usedResources
  def clusterSize: Int = _clusterSize

  /**
   * @param memory
   * @return calculated invoker slot
   */
  private def getInvokerSlot(memory: ByteSize): ByteSize = {
    val invokerShardMemorySize = memory / _clusterSize
    val newTreshold = if (invokerShardMemorySize < MemoryLimit.MIN_MEMORY) {
      logging.error(
        this,
        s"registered controllers: calculated controller's invoker shard memory size falls below the min memory of one action. "
          + s"Setting to min memory. Expect invoker overloads. Cluster size ${_clusterSize}, invoker user memory size ${memory.toMB.MB}, "
          + s"min action memory size ${MemoryLimit.MIN_MEMORY.toMB.MB}, calculated shard size ${invokerShardMemorySize.toMB.MB}.")(
        TransactionId.loadbalancer)
      MemoryLimit.MIN_MEMORY
    } else {
      invokerShardMemorySize
    }
    newTreshold
  }

  /**
   * Updates the scheduling state with the new invokers.
   *
   * This is okay to not happen atomically since dirty reads of the values set are not dangerous. It is important though
   * to update the "invokers" variables last, since they will determine the range of invokers to choose from.
   *
   * Handling a shrinking invokers list is not necessary, because InvokerPool won't shrink its own list but rather
   * report the invoker as "Offline".
   *
   * It is important that this method does not run concurrently to itself and/or to [[updateCluster]]
   */
  def updateInvokers(newInvokers: IndexedSeq[InvokerHealth]): Unit = {
    val oldSize = _invokers.size
    val newSize = newInvokers.size

    // for small N, allow the managed invokers to overlap with blackbox invokers, and
    // further assume that blackbox invokers << managed invokers
    val managed = Math.max(1, Math.ceil(newSize.toDouble * managedFraction).toInt)
    val blackboxes = Math.max(1, Math.floor(newSize.toDouble * blackboxFraction).toInt)

    _invokers = newInvokers
    _managedInvokers = _invokers.take(managed)
    _blackboxInvokers = _invokers.takeRight(blackboxes)

    val logDetail = if (oldSize != newSize) {
      _managedStepSizes = HarvestVMContainerPoolBalancer.pairwiseCoprimeNumbersUntil(managed)
      _blackboxStepSizes = HarvestVMContainerPoolBalancer.pairwiseCoprimeNumbersUntil(blackboxes)

      if (oldSize < newSize) {
        // Keeps the existing state..
        // val onlyNewInvokers = _invokers.drop(_invokerSlots.length)
        val onlyNewInvokers = _invokers.filter(invoker => ! _usedResources.contains(invoker.id.toInt))
        _usedResources = _usedResources ++ onlyNewInvokers.map{ invoker => (invoker.id.toInt -> new InvokerResourceUsage(0, 0, invoker.id.toInt, _cpuCoeff, _memCoeff)) }

        // _invokerSlots = _invokerSlots ++ onlyNewInvokers.map { invoker =>
        //   new NestedSemaphore[FullyQualifiedEntityName](getInvokerSlot(invoker.id.userMemory).toMB.toInt)
        // }
        // val newInvokerDetails = onlyNewInvokers
        //   .map(i =>
        //     s"${i.id.toString}: ${i.status} / ${getInvokerSlot(i.id.userMemory).toMB.MB} of ${i.id.userMemory.toMB.MB} avail core ${i.core} mem ${i.memory}")
        //   .mkString(", ")

        // val newInvokerDetails = onlyNewInvokers
        // record info of all invokers for rsc change
        val invokerDetails = _invokers
          .map(i =>
            s"${i.id.toString}: ${i.status} avail cpu(cores) ${i.cpu} mem ${i.memory}MB")
          .mkString(", ")
        s"number of known invokers increased: new = $newSize, old = $oldSize. details: $invokerDetails."
      } else {
        val invokerDetails = _invokers
          .map(i =>
            s"${i.id.toString}: ${i.status} avail cpu(cores) ${i.cpu} mem ${i.memory}MB")
          .mkString(", ")
        s"number of known invokers decreased: new = $newSize, old = $oldSize. details: $invokerDetails."
      }
    } else {
      val invokerDetails = _invokers
          .map(i =>
            s"${i.id.toString}: ${i.status} avail cpu(cores) ${i.cpu} mem ${i.memory}MB")
          .mkString(", ")
      s"no update required - number of known invokers unchanged: $newSize. details: $invokerDetails."
    }

    logging.info(
      this,
      s"loadbalancer invoker status updated. managedInvokers = $managed blackboxInvokers = $blackboxes. $logDetail")(
      TransactionId.loadbalancer)
  }

  /**
   * Updates the size of a cluster. Throws away all state for simplicity.
   *
   * This is okay to not happen atomically, since a dirty read of the values set are not dangerous. At worst the
   * scheduler works on outdated invoker-load data which is acceptable.
   *
   * It is important that this method does not run concurrently to itself and/or to [[updateInvokers]]
   */
  def updateCluster(newSize: Int): Unit = {
    val actualSize = newSize max 1 // if a cluster size < 1 is reported, falls back to a size of 1 (alone)
    if (_clusterSize != actualSize) {
      val oldSize = _clusterSize
      _clusterSize = actualSize
      // _invokerSlots = _invokers.map { invoker =>
      //   new NestedSemaphore[FullyQualifiedEntityName](getInvokerSlot(invoker.id.userMemory).toMB.toInt)
      // }
      // _usedResources = _invokers.map{ invoker => new InvokerResourceUsage(0, 0, invoker.id.toInt) }
      // Directly after startup, no invokers have registered yet. This needs to be handled gracefully.
      val invokerCount = _invokers.size
      // val totalInvokerMemory =
      //   _invokers.foldLeft(0L)((total, invoker) => total + getInvokerSlot(invoker.id.userMemory).toMB).MB
      val totalInvokerMemory =
        _invokers.foldLeft(0L)((total, invoker) => total + invoker.memory)
      val averageInvokerMemory =
        if (totalInvokerMemory > 0 && invokerCount > 0) {
          (totalInvokerMemory / invokerCount)
        } else {
          0.MB
        }
      logging.info(
        this,
        s"loadbalancer cluster size changed from $oldSize to $actualSize active nodes. ${invokerCount} invokers with ${averageInvokerMemory}MB average memory size - total invoker memory ${totalInvokerMemory}.")(
        TransactionId.loadbalancer)
    }
  }
}

/**
 * Configuration for the cluster created between loadbalancers.
 *
 * @param useClusterBootstrap Whether or not to use a bootstrap mechanism
 */
case class ClusterConfig(useClusterBootstrap: Boolean)

/**
 * Configuration for the sharding container pool balancer.
 *
 * @param blackboxFraction the fraction of all invokers to use exclusively for blackboxes
 * @param timeoutFactor factor to influence the timeout period for forced active acks (time-limit.std * timeoutFactor + 1m)
 */
case class HarvestVMContainerPoolBalancerConfig(managedFraction: Double, blackboxFraction: Double, timeoutFactor: Int)

/**
 * State kept for each activation slot until completion.
 *
 * @param id id of the activation
 * @param namespaceId namespace that invoked the action
 * @param invokerName invoker the action is scheduled to
 * @param memoryLimit memory limit of the invoked action
 * @param cpuUtil cpu utilization of the invokerd action 
 * @param timeLimit time limit of the invoked action
 * @param maxConcurrent concurrency limit of the invoked action
 * @param fullyQualifiedEntityName fully qualified name of the invoked action
 * @param timeoutHandler times out completion of this activation, should be canceled on good paths
 * @param isBlackbox true if the invoked action is a blackbox action, otherwise false (managed action)
 * @param isBlocking true if the action is invoked in a blocking fashion, i.e. "somebody" waits for the result
 * @param isBlocking true if this is the first invocation of the function, and cpu limit is forced to update
 */
case class ActivationEntry(id: ActivationId,
                           namespaceId: UUID,
                           invokerName: InvokerInstanceId,
                           cpuUtil:  Double,
                           memoryLimit: ByteSize,
                           timeLimit: FiniteDuration,
                           maxConcurrent: Int,
                           fullyQualifiedEntityName: FullyQualifiedEntityName,
                           timeoutHandler: Cancellable,
                           isBlackbox: Boolean,
                           isBlocking: Boolean,
                           updateCpuLimit: Boolean)
